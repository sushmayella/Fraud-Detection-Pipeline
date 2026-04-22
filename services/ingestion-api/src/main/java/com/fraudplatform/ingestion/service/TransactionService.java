package com.fraudplatform.ingestion.service;

import com.fraudplatform.events.Transaction;
import com.fraudplatform.ingestion.api.TransactionAcknowledgement;
import com.fraudplatform.ingestion.api.TransactionRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Validates, stamps metadata, and publishes a transaction to the {@code raw-transactions} topic.
 *
 * <p>This service is deliberately thin: downstream services own enrichment, scoring, and
 * decisioning. See {@code docs/ARCHITECTURE.md}.
 */
@Service
public class TransactionService {

  private static final Logger log = LoggerFactory.getLogger(TransactionService.class);
  static final String TOPIC = "raw-transactions";

  private final KafkaTemplate<String, Transaction> kafkaTemplate;
  private final Counter acceptedCounter;

  public TransactionService(KafkaTemplate<String, Transaction> kafkaTemplate, MeterRegistry meters) {
    this.kafkaTemplate = kafkaTemplate;
    this.acceptedCounter =
        Counter.builder("ingestion.transactions.accepted")
            .description("Transactions accepted into the pipeline")
            .register(meters);
  }

  public TransactionAcknowledgement submit(TransactionRequest request) {
    String txnId =
        request.transactionId() == null || request.transactionId().isBlank()
            ? UUID.randomUUID().toString()
            : request.transactionId();
    Instant now = Instant.now();
    Instant occurredAt = request.timestamp() != null ? request.timestamp() : now;

    Transaction event =
        Transaction.newBuilder()
            .setTransactionId(txnId)
            .setUserId(request.userId())
            .setCardFingerprint(request.cardFingerprint())
            .setAmount(request.amount())
            .setCurrency(request.currency())
            .setMerchantId(request.merchantId())
            .setMerchantCategoryCode(request.merchantCategoryCode())
            .setCountry(request.country())
            .setLatitude(request.latitude())
            .setLongitude(request.longitude())
            .setDeviceId(request.deviceId())
            .setTimestamp(occurredAt)
            .setIngestedAt(now)
            .build();

    // Key by transactionId for ordering guarantees per transaction across retries.
    kafkaTemplate.send(TOPIC, txnId, event);
    acceptedCounter.increment();

    log.info(
        "accepted transaction id={} user={} amount={} {} country={}",
        txnId,
        request.userId(),
        request.amount(),
        request.currency(),
        request.country());

    return new TransactionAcknowledgement(txnId, now, "/api/v1/decisions/" + txnId);
  }
}
