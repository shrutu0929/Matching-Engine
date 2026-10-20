package com.matchbook.engine;

import java.util.List;

public record OrderBookSnapshot(String symbol, List<BookLevel> bids, List<BookLevel> asks) {
    public OrderBookSnapshot {
        bids = List.copyOf(bids);
        asks = List.copyOf(asks);
    }
}
