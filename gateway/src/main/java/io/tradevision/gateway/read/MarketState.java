package io.tradevision.gateway.read;

import io.tradevision.engine.MarketStatus;
import io.tradevision.engine.Outcome;
import io.tradevision.engine.Prices;
import io.tradevision.engine.Side;
import io.tradevision.gateway.engine.Event;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Query-side view of one market, rebuilt from events. Written only by the publisher thread, read by HTTP
 * and WebSocket threads; every method synchronizes on this instance (uncontended in practice: one writer,
 * short critical sections).
 */
public final class MarketState {
    public record Level(int price, long quantity) {}

    public record Depth(long marketId, long seq, List<Level> bids, List<Level> asks) {}

    public record Stats(long marketId, MarketStatus status, Outcome winner, int bestBid, int bestAsk, int last,
                        int change24h, long volume, long notional, long trades, long bidDepth, long askDepth, int mark) {}

    public record TapeEntry(long tradeId, int price, long quantity, Side aggressor, long ts) {}

    public final long marketId;
    private MarketStatus status = MarketStatus.OPEN;
    private Outcome winner;
    private final TreeMap<Integer, Long> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Integer, Long> asks = new TreeMap<>();
    private long bookSeq;
    private int last = -1;
    private long volume, notional, trades;
    private final ArrayDeque<TapeEntry> tape = new ArrayDeque<>();
    private final CandleSeries c5s = new CandleSeries(5, 720);
    private final CandleSeries c1m = new CandleSeries(60, 1440);
    private final CandleSeries c15m = new CandleSeries(900, 672);

    MarketState(long marketId) {
        this.marketId = marketId;
    }

    synchronized void status(MarketStatus status, Outcome winner) {
        this.status = status;
        this.winner = winner;
    }

    /** @return the book sequence after applying this level change */
    synchronized long level(Side side, int price, long qty) {
        TreeMap<Integer, Long> book = side == Side.BID ? bids : asks;
        if (qty == 0) book.remove(price);
        else book.put(price, qty);
        return ++bookSeq;
    }

    synchronized void trade(Event.Trade t) {
        last = t.price();
        volume += t.quantity();
        notional += (long) t.price() * t.quantity();
        trades++;
        tape.addFirst(new TapeEntry(t.tradeId(), t.price(), t.quantity(), t.aggressor(), t.ts()));
        if (tape.size() > 200) tape.pollLast();
        c5s.add(t.ts(), t.price(), t.quantity());
        c1m.add(t.ts(), t.price(), t.quantity());
        c15m.add(t.ts(), t.price(), t.quantity());
    }

    synchronized void seedStats(MarketStatus status, Outcome winner, int last, long volume, long notional, long trades) {
        this.status = status;
        this.winner = winner;
        this.last = last;
        this.volume = volume;
        this.notional = notional;
        this.trades = trades;
    }

    public synchronized Depth depth(int levels) {
        return new Depth(marketId, bookSeq, top(bids, levels), top(asks, levels));
    }

    private static List<Level> top(TreeMap<Integer, Long> book, int n) {
        List<Level> out = new ArrayList<>(Math.min(n, book.size()));
        for (Map.Entry<Integer, Long> e : book.entrySet()) {
            if (out.size() == n) break;
            out.add(new Level(e.getKey(), e.getValue()));
        }
        return out;
    }

    /**
     * Current quantities (0 = removed) for the given touched prices, read atomically with the book sequence.
     * Because values are absolute (not increments) and read at flush time, a delta with {@code seq > S} is
     * always at least as new as a snapshot taken at {@code S}: clients can apply it without replaying history.
     */
    public synchronized Depth levels(java.util.Collection<Integer> bidPrices, java.util.Collection<Integer> askPrices) {
        List<Level> b = new ArrayList<>(bidPrices.size()), a = new ArrayList<>(askPrices.size());
        for (int p : bidPrices) b.add(new Level(p, bids.getOrDefault(p, 0L)));
        for (int p : askPrices) a.add(new Level(p, asks.getOrDefault(p, 0L)));
        return new Depth(marketId, bookSeq, b, a);
    }

    public synchronized List<TapeEntry> tape(int n) {
        List<TapeEntry> out = new ArrayList<>(Math.min(n, tape.size()));
        for (TapeEntry e : tape) {
            if (out.size() == n) break;
            out.add(e);
        }
        return out;
    }

    public synchronized List<CandleSeries.Candle> candles(String tf) {
        return switch (tf) {
            case "1m" -> c1m.toList();
            case "15m" -> c15m.toList();
            default -> c5s.toList();
        };
    }

    /** Mid if two-sided, else last trade, else 50¢: the same mark rule as the engine. */
    public synchronized int mark() {
        if (!bids.isEmpty() && !asks.isEmpty()) return (bids.firstKey() + asks.firstKey()) >>> 1;
        return last != -1 ? last : Prices.PAYOUT / 2;
    }

    public synchronized Stats stats() {
        int bb = bids.isEmpty() ? -1 : bids.firstKey();
        int ba = asks.isEmpty() ? -1 : asks.firstKey();
        long nowSec = System.currentTimeMillis() / 1000;
        int open = c1m.openSince(nowSec - 86_400);
        int change = (open == -1 || last == -1) ? 0 : last - open;
        long bd = 0, ad = 0;
        for (long q : bids.values()) bd += q;
        for (long q : asks.values()) ad += q;
        int mark = (bb != -1 && ba != -1) ? (bb + ba) >>> 1 : (last != -1 ? last : Prices.PAYOUT / 2);
        return new Stats(marketId, status, winner, bb, ba, last, change, volume, notional, trades, bd, ad, mark);
    }

    public synchronized MarketStatus status() {
        return status;
    }
}
