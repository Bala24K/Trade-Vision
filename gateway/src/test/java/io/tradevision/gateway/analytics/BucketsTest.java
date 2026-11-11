package io.tradevision.gateway.analytics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BucketsTest {
    static final double N = Double.NaN;

    static Bucket traded(long t, int close, long vol, long buy) {
        return new Bucket(t, close, close, close, close, vol, buy, vol - buy, (long) close * vol, 1, 10, 0.2, close + 5);
    }

    static Bucket silent(long t) {
        return new Bucket(t, -1, -1, -1, -1, 0, 0, 0, 0, 0, N, N, N);
    }

    @Test
    void vwapFlowSpreadAndBasis() {
        var m = Buckets.metrics(List.of(traded(0, 500, 10, 10), traded(60, 600, 30, 0)), 60);
        assertEquals(575.0, m.vwap(), 1e-9, "(500*10 + 600*30) / 40");
        assertEquals(40, m.volume());
        assertEquals(0.25, m.buyShare(), 1e-9);
        assertEquals(500, m.open());
        assertEquals(600, m.last());
        assertEquals(10.0, m.avgSpread(), 1e-9);
        assertEquals(-0.5, m.refBasisCents(), 1e-9, "trades 5 ticks (0.5¢) below the reference mid");
    }

    @Test
    void realizedVolatilityUsesOnlyConsecutiveTradedBuckets() {
        // moves +10, -10, +10 (ticks) => sample stdev = sqrt(100 + 100/3*...) computed explicitly below
        var series = List.of(traded(0, 500, 1, 1), traded(60, 510, 1, 1), traded(120, 500, 1, 1), traded(180, 510, 1, 1),
                silent(240), traded(300, 900, 1, 1)); // the jump after a silent bucket is not a 1-minute move
        var m = Buckets.metrics(series, 60);
        double mean = 10.0 / 3, var = (Math.pow(10 - mean, 2) * 2 + Math.pow(-10 - mean, 2)) / 2;
        assertEquals(Math.sqrt(var) * Math.sqrt(60) / 10, m.realizedVolCentsPerHour(), 1e-9);
    }

    @Test
    void emptyWindowGivesNullsNotNaN() {
        var m = Buckets.metrics(List.of(silent(0), silent(60)), 60);
        assertNull(m.vwap());
        assertNull(m.realizedVolCentsPerHour());
        assertNull(m.avgSpread());
        assertEquals(0, m.trades());
    }

    @Test
    void rollupMergesMinutesIntoWiderBuckets() {
        var r = Buckets.rollup(List.of(traded(0, 500, 10, 5), traded(60, 520, 10, 5), traded(300, 530, 1, 1)), 300);
        assertEquals(2, r.size());
        assertEquals(500, r.get(0).open());
        assertEquals(520, r.get(0).close());
        assertEquals(20, r.get(0).volume());
        assertEquals(300, r.get(1).t());
    }
}
