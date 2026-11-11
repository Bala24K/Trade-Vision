package io.tradevision.gateway.engine;

import io.tradevision.engine.Command;
import io.tradevision.engine.EngineSnapshot;
import io.tradevision.engine.MatchingEngine;
import io.tradevision.engine.journal.Journal;
import io.tradevision.engine.runtime.EngineRunner;
import io.tradevision.engine.runtime.SnapshotFollower;
import io.tradevision.gateway.TvProperties;
import io.tradevision.gateway.read.ReadModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * Hosts the engine inside the Spring process: recovery on start, the two engine threads, and a publisher thread
 * that releases each command's events only once the journal has made that command durable.
 *
 * <pre>
 *  HTTP/WS/bots ──submit()──▶ EngineRunner ring ──▶ matcher ──EventTranslator──▶ outbound queue
 *                                         └──▶ journaler ──durableSequence──┐        │
 *                                                                           ▼        ▼
 *                                              publisher thread: wait(durable ≥ seq) → ReadModel → futures → sinks
 * </pre>
 */
@Component
public class EngineHost implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(EngineHost.class);

    /**
     * Downstream consumer of durable events (WebSocket hub, Postgres and ClickHouse projectors). Called on the
     * publisher thread, in sequence order, once per command. Implementations must not block.
     */
    public interface Sink {
        void onEvents(long sequence, List<Event> events);

        /**
         * Events re-derived from the journal tail during startup recovery. They were already published before the
         * restart, so live clients must not see them again, but an idempotent projection uses them to catch up
         * on anything it had not stored yet.
         */
        default void onReplay(long sequence, List<Event> events) {}
    }

    public static final class OverloadedException extends RuntimeException {
        public OverloadedException() {
            super("engine inbound queue is full", null, false, false);
        }
    }

    /**
     * The caller stopped waiting, but the command <b>was submitted</b>: it is in the ring/journal and will execute.
     * Contrast with {@link OverloadedException}, which guarantees the command was never submitted.
     */
    public static final class OutcomePendingException extends RuntimeException {
        public OutcomePendingException() {
            super("submitted; the result was not durable before the timeout. The command will still execute: "
                    + "retry with the same clientOrderId to get its result", null, false, false);
        }
    }

    private static final long DEFAULT_TIMEOUT_MS = 5_000;

    private record Outcome(long sequence, long correlation, List<Event> events) {}

    private final TvProperties props;
    private final ReadModel readModel;
    private final List<Sink> sinks = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Long, CompletableFuture<List<Event>>> pending = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Outcome> outbound = new ConcurrentLinkedQueue<>();
    private final AtomicLong correlation = new AtomicLong();
    private final AtomicLong outboundDepth = new AtomicLong();

    private volatile boolean running;
    private volatile boolean publisherParked;
    private EngineRunner runner;
    private SnapshotFollower snapshots;
    private volatile Thread publisher;
    private long maxAccountId;
    private long recoveredSequence, replayed;

    public EngineHost(TvProperties props, ReadModel readModel) {
        this.props = props;
        this.readModel = readModel;
    }

    public void addSink(Sink sink) {
        sinks.add(sink);
    }

    @Override
    public void start() {
        Path data = Path.of(props.dataDir());
        Path journalDir = data.resolve("journal"), snapDir = data.resolve("snapshots");
        long t0 = System.nanoTime();

        // Replay rebuilds trade history for charts and lets projections catch up; full read-model state is then
        // seeded straight from the recovered engine.
        var replay = new EventTranslator((seq, corr, events) -> {
            for (Event e : events) if (e instanceof Event.Trade t) readModel.replayTrade(t);
            for (Sink s : sinks) s.onReplay(seq, events);
        });
        var translator = new EventTranslator((seq, corr, events) -> {
            outbound.offer(new Outcome(seq, corr, events));
            outboundDepth.incrementAndGet();
            if (publisherParked) LockSupport.unpark(publisher); // same wake-on-submit idea as the engine runner
        });
        var rec = EngineSnapshot.recover(snapDir, journalDir, replay, translator);
        MatchingEngine engine = rec.engine();
        readModel.seed(engine);
        for (long id : engine.accounts().sortedKeys()) if (id < 1_000_000) maxAccountId = Math.max(maxAccountId, id);
        recoveredSequence = rec.lastSequence();
        replayed = rec.replayed();

        var journal = new Journal(journalDir, 64L << 20, props.syncPolicy());
        if (journal.lastSequence() != rec.lastSequence()) {
            throw new IllegalStateException("journal at " + journal.lastSequence() + " but recovery reached " + rec.lastSequence());
        }
        if (props.busySpin()) System.setProperty("tv.busySpin", "true");
        boolean follower = !"INLINE".equalsIgnoreCase(props.snapshotMode());
        runner = new EngineRunner(engine, journal, translator, () -> System.currentTimeMillis() * 1000, journalDir, snapDir,
                follower ? 0 : props.snapshotEvery(), 1 << 16, 4096).start();
        if (follower) snapshots = new SnapshotFollower(snapDir, journalDir, runner::durableSequence, props.snapshotEvery()).start();
        running = true;
        publisher = Thread.ofPlatform().name("tv-publisher").start(this::publishLoop);
        log.info("engine up: snapshot@{} + replayed {} commands -> seq {} in {} ms ({} markets, {} accounts)",
                rec.snapshotSequence(), rec.replayed(), rec.lastSequence(), (System.nanoTime() - t0) / 1_000_000,
                engine.markets().size(), engine.accounts().size());
    }

    private void publishLoop() {
        int idle = 0;
        while (running || !outbound.isEmpty()) {
            Outcome o = outbound.peek();
            if (o == null) {
                if (++idle < 2_000) {
                    Thread.onSpinWait();
                } else {
                    publisherParked = true;
                    if (outbound.isEmpty() && running) LockSupport.parkNanos(1_000_000);
                    publisherParked = false;
                }
                continue;
            }
            idle = 0;
            // Durability gate: never show anyone a result that a crash-and-replay could not reproduce.
            int spins = 0;
            while (runner.durableSequence() < o.sequence()) {
                if (++spins < 1_000) Thread.onSpinWait();
                else Thread.yield();
            }
            outbound.poll();
            outboundDepth.decrementAndGet();
            try {
                readModel.apply(o.events());
                if (o.correlation() != 0) {
                    CompletableFuture<List<Event>> f = pending.remove(o.correlation());
                    if (f != null) f.complete(o.events());
                }
                for (Sink s : sinks) s.onEvents(o.sequence(), o.events());
            } catch (RuntimeException e) {
                log.error("publisher failed on seq {}", o.sequence(), e);
            }
        }
    }

    /**
     * Submit a command and get its <b>outcome</b>: the future completes with the events it caused once they are
     * durable. It never times out, because every submitted command produces an outcome. It fails immediately with
     * {@link OverloadedException} only when the ring was full, which means the command was never submitted.
     * Callers that need a deadline wrap it with {@link #withTimeout}, which leaves this future intact.
     */
    public CompletableFuture<List<Event>> submitTracked(Consumer<Command> fill) {
        long corr = correlation.incrementAndGet();
        var f = new CompletableFuture<List<Event>>();
        pending.put(corr, f);
        boolean ok = runner.trySubmit(c -> {
            fill.accept(c);
            c.sessionId = corr;
        });
        if (!ok) {
            pending.remove(corr);
            f.completeExceptionally(new OverloadedException());
        }
        return f; // removed from `pending` by the publisher when the outcome is released
    }

    /** {@link #submitTracked} with the default 5 s deadline for the caller. Expiry raises {@link OutcomePendingException}. */
    public CompletableFuture<List<Event>> submit(Consumer<Command> fill) {
        return withTimeout(submitTracked(fill), DEFAULT_TIMEOUT_MS);
    }

    /**
     * A view of {@code outcome} that fails with {@link OutcomePendingException} after {@code millis}. Expiry only
     * stops the caller waiting: {@code outcome} itself still completes, so an idempotency cache holding it can answer
     * a retry with the real result. (Before the audit, the timeout was applied to the shared future itself.)
     */
    public static <T> CompletableFuture<T> withTimeout(CompletableFuture<T> outcome, long millis) {
        var view = new CompletableFuture<T>();
        outcome.whenComplete((r, e) -> {
            if (e == null) view.complete(r);
            else view.completeExceptionally(e instanceof java.util.concurrent.CompletionException ce && ce.getCause() != null ? ce.getCause() : e);
        });
        if (!view.isDone()) {
            CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS)
                    .execute(() -> view.completeExceptionally(new OutcomePendingException()));
        }
        return view;
    }

    public synchronized long allocateAccountId() {
        return ++maxAccountId;
    }

    @Override
    public void stop() {
        try {
            if (runner != null) runner.stop();
            if (snapshots != null) snapshots.stop();
            running = false;
            if (publisher != null) publisher.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("engine stopped at seq {}", runner == null ? 0 : runner.durableSequence());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return 0; // first up, last down: bots and feeds (phase 10) depend on it
    }

    public EngineRunner runner() { return runner; }
    /** Background snapshotter, or null when snapshots are taken inline on the matcher thread. */
    public SnapshotFollower snapshots() { return snapshots; }
    public long outboundDepth() { return outboundDepth.get(); }
    public long recoveredSequence() { return recoveredSequence; }
    public long replayedOnStart() { return replayed; }
}
