package io.tradevision.engine;

import io.tradevision.engine.account.Account;
import io.tradevision.engine.account.Position;
import io.tradevision.engine.book.Order;
import io.tradevision.engine.book.OrderBook;
import io.tradevision.engine.journal.Journal;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.CRC32C;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

/**
 * Point-in-time image of the full engine state, tagged with the journal sequence it reflects.
 *
 * <p>Recovery = load newest valid snapshot, then replay journal records after its sequence
 * ({@link #recover}). Snapshots bound recovery time; the journal remains the source of truth.
 * Resting orders are written in exact price-time order, so the restored book has identical queue priority.
 *
 * <p>Writes are crash-safe: temp file, fsync, atomic rename. A trailing CRC32C rejects partial or corrupt
 * files, in which case recovery falls back to the previous snapshot.
 */
public final class EngineSnapshot {
    private static final int MAGIC = 0x54565342; // "TVSB"
    private static final int VERSION = 1;
    private static final String PREFIX = "snapshot-";
    private static final String SUFFIX = ".bin";

    private EngineSnapshot() {}

    public record Recovered(MatchingEngine engine, long snapshotSequence, long lastSequence, long replayed) {}

    public static Path write(MatchingEngine e, long sequence, Path dir) {
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(PREFIX + String.format("%020d", sequence) + ".tmp");
            Path dst = dir.resolve(PREFIX + String.format("%020d", sequence) + SUFFIX);
            try (OutputStream fos = Files.newOutputStream(tmp)) {
                CRC32C crc = new CRC32C();
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new CheckedOutputStream(fos, crc), 1 << 16));
                writeState(e, sequence, out);
                out.flush();
                out.writeInt((int) crc.getValue());
                out.flush();
            }
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            Files.move(tmp, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return dst;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void writeState(MatchingEngine e, long sequence, DataOutputStream out) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeLong(sequence);
        out.writeLong(e.lastOrderId());
        out.writeLong(e.lastTradeId());

        long[] marketIds = e.markets().sortedKeys();
        out.writeInt(marketIds.length);
        for (long id : marketIds) {
            Market m = e.market(id);
            out.writeLong(id);
            out.writeByte(m.status().ordinal());
            out.writeByte(m.winner() == null ? -1 : m.winner().ordinal());
            out.writeInt(m.lastTradePrice());
            out.writeLong(m.volume());
            out.writeLong(m.notional());
            out.writeLong(m.tradeCount());
            out.writeLong(m.openInterest());
        }

        long[] accountIds = e.accounts().sortedKeys();
        out.writeInt(accountIds.length);
        for (long id : accountIds) {
            Account a = e.account(id);
            out.writeLong(id);
            out.writeLong(a.available());
            out.writeLong(a.reserved());
            out.writeLong(a.deposited());
            out.writeLong(a.withdrawn());
            long[] pids = a.positions().sortedKeys();
            out.writeInt(pids.length);
            for (long mid : pids) {
                Position p = a.position(mid);
                out.writeLong(mid);
                out.writeLong(p.yes());
                out.writeLong(p.no());
                out.writeLong(p.yesLocked());
                out.writeLong(p.noLocked());
                out.writeLong(p.yesCost());
                out.writeLong(p.noCost());
                out.writeLong(p.realizedPnl());
            }
        }

        out.writeInt(e.openOrders().size());
        for (long id : marketIds) {
            OrderBook book = e.market(id).book();
            for (Side side : Side.values()) {
                for (int p = book.best(side); p != -1; p = book.nextLevel(side, p)) {
                    for (Order o = book.level(side, p).head(); o != null; o = o.next()) {
                        out.writeLong(o.orderId);
                        out.writeLong(o.clientOrderId);
                        out.writeLong(o.accountId);
                        out.writeLong(o.marketId);
                        out.writeByte(o.outcome.ordinal());
                        out.writeByte(o.action.ordinal());
                        out.writeByte(o.tif.ordinal());
                        out.writeShort(o.limitPrice);
                        out.writeLong(o.quantity);
                        out.writeLong(o.remaining);
                        out.writeLong(o.reservedCash);
                        out.writeLong(o.timestamp);
                    }
                }
            }
        }
    }

    /** @return the engine and the journal sequence it reflects, or empty if the file is invalid. */
    public static Optional<MatchingEngine> read(Path file, EngineListener listener, long[] sequenceOut) {
        try (InputStream fis = Files.newInputStream(file)) {
            long size = Files.size(file);
            CRC32C crc = new CRC32C();
            CheckedInputStream checked = new CheckedInputStream(new BoundedInputStream(fis, size - 4), crc);
            DataInputStream in = new DataInputStream(new BufferedInputStream(checked, 1 << 16));
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return Optional.empty();
            long sequence = in.readLong();
            MatchingEngine e = new MatchingEngine(listener);
            e.restoreCounters(in.readLong(), in.readLong());

            int markets = in.readInt();
            for (int i = 0; i < markets; i++) {
                long id = in.readLong();
                MarketStatus status = MarketStatus.values()[in.readByte()];
                byte w = in.readByte();
                e.restoreMarket(id, status, w < 0 ? null : Outcome.of(w), in.readInt(), in.readLong(), in.readLong(),
                        in.readLong(), in.readLong());
            }
            int accounts = in.readInt();
            for (int i = 0; i < accounts; i++) {
                Account a = e.restoreAccount(in.readLong());
                a.restore(in.readLong(), in.readLong(), in.readLong(), in.readLong());
                int positions = in.readInt();
                for (int j = 0; j < positions; j++) {
                    a.restorePosition(in.readLong(), in.readLong(), in.readLong(), in.readLong(), in.readLong(),
                            in.readLong(), in.readLong(), in.readLong());
                }
            }
            int orders = in.readInt();
            for (int i = 0; i < orders; i++) {
                e.restoreOrder(in.readLong(), in.readLong(), in.readLong(), in.readLong(), Outcome.of(in.readByte()),
                        Action.of(in.readByte()), TimeInForce.of(in.readByte()), in.readShort(), in.readLong(),
                        in.readLong(), in.readLong(), in.readLong());
            }
            if (in.read() != -1) return Optional.empty(); // trailing garbage before CRC
            int computed = (int) crc.getValue();
            int stored = readTrailer(file);
            if (computed != stored) return Optional.empty();
            sequenceOut[0] = sequence;
            return Optional.of(e);
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    private static int readTrailer(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(4);
            ch.read(b, ch.size() - 4);
            return b.flip().getInt();
        }
    }

    /**
     * Rebuild engine state: newest valid snapshot (or empty engine) + journal replay.
     * Events produced during replay go to {@code replayListener} (normally a no-op: they were already
     * published before the crash); {@code liveListener} is installed afterwards.
     */
    public static Recovered recover(Path snapshotDir, Path journalDir, EngineListener replayListener, EngineListener liveListener) {
        MatchingEngine engine = null;
        long snapSeq = 0;
        for (Path snap : snapshots(snapshotDir)) {
            long[] seq = new long[1];
            Optional<MatchingEngine> e = read(snap, replayListener, seq);
            if (e.isPresent()) {
                engine = e.get();
                snapSeq = seq[0];
                break;
            }
        }
        if (engine == null) engine = new MatchingEngine(replayListener);
        final MatchingEngine target = engine;
        long[] last = {snapSeq};
        long replayed = Journal.replay(journalDir, snapSeq, c -> {
            if (c.sequence != last[0] + 1) throw new IllegalStateException("journal gap at " + c.sequence + " after " + last[0]);
            c.applyTo(target);
            last[0] = c.sequence;
        });
        engine.setListener(liveListener);
        return new Recovered(engine, snapSeq, last[0], replayed);
    }

    /** Snapshot files, newest first. */
    static List<Path> snapshots(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(PREFIX) && p.getFileName().toString().endsWith(SUFFIX))
                    .sorted(Comparator.reverseOrder()).toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Stream wrapper that stops before the 4-byte CRC trailer. */
    private static final class BoundedInputStream extends InputStream {
        private final InputStream in;
        private long left;

        BoundedInputStream(InputStream in, long limit) {
            this.in = in;
            this.left = limit;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int b = in.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(buf, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
    }
}
