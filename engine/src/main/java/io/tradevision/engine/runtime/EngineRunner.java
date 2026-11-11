package io.tradevision.engine.runtime;

import io.tradevision.engine.Command;
import io.tradevision.engine.EngineSnapshot;
import io.tradevision.engine.MatchingEngine;
import io.tradevision.engine.journal.Journal;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Owns the engine's two threads and the inbound ring they share.
 *
 * <pre>
 *   gateway threads ──submit──▶ [ inbound RingBuffer&lt;Command&gt;  (seq = journal position) ]
 *                                       │                              │
 *                        consumer 0     ▼                consumer 1    ▼
 *                 ┌──── journaler thread ────┐    ┌──── matcher thread ──────────┐
 *                 │ append batch (memcpy)    │    │ engine.apply(cmd) per slot   │
 *                 │ 1 write + (opt) 1 fsync  │    │ events ──▶ listener          │
 *                 │ durable = last seq  ─────┼──▶ │ onBatchApplied(seq)          │
 *                 └──────────────────────────┘    └──────────────────────────────┘
 *                                                       publisher releases events
 *                                                       only once durable >= seq
 * </pre>
 *
 * <h2>Why two consumers (measured, see docs/benchmarks.md)</h2>
 * The first version journaled on the matching thread: append, match, then flush once per batch. At a
 * paced 200k cmd/s matching took p99 ≈ 3 µs, yet end-to-end p99 was ≈ 6.7 ms, because each batch's
 * {@code write()} syscall occasionally stalled for milliseconds and every queued command waited behind it.
 * Making the journal a <i>parallel</i> consumer of the same slots (the LMAX architecture) takes disk I/O off
 * the matching path entirely. Safety is unchanged: outputs are still withheld until
 * {@link #durableSequence()} covers them, so nothing is acknowledged that replay could not reproduce.
 *
 * <h2>Sequencing</h2>
 * The ring sequence <i>is</i> the journal sequence ({@code base + ringSeq}), stamped by the producer at claim
 * time together with the gateway timestamp. Both consumers therefore agree on order and time without
 * talking to each other, and treat slots as read-only.
 */
public final class EngineRunner {
    public static final int JOURNALER = 0;
    public static final int MATCHER = 1;

    private final RingBuffer<Command> inbound;
    private final MatchingEngine engine;
    private final Journal journal;
    private final SequencedListener listener;
    private final LongSupplier clockMicros;
    private final Path journalDir;
    private final Path snapshotDir;
    private final long snapshotEvery;
    private final int maxBatch;
    private final long base;

    private final LatencyHistogram ingressToApplied = new LatencyHistogram();
    private final LatencyHistogram ingressToDurable = new LatencyHistogram();
    private final LatencyHistogram applyTime = new LatencyHistogram();
    private final RingBuffer.Sequence durable;
    private final CountDownLatch stopped = new CountDownLatch(2);

    private volatile boolean running = true;
    private volatile long processed;
    private long lastSnapshotSequence;
    private volatile Thread journalThread;
    private volatile Thread matcherThread;
    private final Idle journalIdle = new Idle(false, JOURNALER);
    private final Idle matcherIdle;

    public EngineRunner(MatchingEngine engine, Journal journal, SequencedListener listener, LongSupplier clockMicros,
                        Path journalDir, Path snapshotDir, long snapshotEvery, int ringSize, int maxBatch) {
        this.inbound = new RingBuffer<>(ringSize, 2, Command::new);
        this.engine = engine;
        this.journal = journal;
        this.listener = listener;
        this.clockMicros = clockMicros;
        this.journalDir = journalDir;
        this.snapshotDir = snapshotDir;
        this.snapshotEvery = snapshotEvery;
        this.maxBatch = maxBatch;
        this.base = journal.lastSequence();
        this.durable = new RingBuffer.Sequence(base);
        this.lastSnapshotSequence = base;
        this.matcherIdle = new Idle(Boolean.getBoolean("tv.busySpin"), MATCHER);
    }

    // ------------------------------------------------------------------ producer API (any thread)

    /** Submit without blocking. @return false if the ring is full (caller should reject: "overloaded"). */
    public boolean trySubmit(Consumer<Command> fill) {
        long s = inbound.tryClaim();
        if (s < 0) return false;
        stampAndPublish(s, fill);
        return true;
    }

    /** Submit, waiting for space. For internal producers (admin, simulators) that must not drop. */
    public void submit(Consumer<Command> fill) {
        stampAndPublish(inbound.claim(), fill);
    }

    private void stampAndPublish(long s, Consumer<Command> fill) {
        Command c = inbound.get(s);
        c.ingressNanos = 0; // slots are reused: never inherit measurement/routing fields from a previous command
        c.sessionId = 0;
        fill.accept(c);
        c.canonicalize(); // before publish: matcher, journaler and replay must all see the same command (audit C1)
        c.sequence = base + s;
        c.timestamp = clockMicros.getAsLong();
        if (c.ingressNanos == 0) c.ingressNanos = System.nanoTime();
        inbound.publish(s);
        // Wake-on-submit: a parked consumer is unparked right away instead of oversleeping its park timeout.
        // The full fence orders our publish before reading the flags (StoreLoad); the consumer re-checks the ring
        // after raising its flag, so between the two sides a wakeup can never be lost.
        java.lang.invoke.VarHandle.fullFence();
        if (journalIdle.parked) LockSupport.unpark(journalThread);
        if (matcherIdle.parked) LockSupport.unpark(matcherThread);
    }

    // ------------------------------------------------------------------ lifecycle

    public EngineRunner start() {
        journalThread = Thread.ofPlatform().name("tv-journaler").start(this::journalLoop);
        matcherThread = Thread.ofPlatform().name("tv-matcher").start(this::matchLoop);
        return this;
    }

    public void stop() throws InterruptedException {
        running = false;
        if (journalThread != null) LockSupport.unpark(journalThread);
        if (matcherThread != null) LockSupport.unpark(matcherThread);
        stopped.await(30, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ journaler

    private final RingBuffer.Handler<Command> journalOne = this::journalOne;

    private void journalOne(Command c, long s) {
        journal.append(c);
    }

    private void journalLoop() {
        Idle idle = journalIdle;
        try {
            while (running || inbound.consumed(JOURNALER) < inbound.claimed()) {
                long before = inbound.consumed(JOURNALER);
                int n = inbound.poll(JOURNALER, journalOne, maxBatch);
                if (n > 0) {
                    journal.flush();
                    long now = System.nanoTime();
                    // Record ingress->durable for the batch's last command (the one that waited least):
                    // a conservative per-batch sample that costs nothing extra on the hot path.
                    ingressToDurable.record(now - inbound.get(before + n).ingressNanos);
                    durable.setRelease(base + before + n);
                    idle.reset();
                } else {
                    idle.idle();
                }
            }
            journal.flush();
        } finally {
            journal.close();
            stopped.countDown();
        }
    }

    // ------------------------------------------------------------------ matcher

    private final RingBuffer.Handler<Command> applyOne = this::apply;

    private void apply(Command c, long s) {
        long t0 = System.nanoTime();
        listener.onCommandStart(c);
        c.applyTo(engine);
        listener.onCommandEnd(c);
        long t1 = System.nanoTime();
        applyTime.record(t1 - t0);
        ingressToApplied.record(t1 - c.ingressNanos);
    }

    private void matchLoop() {
        Idle idle = matcherIdle;
        try {
            while (running || inbound.consumed(MATCHER) < inbound.claimed()) {
                int n = inbound.poll(MATCHER, applyOne, maxBatch);
                if (n > 0) {
                    long seq = base + inbound.consumed(MATCHER);
                    processed += n;
                    listener.onBatchApplied(seq);
                    idle.reset();
                    if (snapshotEvery > 0 && seq - lastSnapshotSequence >= snapshotEvery) snapshot(seq);
                } else {
                    listener.onIdle();
                    idle.idle();
                }
            }
        } finally {
            stopped.countDown();
        }
    }

    /**
     * Stop-the-world snapshot on the matcher thread: consistent by construction, costs a pause proportional
     * to state size. Journal segments are only compacted once the journaler has passed the snapshot point.
     * The production-grade alternative is a follower engine replaying the journal and snapshotting off the
     * hot path (docs/ROADMAP.md).
     */
    private void snapshot(long seq) {
        EngineSnapshot.write(engine, seq, snapshotDir);
        EngineSnapshot.prune(snapshotDir, 2);
        if (durable.getAcquire() >= seq) Journal.compact(journalDir, seq);
        lastSnapshotSequence = seq;
    }

    /**
     * Idle strategy: spin, then yield, then park until a producer wakes us.
     *
     * <p>A timed park alone is not good enough: it oversleeps by the OS timer granularity (1-15 ms on Windows),
     * measured as ~15 ms p50 end-to-end latency at low load. So a consumer about to park raises {@code parked},
     * re-checks the ring, and only then parks; producers unpark it after publishing. The 1 ms timeout is only a
     * safety net. {@code busySpin} never parks at all (dedicated-core deployments).
     */
    private final class Idle {
        final boolean busySpin;
        final int consumer;
        volatile boolean parked;
        int n;

        Idle(boolean busySpin, int consumer) {
            this.busySpin = busySpin;
            this.consumer = consumer;
        }

        void reset() { n = 0; }

        void idle() {
            if (busySpin) { Thread.onSpinWait(); return; }
            if (++n < 200) { Thread.onSpinWait(); return; }
            if (n < 300) { Thread.yield(); return; }
            parked = true;
            java.lang.invoke.VarHandle.fullFence();
            if (!inbound.hasPublished(consumer) && running) LockSupport.parkNanos(1_000_000);
            parked = false;
        }
    }

    // ------------------------------------------------------------------ observability

    /** Highest sequence durably journaled. Outputs for commands above this must not leave the process. */
    public long durableSequence() { return durable.getAcquire(); }
    public long processed() { return processed; }
    public long queueDepth() { return inbound.depth(); }
    public LatencyHistogram ingressToApplied() { return ingressToApplied; }
    public LatencyHistogram ingressToDurable() { return ingressToDurable; }
    public LatencyHistogram applyTime() { return applyTime; }
    public long journalFlushes() { return journal.flushes(); }
    public long journalBytes() { return journal.bytesWritten(); }
}
