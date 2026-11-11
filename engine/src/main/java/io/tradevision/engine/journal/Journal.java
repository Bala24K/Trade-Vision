package io.tradevision.engine.journal;

import io.tradevision.engine.Command;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

/**
 * Append-only, segmented command log: the source of truth for engine state (ADR-0003).
 *
 * <h2>Record format</h2>
 * <pre>
 *   int32  bodyLength          (little-endian)
 *   int32  crc32c(body)
 *   body:  int64 sequence | int64 timestamp | command (see {@link CommandCodec})
 * </pre>
 * CRC32C is hardware-accelerated (SSE4.2 {@code crc32} instruction) via the JDK intrinsic, so
 * checksumming every record costs a few nanoseconds.
 *
 * <h2>Group commit</h2>
 * {@link #append} only copies into an in-memory batch buffer. {@link #flush} writes the whole batch with one
 * {@code write} syscall and, depending on {@link SyncPolicy}, one {@code fsync}. The journaler thread calls
 * flush once per drained batch, so under load hundreds of commands share a single fsync: latency stays
 * bounded while throughput scales with batch size (the same idea as Postgres' commit_delay or Kafka's
 * linger.ms, without the timer).
 *
 * <h2>Torn writes</h2>
 * A crash can leave a half-written record at the tail. On open, the last segment is scanned and truncated
 * at the first record whose length or CRC does not check out. Everything before it is intact by
 * construction; everything after it was never acknowledged.
 */
public final class Journal implements Closeable {
    public enum SyncPolicy {
        /** fsync on every flush: no acknowledged command is ever lost, even on power failure. */
        EVERY_BATCH,
        /** Rely on the OS page cache: survives process crashes, not power loss. Faster on consumer disks. */
        OS_CACHE
    }

    static final int HEADER = 8;
    static final int BODY_PREFIX = 16;
    private static final String PREFIX = "journal-";
    private static final String SUFFIX = ".log";

    private final Path dir;
    private final long segmentBytes;
    private final SyncPolicy syncPolicy;
    private final ByteBuffer batch;
    private final ByteBuffer scratch = ByteBuffer.allocate(BODY_PREFIX + CommandCodec.MAX_BODY).order(ByteOrder.LITTLE_ENDIAN);
    private final CRC32C crc = new CRC32C();

    private FileChannel channel;
    private long segmentSize;
    private long lastSequence;
    private long flushes;
    private long bytesWritten;

