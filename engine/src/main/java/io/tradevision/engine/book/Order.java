package io.tradevision.engine.book;

import io.tradevision.engine.Action;
import io.tradevision.engine.Outcome;
import io.tradevision.engine.Side;
import io.tradevision.engine.TimeInForce;

/**
 * A live order. Mutable and pooled: the engine recycles instances through {@link OrderPool} so steady-state
 * order flow allocates nothing.
 *
 * <p>Orders are nodes of an <b>intrusive</b> doubly-linked FIFO inside their {@link PriceLevel}: the
 * {@code prev/next} links live on the order itself. Given an order (found via the id map), cancel unlinks
 * it in O(1): no queue search, no iterator, no wrapper node.
 */
public final class Order {
    public long orderId;
    public long clientOrderId;
    public long accountId;
    public long marketId;
    public Outcome outcome;
    public Action action;
    public TimeInForce tif;
    /** Book side derived from outcome + action. */
    public Side side;
    /** Limit price in the order's own outcome terms (what the user typed). */
    public int limitPrice;
    /** Limit price translated into the unified YES book. */
    public int bookPrice;
    public long quantity;
    public long remaining;
    /** Cash still reserved for the unfilled part of a BUY: always {@code remaining * limitPrice}. */
    public long reservedCash;
    /** Sequencer timestamp of acceptance (never the wall clock, so replay is deterministic). */
    public long timestamp;

    PriceLevel level;
    Order prev;
    Order next;

    public long filled() {
        return quantity - remaining;
    }

    public boolean isResting() {
        return level != null;
    }

    public Order next() {
        return next;
    }

    void reset() {
        orderId = clientOrderId = accountId = marketId = 0;
        outcome = null;
        action = null;
        tif = null;
        side = null;
        limitPrice = bookPrice = 0;
        quantity = remaining = reservedCash = timestamp = 0;
        level = null;
        prev = next = null;
    }

    @Override
    public String toString() {
        return "Order{" + orderId + " acct=" + accountId + " " + action + " " + outcome + " " + remaining + "/" + quantity
                + " @" + limitPrice + " book=" + side + "@" + bookPrice + "}";
    }
}
