package io.tradevision.gateway.analytics;

/**
 * One time bucket of market activity. Produced either in memory ({@link MinuteBars}) or by a ClickHouse
 * aggregation ({@link ClickHouseAnalytics}); every metric in {@link AnalyticsService} is computed from these.
 *
 * <p>Prices in YES ticks ($0.001). {@code open..close} are -1 when the bucket had no trades. Book fields are
 * averages over the ~1 s book samples in the bucket, {@code NaN} if there were none.
 */
public record Bucket(long t, int open, int high, int low, int close, long volume, long buyVolume, long sellVolume,
                     long notional, long trades, double spread, double imbalance, double refMid) {
    public boolean traded() {
        return trades > 0;
    }
}
