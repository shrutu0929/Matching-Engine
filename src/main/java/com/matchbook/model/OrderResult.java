package com.matchbook.model;

import java.util.List;

public record OrderResult(String orderId, OrderStatus status, long remainingQuantity, List<Trade> trades) {
    public OrderResult {
        trades = List.copyOf(trades);
    }
}
