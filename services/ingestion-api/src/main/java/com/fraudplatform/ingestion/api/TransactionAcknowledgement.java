package com.fraudplatform.ingestion.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Acknowledgement that a transaction has been accepted for evaluation.")
public record TransactionAcknowledgement(
    @Schema(description = "The transaction id (generated if the client did not provide one).")
        String transactionId,
    @Schema(description = "Time the transaction was accepted for processing.") Instant acceptedAt,
    @Schema(description = "Where to poll for the decision (Query API).") String decisionUrl) {}
