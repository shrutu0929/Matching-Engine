package com.matchbook.model;

public record OrderEvent(String orderId, String symbol, EventType type, OrderStatus status, long remainingQuantity, String detail) {
    public enum EventType {
        ACCEPTED,
        PARTIALLY_FILLED,
        FILLED,
        CANCELLED,
        MODIFIED,
        REJECTED
    }
}
