package com.matchbook;

import com.matchbook.engine.MatchingEngine;
import com.matchbook.model.OrderFactory;
import com.matchbook.model.Side;
import com.matchbook.model.TimeInForce;

public final class Example {
    private Example() {
    }

    public static void main(String[] args) {
        try (MatchingEngine engine = new MatchingEngine(2, 1_024)) {
            engine.addTradeObserver("MATCH", trade -> System.out.printf(
                    "Trade %s: %d @ %d ticks%n", trade.symbol(), trade.quantity(), trade.priceTicks()));

            engine.submit(OrderFactory.limit("ask-1", "MATCH", Side.SELL, 5, 10_025, TimeInForce.GTC)).join();
            engine.submit(OrderFactory.limit("bid-1", "MATCH", Side.BUY, 3, 10_030, TimeInForce.IOC)).join();

            System.out.println(engine.snapshot("MATCH").join());
        }
    }
}
