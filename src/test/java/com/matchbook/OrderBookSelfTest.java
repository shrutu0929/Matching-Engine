package com.matchbook;

import com.matchbook.engine.OrderBook;
import com.matchbook.engine.OrderBookSnapshot;
import com.matchbook.model.LimitOrder;
import com.matchbook.model.OrderFactory;
import com.matchbook.model.OrderResult;
import com.matchbook.model.OrderStatus;
import com.matchbook.model.Side;
import com.matchbook.model.TimeInForce;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class OrderBookSelfTest {
    private OrderBookSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        matchesByPriceThenTimeAndSupportsPartialFills();
        fokIsAtomicAndIocCancelsRemainder();
        modifiesAndCancelsRestingOrders();
        triggersStopOrders();
        serializesCommandsPerSymbol();
        System.out.println("All Matchbook self-tests passed.");
    }

    private static void matchesByPriceThenTimeAndSupportsPartialFills() {
        OrderBook book = new OrderBook("XYZ");
        book.submit(limit("ask-1", Side.SELL, 2, 100, TimeInForce.GTC));
        book.submit(limit("ask-2", Side.SELL, 3, 100, TimeInForce.GTC));
        book.submit(limit("ask-3", Side.SELL, 2, 101, TimeInForce.GTC));

        OrderResult result = book.submit(limit("buyer", Side.BUY, 6, 101, TimeInForce.IOC));
        check(result.trades().size() == 3, "expected three fills");
        check(result.trades().get(0).sellOrderId().equals("ask-1"), "first order should have time priority");
        check(result.trades().get(1).sellOrderId().equals("ask-2"), "second order should have time priority");
        check(result.trades().get(2).priceTicks() == 101, "third fill should use the next price level");
        check(book.statusOf("buyer") == OrderStatus.FILLED, "buyer should be filled");
        check(book.statusOf("ask-3") == OrderStatus.PARTIALLY_FILLED, "last ask should be partially filled");
        check(book.snapshot().asks().get(0).quantity() == 1, "one share should remain at 101");
    }

    private static void fokIsAtomicAndIocCancelsRemainder() {
        OrderBook book = new OrderBook("XYZ");
        book.submit(limit("ask", Side.SELL, 2, 100, TimeInForce.GTC));
        OrderResult fok = book.submit(limit("fok", Side.BUY, 3, 100, TimeInForce.FOK));
        check(fok.trades().isEmpty(), "unfillable FOK must not trade");
        check(book.snapshot().asks().get(0).quantity() == 2, "FOK must not change the book");

        OrderResult ioc = book.submit(limit("ioc", Side.BUY, 3, 100, TimeInForce.IOC));
        check(ioc.trades().size() == 1, "IOC should fill available liquidity");
        check(ioc.status() == OrderStatus.CANCELLED, "IOC remainder should be cancelled");
        check(ioc.remainingQuantity() == 1, "IOC should report the unfilled amount");
    }

    private static void modifiesAndCancelsRestingOrders() {
        OrderBook book = new OrderBook("XYZ");
        book.submit(limit("first", Side.BUY, 4, 99, TimeInForce.GTC));
        book.submit(limit("second", Side.BUY, 3, 99, TimeInForce.GTC));
        book.modify("first", 2, null);
        check(book.snapshot().bids().get(0).quantity() == 5, "quantity reduction should update level total");
        book.cancel("first");
        check(book.snapshot().bids().get(0).quantity() == 3, "cancel should remove remaining quantity");
    }

    private static void triggersStopOrders() {
        OrderBook book = new OrderBook("XYZ");
        book.submit(limit("ask-1", Side.SELL, 1, 105, TimeInForce.GTC));
        book.submit(limit("ask-2", Side.SELL, 1, 106, TimeInForce.GTC));
        book.submit(OrderFactory.stop("stop-buy", "XYZ", Side.BUY, 1, 105, TimeInForce.IOC));
        book.submit(limit("trigger", Side.BUY, 1, 105, TimeInForce.IOC));
        check(book.statusOf("stop-buy") == OrderStatus.FILLED, "stop should trigger and execute");
        check(book.snapshot().asks().isEmpty(), "stop execution should consume next ask");
    }

    private static void serializesCommandsPerSymbol() throws Exception {
        try (var engine = new com.matchbook.engine.MatchingEngine(2, 1_024)) {
            AtomicInteger acceptedEvents = new AtomicInteger();
            engine.addOrderObserver("XYZ", event -> {
                if (event.type() == com.matchbook.model.OrderEvent.EventType.ACCEPTED) {
                    acceptedEvents.incrementAndGet();
                }
            });
            CompletableFuture<?>[] submissions = new CompletableFuture<?>[100];
            for (int i = 0; i < submissions.length; i++) {
                submissions[i] = engine.submit(limit("order-" + i, Side.BUY, 1, 100, TimeInForce.GTC));
            }
            CompletableFuture.allOf(submissions).get(5, TimeUnit.SECONDS);
            OrderBookSnapshot snapshot = engine.snapshot("XYZ").get(5, TimeUnit.SECONDS);
            check(snapshot.bids().size() == 1, "same-price orders should aggregate at one level");
            check(snapshot.bids().get(0).quantity() == 100, "all submitted quantities should be present");
            check(acceptedEvents.get() == 100, "observer registration should work before first order");
        }
    }

    private static LimitOrder limit(String id, Side side, long quantity, long price, TimeInForce tif) {
        return new LimitOrder(id, "XYZ", side, quantity, price, tif);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
