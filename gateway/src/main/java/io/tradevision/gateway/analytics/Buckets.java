package io.tradevision.gateway.analytics;

import java.util.ArrayList;
import java.util.List;

/** Pure functions over {@link Bucket} series: roll-ups and the market metrics. Unit-tested without Spring. */
public final class Buckets {
    private Buckets() {}

    /**
     * Window metrics. Nullable fields are {@code null} when undefined (no trades, no book samples...), never NaN:
     * NaN is not valid JSON. Prices in ticks ($0.001) unless named *Cents.
     */
    public record Metrics(long trades, long volume, long buyVolume, long sellVolume, Double buyShare, Double vwap,
                          Integer open, Integer last, Integer high, Integer low, Double avgTradeSize,
                          Double realizedVolCentsPerHour, Double avgSpread, Double avgImbalance,
                          Double refBasisCents, int buckets) {}

    public static Metrics metrics(List<Bucket> series, int bucketSeconds) {
        long trades = 0, volume = 0, buy = 0, sell = 0, notional = 0;
        Integer open = null, last = null, high = null, low = null;
        double spreadSum = 0, imbSum = 0, basisSum = 0;
        int spreadN = 0, imbN = 0, basisN = 0;
        List<Double> moves = new ArrayList<>();
        Integer prevClose = null;
        for (Bucket b : series) {
            trades += b.trades();
            volume += b.volume();
            buy += b.buyVolume();
            sell += b.sellVolume();
            notional += b.notional();
            if (b.traded()) {
                if (open == null) open = b.open();
                last = b.close();
                high = high == null ? b.high() : Math.max(high, b.high());
                low = low == null ? b.low() : Math.min(low, b.low());
                // Close-to-close moves between consecutive buckets that both traded. Skipping silent buckets
                // (rather than carrying the price forward) avoids diluting volatility with artificial zeros.
                if (prevClose != null) moves.add((double) (b.close() - prevClose));
                prevClose = b.close();
                if (!Double.isNaN(b.refMid())) { basisSum += b.close() - b.refMid(); basisN++; }
            } else {
                prevClose = null;
            }
            if (!Double.isNaN(b.spread())) { spreadSum += b.spread(); spreadN++; }
            if (!Double.isNaN(b.imbalance())) { imbSum += b.imbalance(); imbN++; }
        }
        return new Metrics(trades, volume, buy, sell, volume == 0 ? null : (double) buy / volume,
                volume == 0 ? null : (double) notional / volume, open, last, high, low,
                trades == 0 ? null : (double) volume / trades, realizedVol(moves, bucketSeconds),
                spreadN == 0 ? null : spreadSum / spreadN, imbN == 0 ? null : imbSum / imbN,
                basisN == 0 ? null : basisSum / basisN / 10.0, series.size());
    }

    /**
     * Realized volatility: standard deviation of close-to-close price moves, scaled by sqrt(time) to one hour and
     * expressed in cents. Prediction-market prices are bounded probabilities, so absolute moves in cents are more
     * meaningful than the log returns used for stocks (a 1¢ -> 2¢ move is not "+100%" of anything useful).
     */
    static Double realizedVol(List<Double> moves, int bucketSeconds) {
        if (moves.size() < 2) return null;
        double mean = 0;
        for (double m : moves) mean += m;
        mean /= moves.size();
        double var = 0;
        for (double m : moves) var += (m - mean) * (m - mean);
        var /= moves.size() - 1;
        return Math.sqrt(var) * Math.sqrt(3600.0 / bucketSeconds) / 10.0;
    }

    /** Merge consecutive buckets into {@code bucketSeconds}-wide ones (book fields: mean of the defined values). */
    public static List<Bucket> rollup(List<Bucket> in, int bucketSeconds) {
        List<Bucket> out = new ArrayList<>();
        int i = 0;
        while (i < in.size()) {
            long t = in.get(i).t() - Math.floorMod(in.get(i).t(), bucketSeconds);
            int open = -1, high = -1, low = -1, close = -1;
            long vol = 0, buy = 0, sell = 0, notional = 0, trades = 0;
            double s = 0, im = 0, r = 0;
            int sn = 0, imn = 0, rn = 0;
            for (; i < in.size() && in.get(i).t() < t + bucketSeconds; i++) {
                Bucket b = in.get(i);
                if (b.traded()) {
                    if (open == -1) { open = b.open(); high = b.high(); low = b.low(); }
                    high = Math.max(high, b.high());
                    low = Math.min(low, b.low());
                    close = b.close();
                }
                vol += b.volume();
                buy += b.buyVolume();
                sell += b.sellVolume();
                notional += b.notional();
                trades += b.trades();
                if (!Double.isNaN(b.spread())) { s += b.spread(); sn++; }
                if (!Double.isNaN(b.imbalance())) { im += b.imbalance(); imn++; }
                if (!Double.isNaN(b.refMid())) { r += b.refMid(); rn++; }
            }
            out.add(new Bucket(t, open, high, low, close, vol, buy, sell, notional, trades,
                    sn == 0 ? Double.NaN : s / sn, imn == 0 ? Double.NaN : im / imn, rn == 0 ? Double.NaN : r / rn));
        }
        return out;
    }
}
