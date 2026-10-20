# Matchbook — Order Matching Engine

Matchbook is a Java 17, single-symbol order book and sharded asynchronous matching engine. Prices are represented as integer ticks (never floating-point values).

## Features

- Price-time priority using `TreeMap` price levels and FIFO queues.
- Limit, market, and stop-market order types; partial fills; cancel and modify.
- GTC, IOC, and FOK time-in-force rules.
- Immutable trade/order event records and observer hooks for trade and audit streams.
- Per-symbol command serialization on bounded `BlockingQueue`s, with symbols processed concurrently by a shared `ExecutorService`.
- Optional JMH throughput benchmark.

Each `OrderBook` is single-writer and intentionally does not add synchronization to its matching path. Use `MatchingEngine` for concurrent callers; it routes every symbol to one serial command worker. When a symbol queue is full, the command fails explicitly with `IllegalStateException`. A future completes exceptionally for command failures.

Stop orders are stop-market orders: buy stops trigger at or above their stop price and sell stops at or below. Once triggered, their remaining quantity executes against the book using IOC or FOK behavior. Market orders must use IOC or FOK. FOK orders are checked against current visible liquidity before any fill.

## Build and run

Requires JDK 17 or newer. Maven is optional for the basic application.

```powershell
.\run-tests.ps1
```

With Maven installed:

```powershell
mvn test
mvn -q exec:java -Dexec.mainClass=com.matchbook.Example
```

Or compile/run the example directly with `javac` after compiling `src\main\java`.

## JMH benchmark

The benchmark matches one incoming order against a resting order. Install Maven and run:

```powershell
mvn -Pjmh clean package
java -jar target\benchmarks.jar OrderBookBenchmark
```
