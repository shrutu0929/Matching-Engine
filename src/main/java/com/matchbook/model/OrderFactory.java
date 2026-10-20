package com.matchbook.model;

public final class OrderFactory {
    private OrderFactory() {
    }

    public static LimitOrder limit(String id, String symbol, Side side, long quantity, long priceTicks, TimeInForce tif) {
        return new LimitOrder(id, symbol, side, quantity, priceTicks, tif);
    }

    public static MarketOrder market(String id, String symbol, Side side, long quantity, TimeInForce tif) {
        return new MarketOrder(id, symbol, side, quantity, tif);
    }

    public static StopOrder stop(String id, String symbol, Side side, long quantity, long stopPriceTicks, TimeInForce tif) {
        return new StopOrder(id, symbol, side, quantity, stopPriceTicks, tif);
    }
}
