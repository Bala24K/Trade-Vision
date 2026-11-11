package io.tradevision.engine.book;

import io.tradevision.engine.Prices;
import io.tradevision.engine.Side;

/**
 * Price-time priority order book for one binary market, quoted in YES ticks.
 *
 * <h2>Why arrays instead of a TreeMap</h2>
 * Equity books need a sorted map because prices are unbounded. A binary contract's price is a
 * probability, so there are only 999 valid ticks. We exploit that: price levels live in a dense array
 * indexed by price (O(1) lookup, no rebalancing, no per-level allocation after warm-up), and a
 * {@link PriceBitSet} per side answers "what is the next best price?" with hardware bit-scan
 * instructions. Best bid/ask are cached and only recomputed when the best level empties.
 *
 * <p>The book is a passive data structure: it knows nothing about accounts, money or matching rules.
 * {@code MatchingEngine} drives it. Not thread-safe by design (see ADR-0002, single writer).
 */
public final class OrderBook {
    private final long marketId;
    private final PriceLevel[] bids = new PriceLevel[Prices.PAYOUT];
    private final PriceLevel[] asks = new PriceLevel[Prices.PAYOUT];
    private final PriceBitSet bidPrices = new PriceBitSet();
    private final PriceBitSet askPrices = new PriceBitSet();
    private int bestBid = -1;
    private int bestAsk = -1;
    private int restingOrders;

    public OrderBook(long marketId) {
        this.marketId = marketId;
    }

    public long marketId() {
        return marketId;
    }

    /** Best bid price, or -1 if there are no bids. */
    public int bestBid() {
        return bestBid;
    }

    /** Best ask price, or -1 if there are no asks. */
    public int bestAsk() {
        return bestAsk;
    }

    public int best(Side side) {
        return side == Side.BID ? bestBid : bestAsk;
    }

    public int restingOrders() {
        return restingOrders;
    }

    /** Level at {@code price}, or null if nothing rests there. */
    public PriceLevel level(Side side, int price) {
        PriceLevel l = (side == Side.BID ? bids : asks)[price];
        return l == null || l.isEmpty() ? null : l;
    }

    public long quantityAt(Side side, int price) {
        PriceLevel l = level(side, price);
        return l == null ? 0 : l.totalQuantity;
    }

    /** Would an order on {@code side} at {@code bookPrice} trade immediately? */
    public boolean crosses(Side side, int bookPrice) {
        return side == Side.BID ? bestAsk != -1 && bestAsk <= bookPrice : bestBid != -1 && bestBid >= bookPrice;
    }

    /** Next price strictly worse than {@code price} on {@code side} that has orders, or -1. */
    public int nextLevel(Side side, int price) {
        return side == Side.BID ? bidPrices.highestAtOrBelow(price - 1) : askPrices.lowestAtOrAbove(price + 1);
    }

    public void add(Order o) {
        PriceLevel[] levels = o.side == Side.BID ? bids : asks;
        PriceLevel level = levels[o.bookPrice];
        if (level == null) levels[o.bookPrice] = level = new PriceLevel(o.bookPrice);
        boolean wasEmpty = level.isEmpty();
        level.append(o);
        restingOrders++;
        if (wasEmpty) {
            if (o.side == Side.BID) {
                bidPrices.set(o.bookPrice);
                if (o.bookPrice > bestBid) bestBid = o.bookPrice;
            } else {
                askPrices.set(o.bookPrice);
                if (bestAsk == -1 || o.bookPrice < bestAsk) bestAsk = o.bookPrice;
            }
        }
    }

    public void remove(Order o) {
        PriceLevel level = o.level;
        level.unlink(o);
        restingOrders--;
        if (level.isEmpty()) onLevelEmptied(o.side, level.price);
    }

    /** A resting order traded {@code qty}: shrink it in place, keeping its time priority. */
    public void reduce(Order o, long qty) {
        o.remaining -= qty;
        o.level.totalQuantity -= qty;
    }

    private void onLevelEmptied(Side side, int price) {
        if (side == Side.BID) {
            bidPrices.clear(price);
            if (price == bestBid) bestBid = bidPrices.highestAtOrBelow(price - 1);
        } else {
            askPrices.clear(price);
            if (price == bestAsk) bestAsk = askPrices.lowestAtOrAbove(price + 1);
        }
    }

    /**
     * Copy the top {@code maxLevels} of {@code side} into caller-owned arrays (no allocation).
     * @return number of levels written
     */
    public int depth(Side side, int maxLevels, int[] prices, long[] quantities, int[] orderCounts) {
        PriceLevel[] levels = side == Side.BID ? bids : asks;
        int n = 0;
        for (int p = best(side); p != -1 && n < maxLevels; p = nextLevel(side, p)) {
            PriceLevel l = levels[p];
            prices[n] = p;
            quantities[n] = l.totalQuantity;
            if (orderCounts != null) orderCounts[n] = l.orderCount;
            n++;
        }
        return n;
    }
}
