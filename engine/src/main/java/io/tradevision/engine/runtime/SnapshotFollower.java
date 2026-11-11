package io.tradevision.engine.runtime;

import io.tradevision.engine.EngineListener;
import io.tradevision.engine.EngineSnapshot;
import io.tradevision.engine.MatchingEngine;
import io.tradevision.engine.journal.Journal;
import io.tradevision.engine.journal.JournalTailer;

import java.nio.file.Path;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/**
 * Takes snapshots <b>off the matching thread</b>.
 *
 * <p>An inline snapshot pauses the matcher for as long as it takes to serialize all engine state. With
 * ~1M resting orders that is tens of milliseconds, a tail-latency spike for every order queued behind it.
 * The follower is a second, private {@link MatchingEngine} that replays the journal as it is written (via
 * {@link JournalTailer}) and serializes <i>its own</i> copy. The live engine never stops.
 *
 * <p>This is safe because the engine is deterministic: the same command sequence always gives the same state
 * (checked by {@code EngineDigest} in tests). The follower only reads records up to the durable sequence, so
 * a snapshot never contains a command that a crash could lose from the journal.
 *
 * <p>Cost: a second copy of engine state in memory and one mostly idle core. Recovery still uses the newest
 * snapshot plus the journal tail, unchanged.
 */
public final class SnapshotFollower {
    private static final EngineListener SILENT = new EngineListener() {};

    private final Path snapshotDir;
    private final Path journalDir;
    private final LongSupplier durableSequence;
    private final long every;

    private volatile boolean running;
    private volatile long position;
    private volatile long lastSnapshot;
    private volatile long lastSnapshotMillis;
    private volatile long snapshots;
    private Thread thread;

    public SnapshotFollower(Path snapshotDir, Path journalDir, LongSupplier durableSequence, long every) {
        this.snapshotDir = snapshotDir;
        this.journalDir = journalDir;
        this.durableSequence = durableSequence;
        this.every = every;
    }

    public SnapshotFollower start() {
        running = true;
        thread = Thread.ofPlatform().name("tv-snapshot-follower").daemon(true).start(this::run);
        return this;
    }

    public void stop() throws InterruptedException {
        running = false;
        if (thread != null) {
            LockSupport.unpark(thread);
            thread.join(30_000);
        }
    }

    private void run() {
        var loaded = EngineSnapshot.loadLatest(snapshotDir, SILENT);
        MatchingEngine engine = loaded.map(EngineSnapshot.Loaded::engine).orElseGet(() -> new MatchingEngine(SILENT));
        long from = loaded.map(EngineSnapshot.Loaded::sequence).orElse(0L);
        lastSnapshot = from;
        try (JournalTailer tailer = new JournalTailer(journalDir, from)) {
            while (running) {
                int n = tailer.poll(durableSequence.getAsLong(), c -> c.applyTo(engine), 8192);
                position = tailer.position();
                if (position - lastSnapshot >= every) {
                    long t0 = System.nanoTime();
                    EngineSnapshot.write(engine, position, snapshotDir);
                    EngineSnapshot.prune(snapshotDir, 2);
                    // Delete only segments the tailer has fully passed and the snapshot fully covers.
                    Journal.compact(journalDir, Math.min(position, tailer.currentSegmentFirstSequence() - 1));
                    lastSnapshotMillis = (System.nanoTime() - t0) / 1_000_000;
                    lastSnapshot = position;
                    snapshots++;
                }
                if (n == 0) LockSupport.parkNanos(2_000_000); // background work: latency here does not matter
            }
        }
    }

    public long position() { return position; }
    public long lastSnapshotSequence() { return lastSnapshot; }
    public long lastSnapshotMillis() { return lastSnapshotMillis; }
    public long snapshotsTaken() { return snapshots; }
}
