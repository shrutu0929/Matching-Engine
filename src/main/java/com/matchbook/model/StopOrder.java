package com.matchbook.model;

public final class StopOrder extends Order {
    private final long stopPriceTicks;

    public StopOrder(String id, String symbol, Side side, long quantity, long stopPriceTicks, TimeInForce timeInForce) {
        super(id, symbol, side, quantity, timeInForce);
        if (stopPriceTicks <= 0) {
            throw new IllegalArgumentException("stopPriceTicks must be positive");
        }
        if (timeInForce == TimeInForce.GTC) {
            throw new IllegalArgumentException("stop-market orders cannot use GTC");
        }
        this.stopPriceTicks = stopPriceTicks;
    }

    @Override
    public OrderType type() {
        return OrderType.STOP;
    }

    public long stopPriceTicks() {
        return stopPriceTicks;
    }
}
