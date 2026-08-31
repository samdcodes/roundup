package com.roundup.model;

import java.util.Objects;

public record Money(String currency, long minorUnits) {

    public Money {
        Objects.requireNonNull(currency, "currency is required");
        if (currency.isBlank()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
        if (minorUnits < 0) {
            throw new IllegalArgumentException("minorUnits must not be negative");
        }
    }
}
