package io.tradevision.gateway.read;

import io.tradevision.engine.Action;
import io.tradevision.engine.Outcome;
import io.tradevision.engine.Prices;
import io.tradevision.engine.TimeInForce;
import io.tradevision.gateway.engine.Event;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Query-side view of one account. Single writer (publisher thread); readers get immutable copies. */
public final class AccountState {
    public enum OrderStatus { OPEN, PARTIALLY_FILLED, FILLED, CANCELED }

    public record OrderView(long orderId, long clientOrderId, long marketId, Outcome outcome, Action action, TimeInForce tif,
                            int limitPrice, long quantity, long filled, long filledValue, OrderStatus status, String reason,
                            long createdTs, long updatedTs) {
        /** Average fill price in the order's own outcome ticks, x1000 for sub-tick precision; 0 if unfilled. */
        public long avgPriceMilli() {
            return filled == 0 ? 0 : filledValue * 1000 / filled;
        }

        OrderView fill(long qty, long value, long ts) {
            long f = filled + qty;
            return new OrderView(orderId, clientOrderId, marketId, outcome, action, tif, limitPrice, quantity, f,
                    filledValue + value, f >= quantity ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED, reason, createdTs, ts);
        }

        OrderView close(boolean filledFully, String why, long ts) {
            return new OrderView(orderId, clientOrderId, marketId, outcome, action, tif, limitPrice, quantity, filled,
                    filledValue, filledFully ? OrderStatus.FILLED : OrderStatus.CANCELED, why, createdTs, ts);
        }
    }

    public record Fill(long tradeId, long orderId, long marketId, Outcome outcome, Action action, int price, long quantity,
                       boolean maker, long ts) {}

    public record PositionView(long marketId, long yes, long no, long yesLocked, long noLocked, long yesCost, long noCost,
                               long realizedPnl) {}

    public record SettlementView(long marketId, long payout, long ts) {}

    public record Snapshot(long accountId, long available, long reserved, List<PositionView> positions,
                           List<OrderView> openOrders) {}

    public final long accountId;
    private long available, reserved;
    private final TreeMap<Long, PositionView> positions = new TreeMap<>();
    private final LinkedHashMap<Long, OrderView> open = new LinkedHashMap<>();
    private final ArrayDeque<OrderView> history = new ArrayDeque<>();
    private final ArrayDeque<Fill> fills = new ArrayDeque<>();
    private final ArrayDeque<SettlementView> settlements = new ArrayDeque<>();

    AccountState(long accountId) {
        this.accountId = accountId;
    }

    synchronized void balance(long available, long reserved) {
        this.available = available;
        this.reserved = reserved;
    }

    synchronized void position(PositionView p) {
        positions.put(p.marketId(), p);
    }

    synchronized void accepted(Event.OrderAccepted e) {
        open.put(e.orderId(), new OrderView(e.orderId(), e.clientOrderId(), e.marketId(), e.outcome(), e.action(), e.tif(),
                e.limitPrice(), e.quantity(), 0, 0, OrderStatus.OPEN, null, e.ts(), e.ts()));
    }

    synchronized void seedOrder(OrderView o) {
        open.put(o.orderId(), o);
    }

    synchronized void fill(long orderId, long tradeId, long marketId, Outcome outcome, Action action, int bookPrice, long qty,
                           boolean maker, long ts) {
        int px = outcome == Outcome.YES ? bookPrice : Prices.complement(bookPrice);
        OrderView o = open.get(orderId);
        if (o != null) open.put(orderId, o.fill(qty, (long) px * qty, ts));
        fills.addFirst(new Fill(tradeId, orderId, marketId, outcome, action, px, qty, maker, ts));
        if (fills.size() > 300) fills.pollLast();
    }

    synchronized void closed(Event.OrderClosed e) {
        OrderView o = open.remove(e.orderId());
        if (o == null) return;
        history.addFirst(o.close(e.filled(), e.reason() == null ? null : e.reason().name(), e.ts()));
        if (history.size() > 300) history.pollLast();
    }

    synchronized void settled(Event.Settlement s) {
        settlements.addFirst(new SettlementView(s.marketId(), s.payout(), s.ts()));
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(accountId, available, reserved, new ArrayList<>(positions.values()), new ArrayList<>(open.values()));
    }

    public synchronized List<OrderView> openOrders() {
        return new ArrayList<>(open.values());
    }

    public synchronized List<OrderView> openOrders(long marketId) {
        return open.values().stream().filter(o -> o.marketId() == marketId).toList();
    }

    public synchronized List<OrderView> history(int n) {
        return history.stream().limit(n).toList();
    }

    public synchronized List<Fill> fills(int n) {
        return fills.stream().limit(n).toList();
    }

    public synchronized List<SettlementView> settlements() {
        return new ArrayList<>(settlements);
    }

    /** Engine order id of an open order by the client's own id, or -1. */
    public synchronized long openOrderIdByClientId(long clientOrderId) {
        for (OrderView o : open.values()) if (o.clientOrderId() == clientOrderId) return o.orderId();
        return -1;
    }

    public synchronized long available() {
        return available;
    }

    static Comparator<OrderView> newestFirst() {
        return Comparator.comparingLong(OrderView::createdTs).reversed();
    }

    Map<Long, OrderView> openView() {
        return open;
    }
}
