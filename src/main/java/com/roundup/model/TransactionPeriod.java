package com.roundup.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;

public record TransactionPeriod(Instant startInclusive, Instant endInclusive) {

    public TransactionPeriod {
        Objects.requireNonNull(startInclusive, "startInclusive is required");
        Objects.requireNonNull(endInclusive, "endInclusive is required");
        if (endInclusive.isBefore(startInclusive)) {
            throw new IllegalArgumentException("endInclusive must not be before startInclusive");
        }
    }

    public TransactionPeriod(LocalDate startDate) {
        this(
                startDate.atStartOfDay(ZoneOffset.UTC).toInstant(),
                startDate.plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1));
    }

    public boolean contains(Instant instant) {
        return instant != null
                && !instant.isBefore(startInclusive)
                && !instant.isAfter(endInclusive);
    }
}