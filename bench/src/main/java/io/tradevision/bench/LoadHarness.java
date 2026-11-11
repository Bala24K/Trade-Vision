package io.tradevision.bench;

import io.tradevision.engine.Action;
import io.tradevision.engine.Command;
import io.tradevision.engine.CommandType;
import io.tradevision.engine.Market;
import io.tradevision.engine.MatchingEngine;
import io.tradevision.engine.Outcome;
import io.tradevision.engine.TimeInForce;
import io.tradevision.engine.book.Order;
import io.tradevision.engine.journal.Journal;
import io.tradevision.engine.runtime.EngineRunner;
import io.tradevision.engine.runtime.SequencedListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * End-to-end load test of the real pipeline: N producer threads -> broadcast ring -> journaler thread
 * (append + group-commit flush) and matcher thread (match + accounting) in parallel. Two modes:
 *
 * <ul>
 *   <li><b>saturate</b>: producers submit as fast as possible. Reports max sustained throughput.</li>
 *   <li><b>paced</b>: producers submit at a fixed aggregate rate. Reports the latency distribution at a
 *       realistic load, which is what a trader actually feels. (Latency at saturation is just queue length.)</li>
 * </ul>
 *
 * Usage: {@code java -cp benchmarks.jar io.tradevision.bench.LoadHarness [saturate|paced] [commands] [rate/s] [fsync|nosync]}
 */
public final class LoadHarness {
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "saturate";
        long total = args.length > 1 ? Long.parseLong(args[1]) : 5_000_000;
        long rate = args.length > 2 ? Long.parseLong(args[2]) : 200_000;
        Journal.SyncPolicy sync = args.length > 3 && args[3].equals("fsync") ? Journal.SyncPolicy.EVERY_BATCH : Journal.SyncPolicy.OS_CACHE;
        boolean paced = mode.equals("paced");
        int producers = 4, markets = 8, accounts = 64;

        Path dir = Files.createTempDirectory("tv-load");
        try {
            AtomicLong trades = new AtomicLong();
            var listener = new SequencedListener() {
                @Override
                public void onTrade(long id, Market m, int p, long q, Order maker, Order taker, long ts) {
                    trades.incrementAndGet();
                }
            };
            int presize = Integer.getInteger("tv.presize", 1 << 16);
            var engine = new MatchingEngine(listener, 1 << 20, presize);
            var journal = new Journal(dir.resolve("journal"), 256L << 20, sync);
            var runner = new EngineRunner(engine, journal, listener, () -> System.nanoTime() / 1000,
                    dir.resolve("journal"), dir.resolve("snap"), 0, 1 << 16, 4096).start();

            for (int m = 1; m <= markets; m++) {
                final long id = m;
                runner.submit(c -> c.admin(CommandType.CREATE_MARKET, id, 0));
            }
            for (int a = 1; a <= accounts; a++) {
                final long id = a;
                runner.submit(c -> c.admin(CommandType.CREATE_ACCOUNT, id, 0));
                runner.submit(c -> c.admin(CommandType.DEPOSIT, id, Long.MAX_VALUE / 1024));
            }
            while (runner.queueDepth() > 0) Thread.onSpinWait();
            long setupCommands = runner.processed();
            runner.ingressToApplied().reset();
            runner.ingressToDurable().reset();
            runner.applyTime().reset();

            long perProducer = total / producers;
            long intervalNanos = paced ? producers * 1_000_000_000L / rate : 0;
            var start = new CountDownLatch(1);
            AtomicLong rejected = new AtomicLong();
            Thread[] threads = new Thread[producers];
            for (int p = 0; p < producers; p++) {
                final int pid = p;
                threads[p] = Thread.ofPlatform().name("producer-" + p).start(() -> {
                    var rnd = new SplittableRandom(pid);
                    long[] recent = new long[1024];
                    long[] counter = {0};
                    Consumer<Command> fill = c -> {
                        long i = counter[0];
                        long acct = 1 + rnd.nextInt(accounts);
                        long market = 1 + rnd.nextInt(markets);
                        int slot = (int) (i & 1023);
                        if (rnd.nextInt(100) < 35 && recent[slot] != 0) {
                            // Cancel a guess at a recent order id; misses are cheap rejects, like stale cancels in reality.
                            c.cancelOrder(acct, recent[slot]);
                        } else {
                            boolean yes = rnd.nextBoolean();
                            int mid = 400 + (int) (market * 20);
                            int px = mid + (rnd.nextInt(100) < 15 ? (yes ? 3 : -3) : (yes ? -1 : 1) * (1 + rnd.nextInt(10)));
                            int limit = Math.clamp(yes ? px : 1000 - px, 1, 999);
                            c.placeOrder(acct, i, market, yes ? Outcome.YES : Outcome.NO, Action.BUY, TimeInForce.GTC,
                                    limit, 1 + rnd.nextInt(50));
                            recent[slot] = i * producers + pid;
                        }
                        c.ingressNanos = System.nanoTime();
                    };
                    try { start.await(); } catch (InterruptedException e) { return; }
                    long next = System.nanoTime();
                    for (long i = 0; i < perProducer; i++) {
                        counter[0] = i;
                        if (paced) {
                            next += intervalNanos;
                            long wait;
                            while ((wait = next - System.nanoTime()) > 0) {
                                if (wait > 20_000) LockSupport.parkNanos(wait - 10_000);
                                else Thread.onSpinWait();
                            }
                            if (!runner.trySubmit(fill)) rejected.incrementAndGet();
                        } else {
                            runner.submit(fill);
                        }
                    }
                });
            }
            long t0 = System.nanoTime();
            start.countDown();
            for (Thread t : threads) t.join();
            while (runner.queueDepth() > 0) Thread.onSpinWait();
            long elapsed = System.nanoTime() - t0;
            runner.stop();

            long processed = runner.processed() - setupCommands;
            System.out.printf("%n=== TradeVision load harness: %s, journal %s ===%n", mode, sync);
            System.out.printf("commands          %,d in %.2fs -> %,.0f cmd/s%n", processed, elapsed / 1e9, processed / (elapsed / 1e9));
            System.out.printf("trades            %,d%n", trades.get());
            System.out.printf("journal           %,d flushes, avg batch %.1f cmds, %,d MB%n", runner.journalFlushes(),
                    (double) runner.processed() / Math.max(1, runner.journalFlushes()), runner.journalBytes() >> 20);
            System.out.printf("match (apply)     %s%n", runner.applyTime().summary("ns", 1));
            System.out.printf("ingress->applied  %s%n", runner.ingressToApplied().summary("us", 1000));
            System.out.printf("ingress->durable  %s  (sampled per batch)%n", runner.ingressToDurable().summary("us", 1000));
            System.out.printf("resting orders    %,d (index pre-sized for %,d)%n", engine.openOrders().size(), presize);
            if (rejected.get() > 0) System.out.printf("backpressure      %,d rejected%n", rejected.get());
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private LoadHarness() {}
}
