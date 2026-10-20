package com.matchbook.model;

import java.util.Objects;

public abstract class Order {
    private final String id;
    private final String symbol;
    private final Side side;
    private final long quantity;
    private final TimeInForce timeInForce;

    protected Order(String id, String symbol, Side side, long quantity, TimeInForce timeInForce) {
        this.id = requireText(id, "id");
        this.symbol = requireText(symbol, "symbol");
        this.side = Objects.requireNonNull(side, "side");
        this.timeInForce = Objects.requireNonNull(timeInForce, "timeInForce");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        this.quantity = quantity;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public abstract OrderType type();

    public String id() {
        return id;
    }

    public String symbol() {
        return symbol;
    }

    public Side side() {
        return side;
    }

    public long quantity() {
        return quantity;
    }

    public TimeInForce timeInForce() {
        return timeInForce;
    }
}
