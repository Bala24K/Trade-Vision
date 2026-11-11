package io.tradevision.bench;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * The textbook baseline: {@code TreeMap<price, ArrayDeque<order>>} per side plus a {@code HashMap} id index.
 * Same semantics as the engine's book (price-time priority, match at maker price, cancel by id) so the
 * benchmark compares data structures, not features.
 */
final class TreeMapBook {
    static final class O {
        final long id;
        final boolean bid;
        final int price;
        long remaining;

        O(long id, boolean bid, int price, long qty) {
            this.id = id;
            this.bid = bid;
            this.price = price;
            this.remaining = qty;
        }
    }

    private final TreeMap<Integer, ArrayDeque<O>> bids = new TreeMap<>();
    private final TreeMap<Integer, ArrayDeque<O>> asks = new TreeMap<>();
    private final Map<Long, O> index = new HashMap<>();
    long traded;

    void place(long id, boolean bid, int price, long qty) {
        TreeMap<Integer, ArrayDeque<O>> opp = bid ? asks : bids;
        while (qty > 0 && !opp.isEmpty()) {
            Map.Entry<Integer, ArrayDeque<O>> best = bid ? opp.firstEntry() : opp.lastEntry();
            if (bid ? best.getKey() > price : best.getKey() < price) break;
            ArrayDeque<O> q = best.getValue();
            O maker = q.peekFirst();
            long x = Math.min(qty, maker.remaining);
            maker.remaining -= x;
            qty -= x;
            traded += x;
            if (maker.remaining == 0) {
                q.pollFirst();
                index.remove(maker.id);
                if (q.isEmpty()) opp.remove(best.getKey());
            }
        }
        if (qty > 0) {
            O o = new O(id, bid, price, qty);
            (bid ? bids : asks).computeIfAbsent(price, p -> new ArrayDeque<>()).addLast(o);
            index.put(id, o);
        }
    }

    void cancel(long id) {
        O o = index.remove(id);
        if (o == null) return;
        TreeMap<Integer, ArrayDeque<O>> side = o.bid ? bids : asks;
        ArrayDeque<O> q = side.get(o.price);
        // ArrayDeque cannot unlink from the middle in O(1): this linear scan is the baseline's real cost.
        for (Iterator<O> it = q.iterator(); it.hasNext(); ) {
            if (it.next() == o) {
                it.remove();
                break;
            }
        }
        if (q.isEmpty()) side.remove(o.price);
    }
}
