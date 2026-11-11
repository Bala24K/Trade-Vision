package io.tradevision.bench;

import io.tradevision.engine.Action;
import io.tradevision.engine.EngineListener;
import io.tradevision.engine.MatchingEngine;
import io.tradevision.engine.Outcome;
import io.tradevision.engine.TimeInForce;
import io.tradevision.engine.util.LongObjectMap;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Replays the same 1M-operation tape through:
 * <ol>
 *   <li>{@code treeMapBaseline}: TreeMap + ArrayDeque + HashMap (bare book, no accounting)</li>
 *   <li>{@code engine}: the full MatchingEngine (array/bitset book, intrusive lists, pooled orders,
 *       <i>plus</i> risk checks, reservations, position accounting and event callbacks)</li>
 * </ol>
 * The engine does strictly more work per operation; if it is still faster, the data-structure choice is
 * carrying its weight. Reported as average time per operation.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"-Xms2g", "-Xmx2g", "-XX:+UseParallelGC"})
public class OrderBookBench {
    static final int OPS = 1_000_000;
    OrderTape tape;
    TreeMapBook treeBook;
    MatchingEngine engine;
    LongObjectMap<long[]> tapeToEngineId;

    @Setup(Level.Trial)
    public void tape() {
        tape = new OrderTape(OPS, 1);
    }

    @Setup(Level.Invocation)
    public void fresh() {
        treeBook = new TreeMapBook();
        engine = new MatchingEngine(EngineListener.NO_OP, 1 << 20);
        engine.createMarket(1, 0);
        for (int a = 1; a <= 2; a++) {
            engine.createAccount(a, 0);
            engine.deposit(a, Long.MAX_VALUE / 4, 0);
        }
        tapeToEngineId = new LongObjectMap<>(OPS);
    }

    @Benchmark
    @OperationsPerInvocation(OPS)
    public void treeMapBaseline(Blackhole bh) {
        TreeMapBook b = treeBook;
        OrderTape t = tape;
        for (int i = 0; i < t.length; i++) {
            if (t.op[i] == OrderTape.PLACE) b.place(t.id[i], t.bid[i], t.price[i], t.qty[i]);
            else b.cancel(t.id[i]);
        }
        bh.consume(b.traded);
    }

    @Benchmark
    @OperationsPerInvocation(OPS)
    public void engine(Blackhole bh) {
        MatchingEngine e = engine;
        OrderTape t = tape;
        long[] engineId = new long[OPS + 1]; // tape id -> engine order id (tape ids are dense)
        for (int i = 0; i < t.length; i++) {
            if (t.op[i] == OrderTape.PLACE) {
                // Bids = BUY YES (account 1); asks = BUY NO (account 2) at the complement, so no inventory is needed.
                long id = t.bid[i]
                        ? e.placeOrder(1, t.id[i], 1, Outcome.YES, Action.BUY, TimeInForce.GTC, t.price[i], t.qty[i], i)
                        : e.placeOrder(2, t.id[i], 1, Outcome.NO, Action.BUY, TimeInForce.GTC, 1000 - t.price[i], t.qty[i], i);
                engineId[(int) t.id[i]] = id;
            } else {
                long tid = t.id[i];
                long id = engineId[(int) tid];
                var o = e.order(id);
                if (o != null) e.cancelOrder(o.accountId, id, i);
            }
        }
        bh.consume(e.lastTradeId());
    }
}