    public Journal(Path dir, long segmentBytes, SyncPolicy syncPolicy) {
        this.dir = dir;
        this.segmentBytes = segmentBytes;
        this.syncPolicy = syncPolicy;
        this.batch = ByteBuffer.allocateDirect(4 << 20).order(ByteOrder.LITTLE_ENDIAN);
        try {
            Files.createDirectories(dir);
            List<Path> segments = segments(dir);
            if (segments.isEmpty()) {
                openSegment(1);
            } else {
                Path last = segments.get(segments.size() - 1);
                long[] tail = scanAndTruncate(last);
                lastSequence = tail[0] != 0 ? tail[0] : sequenceOf(last) - 1;
                channel = FileChannel.open(last, StandardOpenOption.WRITE);
                channel.position(tail[1]);
                segmentSize = tail[1];
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Sequence of the last record durably present (or batched, after {@link #append}). */
    public long lastSequence() {
        return lastSequence;
    }

    public long flushes() {
        return flushes;
    }

    public long bytesWritten() {
        return bytesWritten;
    }

    public void append(Command c) {
        if (c.sequence != lastSequence + 1) {
            throw new IllegalStateException("journal gap: expected " + (lastSequence + 1) + " got " + c.sequence);
        }
        scratch.clear();
        scratch.putLong(c.sequence).putLong(c.timestamp);
        CommandCodec.encode(c, scratch);
        scratch.flip();
        int len = scratch.remaining();
        crc.reset();
        crc.update(scratch.array(), 0, len);
        if (batch.remaining() < HEADER + len) flush();
        batch.putInt(len).putInt((int) crc.getValue()).put(scratch);
        lastSequence = c.sequence;
    }

    /** Write (and per policy, fsync) everything appended since the last flush. */
    public void flush() {
        if (batch.position() == 0) return;
        try {
            batch.flip();
            int n = batch.remaining();
            while (batch.hasRemaining()) channel.write(batch);
            batch.clear();
            if (syncPolicy == SyncPolicy.EVERY_BATCH) channel.force(false);
            segmentSize += n;
            bytesWritten += n;
            flushes++;
            if (segmentSize >= segmentBytes) {
                channel.close();
                openSegment(lastSequence + 1);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void openSegment(long firstSequence) throws IOException {
        Path p = dir.resolve(PREFIX + String.format("%020d", firstSequence) + SUFFIX);
        channel = FileChannel.open(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        segmentSize = 0;
        if (syncPolicy == SyncPolicy.EVERY_BATCH) fsyncDirectory(dir);
    }

    @Override
    public void close() {
        flush();
        try {
            channel.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ reading

    /**
     * Stream every intact command with {@code sequence > afterSequence}, in order, into {@code sink}.
     * The {@link Command} passed to the sink is reused between calls.
     * @return number of commands replayed
     */
    public static long replay(Path dir, long afterSequence, Consumer<Command> sink) {
        try {
            List<Path> segments = segments(dir);
            long count = 0;
            Command c = new Command();
            for (int i = 0; i < segments.size(); i++) {
                // Skip whole segments that end before the snapshot point.
                if (i + 1 < segments.size() && sequenceOf(segments.get(i + 1)) <= afterSequence + 1) continue;
                ByteBuffer buf = ByteBuffer.wrap(Files.readAllBytes(segments.get(i))).order(ByteOrder.LITTLE_ENDIAN);
                CRC32C crc = new CRC32C();
                while (true) {
                    int start = buf.position();
                    if (!validRecordAt(buf, crc)) break;
                    int len = buf.getInt(start);
                    buf.position(start + HEADER);
                    long seq = buf.getLong();
                    c.sequence = seq;
                    c.timestamp = buf.getLong();
                    CommandCodec.decode(buf, c);
                    buf.position(start + HEADER + len);
                    if (seq > afterSequence) {
                        sink.accept(c);
                        count++;
                    }
                }
            }
            return count;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean validRecordAt(ByteBuffer buf, CRC32C crc) {
        int start = buf.position();
        if (buf.remaining() < HEADER) return false;
        int len = buf.getInt(start);
        if (len < BODY_PREFIX + 1 || len > BODY_PREFIX + CommandCodec.MAX_BODY || buf.remaining() < HEADER + len) return false;
        crc.reset();
        crc.update(buf.array(), start + HEADER, len);
        return (int) crc.getValue() == buf.getInt(start + 4);
    }

    /** @return {lastValidSequence, validLength}; truncates the file to validLength. */
    private static long[] scanAndTruncate(Path segment) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(Files.readAllBytes(segment)).order(ByteOrder.LITTLE_ENDIAN);
        CRC32C crc = new CRC32C();
        long lastSeq = 0;
        while (validRecordAt(buf, crc)) {
            int start = buf.position();
            lastSeq = buf.getLong(start + HEADER);
            buf.position(start + HEADER + buf.getInt(start));
        }
        int valid = buf.position();
        if (valid < buf.capacity()) {
            try (FileChannel ch = FileChannel.open(segment, StandardOpenOption.WRITE)) {
                ch.truncate(valid);
                ch.force(true);
            }
        }
        return new long[] {lastSeq, valid};
    }

    static List<Path> segments(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> out = new ArrayList<>(s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(PREFIX) && n.endsWith(SUFFIX);
            }).sorted().toList());
            return out;
        }
    }

    static long sequenceOf(Path segment) {
        String n = segment.getFileName().toString();
        return Long.parseLong(n.substring(PREFIX.length(), n.length() - SUFFIX.length()));
    }

    /** Delete segments made redundant by a snapshot at {@code snapshotSequence}. */
    public static int compact(Path dir, long snapshotSequence) {
        try {
            List<Path> segs = segments(dir);
            int deleted = 0;
            for (int i = 0; i + 1 < segs.size(); i++) {
                if (sequenceOf(segs.get(i + 1)) <= snapshotSequence + 1) {
                    Files.delete(segs.get(i));
                    deleted++;
                }
            }
            return deleted;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void fsyncDirectory(Path dir) {
        // Persists the new directory entry on POSIX. Windows cannot open directories as channels; skip there.
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException ignored) {
        }
    }
}
