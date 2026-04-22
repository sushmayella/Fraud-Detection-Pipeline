package com.fraudplatform.ingestion.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

@Schema(description = "Transaction submitted for fraud evaluation")
public record TransactionRequest(
    @Schema(description = "Optional client-provided id for idempotency. A UUID will be generated if omitted.")
        String transactionId,
    @NotBlank @Schema(description = "Opaque stable identifier for the user.") String userId,
    @NotBlank
        @Schema(description = "Tokenized card identifier. Never the PAN.")
        String cardFingerprint,
    @NotNull @DecimalMin("0.01") @Schema(description = "Amount in the transaction currency.")
        Double amount,
    @NotBlank
        @Size(min = 3, max = 3)
        @Pattern(regexp = "[A-Z]{3}")
        @Schema(description = "ISO 4217 currency code.")
        String currency,
    @NotBlank @Schema(description = "Merchant identifier.") String merchantId,
    @NotNull @Schema(description = "MCC (Merchant Category Code).") Integer merchantCategoryCode,
    @NotBlank
        @Size(min = 2, max = 2)
        @Pattern(regexp = "[A-Z]{2}")
        @Schema(description = "ISO 3166-1 alpha-2 country code.")
        String country,
    Double latitude,
    Double longitude,
    String deviceId,
    @Schema(description = "Time the transaction occurred. Defaults to now if omitted.")
        Instant timestamp) {}
