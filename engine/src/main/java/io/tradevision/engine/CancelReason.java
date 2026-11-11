package io.tradevision.engine;

public enum CancelReason {
    USER_REQUESTED,
    /** Unfilled remainder of an IOC / market order. */
    IOC_REMAINDER,
    /** A resting order was removed because the same account's incoming order would have traded with it. */
    SELF_TRADE_PREVENTION,
    MARKET_RESOLVED
}
