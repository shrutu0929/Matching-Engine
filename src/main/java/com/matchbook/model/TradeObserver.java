package com.matchbook.model;

@FunctionalInterface
public interface TradeObserver {
    void onTrade(Trade trade);
}
