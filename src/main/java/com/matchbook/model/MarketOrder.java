package com.matchbook.model;

public final class MarketOrder extends Order {
    public MarketOrder(String id, String symbol, Side side, long quantity, TimeInForce timeInForce) {
        super(id, symbol, side, quantity, timeInForce);
        if (timeInForce == TimeInForce.GTC) {
            throw new IllegalArgumentException("market orders cannot use GTC");
        }
    }

    @Override
    public OrderType type() {
        return OrderType.MARKET;
    }
}
