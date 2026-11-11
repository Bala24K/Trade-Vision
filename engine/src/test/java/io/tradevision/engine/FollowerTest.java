package io.tradevision.engine;

import io.tradevision.engine.journal.Journal;
import io.tradevision.engine.journal.JournalTailer;
import io.tradevision.engine.runtime.SnapshotFollower;
import io.tradevision.engine.sim.RandomWorkload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FollowerTest {
    @TempDir
    Path tmp;

    /** Live engine + journal driven on this thread; {@code durable} is what the follower is allowed to see. */
    private static final class Live {
        final MatchingEngine engine = new MatchingEngine(EngineListener.NO_OP);
        final Journal journal;
        final AtomicLong durable = new AtomicLong();
        long seq;

        Live(Path dir, long segmentBytes) {
            journal = new Journal(dir, segmentBytes, Journal.SyncPolicy.OS_CACHE);
        }

        final Consumer<Command> sink = this::accept;

        private void accept(Command c) {
            c.sequence = ++seq;
            c.timestamp = seq * 10;
            journal.append(c);
            c.applyTo(engine);
        }

        void flush() {
            journal.flush();
            durable.set(journal.lastSequence());
        }
    }

    @Test
    void tailerFollowsSegmentRollsAndNeverPassesTheLimit() {
        Path j = tmp.resolve("j");
        Live live = new Live(j, 16 << 10); // tiny segments: many rollovers
        var w = new RandomWorkload(7, 3, 20);
        w.setup(live.sink);
        for (int i = 0; i < 5_000; i++) {
            w.step(live.engine, live.sink);
            if (i % 50 == 0) live.flush(); // segments roll at flush time
        }
        live.flush();

        List<Long> seen = new ArrayList<>();
        try (var t = new JournalTailer(j, 0)) {
            assertEquals(100, t.poll(100, c -> seen.add(c.sequence), 1_000), "stops at the limit");
            assertEquals(0, t.poll(100, c -> seen.add(c.sequence), 1_000));
            long total = 0;
            int n;
            while ((n = t.poll(live.durable.get(), c -> seen.add(c.sequence), 777)) > 0) total += n;
            assertEquals(live.seq - 100, total);
        }
        for (int i = 0; i < seen.size(); i++) assertEquals(i + 1, seen.get(i), "contiguous, in order");
        assertTrue(listSegments(j) > 5, "test exercised segment rolls");
    }

    @Test
    void followerSnapshotsOffThread_andRecoveryReproducesLiveState() throws Exception {
        Path j = tmp.resolve("j"), s = tmp.resolve("s");
        Live live = new Live(j, 32 << 10);
        var w = new RandomWorkload(11, 4, 30);
        w.setup(live.sink);
        live.flush();

        var follower = new SnapshotFollower(s, j, live.durable::get, 2_000).start();
        // Keep writing while the follower tails and snapshots concurrently.
        for (int i = 0; i < 20_000; i++) {
            w.step(live.engine, live.sink);
            if (i % 64 == 0) live.flush();
        }
        live.flush();
        long deadline = System.currentTimeMillis() + 10_000;
        while (follower.position() < live.seq && System.currentTimeMillis() < deadline) Thread.sleep(5);
        follower.stop();
        live.journal.close();

        assertEquals(live.seq, follower.position(), "follower caught up to the durable sequence");
        assertTrue(follower.snapshotsTaken() >= 5, "snapshots taken: " + follower.snapshotsTaken());
        assertTrue(listSnapshots(s) <= 2, "old snapshots pruned");

        var rec = EngineSnapshot.recover(s, j, EngineListener.NO_OP, EngineListener.NO_OP);
        assertTrue(rec.snapshotSequence() > 0);
        assertEquals(live.seq, rec.lastSequence());
        assertEquals(EngineDigest.of(live.engine), EngineDigest.of(rec.engine()),
                "follower snapshot + journal tail == live engine, bit for bit");
    }

    private static long listSegments(Path dir) {
        try (var st = Files.list(dir)) { return st.filter(p -> p.toString().endsWith(".log")).count(); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static long listSnapshots(Path dir) {
        try (var st = Files.list(dir)) { return st.filter(p -> p.toString().endsWith(".bin")).count(); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
