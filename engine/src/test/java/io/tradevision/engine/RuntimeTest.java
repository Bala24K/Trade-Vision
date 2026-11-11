package io.tradevision.engine;

import io.tradevision.engine.journal.Journal;
import io.tradevision.engine.runtime.EngineRunner;
import io.tradevision.engine.runtime.LatencyHistogram;
import io.tradevision.engine.runtime.RingBuffer;
import io.tradevision.engine.runtime.SequencedListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeTest {
    @TempDir
    Path tmp;

    @Test
    void ringBufferDeliversEverythingInClaimOrderUnderContention() throws Exception {
        var ring = new RingBuffer<long[]>(1024, () -> new long[2]);
        int producers = 4, perProducer = 200_000;
        var start = new CountDownLatch(1);
        Thread[] threads = new Thread[producers];
        for (int p = 0; p < producers; p++) {
            final int id = p;
            threads[p] = new Thread(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                for (int i = 0; i < perProducer; i++) {
                    long s = ring.claim();
                    long[] slot = ring.get(s);
                    slot[0] = id;
                    slot[1] = i;
                    ring.publish(s);
                }
            });
            threads[p].start();
        }
        start.countDown();
        long[] lastPerProducer = new long[producers];
        java.util.Arrays.fill(lastPerProducer, -1);
        long total = 0;
        long[] expectedSeq = {1};
        while (total < (long) producers * perProducer) {
            total += ring.poll((slot, seq) -> {
                assertEquals(expectedSeq[0]++, seq);
                int id = (int) slot[0];
                assertEquals(lastPerProducer[id] + 1, slot[1], "per-producer FIFO");
                lastPerProducer[id] = slot[1];
            }, 256);
        }
        for (Thread t : threads) t.join();
        assertEquals(0, ring.depth());
    }

    @Test
    void tryClaimFailsFastWhenFull() {
        var ring = new RingBuffer<long[]>(4, () -> new long[1]);
        for (int i = 0; i < 4; i++) ring.publish(ring.tryClaim());
        assertEquals(-1, ring.tryClaim(), "full ring rejects instead of blocking");
        ring.poll((s, q) -> {}, 1);
        assertTrue(ring.tryClaim() > 0, "space frees up after consumption");
    }

    @Test
    void histogramPercentilesAreWithinBucketError() {
        var h = new LatencyHistogram();
        for (int i = 1; i <= 100_000; i++) h.record(i);
        assertEquals(100_000, h.count());
        long p50 = h.percentile(50), p99 = h.percentile(99);
        assertTrue(Math.abs(p50 - 50_000) < 50_000 * 0.04, "p50=" + p50);
        assertTrue(Math.abs(p99 - 99_000) < 99_000 * 0.04, "p99=" + p99);
    }

    @Test
    void journalerAndMatcherConsumeInParallelAndRecoveryMatches() throws Exception {
        Path j = tmp.resolve("journal"), s = tmp.resolve("snap");
        var engine = new MatchingEngine(EngineListener.NO_OP);
        AtomicLong applied = new AtomicLong();
        AtomicLong trades = new AtomicLong();
        var listener = new SequencedListener() {
            @Override public void onBatchApplied(long seq) {
                assertTrue(seq > applied.get(), "applied sequence is monotonic");
                applied.set(seq);
            }
            @Override public void onTrade(long id, Market m, int p, long q, io.tradevision.engine.book.Order mk,
                                          io.tradevision.engine.book.Order tk, long ts) { trades.incrementAndGet(); }
        };
        engine.setListener(listener);
        var journal = new Journal(j, 1 << 20, Journal.SyncPolicy.OS_CACHE);
        var runner = new EngineRunner(engine, journal, listener, () -> System.currentTimeMillis() * 1000, j, s, 0, 4096, 512)
                .start();

        runner.submit(c -> c.admin(CommandType.CREATE_MARKET, 1, 0));
        for (int a = 1; a <= 2; a++) {
            final long acct = a;
            runner.submit(c -> c.admin(CommandType.CREATE_ACCOUNT, acct, 0));
            runner.submit(c -> c.admin(CommandType.DEPOSIT, acct, 100_000_000));
        }
        int orders = 50_000;
        for (int i = 0; i < orders; i++) {
            final int n = i;
            runner.submit(c -> c.placeOrder(1 + (n & 1), n, 1, n % 2 == 0 ? Outcome.YES : Outcome.NO, Action.BUY,
                    TimeInForce.IOC, 500, 1));
            if (i % 2 == 0) runner.submit(c -> c.placeOrder(1, 1_000_000 + n, 1, Outcome.YES, Action.BUY, TimeInForce.GTC, 500, 1));
        }
        runner.stop();

        long expected = 5 + orders + orders / 2;
        assertEquals(expected, runner.processed());
        assertEquals(expected, applied.get());
        assertEquals(expected, runner.durableSequence(), "everything applied is also durable after a clean stop");
        assertTrue(trades.get() > 0);
        assertTrue(runner.journalFlushes() < expected, "group commit: fewer flushes than commands");

        var rec = EngineSnapshot.recover(s, j, EngineListener.NO_OP, EngineListener.NO_OP);
        assertEquals(expected, rec.lastSequence());
        assertEquals(EngineDigest.of(engine), EngineDigest.of(rec.engine()));
    }
}
