package com.roundup.model.starling;

import java.util.List;

public record FeedItemsResponse(List<FeedItem> feedItems) {

    public FeedItemsResponse {
        feedItems = feedItems == null ? List.of() : List.copyOf(feedItems);
    }
}
