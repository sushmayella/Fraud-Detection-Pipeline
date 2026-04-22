package com.fraudplatform.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraudplatform.ingestion.api.TransactionRequest;
import java.time.Duration;
import java.time.Instant;
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

/**
 * Verifies the full happy path: POST /transactions → 202 → event lands on raw-transactions topic.
 *
 * <p>Uses Testcontainers for real Kafka. No mocks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class IngestionApiIntegrationTest {

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
    // For integration tests we fall back to string serializer to avoid needing schema registry.
    registry.add(
        "spring.kafka.producer.value-serializer",
        () -> "org.apache.kafka.common.serialization.StringSerializer");
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void submittedTransactionLandsOnKafka() throws Exception {
    String txnId = UUID.randomUUID().toString();
    TransactionRequest request =
        new TransactionRequest(
            txnId, "user-it-1", "card-fp-it", 99.99, "USD", "m-1", 5411, "US", null, null, null,
            Instant.now());

    mockMvc
        .perform(
            post("/api/v1/transactions")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.transactionId").value(txnId));

    try (Consumer<String, String> consumer = testConsumer()) {
      consumer.subscribe(java.util.List.of("raw-transactions"));
      ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
      assertThat(records.count()).isGreaterThanOrEqualTo(1);
      ConsumerRecord<String, String> first = records.iterator().next();
      assertThat(first.key()).isEqualTo(txnId);
      assertThat(first.value()).contains("user-it-1").contains("99.99");
    }
  }

  @Test
  void rejectsInvalidPayload() throws Exception {
    // Missing currency
    String badJson = "{\"userId\":\"u\",\"cardFingerprint\":\"c\",\"amount\":10,"
        + "\"merchantId\":\"m\",\"merchantCategoryCode\":5411,\"country\":\"US\"}";
    mockMvc
        .perform(post("/api/v1/transactions").contentType("application/json").content(badJson))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("validation failed"));
  }

  private Consumer<String, String> testConsumer() {
    return new KafkaConsumer<>(
        Map.of(
            "bootstrap.servers", KAFKA.getBootstrapServers(),
            "group.id", "it-consumer-" + UUID.randomUUID(),
            "auto.offset.reset", "earliest",
            "key.deserializer", StringDeserializer.class.getName(),
            "value.deserializer", StringDeserializer.class.getName()));
  }
}
