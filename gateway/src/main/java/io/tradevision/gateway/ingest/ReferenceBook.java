package io.tradevision.gateway.ingest;

import java.util.Comparator;
import java.util.TreeMap;

/**
 * Local mirror of one external (Polymarket) order book for a YES token, normalised to our tick units.
 *
 * <h2>Integrity without sequence numbers</h2>
 * Polymarket's public market channel carries no per-message sequence number, so classic gap detection
 * ("expected seq N, got N+2") is impossible. We use what the protocol does give us:
 * <ul>
 *   <li>Every {@code book} message is an authoritative full snapshot with a timestamp; it resets the mirror.</li>
 *   <li>A {@code price_change} older than the current snapshot is <b>stale</b> (out-of-order delivery) and dropped.</li>
 *   <li>A delta whose {@code hash} equals the previous one is a <b>duplicate</b> (redelivery) and dropped.</li>
 *   <li>After applying a delta, a <b>crossed</b> book (best bid ≥ best ask) means we missed something; the feed
 *       resynchronises by resubscribing, which makes the venue send fresh snapshots.</li>
 * </ul>
 */
public final class ReferenceBook {
    public enum Apply { OK, STALE, DUPLICATE, CROSSED }

    public record Quote(int bestBid, int bestAsk, int mid, int last, long updatedAtMillis) {}

    private final TreeMap<Integer, Long> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Integer, Long> asks = new TreeMap<>();
    private long snapshotTs = -1;
    private String lastHash;
    private int last = -1;
    private long updatedAt;

    public static int ticks(String price) {
        return (int) Math.max(0, Math.min(1000, Math.round(Double.parseDouble(price) * 1000)));
    }

    public static long size(String size) {
        return Math.round(Double.parseDouble(size));
    }

    public synchronized void snapshot(long ts, int[] bidPx, long[] bidSz, int[] askPx, long[] askSz) {
        bids.clear();
        asks.clear();
        for (int i = 0; i < bidPx.length; i++) if (bidSz[i] > 0) bids.put(bidPx[i], bidSz[i]);
        for (int i = 0; i < askPx.length; i++) if (askSz[i] > 0) asks.put(askPx[i], askSz[i]);
        snapshotTs = ts;
        lastHash = null;
        updatedAt = System.currentTimeMillis();
    }

    public synchronized Apply delta(long ts, String hash, boolean bid, int price, long size) {
        if (snapshotTs < 0 || ts < snapshotTs) return Apply.STALE;
        if (hash != null && hash.equals(lastHash)) return Apply.DUPLICATE;
        lastHash = hash;
        TreeMap<Integer, Long> side = bid ? bids : asks;
        if (size <= 0) side.remove(price);
        else side.put(price, size);
        updatedAt = System.currentTimeMillis();
        if (!bids.isEmpty() && !asks.isEmpty() && bids.firstKey() >= asks.firstKey()) return Apply.CROSSED;
        return Apply.OK;
    }

    public synchronized void lastTrade(int price) {
        last = price;
        updatedAt = System.currentTimeMillis();
    }

    public synchronized boolean hasSnapshot() {
        return snapshotTs >= 0;
    }

    public synchronized Quote quote() {
        int bb = bids.isEmpty() ? -1 : bids.firstKey();
        int ba = asks.isEmpty() ? -1 : asks.firstKey();
        int mid = (bb != -1 && ba != -1) ? (bb + ba) / 2 : (last != -1 ? last : (bb != -1 ? bb : ba));
        return new Quote(bb, ba, mid, last, updatedAt);
    }
}
