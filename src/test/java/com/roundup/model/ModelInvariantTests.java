package com.roundup.model;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

import com.roundup.model.api.RoundUpResult;

class ModelInvariantTests {

    private static final Instant START = Instant.parse("2026-08-24T00:00:00Z");
    private static final Instant END = Instant.parse("2026-08-30T23:59:59.999999999Z");

    @Test
    void moneyRejectsNullCurrency() {
        assertThatThrownBy(() -> new Money(null, 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("currency is required");
    }

    @Test
    void moneyRejectsBlankCurrency() {
        assertThatThrownBy(() -> new Money(" ", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("currency must not be blank");
    }

    @Test
    void moneyRejectsNegativeMinorUnits() {
        assertThatThrownBy(() -> new Money("GBP", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("minorUnits must not be negative");
    }

    @Test
    void moneyAcceptsZeroAndPositiveMinorUnits() {
        assertThat(new Money("GBP", 0).minorUnits()).isZero();
        assertThat(new Money("GBP", 158).minorUnits()).isEqualTo(158);
    }

    @Test
    void roundUpCalculationRejectsNegativeCount() {
        assertThatThrownBy(() -> new RoundUpCalculation(-1, new Money("GBP", 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("eligibleTransactionCount must not be negative");
    }

    @Test
    void roundUpCalculationRejectsNullTotal() {
        assertThatThrownBy(() -> new RoundUpCalculation(0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("total is required");
    }

    @Test
    void transactionPeriodRejectsNullEndpoints() {
        assertThatThrownBy(() -> new TransactionPeriod(null, END))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("startInclusive is required");
        assertThatThrownBy(() -> new TransactionPeriod(START, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("endInclusive is required");
    }

    @Test
    void transactionPeriodRejectsEndBeforeStart() {
        assertThatThrownBy(() -> new TransactionPeriod(END, START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("endInclusive must not be before startInclusive");
    }

    @Test
    void roundUpResultRejectsNullRequiredFields() {
        assertThatThrownBy(() -> new RoundUpResult(null, END, 0, new Money("GBP", 0), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("periodStart is required");
        assertThatThrownBy(() -> new RoundUpResult(START, END, 0, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("total is required");
    }

    @Test
    void roundUpResultAllowsNullTransferUidForZeroTotal() {
        RoundUpResult result = new RoundUpResult(START, END, 0, new Money("GBP", 0), null);

        assertThat(result.transferUid()).isNull();
    }

    @Test
    void roundUpResultRetainsTransferUidWhenPresent() {
        UUID transferUid = UUID.fromString("cccccccc-cccc-4ccc-cccc-cccccccccccc");

        RoundUpResult result = new RoundUpResult(START, END, 3, new Money("GBP", 158), transferUid);

        assertThat(result.transferUid()).isEqualTo(transferUid);
    }
}
