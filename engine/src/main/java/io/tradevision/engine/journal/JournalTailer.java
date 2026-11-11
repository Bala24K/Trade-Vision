package io.tradevision.engine.journal;

import io.tradevision.engine.Command;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.CRC32C;

/**
 * Incremental reader that follows a journal while the journaler is still appending to it ({@code tail -f}
 * for the command log). Unlike {@link Journal#replay}, which reads whole segments, it keeps a file position and
 * reads only new bytes, and it moves to the next segment when the journaler rolls over.
 *
 * <p>Callers pass an upper bound, normally the runner's durable sequence. Records above it are never
 * delivered, even if their bytes are already in the file: under {@code EVERY_BATCH} a record can be readable
 * before its fsync returns, and a follower that consumed it could get ahead of what a crash would preserve.
 *
 * <p>Single-threaded. Not safe to share between threads.
 */
public final class JournalTailer implements Closeable {
    private final Path dir;
    private final ByteBuffer buf = ByteBuffer.allocate(1 << 20).order(ByteOrder.LITTLE_ENDIAN);
    private final CRC32C crc = new CRC32C();
    private final Command command = new Command();

    private FileChannel channel;
    private long segmentFirst;
    private long lastDelivered;

    /** Start after {@code afterSequence}: the first record delivered will be {@code afterSequence + 1}. */
    public JournalTailer(Path dir, long afterSequence) {
        this.dir = dir;
        this.lastDelivered = afterSequence;
        buf.limit(0);
        try {
            List<Path> segs = Journal.segments(dir);
            Path start = null;
            for (Path p : segs) {
                if (Journal.sequenceOf(p) <= afterSequence + 1) start = p;
            }
            if (start == null && !segs.isEmpty()) start = segs.get(0);
            if (start != null) open(start);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Last sequence handed to a sink. */
    public long position() {
        return lastDelivered;
    }

    /** First sequence of the segment currently open. Segments before it may be deleted safely. */
    public long currentSegmentFirstSequence() {
        return segmentFirst;
    }

    /**
     * Deliver up to {@code max} records with sequence in {@code (position(), limitSequence]}.
     * The {@link Command} passed to {@code sink} is reused.
     * @return the number of records delivered
     */
    public int poll(long limitSequence, Consumer<Command> sink, int max) {
        if (lastDelivered >= limitSequence) return 0;
        int n = 0;
        try {
            while (n < max && lastDelivered < limitSequence) {
                if (channel == null && !openNext()) break;
                int start = buf.position();
                if (!completeRecordAt(start)) {
                    if (!fill()) {
                        // Current segment is exhausted. Move on only if the journaler has rolled to a new one.
                        if (buf.hasRemaining() || !openNext()) break;
                    }
                    continue;
                }
                int len = buf.getInt(start);
                long seq = buf.getLong(start + Journal.HEADER);
                if (seq > limitSequence) break;
                crc.reset();
                crc.update(buf.array(), start + Journal.HEADER, len);
                if ((int) crc.getValue() != buf.getInt(start + 4)) {
                    throw new IllegalStateException("corrupt journal record at seq " + seq + " in segment " + segmentFirst);
                }
                buf.position(start + Journal.HEADER);
                command.sequence = buf.getLong();
                command.timestamp = buf.getLong();
                CommandCodec.decode(buf, command);
                buf.position(start + Journal.HEADER + len);
                if (seq <= lastDelivered) continue; // positioned inside a segment that starts before our offset
                if (seq != lastDelivered + 1) throw new IllegalStateException("journal gap: " + seq + " after " + lastDelivered);
                sink.accept(command);
                lastDelivered = seq;
                n++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return n;
    }

    private boolean completeRecordAt(int start) {
        if (buf.limit() - start < Journal.HEADER) return false;
        int len = buf.getInt(start);
        return buf.limit() - start >= Journal.HEADER + len;
    }

    /** Read more bytes from the current segment. @return false if none were available. */
    private boolean fill() throws IOException {
        buf.compact();
        int r = channel.read(buf);
        buf.flip();
        return r > 0;
    }

    private boolean openNext() throws IOException {
        List<Path> segs = Journal.segments(dir);
        Path next = null;
        if (channel == null) {
            // Nothing open yet (journal was empty at construction): the segment holding lastDelivered + 1.
            for (Path p : segs) if (next == null || Journal.sequenceOf(p) <= lastDelivered + 1) next = p;
        } else {
            for (Path p : segs) {
                if (Journal.sequenceOf(p) > segmentFirst) { next = p; break; }
            }
        }
        if (next == null) return false;
        if (channel != null) channel.close();
        open(next);
        return true;
    }

    private void open(Path p) throws IOException {
        channel = FileChannel.open(p, StandardOpenOption.READ);
        segmentFirst = Journal.sequenceOf(p);
        buf.clear().limit(0);
    }

    @Override
    public void close() {
        try {
            if (channel != null) channel.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
