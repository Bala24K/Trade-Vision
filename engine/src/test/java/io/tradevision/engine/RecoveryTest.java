package io.tradevision.engine;

import io.tradevision.engine.journal.Journal;
import io.tradevision.engine.sim.RandomWorkload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crash-recovery guarantees: snapshot + journal replay must rebuild a byte-for-byte identical state,
 * torn tails must be discarded, and a corrupt snapshot must fall back to an older one.
 */
class RecoveryTest {
    @TempDir
    Path tmp;

    /** Applies commands to a live engine while journaling them, as the EngineRunner does. */
    static final class JournaledEngine implements Consumer<Command> {
        final MatchingEngine engine = new MatchingEngine(EngineListener.NO_OP);
        final Journal journal;
        long seq;

        JournaledEngine(Journal journal) {
            this.journal = journal;
            this.seq = journal.lastSequence();
        }

        @Override
        public void accept(Command c) {
            c.sequence = ++seq;
            journal.append(c);
            c.applyTo(engine);
            if (seq % 97 == 0) journal.flush(); // irregular batch boundaries
        }
    }

    private JournaledEngine run(Path journalDir, Path snapDir, long seed, int steps, int snapshotAt) {
        var journal = new Journal(journalDir, 256 * 1024, Journal.SyncPolicy.OS_CACHE); // small segments: exercise rolling
        var live = new JournaledEngine(journal);
        var w = new RandomWorkload(seed, 4, 16);
        w.setup(live);
        for (int i = 0; i < steps; i++) {
            w.step(live.engine, live);
            if (i == snapshotAt) {
                journal.flush();
                EngineSnapshot.write(live.engine, live.seq, snapDir);
            }
        }
        journal.close();
        return live;
    }

    @Test
    void snapshotPlusReplayReproducesExactState() {
        Path j = tmp.resolve("journal"), s = tmp.resolve("snap");
        var live = run(j, s, 7, 40_000, 25_000);
        var rec = EngineSnapshot.recover(s, j, EngineListener.NO_OP, EngineListener.NO_OP);

        assertTrue(rec.snapshotSequence() > 0, "recovery should start from the snapshot");
        assertTrue(rec.replayed() > 0 && rec.replayed() < live.seq, "and replay only the tail");
        assertEquals(live.seq, rec.lastSequence());
        assertEquals(EngineDigest.of(live.engine), EngineDigest.of(rec.engine()));
        EngineInvariants.assertValid(rec.engine());
    }

    @Test
    void replayFromEmptyWithoutSnapshot() {
        Path j = tmp.resolve("journal"), s = tmp.resolve("snap");
        var live = run(j, s, 11, 20_000, -1);
        var rec = EngineSnapshot.recover(s, j, EngineListener.NO_OP, EngineListener.NO_OP);
        assertEquals(0, rec.snapshotSequence());
        assertEquals(live.seq, rec.replayed());
        assertEquals(EngineDigest.of(live.engine), EngineDigest.of(rec.engine()));
    }

    @Test
    void tornTailIsTruncatedAndAppendingResumes() throws IOException {
        Path j = tmp.resolve("journal"), s = tmp.resolve("snap");
        var live = run(j, s, 13, 5_000, -1);
        Path last = lastSegment(j);
        long size = Files.size(last);
        try (var f = new RandomAccessFile(last.toFile(), "rw")) {
            f.setLength(size - 5); // crash mid-write of the final record
        }

        var journal = new Journal(j, 256 * 1024, Journal.SyncPolicy.OS_CACHE);
        assertEquals(live.seq - 1, journal.lastSequence(), "exactly the torn record is dropped");

        // The system keeps going: append after the truncation point and recover again.
        var cmd = new Command().admin(CommandType.DEPOSIT, 1, 12345);
        cmd.sequence = journal.lastSequence() + 1;
        journal.append(cmd);
        journal.close();
        var rec = EngineSnapshot.recover(s, j, EngineListener.NO_OP, EngineListener.NO_OP);
        assertEquals(live.seq, rec.lastSequence());
        EngineInvariants.assertValid(rec.engine());
    }

    @Test
    void corruptSnapshotFallsBackToOlderOne() throws IOException {
        Path j = tmp.resolve("journal"), s = tmp.resolve("snap");
        var journal = new Journal(j, 1 << 20, Journal.SyncPolicy.OS_CACHE);
        var live = new JournaledEngine(journal);
        var w = new RandomWorkload(17, 3, 8);
        w.setup(live);
        for (int i = 0; i < 10_000; i++) {
            w.step(live.engine, live);
            if (i == 3_000 || i == 7_000) {
                journal.flush();
                EngineSnapshot.write(live.engine, live.seq, s);
            }
        }
        journal.close();

        List<Path> snaps;
        try (Stream<Path> st = Files.list(s)) {
            snaps = st.sorted().toList();
        }
        Path newest = snaps.get(snaps.size() - 1);
        try (var f = new RandomAccessFile(newest.toFile(), "rw")) {
            f.seek(100);
            f.write(f.read() ^ 0xFF); // flip bits in the middle
        }
        var rec = EngineSnapshot.recover(s, j, EngineListener.NO_OP, EngineListener.NO_OP);
        assertTrue(rec.snapshotSequence() < Long.parseLong(newest.getFileName().toString().replaceAll("\\D", "")));
        assertEquals(EngineDigest.of(live.engine), EngineDigest.of(rec.engine()));
    }

    private static Path lastSegment(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().reduce((a, b) -> b).orElseThrow();
        }
    }
}
