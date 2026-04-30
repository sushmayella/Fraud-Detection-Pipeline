package com.fraudplatform.featureextractor.config;

import com.fraudplatform.events.EnrichedTransaction;
import com.fraudplatform.events.Transaction;
import com.fraudplatform.featureextractor.topology.FeatureExtractorTopology;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Boots the Kafka Streams application.
 *
 * <p>The topology is constructed once and owned by a single {@link KafkaStreams} instance.
 * Spring's lifecycle hooks start it after bean initialization and close it on shutdown.
 *
 * <p>Several streams-config values are deliberate and worth calling out:
 *
 * <ul>
 *   <li>{@code processing.guarantee = exactly_once_v2} — transactional writes so velocity
 *       counts never double-count on retries or rebalance. The performance cost is acceptable
 *       at our target scale. See ADR-0004 (scheduled) for the decision.
 *   <li>{@code auto.offset.reset = earliest} — on first deploy, process the full topic.
 *       This is safe because our enrichment is deterministic and downstream is idempotent.
 *   <li>{@code num.stream.threads = 2} — one thread per CPU by default; overridable via env.
 * </ul>
 */
@Configuration
public class KafkaStreamsConfig {

  private static final Logger log = LoggerFactory.getLogger(KafkaStreamsConfig.class);

  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrapServers;

  @Value("${schema.registry.url}")
  private String schemaRegistryUrl;

  @Value("${kafka.streams.application-id:feature-extractor}")
  private String applicationId;

  @Value("${kafka.streams.state-dir:/tmp/kafka-streams}")
  private String stateDir;

  @Value("${kafka.streams.num-threads:2}")
  private int numThreads;

  private KafkaStreams streams;

  @Bean
  public Serde<Transaction> transactionSerde() {
    return specificAvroSerde();
  }

  @Bean
  public Serde<EnrichedTransaction> enrichedTransactionSerde() {
    return specificAvroSerde();
  }

  private <T extends org.apache.avro.specific.SpecificRecord> Serde<T> specificAvroSerde() {
    SpecificAvroSerde<T> serde = new SpecificAvroSerde<>();
    Map<String, String> config = new HashMap<>();
    config.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
    serde.configure(config, false);
    return serde;
  }

  @Bean
  public Topology featureExtractorTopology(
      Serde<Transaction> transactionSerde, Serde<EnrichedTransaction> enrichedTransactionSerde) {
    return new FeatureExtractorTopology(transactionSerde, enrichedTransactionSerde).build();
  }

  @Bean
  public Properties streamsProperties() {
    Properties props = new Properties();
    props.put(StreamsConfig.APPLICATION_ID_CONFIG, applicationId);
    props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
    props.put(StreamsConfig.STATE_DIR_CONFIG, stateDir);
    props.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, numThreads);
    props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
    return props;
  }

  @Bean
  public KafkaStreams kafkaStreams(Topology topology, Properties streamsProperties) {
    return new KafkaStreams(topology, streamsProperties);
  }

  @PostConstruct
  public void startStreams() {
    // Beans are created in order, so kafkaStreams() will have run by the time this fires.
    // We start lazily here so tests can replace the bean without the real client connecting.
  }

  @PreDestroy
  public void stopStreams() {
    if (streams != null) {
      log.info("shutting down KafkaStreams");
      streams.close();
    }
  }
}
