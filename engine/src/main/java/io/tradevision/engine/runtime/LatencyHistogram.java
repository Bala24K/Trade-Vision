package io.tradevision.engine.runtime;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Fixed-memory log-linear latency histogram (the HdrHistogram idea, in ~80 lines, zero dependencies).
 *
 * <p>Values are bucketed by power of two (the "magnitude") and each magnitude is split into
 * {@code 2^SUB_BITS} linear sub-buckets, giving a constant ~{@code 1/2^SUB_BITS} relative error
 * (3% here) from 1 ns to ~1 hour in under 2,500 counters. Recording is one {@code numberOfLeadingZeros},
 * a shift and an atomic increment: cheap enough for the engine hot path, safe for concurrent writers.
 *
 * <p>Reporting p50/p99/p99.9 rather than averages matters: a matching engine that averages 2 µs but
 * stalls 5 ms on every GC is useless to a trader, and an average hides exactly that.
 */
public final class LatencyHistogram {
    private static final int SUB_BITS = 5;
    private static final int SUB_COUNT = 1 << SUB_BITS;
    private final AtomicLongArray counts = new AtomicLongArray(64 * SUB_COUNT);

    public void record(long valueNanos) {
        if (valueNanos < 0) valueNanos = 0;
        counts.incrementAndGet(index(valueNanos));
    }

    /**
     * Values below {@code 2 * SUB_COUNT} get exact buckets. Above that, shift right by magnitude {@code m}
     * so exactly {@code SUB_BITS + 1} significant bits remain: the leading 1 picks the magnitude, the next
     * {@code SUB_BITS} bits pick the sub-bucket. Bucket width is {@code 2^m} for values {@code >= 32 * 2^m}.
     */
    static int index(long v) {
        int m = Math.max(0, 64 - Long.numberOfLeadingZeros(v) - (SUB_BITS + 1));
        if (m == 0) return (int) v;
        int sub = (int) (v >>> m) - SUB_COUNT;
        return (m + 1) * SUB_COUNT + sub;
    }

    /** Upper bound of the value range represented by bucket {@code i}. */
    static long valueAt(int i) {
        if (i < 2 * SUB_COUNT) return i;
        int m = i / SUB_COUNT - 1;
        int sub = i % SUB_COUNT;
        return ((long) (SUB_COUNT + sub) << m) + (1L << m) - 1;
    }

    public long count() {
        long n = 0;
        for (int i = 0; i < counts.length(); i++) n += counts.get(i);
        return n;
    }

    /** Value at {@code percentile} (0..100], e.g. 99.9. Returns 0 when empty. */
    public long percentile(double percentile) {
        long total = count();
        if (total == 0) return 0;
        long target = Math.max(1, (long) Math.ceil(total * percentile / 100.0));
        long seen = 0;
        for (int i = 0; i < counts.length(); i++) {
            seen += counts.get(i);
            if (seen >= target) return valueAt(i);
        }
        return valueAt(counts.length() - 1);
    }

    public long max() {
        for (int i = counts.length() - 1; i >= 0; i--) if (counts.get(i) != 0) return valueAt(i);
        return 0;
    }

    public void reset() {
        for (int i = 0; i < counts.length(); i++) counts.set(i, 0);
    }

    public String summary(String unit, long divisor) {
        return String.format("n=%d p50=%d%s p90=%d%s p99=%d%s p99.9=%d%s max=%d%s", count(),
                percentile(50) / divisor, unit, percentile(90) / divisor, unit, percentile(99) / divisor, unit,
                percentile(99.9) / divisor, unit, max() / divisor, unit);
    }
}
