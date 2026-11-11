package io.tradevision.gateway.analytics;

/**
 * Top-of-book snapshot taken once per second per market. {@code -1} = side empty / no reference.
 * Depth is the quantity in the best 5 levels of each side.
 */
public record BookSample(long marketId, long tsMillis, int bestBid, int bestAsk, long bidDepth, long askDepth,
                         int refBid, int refAsk) {
    public double spread() {
        return bestBid > 0 && bestAsk > 0 ? bestAsk - bestBid : Double.NaN;
    }

    /** (bid depth - ask depth) / total, in [-1, 1]. Positive = more resting buying interest. */
    public double imbalance() {
        long t = bidDepth + askDepth;
        return t == 0 ? Double.NaN : (double) (bidDepth - askDepth) / t;
    }

    public double refMid() {
        return refBid > 0 && refAsk > 0 ? (refBid + refAsk) / 2.0 : Double.NaN;
    }
}
