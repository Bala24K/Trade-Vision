package io.tradevision.gateway.analytics;

import io.tradevision.gateway.engine.EngineHost;
import io.tradevision.gateway.engine.Event;
import io.tradevision.gateway.ingest.PolymarketFeed;
import io.tradevision.gateway.ingest.ReferenceBook;
import io.tradevision.gateway.read.MarketState;
import io.tradevision.gateway.read.ReadModel;
import io.tradevision.engine.Side;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Always-on, in-memory analytics store: one-minute buckets for the last 24 hours per market, built from durable
 * trade events plus a once-per-second book sampler. Serves analytics when ClickHouse is not configured, and the
 * sampler also feeds the ClickHouse sink when it is.
 *
 * <p>Memory is bounded: 16 markets x 1440 buckets x ~120 bytes = under 3 MB.
 */
@Component
public class MinuteBars implements EngineHost.Sink {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MinuteBars.class);
    static final int BUCKET_SECONDS = 60;
    static final int KEEP = 1_440;

    private static final class Acc {
        final long t;
        int open = -1, high = -1, low = -1, close = -1;
        long volume, buy, sell, notional, trades;
        double spreadSum, imbSum, refSum;
        int spreadN, imbN, refN;

        Acc(long t) { this.t = t; }

        Bucket toBucket() {
            return new Bucket(t, open, high, low, close, volume, buy, sell, notional, trades,
                    spreadN == 0 ? Double.NaN : spreadSum / spreadN, imbN == 0 ? Double.NaN : imbSum / imbN,
                    refN == 0 ? Double.NaN : refSum / refN);
        }
    }

    private final ReadModel readModel;
    private final PolymarketFeed polymarket;
    private final ConcurrentHashMap<Long, TreeMap<Long, Acc>> markets = new ConcurrentHashMap<>();
    private final List<Consumer<List<BookSample>>> sampleListeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofPlatform().name("tv-book-sampler").daemon(true).unstarted(r));

    public MinuteBars(EngineHost engine, ReadModel readModel, PolymarketFeed polymarket) {
        this.readModel = readModel;
        this.polymarket = polymarket;
        engine.addSink(this);
        sampler.scheduleAtFixedRate(this::sample, 1, 1, TimeUnit.SECONDS);
    }

    public void onSamples(Consumer<List<BookSample>> listener) {
        sampleListeners.add(listener);
    }

    // ------------------------------------------------------------------ inputs

    @Override
    public void onEvents(long sequence, List<Event> events) {
        for (Event e : events) if (e instanceof Event.Trade t) trade(t);
    }

    @Override
    public void onReplay(long sequence, List<Event> events) {
        onEvents(sequence, events); // rebuild the recent window after a restart from the journal tail
    }

    void trade(Event.Trade t) {
        long sec = t.ts() / 1_000_000;
        Acc a = acc(t.marketId(), sec);
        if (a == null) return;
        synchronized (a) {
            int p = t.price();
            if (a.open == -1) { a.open = p; a.high = p; a.low = p; }
            a.high = Math.max(a.high, p);
            a.low = Math.min(a.low, p);
            a.close = p;
            a.volume += t.quantity();
            a.notional += (long) p * t.quantity();
            if (t.aggressor() == Side.BID) a.buy += t.quantity(); else a.sell += t.quantity();
            a.trades++;
        }
    }

    void sample(BookSample s) {
        Acc a = acc(s.marketId(), s.tsMillis() / 1000);
        if (a == null) return;
        synchronized (a) {
            if (!Double.isNaN(s.spread())) { a.spreadSum += s.spread(); a.spreadN++; }
            if (!Double.isNaN(s.imbalance())) { a.imbSum += s.imbalance(); a.imbN++; }
            if (!Double.isNaN(s.refMid())) { a.refSum += s.refMid(); a.refN++; }
        }
    }

    private void sample() {
        try {
            long now = System.currentTimeMillis();
            List<BookSample> batch = new ArrayList<>();
            for (MarketState m : readModel.markets()) {
                MarketState.Depth d = m.depth(5);
                long bd = 0, ad = 0;
                for (MarketState.Level l : d.bids()) bd += l.quantity();
                for (MarketState.Level l : d.asks()) ad += l.quantity();
                ReferenceBook.Quote q = polymarket.reference(m.marketId).orElse(null);
                BookSample s = new BookSample(m.marketId, now,
                        d.bids().isEmpty() ? -1 : d.bids().get(0).price(), d.asks().isEmpty() ? -1 : d.asks().get(0).price(),
                        bd, ad, q == null ? -1 : q.bestBid(), q == null ? -1 : q.bestAsk());
                sample(s);
                batch.add(s);
            }
            for (var l : sampleListeners) l.accept(batch);
        } catch (RuntimeException e) {
            // An exception would cancel the scheduled task for good; log it and keep sampling on the next tick.
            log.warn("book sampler failed", e);
        }
    }

    private Acc acc(long marketId, long sec) {
        long t = sec - Math.floorMod(sec, BUCKET_SECONDS);
        TreeMap<Long, Acc> m = markets.computeIfAbsent(marketId, k -> new TreeMap<>());
        synchronized (m) {
            if (!m.isEmpty() && t < m.lastKey() - (long) KEEP * BUCKET_SECONDS) return null; // older than retention
            Acc a = m.computeIfAbsent(t, Acc::new);
            while (m.size() > KEEP) m.pollFirstEntry();
            return a;
        }
    }

    // ------------------------------------------------------------------ query

    /** Buckets with {@code t >= fromSec}, oldest first, re-aggregated to {@code bucketSeconds} (a multiple of 60). */
    public List<Bucket> buckets(long marketId, long fromSec, int bucketSeconds) {
        TreeMap<Long, Acc> m = markets.get(marketId);
        if (m == null) return List.of();
        List<Bucket> minutes = new ArrayList<>();
        synchronized (m) {
            for (Map.Entry<Long, Acc> e : m.tailMap(fromSec, true).entrySet()) {
                synchronized (e.getValue()) {
                    minutes.add(e.getValue().toBucket());
                }
            }
        }
        return bucketSeconds <= BUCKET_SECONDS ? minutes : Buckets.rollup(minutes, bucketSeconds);
    }
}
