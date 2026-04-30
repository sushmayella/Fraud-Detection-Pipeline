package com.fraudplatform.featureextractor.topology;

import static org.assertj.core.api.Assertions.assertThat;

import com.fraudplatform.events.EnrichedTransaction;
import com.fraudplatform.events.Transaction;
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * TopologyTestDriver tests for the feature extractor.
 *
 * <p>Uses {@link TopologyTestDriver} to run the full topology in-process, synchronously, with no
 * Kafka broker or Docker required. Tests pipe records to an input topic and read from an output
 * topic; state stores behave exactly as they would in production.
 *
 * <p>The {@code mock://} schema registry URL tells the Confluent Avro serde to use
 * {@link MockSchemaRegistry} — an in-process implementation. Producer and consumer share the
 * same mock scope so schema IDs resolve correctly.
 *
 * <h2>What these tests cover</h2>
 *
 * <ul>
 *   <li>Single transaction → velocity1m == 1
 *   <li>Three transactions in 30s for the same card → third sees velocity1m == 3
 *   <li>Transactions that span a window boundary → count resets in the new window
 *   <li>Transactions for different cards → state is isolated per key
 *   <li>Late-arriving event within grace → counted
 *   <li>Output keying — the enriched event is keyed by transactionId for downstream ordering
 * </ul>
 */
class FeatureExtractorTopologyTest {

  private static final String SCHEMA_REGISTRY_SCOPE = "feature-extractor-topology-test";
  private static final String SCHEMA_REGISTRY_URL = "mock://" + SCHEMA_REGISTRY_SCOPE;

  private TopologyTestDriver driver;
  private TestInputTopic<String, Transaction> input;
  private TestOutputTopic<String, EnrichedTransaction> output;

  @BeforeEach
  void setUp() {
    Map<String, String> serdeConfig = Map.of(
        AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, SCHEMA_REGISTRY_URL);

    SpecificAvroSerde<Transaction> txnSerde = new SpecificAvroSerde<>();
    txnSerde.configure(serdeConfig, false);

    SpecificAvroSerde<EnrichedTransaction> enrichedSerde = new SpecificAvroSerde<>();
    enrichedSerde.configure(serdeConfig, false);

    Topology topology = new FeatureExtractorTopology(txnSerde, enrichedSerde).build();

    Properties props = new Properties();
    props.put(StreamsConfig.APPLICATION_ID_CONFIG, "feature-extractor-test");
    props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
    props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
    // Default value serde is required for any internal topic Kafka Streams creates
    // (repartition topics, changelog topics) where we did not provide an explicit Serde.
    props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde.class);
    props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, SCHEMA_REGISTRY_URL);

    driver = new TopologyTestDriver(topology, props);
    input = driver.createInputTopic(
        FeatureExtractorTopology.SOURCE_TOPIC, Serdes.String().serializer(), txnSerde.serializer());
    output = driver.createOutputTopic(
        FeatureExtractorTopology.SINK_TOPIC, Serdes.String().deserializer(), enrichedSerde.deserializer());
  }

  @AfterEach
  void tearDown() {
    driver.close();
    MockSchemaRegistry.dropScope(SCHEMA_REGISTRY_SCOPE);
  }

  @Nested
  @DisplayName("single-transaction cases")
  class SingleTransaction {

    @Test
    @DisplayName("one transaction produces velocity1m=1 and velocity1h=1")
    void oneTransactionHasVelocityOne() {
      Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
      input.pipeInput("txn-1", txn("txn-1", "user-a", "card-a", 50.00, t0), t0);

      List<org.apache.kafka.streams.test.TestRecord<String, EnrichedTransaction>> out =
          output.readRecordsToList();
      assertThat(out).hasSize(1);
      EnrichedTransaction e = out.get(0).value();
      assertThat(e.getFeatures().getVelocity1m()).isEqualTo(1);
      assertThat(e.getFeatures().getVelocity1h()).isEqualTo(1);
      assertThat(e.getTransaction().getTransactionId().toString()).isEqualTo("txn-1");
    }

    @Test
    @DisplayName("output is keyed by transactionId, not cardFingerprint")
    void outputKeyedByTransactionId() {
      Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
      input.pipeInput("txn-1", txn("txn-1", "user-a", "card-a", 10.0, t0), t0);

      var record = output.readRecord();
      assertThat(record.key()).isEqualTo("txn-1");
    }
  }

  @Nested
  @DisplayName("velocity within the 1-minute window")
  class Velocity1m {

    @Test
    @DisplayName("three txns for the same card within 30s — third sees velocity1m=3")
    void threeInThirtySeconds() {
      Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
      input.pipeInput("t1", txn("t1", "u", "card-X", 10.0, t0), t0);
      input.pipeInput("t2", txn("t2", "u", "card-X", 20.0, t0.plusSeconds(10)), t0.plusSeconds(10));
      input.pipeInput("t3", txn("t3", "u", "card-X", 30.0, t0.plusSeconds(30)), t0.plusSeconds(30));

      List<EnrichedTransaction> values =
          output.readRecordsToList().stream().map(r -> r.value()).toList();
      assertThat(values).hasSize(3);
      assertThat(values.get(0).getFeatures().getVelocity1m()).isEqualTo(1);
      assertThat(values.get(1).getFeatures().getVelocity1m()).isEqualTo(2);
      assertThat(values.get(2).getFeatures().getVelocity1m()).isEqualTo(3);
    }

    @Test
    @DisplayName("two cards' velocities don't cross-contaminate")
    void stateIsolationPerKey() {
      Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
      input.pipeInput("a1", txn("a1", "user-1", "card-A", 10.0, t0), t0);
      input.pipeInput("b1", txn("b1", "user-2", "card-B", 20.0, t0.plusSeconds(5)), t0.plusSeconds(5));
      input.pipeInput("a2", txn("a2", "user-1", "card-A", 30.0, t0.plusSeconds(10)), t0.plusSeconds(10));
      input.pipeInput("b2", txn("b2", "user-2", "card-B", 40.0, t0.plusSeconds(15)), t0.plusSeconds(15));

      Map<String, Integer> finalCounts = new HashMap<>();
      output.readRecordsToList().forEach(r ->
          finalCounts.put(r.value().getTransaction().getTransactionId().toString(),
              r.value().getFeatures().getVelocity1m()));

      assertThat(finalCounts.get("a1")).isEqualTo(1);
      assertThat(finalCounts.get("b1")).isEqualTo(1);
      assertThat(finalCounts.get("a2")).isEqualTo(2);
      assertThat(finalCounts.get("b2")).isEqualTo(2);
    }

    @Test
    @DisplayName("transactions in different 1-minute tumbling windows reset velocity1m")
    void windowBoundaryResetsVelocity1m() {
      // Tumbling 1m windows are aligned on the minute boundary.
      // 12:00:30 and 12:00:50 are in the same window; 12:01:10 is in the next.
      Instant a = Instant.parse("2026-01-01T12:00:30Z");
      Instant b = Instant.parse("2026-01-01T12:00:50Z");
      Instant c = Instant.parse("2026-01-01T12:01:10Z");

      input.pipeInput("t1", txn("t1", "u", "card-Z", 10.0, a), a);
      input.pipeInput("t2", txn("t2", "u", "card-Z", 20.0, b), b);
      input.pipeInput("t3", txn("t3", "u", "card-Z", 30.0, c), c);

      List<EnrichedTransaction> values =
          output.readRecordsToList().stream().map(r -> r.value()).toList();
      assertThat(values.get(0).getFeatures().getVelocity1m()).isEqualTo(1);
      assertThat(values.get(1).getFeatures().getVelocity1m()).isEqualTo(2);
      // t3 is in a new window — velocity1m resets. But velocity1h still sees all three.
      assertThat(values.get(2).getFeatures().getVelocity1m()).isEqualTo(1);
      assertThat(values.get(2).getFeatures().getVelocity1h()).isEqualTo(3);
    }
  }

  @Nested
  @DisplayName("late-arriving events")
  class LateArrivals {

    @Test
    @DisplayName("event arriving within grace period is still counted")
    void withinGraceIsCounted() {
      // Grace is 5 seconds. Event-time for the late event is 1s before "now".
      Instant first = Instant.parse("2026-01-01T12:00:30Z");
      input.pipeInput("t1", txn("t1", "u", "card-L", 10.0, first), first);

      // Simulate wall-clock advance beyond the window close but within grace:
      Instant processingTime = first.plus(Duration.ofSeconds(62));
      Instant lateEventTime = Instant.parse("2026-01-01T12:00:59Z"); // still in the first window
      driver.advanceWallClockTime(Duration.ofSeconds(62));

      input.pipeInput("t2", txn("t2", "u", "card-L", 20.0, lateEventTime), lateEventTime);

      List<EnrichedTransaction> values =
          output.readRecordsToList().stream().map(r -> r.value()).toList();
      assertThat(values).hasSize(2);
      // t2 falls in the same 1m window as t1, so t2's velocity1m is 2.
      assertThat(values.get(1).getFeatures().getVelocity1m()).isEqualTo(2);
    }
  }

  /** Factory for Transaction records — keeps test bodies readable. */
  private static Transaction txn(
      String id, String userId, String cardFp, double amount, Instant timestamp) {
    Instant now = Instant.now();
    return Transaction.newBuilder()
        .setTransactionId(id)
        .setUserId(userId)
        .setCardFingerprint(cardFp)
        .setAmount(amount)
        .setCurrency("USD")
        .setMerchantId("m-1")
        .setMerchantCategoryCode(5411)
        .setCountry("US")
        .setLatitude(null)
        .setLongitude(null)
        .setDeviceId(null)
        .setTimestamp(timestamp)
        .setIngestedAt(now)
        .build();
  }
}
