package io.tradevision.gateway.engine;

import io.tradevision.engine.Action;
import io.tradevision.engine.CancelReason;
import io.tradevision.engine.CommandType;
import io.tradevision.engine.MarketStatus;
import io.tradevision.engine.Outcome;
import io.tradevision.engine.RejectReason;
import io.tradevision.engine.Side;
import io.tradevision.engine.TimeInForce;

/**
 * Immutable copies of engine callbacks. The engine hands out live, recycled objects on its own thread;
 * the gateway snapshots them into these records before they cross to the publisher thread.
 * Timestamps are epoch microseconds from the sequencer.
 */
public sealed interface Event {
    long ts();

    record MarketCreated(long marketId, long ts) implements Event {}

    record MarketStatusChanged(long marketId, MarketStatus status, Outcome winner, long ts) implements Event {}

    record AccountCreated(long accountId, long ts) implements Event {}

    record OrderAccepted(long orderId, long clientOrderId, long accountId, long marketId, Outcome outcome, Action action,
                         TimeInForce tif, int limitPrice, long quantity, long ts) implements Event {}

    record OrderRejected(long accountId, long clientOrderId, long marketId, RejectReason reason, long ts) implements Event {}

    record CancelRejected(long accountId, long orderId, RejectReason reason, long ts) implements Event {}

    record CommandRejected(CommandType command, long subjectId, RejectReason reason, long ts) implements Event {}

    /** {@code price} is in YES ticks; {@code aggressor} is the taker's book side (BID = buyer of YES lifted the offer). */
    record Trade(long tradeId, long marketId, int price, long quantity, Side aggressor,
                 long makerOrderId, long makerAccountId, Outcome makerOutcome, Action makerAction,
                 long takerOrderId, long takerAccountId, Outcome takerOutcome, Action takerAction, long ts) implements Event {}

    record OrderClosed(long orderId, long accountId, long marketId, long remaining, boolean filled, CancelReason reason,
                       long ts) implements Event {}

    record BookLevel(long marketId, Side side, int price, long quantity, long ts) implements Event {}

    record Balance(long accountId, long available, long reserved, long ts) implements Event {}

    record PositionUpdate(long accountId, long marketId, long yes, long no, long yesLocked, long noLocked, long yesCost,
                          long noCost, long realizedPnl, long ts) implements Event {}

    record Settlement(long accountId, long marketId, long payout, long ts) implements Event {}
}
