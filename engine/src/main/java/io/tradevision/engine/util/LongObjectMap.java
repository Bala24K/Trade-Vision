package io.tradevision.engine.util;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Open-addressing {@code long -> V} hash map with linear probing and backward-shift deletion.
 *
 * <p>Why not {@code HashMap<Long, V>}? Every lookup there boxes the key (an allocation on the hot path),
 * chases a pointer to a {@code Node}, then another to the {@code Long}. Here keys live in a flat
 * {@code long[]}, so a probe sequence is a sequential scan over one or two cache lines and nothing is
 * allocated. Backward-shift deletion keeps probe chains short without tombstones, so cancel-heavy order
 * flow (most orders on real venues are cancelled, not filled) does not degrade lookups over time.
 *
 * <p>Key {@code 0} is reserved as the empty marker. Not thread-safe: owned by the single engine thread.
 */
public final class LongObjectMap<V> {
    private static final long EMPTY = 0L;
    private static final float LOAD_FACTOR = 0.5f;

    private long[] keys;
    private Object[] values;
    private int mask;
    private int size;
    private int resizeAt;

    public LongObjectMap() {
        this(16);
    }

    public LongObjectMap(int expectedSize) {
        int capacity = Integer.highestOneBit(Math.max(4, (int) (expectedSize / LOAD_FACTOR)) - 1) << 1;
        allocate(capacity);
    }

    private void allocate(int capacity) {
        keys = new long[capacity];
        values = new Object[capacity];
        mask = capacity - 1;
        resizeAt = (int) (capacity * LOAD_FACTOR);
    }

    /** Fibonacci hashing: spreads sequential ids (the common case for order ids) across the table. */
    private int slot(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32)) & mask;
    }

    @SuppressWarnings("unchecked")
    public V get(long key) {
        for (int i = slot(key); ; i = (i + 1) & mask) {
            long k = keys[i];
            if (k == key) return (V) values[i];
            if (k == EMPTY) return null;
        }
    }

    public boolean containsKey(long key) {
        return key != EMPTY && get(key) != null;
    }

    @SuppressWarnings("unchecked")
    public V put(long key, V value) {
        if (key == EMPTY) throw new IllegalArgumentException("key 0 is reserved");
        if (value == null) throw new IllegalArgumentException("null values are not supported");
        for (int i = slot(key); ; i = (i + 1) & mask) {
            long k = keys[i];
            if (k == key) {
                V old = (V) values[i];
                values[i] = value;
                return old;
            }
            if (k == EMPTY) {
                keys[i] = key;
                values[i] = value;
                if (++size > resizeAt) rehash(keys.length << 1);
                return null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    public V remove(long key) {
        if (key == EMPTY) return null;
        for (int i = slot(key); ; i = (i + 1) & mask) {
            long k = keys[i];
            if (k == EMPTY) return null;
            if (k == key) {
                V old = (V) values[i];
                shiftBack(i);
                size--;
                return old;
            }
        }
    }

    /** Close the gap at {@code hole} by pulling back any later entry whose home slot is not inside (hole, i]. */
    private void shiftBack(int hole) {
        int i = hole;
        while (true) {
            i = (i + 1) & mask;
            long k = keys[i];
            if (k == EMPTY) break;
            int home = slot(k);
            boolean movable = hole <= i ? (home <= hole || home > i) : (home <= hole && home > i);
            if (movable) {
                keys[hole] = k;
                values[hole] = values[i];
                hole = i;
            }
        }
        keys[hole] = EMPTY;
        values[hole] = null;
    }

    private void rehash(int newCapacity) {
        long[] oldKeys = keys;
        Object[] oldValues = values;
        allocate(newCapacity);
        for (int i = 0; i < oldKeys.length; i++) {
            long k = oldKeys[i];
            if (k != EMPTY) {
                int j = slot(k);
                while (keys[j] != EMPTY) j = (j + 1) & mask;
                keys[j] = k;
                values[j] = oldValues[i];
            }
        }
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void clear() {
        Arrays.fill(keys, EMPTY);
        Arrays.fill(values, null);
        size = 0;
    }

    /** Iteration order is unspecified. Do not mutate the map from {@code action}. */
    @SuppressWarnings("unchecked")
    public void forEachValue(Consumer<V> action) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != EMPTY) action.accept((V) values[i]);
        }
    }

    /** Keys in ascending order, for anything that must be deterministic (snapshots, digests). */
    public long[] sortedKeys() {
        long[] out = new long[size];
        int n = 0;
        for (long k : keys) if (k != EMPTY) out[n++] = k;
        Arrays.sort(out);
        return out;
    }
}
