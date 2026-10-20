package com.matchbook.model;

public final class LimitOrder extends Order {
    private final long priceTicks;

    public LimitOrder(String id, String symbol, Side side, long quantity, long priceTicks, TimeInForce timeInForce) {
        super(id, symbol, side, quantity, timeInForce);
        if (priceTicks <= 0) {
            throw new IllegalArgumentException("priceTicks must be positive");
        }
        this.priceTicks = priceTicks;
    }

    @Override
    public OrderType type() {
        return OrderType.LIMIT;
    }

    public long priceTicks() {
        return priceTicks;
    }
}
