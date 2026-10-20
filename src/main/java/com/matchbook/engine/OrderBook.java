package com.matchbook.engine;

import com.matchbook.model.LimitOrder;
import com.matchbook.model.MarketOrder;
import com.matchbook.model.Order;
import com.matchbook.model.OrderEvent;
import com.matchbook.model.OrderObserver;
import com.matchbook.model.OrderResult;
import com.matchbook.model.OrderStatus;
import com.matchbook.model.Side;
import com.matchbook.model.StopOrder;
import com.matchbook.model.TimeInForce;
import com.matchbook.model.Trade;
import com.matchbook.model.TradeObserver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class OrderBook {
    private final String symbol;
    private final NavigableMap<Long, ArrayDeque<Entry>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<Long, ArrayDeque<Entry>> asks = new TreeMap<>();
    private final NavigableMap<Long, ArrayDeque<StopOrder>> buyStops = new TreeMap<>();
    private final NavigableMap<Long, ArrayDeque<StopOrder>> sellStops = new TreeMap<>(Comparator.reverseOrder());
    private final Map<String, Entry> restingOrders = new HashMap<>();
    private final Map<String, StopOrder> pendingStops = new HashMap<>();
    private final Map<String, OrderStatus> statuses = new HashMap<>();
    private final Set<String> usedIds = new HashSet<>();
    private final List<OrderObserver> orderObservers = new CopyOnWriteArrayList<>();
    private final List<TradeObserver> tradeObservers = new CopyOnWriteArrayList<>();
    private long lastTradePrice;

    public OrderBook(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    public void addOrderObserver(OrderObserver observer) {
        orderObservers.add(Objects.requireNonNull(observer, "observer"));
    }

    public void addTradeObserver(TradeObserver observer) {
        tradeObservers.add(Objects.requireNonNull(observer, "observer"));
    }

    public OrderResult submit(Order order) {
        Objects.requireNonNull(order, "order");
        if (!symbol.equals(order.symbol())) {
            throw new IllegalArgumentException("order symbol does not match this book");
        }
        if (!usedIds.add(order.id())) {
            throw new IllegalArgumentException("order id has already been used: " + order.id());
        }
        statuses.put(order.id(), OrderStatus.NEW);
        if (order instanceof StopOrder stop) {
            if (isTriggered(stop, lastTradePrice)) {
                return execute(stop, true, new ArrayDeque<>(), true);
            }
            pendingStops.put(stop.id(), stop);
            NavigableMap<Long, ArrayDeque<StopOrder>> stopSide = stop.side() == Side.BUY ? buyStops : sellStops;
            stopSide.computeIfAbsent(stop.stopPriceTicks(), ignored -> new ArrayDeque<>()).addLast(stop);
            statuses.put(stop.id(), OrderStatus.OPEN);
            emit(stop, OrderEvent.EventType.ACCEPTED, OrderStatus.OPEN, stop.quantity(), "stop order accepted");
            return result(stop.id(), stop.quantity(), List.of());
        }
        return execute(order, false, new ArrayDeque<>(), true);
    }

    public OrderResult cancel(String orderId) {
        StopOrder stop = pendingStops.remove(orderId);
        if (stop != null) {
            removeStop(stop);
            statuses.put(orderId, OrderStatus.CANCELLED);
            emit(stop, OrderEvent.EventType.CANCELLED, OrderStatus.CANCELLED, stop.quantity(), "cancelled");
            return result(orderId, stop.quantity(), List.of());
        }
        Entry entry = restingOrders.remove(orderId);
        if (entry == null) {
            throw new IllegalArgumentException("no open order with id: " + orderId);
        }
        removeFromBook(entry);
        statuses.put(orderId, OrderStatus.CANCELLED);
        emit(entry.order, OrderEvent.EventType.CANCELLED, OrderStatus.CANCELLED, entry.remaining, "cancelled");
        return result(orderId, entry.remaining, List.of());
    }

    public OrderResult modify(String orderId, long newTotalQuantity, Long newPriceTicks) {
        if (newTotalQuantity <= 0) {
            throw new IllegalArgumentException("newTotalQuantity must be positive");
        }
        Entry entry = restingOrders.get(orderId);
        if (entry == null) {
            throw new IllegalArgumentException("no open limit order with id: " + orderId);
        }
        if (!(entry.order instanceof LimitOrder oldOrder)) {
            throw new IllegalArgumentException("only resting limit orders can be modified");
        }
        long price = newPriceTicks == null ? oldOrder.priceTicks() : newPriceTicks;
        if (price <= 0) {
            throw new IllegalArgumentException("newPriceTicks must be positive");
        }
        if (newTotalQuantity <= entry.filled) {
            throw new IllegalArgumentException("new total quantity must exceed the quantity already filled");
        }
        long newRemaining = newTotalQuantity - entry.filled;
        if (price == oldOrder.priceTicks() && newRemaining <= entry.remaining) {
            entry.remaining = newRemaining;
            emit(entry.order, OrderEvent.EventType.MODIFIED, statusOf(orderId), newRemaining, "quantity reduced; priority retained");
            return result(orderId, newRemaining, List.of());
        }

        restingOrders.remove(orderId);
        removeFromBook(entry);
        LimitOrder replacement = new LimitOrder(
                oldOrder.id(), oldOrder.symbol(), oldOrder.side(), newRemaining, price, TimeInForce.GTC);
        entry.order = replacement;
        entry.remaining = newRemaining;
        entry.filled = 0;
        statuses.put(orderId, OrderStatus.NEW);
        return execute(replacement, false, new ArrayDeque<>(), false);
    }

    public OrderStatus statusOf(String orderId) {
        OrderStatus status = statuses.get(orderId);
        if (status == null) {
            throw new IllegalArgumentException("unknown order id: " + orderId);
        }
        return status;
    }

    public OrderBookSnapshot snapshot() {
        return new OrderBookSnapshot(symbol, levels(bids), levels(asks));
    }

    private OrderResult execute(Order order, boolean triggeredStop, ArrayDeque<StopOrder> triggerQueue, boolean emitAccepted) {
        Entry incoming = new Entry(order, order.quantity());
        statuses.put(order.id(), OrderStatus.OPEN);
        if (emitAccepted) {
            emit(order, OrderEvent.EventType.ACCEPTED, OrderStatus.OPEN, incoming.remaining,
                    triggeredStop ? "stop triggered" : "accepted");
        }
        if (order.timeInForce().requiresFullFill() && availableQuantity(order, triggeredStop) < incoming.remaining) {
            statuses.put(order.id(), OrderStatus.CANCELLED);
            emit(order, OrderEvent.EventType.CANCELLED, OrderStatus.CANCELLED, incoming.remaining, "FOK could not be filled completely");
            return result(order.id(), incoming.remaining, List.of());
        }

        List<Trade> trades = new ArrayList<>();
        while (incoming.remaining > 0) {
            Entry maker = bestOpposing(order.side());
            if (maker == null || !crosses(order, maker, triggeredStop)) {
                break;
            }
            long quantity = Math.min(incoming.remaining, maker.remaining);
            long price = ((LimitOrder) maker.order).priceTicks();
            incoming.remaining -= quantity;
            incoming.filled += quantity;
            maker.remaining -= quantity;
            maker.filled += quantity;
            Trade trade = new Trade(symbol,
                    order.side() == Side.BUY ? order.id() : maker.order.id(),
                    order.side() == Side.SELL ? order.id() : maker.order.id(),
                    price, quantity, System.currentTimeMillis());
            trades.add(trade);
            lastTradePrice = price;
            updateFilledStatus(maker);
            if (maker.remaining == 0) {
                restingOrders.remove(maker.order.id());
                removeFromBook(maker);
                statuses.put(maker.order.id(), OrderStatus.FILLED);
                emit(maker.order, OrderEvent.EventType.FILLED, OrderStatus.FILLED, 0, "fully filled");
            } else {
                statuses.put(maker.order.id(), OrderStatus.PARTIALLY_FILLED);
                emit(maker.order, OrderEvent.EventType.PARTIALLY_FILLED, OrderStatus.PARTIALLY_FILLED,
                        maker.remaining, "partially filled");
            }
            for (TradeObserver observer : tradeObservers) {
                observer.onTrade(trade);
            }
            enqueueTriggeredStops(price, triggerQueue);
        }

        if (incoming.remaining == 0) {
            statuses.put(order.id(), OrderStatus.FILLED);
            emit(order, OrderEvent.EventType.FILLED, OrderStatus.FILLED, 0, "fully filled");
        } else if (order instanceof LimitOrder limit && order.timeInForce().restsRemainder()) {
            incoming.order = limit;
            restingOrders.put(order.id(), incoming);
            sideBook(order.side()).computeIfAbsent(limit.priceTicks(), ignored -> new ArrayDeque<>()).addLast(incoming);
            statuses.put(order.id(), incoming.filled > 0 ? OrderStatus.PARTIALLY_FILLED : OrderStatus.OPEN);
            if (incoming.filled > 0) {
                emit(order, OrderEvent.EventType.PARTIALLY_FILLED, OrderStatus.PARTIALLY_FILLED,
                        incoming.remaining, "partially filled; remainder resting");
            }
        } else {
            statuses.put(order.id(), OrderStatus.CANCELLED);
            emit(order, OrderEvent.EventType.CANCELLED, OrderStatus.CANCELLED, incoming.remaining,
                    incoming.filled > 0 ? "unfilled remainder cancelled" : "no matching liquidity");
        }

        while (!triggerQueue.isEmpty()) {
            StopOrder stop = triggerQueue.removeFirst();
            if (pendingStops.remove(stop.id()) == null) {
                continue;
            }
            execute(stop, true, triggerQueue, true);
        }
        return result(order.id(), incoming.remaining, trades);
    }

    private long availableQuantity(Order order, boolean triggeredStop) {
        long available = 0;
        for (ArrayDeque<Entry> level : sideBook(opposite(order.side())).values()) {
            for (Entry maker : level) {
                if (!crosses(order, maker, triggeredStop)) {
                    return available;
                }
                available = Math.addExact(available, maker.remaining);
            }
        }
        return available;
    }

    private boolean crosses(Order incoming, Entry maker, boolean triggeredStop) {
        if (incoming instanceof MarketOrder || triggeredStop) {
            return true;
        }
        long makerPrice = ((LimitOrder) maker.order).priceTicks();
        long limit = ((LimitOrder) incoming).priceTicks();
        return incoming.side() == Side.BUY ? limit >= makerPrice : limit <= makerPrice;
    }

    private Entry bestOpposing(Side side) {
        NavigableMap<Long, ArrayDeque<Entry>> opposing = sideBook(opposite(side));
        Map.Entry<Long, ArrayDeque<Entry>> level = opposing.firstEntry();
        return level == null ? null : level.getValue().peekFirst();
    }

    private void enqueueTriggeredStops(long price, ArrayDeque<StopOrder> queue) {
        while (!buyStops.isEmpty() && buyStops.firstKey() <= price) {
            queue.addAll(buyStops.pollFirstEntry().getValue());
        }
        while (!sellStops.isEmpty() && sellStops.firstKey() >= price) {
            queue.addAll(sellStops.pollFirstEntry().getValue());
        }
    }

    private boolean isTriggered(StopOrder stop, long price) {
        return price > 0 && (stop.side() == Side.BUY
                ? price >= stop.stopPriceTicks()
                : price <= stop.stopPriceTicks());
    }

    private void removeStop(StopOrder stop) {
        NavigableMap<Long, ArrayDeque<StopOrder>> book = stop.side() == Side.BUY ? buyStops : sellStops;
        ArrayDeque<StopOrder> level = book.get(stop.stopPriceTicks());
        if (level != null) {
            level.remove(stop);
            if (level.isEmpty()) {
                book.remove(stop.stopPriceTicks());
            }
        }
    }

    private void removeFromBook(Entry entry) {
        LimitOrder limit = (LimitOrder) entry.order;
        NavigableMap<Long, ArrayDeque<Entry>> book = sideBook(entry.order.side());
        ArrayDeque<Entry> level = book.get(limit.priceTicks());
        if (level != null) {
            level.remove(entry);
            if (level.isEmpty()) {
                book.remove(limit.priceTicks());
            }
        }
    }

    private void updateFilledStatus(Entry entry) {
        if (entry.remaining > 0) {
            statuses.put(entry.order.id(), OrderStatus.PARTIALLY_FILLED);
        }
    }

    private OrderResult result(String id, long remaining, List<Trade> trades) {
        return new OrderResult(id, statusOf(id), remaining, trades);
    }

    private void emit(Order order, OrderEvent.EventType type, OrderStatus status, long remaining, String detail) {
        OrderEvent event = new OrderEvent(order.id(), symbol, type, status, remaining, detail);
        for (OrderObserver observer : orderObservers) {
            observer.onOrderEvent(event);
        }
    }

    private List<BookLevel> levels(NavigableMap<Long, ArrayDeque<Entry>> book) {
        List<BookLevel> result = new ArrayList<>(book.size());
        for (Map.Entry<Long, ArrayDeque<Entry>> level : book.entrySet()) {
            long quantity = 0;
            for (Entry entry : level.getValue()) {
                quantity = Math.addExact(quantity, entry.remaining);
            }
            result.add(new BookLevel(level.getKey(), quantity, level.getValue().size()));
        }
        return result;
    }

    private NavigableMap<Long, ArrayDeque<Entry>> sideBook(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    private Side opposite(Side side) {
        return side == Side.BUY ? Side.SELL : Side.BUY;
    }

    private static final class Entry {
        private Order order;
        private long remaining;
        private long filled;

        private Entry(Order order, long remaining) {
            this.order = order;
            this.remaining = remaining;
        }
    }
}
