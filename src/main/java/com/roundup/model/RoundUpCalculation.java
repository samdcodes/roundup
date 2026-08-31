package com.roundup.model;

import java.util.Objects;

public record RoundUpCalculation(int eligibleTransactionCount, Money total) {

    public RoundUpCalculation {
        if (eligibleTransactionCount < 0) {
            throw new IllegalArgumentException("eligibleTransactionCount must not be negative");
        }
        Objects.requireNonNull(total, "total is required");
    }
}
