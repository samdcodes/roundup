package com.roundup.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.roundup.model.Money;
import com.roundup.model.RoundUpCalculation;
import com.roundup.model.TransactionPeriod;
import com.roundup.model.starling.CurrencyAndAmount;
import com.roundup.model.starling.FeedItem;

class RoundUpServiceTests {

    private static final Instant PERIOD_START = Instant.parse("2026-08-24T12:00:00Z");
    private static final Instant PERIOD_END = Instant.parse("2026-08-31T12:00:00Z");

    private final RoundUpService service = new RoundUpService(null);
        private final TransactionPeriod period = new TransactionPeriod(PERIOD_START, PERIOD_END);

    @Test
    void calculatesExampleUsingAccountAmountInPence() {
        List<FeedItem> items =
                List.of(
                        feedItem(435, null, PERIOD_START),
                        feedItem(520, null, PERIOD_START.plusSeconds(1)),
                        new FeedItem(
                                new CurrencyAndAmount("GBP", 87),
                                new CurrencyAndAmount("EUR", 999),
                                "OUT",
                                "SETTLED",
                                "MASTER_CARD",
                                null,
                                PERIOD_END));

        RoundUpCalculation result = service.calculate(items, period);

        assertThat(result.eligibleTransactionCount()).isEqualTo(3);
        assertThat(result.total()).isEqualTo(new Money("GBP", 158));
    }

    @Test
    void exactPoundsContributeZeroButRemainEligible() {
        RoundUpCalculation result =
                service.calculate(List.of(feedItem(500, null, PERIOD_START)), period);

        assertThat(result.eligibleTransactionCount()).isOne();
        assertThat(result.total()).isEqualTo(new Money("GBP", 0));
    }

    @Test
    void includesBothPeriodBoundaries() {
        RoundUpCalculation result =
                service.calculate(
                        List.of(feedItem(101, null, PERIOD_START), feedItem(101, null, PERIOD_END)),
                        period);

        assertThat(result).isEqualTo(new RoundUpCalculation(2, new Money("GBP", 198)));
    }

    @Test
    void includesOutgoingTransactionsRegardlessOfStatus() {
        Instant inside = PERIOD_START.plusSeconds(1);
        List<FeedItem> items =
                List.of(
                        feedItem(101, null, "OUT", "PENDING", "MASTER_CARD", inside),
                        feedItem(101, null, "OUT", "DECLINED", "MASTER_CARD", inside),
                        feedItem(101, null, "OUT", "REVERSED", "MASTER_CARD", inside));

        assertThat(service.calculate(items, period))
                .isEqualTo(new RoundUpCalculation(3, new Money("GBP", 297)));
    }

    @Test
    void excludesIneligibleAndOutOfRangeItems() {
        Instant inside = PERIOD_START.plusSeconds(1);
        List<FeedItem> items =
                List.of(
                        feedItem(101, null, "IN", "SETTLED", "FASTER_PAYMENTS_IN", inside),
                        feedItem(101, null, "OUT", "SETTLED", "INTERNAL_TRANSFER", inside),
                        new FeedItem(
                                new CurrencyAndAmount("EUR", 101),
                                null,
                                "OUT",
                                "SETTLED",
                                "MASTER_CARD",
                                null,
                                inside),
                        feedItem(101, null, PERIOD_START.minusNanos(1)),
                        feedItem(101, null, PERIOD_END.plusNanos(1)));

        assertThat(service.calculate(items, period))
                .isEqualTo(new RoundUpCalculation(0, new Money("GBP", 0)));
    }

    @Test
    void excludesTransfersMadeByRoundUpApplication() {
        List<FeedItem> items =
                List.of(
                        feedItem(
                                101, "round-up:dddddddd-dddd-4ddd-dddd-dddddddddddd", PERIOD_START),
                        feedItem(101, "ordinary-reference", PERIOD_START));

        assertThat(service.calculate(items, period))
                .isEqualTo(new RoundUpCalculation(1, new Money("GBP", 99)));
    }

    @Test
    void handlesNullOrEmptyFeed() {
        assertThat(service.calculate(null, period))
                .isEqualTo(new RoundUpCalculation(0, new Money("GBP", 0)));
        assertThat(service.calculate(List.of(), period))
                .isEqualTo(new RoundUpCalculation(0, new Money("GBP", 0)));
    }

    @Test
    void ignoresNullFeedItems() {
        List<FeedItem> items = new ArrayList<>();
        items.add(null);
        items.add(feedItem(101, null, PERIOD_START));

        assertThat(service.calculate(items, period))
                .isEqualTo(new RoundUpCalculation(1, new Money("GBP", 99)));
    }

    @Test
    void ignoresFeedItemsWithoutAccountAmount() {
        FeedItem item =
                new FeedItem(
                        null,
                        new CurrencyAndAmount("EUR", 101),
                        "OUT",
                        "SETTLED",
                        "MASTER_CARD",
                        null,
                        PERIOD_START);

        assertThat(service.calculate(List.of(item), period))
                .isEqualTo(new RoundUpCalculation(0, new Money("GBP", 0)));
    }

    @Test
    void ignoresFeedItemsWithoutTransactionTime() {
        FeedItem item = feedItem(101, null, null);

        assertThat(service.calculate(List.of(item), period))
                .isEqualTo(new RoundUpCalculation(0, new Money("GBP", 0)));
    }

    @Test
    void treatsAllItemsAsIneligibleWhenPeriodIsNull() {
        assertThat(service.calculate(List.of(feedItem(101, null, PERIOD_START)), null))
                .isEqualTo(new RoundUpCalculation(0, new Money("GBP", 0)));
    }

    private FeedItem feedItem(long minorUnits, String reference, Instant transactionTime) {
        return feedItem(minorUnits, reference, "OUT", "SETTLED", "MASTER_CARD", transactionTime);
    }

    private FeedItem feedItem(
            long minorUnits,
            String reference,
            String direction,
            String status,
            String source,
            Instant transactionTime) {
        return new FeedItem(
                new CurrencyAndAmount("GBP", minorUnits),
                null,
                direction,
                status,
                source,
                reference,
                transactionTime);
    }
}
