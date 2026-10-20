package com.matchbook.model;

public record Trade(String symbol, String buyOrderId, String sellOrderId, long priceTicks, long quantity, long timestampMillis) {
}
