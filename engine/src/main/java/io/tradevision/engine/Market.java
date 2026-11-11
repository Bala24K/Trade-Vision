package io.tradevision.engine;

import io.tradevision.engine.book.OrderBook;

/** A binary market: its book plus the running statistics the engine maintains for free while matching. */
public final class Market {
    public final long marketId;
    final OrderBook book;
    MarketStatus status = MarketStatus.OPEN;
    Outcome winner;
    int lastTradePrice = -1;
    long volume;
    long notional;
    long tradeCount;
    /** Outstanding YES contracts (always equal to outstanding NO contracts: they are minted in pairs). */
    long openInterest;

    Market(long marketId) {
        this.marketId = marketId;
        this.book = new OrderBook(marketId);
    }

    public OrderBook book() { return book; }
    public MarketStatus status() { return status; }
    public Outcome winner() { return winner; }
    /** Last trade price in YES ticks, or -1 if the market has never traded. */
    public int lastTradePrice() { return lastTradePrice; }
    /** Contracts traded. */
    public long volume() { return volume; }
    /** Cash value traded, in mills (YES-side price x quantity). */
    public long notional() { return notional; }
    public long tradeCount() { return tradeCount; }
    public long openInterest() { return openInterest; }

    /**
     * Mark price used for unrealized PnL: the mid if both sides are quoted, else the last trade, else 500.
     * A one-sided book is not a price, so we fall back rather than marking to a stale quote.
     */
    public int markPrice() {
        int bid = book.bestBid(), ask = book.bestAsk();
        if (bid != -1 && ask != -1) return (bid + ask) >>> 1;
        if (lastTradePrice != -1) return lastTradePrice;
        return Prices.PAYOUT / 2;
    }
}
