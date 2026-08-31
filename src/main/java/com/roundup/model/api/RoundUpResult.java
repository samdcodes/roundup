package com.roundup.model.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.roundup.model.Money;

import io.swagger.v3.oas.annotations.media.Schema;

public record RoundUpResult(
        @Schema(example = "2026-08-24T00:00:00Z") Instant periodStart,
        @Schema(example = "2026-08-30T23:59:59.999999999Z") Instant periodEnd,
        @Schema(example = "3") int eligibleTransactionCount,
        Money total,
        @Schema(example = "cccccccc-cccc-4ccc-cccc-cccccccccccc") UUID transferUid) {

    public RoundUpResult {
        Objects.requireNonNull(periodStart, "periodStart is required");
        Objects.requireNonNull(periodEnd, "periodEnd is required");
        Objects.requireNonNull(total, "total is required");
        if (eligibleTransactionCount < 0) {
            throw new IllegalArgumentException("eligibleTransactionCount must not be negative");
        }
        // transferUid is intentionally nullable: omitted for a zero-total no-op.
    }
}
