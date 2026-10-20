package com.matchbook.model;

@FunctionalInterface
public interface OrderObserver {
    void onOrderEvent(OrderEvent event);
}
