package com.roundup.model.starling;

import java.time.Instant;

public record FeedItem(
        CurrencyAndAmount amount,
        CurrencyAndAmount sourceAmount,
        String direction,
        String status,
        String source,
        String reference,
        Instant transactionTime) {
}
