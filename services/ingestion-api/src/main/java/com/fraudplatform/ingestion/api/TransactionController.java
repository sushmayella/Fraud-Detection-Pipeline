package com.fraudplatform.ingestion.api;

import com.fraudplatform.ingestion.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions")
@Tag(name = "Transactions", description = "Submit transactions for fraud evaluation")
public class TransactionController {

  private final TransactionService service;

  public TransactionController(TransactionService service) {
    this.service = service;
  }

  @PostMapping
  @Operation(
      summary = "Submit a transaction for fraud evaluation",
      description =
          "Accepts the transaction, publishes it to the pipeline, and returns immediately with an "
              + "acknowledgement. The decision is eventually consistent and can be polled via "
              + "the Query API.")
  @ApiResponse(responseCode = "202", description = "Accepted for processing")
  @ApiResponse(responseCode = "400", description = "Validation error")
  public ResponseEntity<TransactionAcknowledgement> submit(
      @Valid @RequestBody TransactionRequest request) {
    TransactionAcknowledgement ack = service.submit(request);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(ack);
  }
}
