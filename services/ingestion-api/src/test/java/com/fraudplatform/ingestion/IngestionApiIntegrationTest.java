package com.fraudplatform.ingestion;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraudplatform.events.Transaction;
import com.fraudplatform.ingestion.api.TransactionRequest;

import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;

/**
 * Verifies the full happy path: POST /transactions → 202 → Avro event on raw-transactions.
 *
 * <p>Uses Testcontainers for real Kafka and Confluent's in-process MockSchemaRegistry
 * (via the mock:// URL scheme) so no external schema registry is needed. The producer
 * and consumer share the same mock registry scope, so schema IDs resolve correctly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class IngestionApiIntegrationTest {

  private static final String MOCK_SCHEMA_REGISTRY = "mock://ingestion-it";

  private static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.3"));

  @BeforeAll
  static void startKafka() {
    KAFKA.start();
  }

  @AfterAll
  static void stopKafka() {
    KAFKA.stop();
  }

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("schema.registry.url", () -> MOCK_SCHEMA_REGISTRY);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void submittedTransactionLandsOnKafkaAsAvro() throws Exception {
    String txnId = UUID.randomUUID().toString();
    TransactionRequest request =
        new TransactionRequest(
            txnId, "user-it-1", "card-fp-it", 99.99, "USD", "m-1", 5411, "US",
            null, null, null, Instant.now());

    mockMvc
        .perform(
            post("/api/v1/transactions")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.transactionId").value(txnId));

    try (Consumer<String, Transaction> consumer = testConsumer()) {
      consumer.subscribe(java.util.List.of("raw-transactions"));
      ConsumerRecords<String, Transaction> records = consumer.poll(Duration.ofSeconds(10));
      assertThat(records.count()).isGreaterThanOrEqualTo(1);
      ConsumerRecord<String, Transaction> first = records.iterator().next();
      assertThat(first.key()).isEqualTo(txnId);
      assertThat(first.value().getUserId()).isEqualTo("user-it-1");
      assertThat(first.value().getAmount()).isEqualTo(99.99);
      assertThat(first.value().getCurrency()).isEqualTo("USD");
    }
  }

  @Test
  void rejectsInvalidPayload() throws Exception {
    String badJson =
        "{\"userId\":\"u\",\"cardFingerprint\":\"c\",\"amount\":10,"
            + "\"merchantId\":\"m\",\"merchantCategoryCode\":5411,\"country\":\"US\"}";
    mockMvc
        .perform(post("/api/v1/transactions").contentType("application/json").content(badJson))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("validation failed"));
  }

  private Consumer<String, Transaction> testConsumer() {
    Map<String, Object> props = new HashMap<>();
    props.put("bootstrap.servers", KAFKA.getBootstrapServers());
    props.put("group.id", "it-consumer-" + UUID.randomUUID());
    props.put("auto.offset.reset", "earliest");
    props.put("key.deserializer", StringDeserializer.class.getName());
    props.put("value.deserializer", KafkaAvroDeserializer.class.getName());
    props.put("schema.registry.url", MOCK_SCHEMA_REGISTRY);
    props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
    return new KafkaConsumer<>(props);
  }
}