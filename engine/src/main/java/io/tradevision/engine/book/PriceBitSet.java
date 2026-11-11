package io.tradevision.engine.book;

/**
 * Occupancy bitmap over the 1024 possible price ticks: bit {@code p} is set iff price level {@code p}
 * has resting quantity.
 *
 * <p>Finding the next best price after a level empties is the classic order-book hot spot. A
 * {@code TreeMap} does it in O(log n) with pointer chasing; scanning the level array does it in O(range).
 * This does it in at most 16 word reads (128 bytes, two cache lines), and usually one: each step is a
 * single {@code Long.numberOfLeadingZeros}/{@code numberOfTrailingZeros}, which HotSpot compiles to the
 * {@code LZCNT}/{@code TZCNT} instructions on x86-64.
 */
public final class PriceBitSet {
    private static final int WORDS = 16; // 16 * 64 = 1024 ticks
    private final long[] words = new long[WORDS];
    private int count;

    public void set(int price) {
        int w = price >>> 6;
        long bit = 1L << (price & 63);
        if ((words[w] & bit) == 0) {
            words[w] |= bit;
            count++;
        }
    }

    public void clear(int price) {
        int w = price >>> 6;
        long bit = 1L << (price & 63);
        if ((words[w] & bit) != 0) {
            words[w] &= ~bit;
            count--;
        }
    }

    public boolean get(int price) {
        return (words[price >>> 6] & (1L << (price & 63))) != 0;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public int cardinality() {
        return count;
    }

    /** Highest set price {@code <= price}, or -1. Used to find the best bid. */
    public int highestAtOrBelow(int price) {
        if (price < 0) return -1;
        int w = price >>> 6;
        long bits;
        if (w >= WORDS) {
            w = WORDS - 1;
            bits = words[w];
        } else {
            bits = words[w] & (-1L >>> (63 - (price & 63)));
        }
        while (true) {
            if (bits != 0) return (w << 6) + 63 - Long.numberOfLeadingZeros(bits);
            if (--w < 0) return -1;
            bits = words[w];
        }
    }

    /** Lowest set price {@code >= price}, or -1. Used to find the best ask. */
    public int lowestAtOrAbove(int price) {
        if (price < 0) price = 0;
        int w = price >>> 6;
        if (w >= WORDS) return -1;
        long bits = words[w] & (-1L << (price & 63));
        while (true) {
            if (bits != 0) return (w << 6) + Long.numberOfTrailingZeros(bits);
            if (++w >= WORDS) return -1;
            bits = words[w];
        }
    }
}
