package io.tradevision.engine.runtime;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * Bounded multi-producer / multi-consumer-broadcast ring of pre-allocated, reusable slots (Disruptor-style).
 * Every consumer sees every slot; a slot is reused only after <i>all</i> consumers have passed it.
 *
 * <h2>Why not ArrayBlockingQueue?</h2>
 * <ul>
 *   <li><b>No allocation:</b> producers fill a pre-allocated slot in place instead of enqueueing a new object.</li>
 *   <li><b>No locks:</b> producers claim a sequence with one CAS; consumers take no lock at all.</li>
 *   <li><b>Broadcast:</b> the journaler and the matcher consume the <i>same</i> slots in parallel, so
 *       durability and matching overlap instead of running back to back (see {@link EngineRunner}).</li>
 *   <li><b>Batching for free:</b> a consumer drains everything published since it last looked in one pass.</li>
 *   <li><b>No false sharing:</b> every cursor owns a padded 128-byte region, so producer CAS traffic never
 *       invalidates a consumer's cache line (and vice versa).</li>
 * </ul>
 *
 * <h2>Publication protocol</h2>
 * A producer (1) claims sequence {@code s} by CAS on the claim cursor, (2) writes slot {@code s & mask},
 * (3) stores {@code s} into {@code published[s & mask]} with release semantics. A consumer reads
 * {@code published[i]} with acquire semantics; if it equals the expected sequence, every write the
 * producer made to that slot happens-before the consumer's reads. Producers may finish out of order;
 * consumers stop at the first gap, preserving claim order. Consumers must treat slots as read-only.
 *
 * <h2>Backpressure</h2>
 * {@link #tryClaim()} fails fast when the slowest consumer is a full lap behind. The gateway turns that into
 * an explicit "overloaded" rejection rather than queueing unboundedly: latency stays bounded and
 * overload is visible to clients.
 */
public final class RingBuffer<T> {
    private static final VarHandle PUBLISHED = MethodHandles.arrayElementVarHandle(long[].class);

    private final Object[] slots;
    private final long[] published;
    private final int mask;
    private final int capacity;
    private final Sequence claimed = new Sequence(0);
    private final Sequence[] consumers;

    public RingBuffer(int capacity, Supplier<T> factory) {
        this(capacity, 1, factory);
    }

    public RingBuffer(int capacity, int consumerCount, Supplier<T> factory) {
        if (Integer.bitCount(capacity) != 1) throw new IllegalArgumentException("capacity must be a power of two");
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.slots = new Object[capacity];
        this.published = new long[capacity];
        for (int i = 0; i < capacity; i++) {
            slots[i] = factory.get();
            published[i] = -1;
        }
        this.consumers = new Sequence[consumerCount];
        for (int i = 0; i < consumerCount; i++) consumers[i] = new Sequence(0);
    }

    public int capacity() {
        return capacity;
    }

    private long minConsumed() {
        long min = Long.MAX_VALUE;
        for (Sequence c : consumers) min = Math.min(min, c.getAcquire());
        return min;
    }

    /** Claim the next sequence, or return -1 if the ring is full. Lock-free. */
    public long tryClaim() {
        while (true) {
            long current = claimed.getVolatile();
            long next = current + 1;
            if (next - capacity > minConsumed()) return -1;
            if (claimed.compareAndSet(current, next)) return next;
        }
    }

    /** Claim the next sequence, waiting for space if necessary. For producers that must not drop. */
    public long claim() {
        long next = claimed.getAndIncrement() + 1;
        int spins = 0;
        while (next - capacity > minConsumed()) {
            if (++spins < 100) Thread.onSpinWait();
            else LockSupport.parkNanos(1_000);
        }
        return next;
    }

    @SuppressWarnings("unchecked")
    public T get(long sequence) {
        return (T) slots[(int) (sequence & mask)];
    }

    public void publish(long sequence) {
        PUBLISHED.setRelease(published, (int) (sequence & mask), sequence);
    }

    @FunctionalInterface
    public interface Handler<T> {
        void onEvent(T slot, long sequence);
    }

    /** Single-consumer convenience. */
    public int poll(Handler<T> handler, int maxBatch) {
        return poll(0, handler, maxBatch);
    }

    /**
     * Consume up to {@code maxBatch} published slots in order as consumer {@code consumer}. Each consumer index
     * must be driven by exactly one thread. Slots are released to producers after the whole batch.
     * @return number of slots consumed
     */
    public int poll(int consumer, Handler<T> handler, int maxBatch) {
        Sequence cursor = consumers[consumer];
        long next = cursor.getPlain() + 1;
        int n = 0;
        while (n < maxBatch && (long) PUBLISHED.getAcquire(published, (int) (next & mask)) == next) {
            handler.onEvent(get(next), next);
            next++;
            n++;
        }
        if (n > 0) cursor.setRelease(next - 1);
        return n;
    }

    /** Number of claimed slots the slowest consumer has not yet passed (queue-depth gauge). */
    public long depth() {
        return Math.max(0, claimed.getVolatile() - minConsumed());
    }

    /** True if consumer {@code consumer} has at least one published slot waiting. */
    public boolean hasPublished(int consumer) {
        long next = consumers[consumer].getAcquire() + 1;
        return (long) PUBLISHED.getVolatile(published, (int) (next & mask)) == next;
    }

    /** Highest sequence claimed by any producer (possibly not yet published). */
    public long claimed() {
        return claimed.getVolatile();
    }

    public long consumed(int consumer) {
        return consumers[consumer].getAcquire();
    }

    // ------------------------------------------------------------------ padded sequence

    @SuppressWarnings("unused")
    abstract static class LhsPadding {
        long p01, p02, p03, p04, p05, p06, p07, p08, p09, p10, p11, p12, p13, p14, p15;
    }

    abstract static class Value extends LhsPadding {
        volatile long value;
    }

    /** A {@code long} cursor with 120 bytes of padding on each side: never shares a cache line (or adjacent-line prefetch pair). */
    @SuppressWarnings("unused")
    public static final class Sequence extends Value {
        private static final VarHandle VALUE;

        static {
            try {
                VALUE = MethodHandles.lookup().findVarHandle(Value.class, "value", long.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        long q01, q02, q03, q04, q05, q06, q07, q08, q09, q10, q11, q12, q13, q14, q15;

        public Sequence(long initial) {
            VALUE.setRelease(this, initial);
        }

        public long getPlain() { return (long) VALUE.get(this); }
        public long getVolatile() { return value; }
        public long getAcquire() { return (long) VALUE.getAcquire(this); }
        public void setRelease(long v) { VALUE.setRelease(this, v); }
        public boolean compareAndSet(long expect, long update) { return VALUE.compareAndSet(this, expect, update); }
        public long getAndIncrement() { return (long) VALUE.getAndAdd(this, 1L); }
    }
}
