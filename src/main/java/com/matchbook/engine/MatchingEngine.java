package com.matchbook.engine;

import com.matchbook.model.Order;
import com.matchbook.model.OrderObserver;
import com.matchbook.model.OrderResult;
import com.matchbook.model.TradeObserver;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class MatchingEngine implements AutoCloseable {
    private final ConcurrentHashMap<String, SymbolWorker> workers = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private boolean closed;

    public MatchingEngine(int shardCount, int queueCapacity) {
        if (shardCount <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("shardCount and queueCapacity must be positive");
        }
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "matchbook-shard-" + threadNumber.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
        executor = Executors.newFixedThreadPool(shardCount, threadFactory);
        this.queueCapacity = queueCapacity;
    }

    private final int queueCapacity;

    public CompletableFuture<OrderResult> submit(Order order) {
        Objects.requireNonNull(order, "order");
        return enqueue(order.symbol(), () -> book(order.symbol()).submit(order));
    }

    public CompletableFuture<OrderResult> cancel(String symbol, String orderId) {
        return enqueue(symbol, () -> book(symbol).cancel(orderId));
    }

    public CompletableFuture<OrderResult> modify(String symbol, String orderId, long newTotalQuantity, Long newPriceTicks) {
        return enqueue(symbol, () -> book(symbol).modify(orderId, newTotalQuantity, newPriceTicks));
    }

    public CompletableFuture<OrderBookSnapshot> snapshot(String symbol) {
        return enqueue(symbol, () -> book(symbol).snapshot());
    }

    public void addOrderObserver(String symbol, OrderObserver observer) {
        workerFor(symbol).book.addOrderObserver(observer);
    }

    public void addTradeObserver(String symbol, TradeObserver observer) {
        workerFor(symbol).book.addTradeObserver(observer);
    }

    private synchronized <T> CompletableFuture<T> enqueue(String symbol, Supplier<T> action) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("matching engine is closed"));
        }
        if (symbol == null || symbol.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("symbol must not be blank"));
        }
        SymbolWorker worker = workerFor(symbol);
        CompletableFuture<T> result = new CompletableFuture<>();
        worker.enqueue(() -> {
            try {
                result.complete(action.get());
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    private synchronized SymbolWorker workerFor(String symbol) {
        if (closed) {
            throw new IllegalStateException("matching engine is closed");
        }
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        return workers.computeIfAbsent(symbol, key -> new SymbolWorker(new OrderBook(key), queueCapacity));
    }

    private OrderBook book(String symbol) {
        SymbolWorker worker = workers.get(symbol);
        if (worker == null) {
            throw new IllegalStateException("symbol worker was not initialized");
        }
        return worker.book;
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            executor.shutdown();
        }
    }

    private final class SymbolWorker {
        private final OrderBook book;
        private final BlockingQueue<Runnable> queue;
        private final AtomicBoolean scheduled = new AtomicBoolean();

        private SymbolWorker(OrderBook book, int queueCapacity) {
            this.book = book;
            this.queue = new ArrayBlockingQueue<>(queueCapacity);
        }

        private void enqueue(Runnable task) {
            if (!queue.offer(task)) {
                throw new IllegalStateException("symbol command queue is full");
            }
            schedule();
        }

        private void schedule() {
            if (scheduled.compareAndSet(false, true)) {
                executor.execute(this::drain);
            }
        }

        private void drain() {
            Runnable task;
            while ((task = queue.poll()) != null) {
                task.run();
            }
            scheduled.set(false);
            if (!queue.isEmpty()) {
                schedule();
            }
        }
    }
}
