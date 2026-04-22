package com.fraudplatform.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fraudplatform.events.Transaction;
import com.fraudplatform.ingestion.api.TransactionAcknowledgement;
import com.fraudplatform.ingestion.api.TransactionRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;

class TransactionServiceTest {

  private KafkaTemplate<String, Transaction> kafkaTemplate;
  private TransactionService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    kafkaTemplate = Mockito.mock(KafkaTemplate.class);
    when(kafkaTemplate.send(any(String.class), any(String.class), any(Transaction.class)))
        .thenReturn(null);
    service = new TransactionService(kafkaTemplate, new SimpleMeterRegistry());
  }

  @Test
  void assignsGeneratedIdWhenNoneProvided() {
    TransactionAcknowledgement ack = service.submit(validRequest(null));

    assertThat(ack.transactionId()).isNotBlank();
    assertThat(ack.transactionId()).hasSize(36); // UUID
    assertThat(ack.decisionUrl()).isEqualTo("/api/v1/decisions/" + ack.transactionId());
  }

  @Test
  void preservesClientProvidedId() {
    TransactionAcknowledgement ack = service.submit(validRequest("client-supplied-id-123"));
    assertThat(ack.transactionId()).isEqualTo("client-supplied-id-123");
  }

  @Test
  void publishesEventKeyedByTransactionId() {
    ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Transaction> eventCaptor = ArgumentCaptor.forClass(Transaction.class);

    TransactionAcknowledgement ack = service.submit(validRequest(null));

    verify(kafkaTemplate, times(1))
        .send(eq(TransactionService.TOPIC), keyCaptor.capture(), eventCaptor.capture());
    assertThat(keyCaptor.getValue()).isEqualTo(ack.transactionId());
    assertThat(eventCaptor.getValue().getAmount()).isEqualTo(250.75);
  }

  @Test
  void stampsIngestionTimestamp() {
    Instant before = Instant.now();
    service.submit(validRequest(null));
    Instant after = Instant.now();

    ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
    verify(kafkaTemplate).send(any(), any(), captor.capture());

    Instant stamped = captor.getValue().getIngestedAt();
    assertThat(stamped).isBetween(before.minusSeconds(1), after.plusSeconds(1));
  }

  private TransactionRequest validRequest(String id) {
    return new TransactionRequest(
        id, "user-42", "card-fp-abc", 250.75, "USD", "merchant-1", 5411, "US", 37.77, -122.41,
        "device-xyz", Instant.now());
  }
}
