package io.tradevision.gateway.read;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Fixed-capacity OHLCV series for one interval. Guarded by the owning {@link MarketState}'s monitor. */
public final class CandleSeries {
    /** {@code time} = bucket start in epoch seconds (the unit charting libraries expect); prices in YES ticks. */
    public record Candle(long time, int open, int high, int low, int close, long volume) {}

    private final long intervalSec;
    private final int capacity;
    private final ArrayDeque<long[]> buckets = new ArrayDeque<>(); // [time, o, h, l, c, v]

    CandleSeries(long intervalSec, int capacity) {
        this.intervalSec = intervalSec;
        this.capacity = capacity;
    }

    void add(long tsMicros, int price, long qty) {
        long t = (tsMicros / 1_000_000L) / intervalSec * intervalSec;
        long[] last = buckets.peekLast();
        if (last != null && last[0] == t) {
            last[2] = Math.max(last[2], price);
            last[3] = Math.min(last[3], price);
            last[4] = price;
            last[5] += qty;
            return;
        }
        if (last != null && t < last[0]) { // late trade (clock step): fold into the latest bucket
            last[4] = price;
            last[5] += qty;
            return;
        }
        buckets.addLast(new long[] {t, price, price, price, price, qty});
        if (buckets.size() > capacity) buckets.pollFirst();
    }

    List<Candle> toList() {
        List<Candle> out = new ArrayList<>(buckets.size());
        for (long[] b : buckets) out.add(new Candle(b[0], (int) b[1], (int) b[2], (int) b[3], (int) b[4], b[5]));
        return out;
    }

    /** Close of the first bucket at or after {@code sinceSec}, or -1. Used for 24h change. */
    int openSince(long sinceSec) {
        for (long[] b : buckets) if (b[0] >= sinceSec) return (int) b[1];
        return -1;
    }
}
