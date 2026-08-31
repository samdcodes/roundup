package com.roundup.model;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class TransactionPeriodTests {

    @Test
    void containsReturnsFalseForNullInstant() {
        TransactionPeriod period =
                new TransactionPeriod(
                        Instant.parse("2026-08-24T00:00:00Z"),
                        Instant.parse("2026-08-30T23:59:59.999999999Z"));

        assertThat(period.contains(null)).isFalse();
    }
}