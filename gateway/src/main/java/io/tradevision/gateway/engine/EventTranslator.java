package io.tradevision.gateway.engine;

import io.tradevision.engine.CancelReason;
import io.tradevision.engine.Command;
import io.tradevision.engine.CommandType;
import io.tradevision.engine.Market;
import io.tradevision.engine.RejectReason;
import io.tradevision.engine.Side;
import io.tradevision.engine.account.Account;
import io.tradevision.engine.account.Position;
import io.tradevision.engine.book.Order;
import io.tradevision.engine.runtime.SequencedListener;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs on the matcher thread. Copies each engine callback into an immutable {@link Event} and groups them per
 * command, so the publisher can (a) wait for that command's sequence to become durable and (b) hand the
 * caller exactly the events its request caused.
 *
 * <p>This is the one place the gateway allocates on the matcher thread (a few small records per command).
 * It keeps the engine core allocation-free; replacing it with flyweights in an outbound ring is a known
 * next step if profiling ever shows it matters.
 */
public final class EventTranslator implements SequencedListener {
    @FunctionalInterface
    public interface Sink {
        void onCommand(long sequence, long correlationId, List<Event> events);
    }

    private final Sink sink;
    private ArrayList<Event> current = new ArrayList<>();
    private long sequence;
    private long correlation;

    public EventTranslator(Sink sink) {
        this.sink = sink;
    }

    @Override
    public void onCommandStart(Command c) {
        current = new ArrayList<>(8);
        sequence = c.sequence;
        correlation = c.sessionId;
    }

    @Override
    public void onCommandEnd(Command c) {
        sink.onCommand(sequence, correlation, current);
    }

    public static Event.Trade trade(long tradeId, Market m, int price, long qty, Order maker, Order taker, long ts) {
        return new Event.Trade(tradeId, m.marketId, price, qty, taker.side, maker.orderId, maker.accountId, maker.outcome,
                maker.action, taker.orderId, taker.accountId, taker.outcome, taker.action, ts);
    }

    @Override public void onMarketCreated(long marketId, long ts) { current.add(new Event.MarketCreated(marketId, ts)); }

    @Override public void onMarketStatus(Market m, long ts) {
        current.add(new Event.MarketStatusChanged(m.marketId, m.status(), m.winner(), ts));
    }

    @Override public void onAccountCreated(long accountId, long ts) { current.add(new Event.AccountCreated(accountId, ts)); }

    @Override public void onBalance(Account a, long ts) {
        current.add(new Event.Balance(a.accountId, a.available(), a.reserved(), ts));
    }

    @Override public void onPosition(Position p, long ts) {
        current.add(new Event.PositionUpdate(p.accountId, p.marketId, p.yes(), p.no(), p.yesLocked(), p.noLocked(),
                p.yesCost(), p.noCost(), p.realizedPnl(), ts));
    }

    @Override public void onOrderAccepted(Order o, long ts) {
        current.add(new Event.OrderAccepted(o.orderId, o.clientOrderId, o.accountId, o.marketId, o.outcome, o.action, o.tif,
                o.limitPrice, o.quantity, ts));
    }

    @Override public void onOrderRejected(long accountId, long clientOrderId, long marketId, RejectReason reason, long ts) {
        current.add(new Event.OrderRejected(accountId, clientOrderId, marketId, reason, ts));
    }

    @Override public void onCancelRejected(long accountId, long orderId, RejectReason reason, long ts) {
        current.add(new Event.CancelRejected(accountId, orderId, reason, ts));
    }

    @Override public void onCommandRejected(CommandType command, long subjectId, RejectReason reason, long ts) {
        current.add(new Event.CommandRejected(command, subjectId, reason, ts));
    }

    @Override public void onTrade(long tradeId, Market m, int price, long qty, Order maker, Order taker, long ts) {
        current.add(trade(tradeId, m, price, qty, maker, taker, ts));
    }

    @Override public void onOrderFilled(Order o, long ts) {
        current.add(new Event.OrderClosed(o.orderId, o.accountId, o.marketId, 0, true, null, ts));
    }

    @Override public void onOrderCanceled(Order o, CancelReason reason, long ts) {
        current.add(new Event.OrderClosed(o.orderId, o.accountId, o.marketId, o.remaining, false, reason, ts));
    }

    @Override public void onBookLevel(long marketId, Side side, int price, long quantity, long ts) {
        current.add(new Event.BookLevel(marketId, side, price, quantity, ts));
    }

    @Override public void onSettlement(Position p, long payout, long ts) {
        current.add(new Event.Settlement(p.accountId, p.marketId, payout, ts));
    }
}
