package io.tradevision.engine;

import io.tradevision.engine.book.Order;

import java.util.ArrayList;
import java.util.List;

/** Captures engine output as immutable records for assertions. */
final class RecordingListener implements EngineListener {
    record Trade(long tradeId, long marketId, int price, long qty, long makerOrder, long takerOrder,
                 long makerAccount, long takerAccount) {}

    record Cancel(long orderId, long remaining, CancelReason reason) {}

    record Reject(long accountId, long clientOrderId, RejectReason reason) {}

    final List<Trade> trades = new ArrayList<>();
    final List<Cancel> cancels = new ArrayList<>();
    final List<Reject> rejects = new ArrayList<>();
    final List<Long> filled = new ArrayList<>();
    final List<String> log = new ArrayList<>();

    @Override
    public void onTrade(long tradeId, Market market, int price, long quantity, Order maker, Order taker, long ts) {
        trades.add(new Trade(tradeId, market.marketId, price, quantity, maker.orderId, taker.orderId, maker.accountId, taker.accountId));
        log.add("trade " + tradeId + " " + quantity + "@" + price);
    }

    @Override
    public void onOrderCanceled(Order order, CancelReason reason, long ts) {
        cancels.add(new Cancel(order.orderId, order.remaining, reason));
        log.add("cancel " + order.orderId + " " + reason);
    }

    @Override
    public void onOrderFilled(Order order, long ts) {
        filled.add(order.orderId);
        log.add("filled " + order.orderId);
    }

    @Override
    public void onOrderRejected(long accountId, long clientOrderId, long marketId, RejectReason reason, long ts) {
        rejects.add(new Reject(accountId, clientOrderId, reason));
        log.add("reject " + clientOrderId + " " + reason);
    }

    @Override
    public void onBookLevel(long marketId, Side side, int price, long quantity, long ts) {
        log.add("book " + marketId + " " + side + "@" + price + "=" + quantity);
    }

    void clear() {
        trades.clear();
        cancels.clear();
        rejects.clear();
        filled.clear();
        log.clear();
    }
}
