package com.matchbook.benchmark;

import com.matchbook.engine.OrderBook;
import com.matchbook.model.LimitOrder;
import com.matchbook.model.Side;
import com.matchbook.model.TimeInForce;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(1)
public class OrderBookBenchmark {
    private OrderBook book;
    private AtomicLong ids;

    @Setup
    public void setup() {
        book = new OrderBook("BENCH");
        ids = new AtomicLong();
    }

    @Benchmark
    public long matchOneOrder() {
        long id = ids.incrementAndGet();
        book.submit(new LimitOrder("ask-" + id, "BENCH", Side.SELL, 1, 10_000, TimeInForce.GTC));
        return book.submit(new LimitOrder("bid-" + id, "BENCH", Side.BUY, 1, 10_000, TimeInForce.IOC))
                .trades().size();
    }
}
