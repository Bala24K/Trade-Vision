package io.tradevision.engine;

import io.tradevision.engine.account.Account;
import io.tradevision.engine.account.Position;
import io.tradevision.engine.book.Order;

/**
 * Output port of the engine. Called synchronously on the engine thread, in a deterministic order.
 *
 * <p><b>Contract:</b> objects passed in ({@link Order}, {@link Account}, {@link Position}) are live,
 * mutable engine state, and orders are recycled right after their terminal event. Implementations must
 * copy whatever they need before returning and must never block. The production implementation copies
 * into pre-allocated ring-buffer slots and hands off to other threads.
 */
public interface EngineListener {
    EngineListener NO_OP = new EngineListener() {};

    default void onMarketCreated(long marketId, long timestamp) {}

    default void onMarketStatus(Market market, long timestamp) {}

    default void onAccountCreated(long accountId, long timestamp) {}

    default void onBalance(Account account, long timestamp) {}

    default void onPosition(Position position, long timestamp) {}

    default void onOrderAccepted(Order order, long timestamp) {}

    default void onOrderRejected(long accountId, long clientOrderId, long marketId, RejectReason reason, long timestamp) {}

    default void onCancelRejected(long accountId, long orderId, RejectReason reason, long timestamp) {}

    /** A command other than place/cancel (deposit, withdraw, create, resolve...) was refused. */
    default void onCommandRejected(CommandType command, long subjectId, RejectReason reason, long timestamp) {}

    /**
     * A trade at {@code price} (YES ticks) for {@code quantity}. {@code maker} rested in the book; {@code taker}
     * is the incoming order. Called before either side's accounting is applied, so both orders still
     * report the remaining quantity as of after this fill.
     */
    default void onTrade(long tradeId, Market market, int price, long quantity, Order maker, Order taker, long timestamp) {}

    /** Order fully filled. Last event for this order; it is recycled after this call. */
    default void onOrderFilled(Order order, long timestamp) {}

    /** Order cancelled with {@code order.remaining} unfilled. Last event for this order. */
    default void onOrderCanceled(Order order, CancelReason reason, long timestamp) {}

    /** Aggregate quantity at one price level changed to {@code quantity} (0 = level removed). */
    default void onBookLevel(long marketId, Side side, int price, long quantity, long timestamp) {}

    /** Market resolved: {@code account} received {@code payout} for its position. */
    default void onSettlement(Position position, long payout, long timestamp) {}
}
