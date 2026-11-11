package io.tradevision.engine;

/**
 * A mutable command <i>slot</i>. Instances are pre-allocated inside ring buffers and reused forever, so
 * submitting a command allocates nothing. Fields not relevant to {@link #type} are ignored.
 *
 * <p>{@link #sequence} and {@link #timestamp} are assigned by the sequencer (the engine thread) at the
 * moment the command is ordered; they are what make the journal a total order that replays identically.
 */
public final class Command {
    public CommandType type;
    public long accountId;
    public long marketId;
    public long clientOrderId;
    public long orderId;
    public Outcome outcome;
    public Action action;
    public TimeInForce tif;
    public int price;
    /** Order quantity, or cash amount for deposit/withdraw. */
    public long quantity;

    /** Total-order position assigned by the sequencer. */
    public long sequence;
    /** Sequencer timestamp (epoch micros). Engine logic only ever sees this clock. */
    public long timestamp;
    /** {@code System.nanoTime()} when the gateway received the request; measurement only, never journaled. */
    public long ingressNanos;
    /** Opaque correlation id for routing the response back to a session; never journaled. */
    public long sessionId;

    public Command placeOrder(long accountId, long clientOrderId, long marketId, Outcome outcome, Action action,
                              TimeInForce tif, int price, long quantity) {
        this.type = CommandType.PLACE_ORDER;
        this.accountId = accountId;
        this.clientOrderId = clientOrderId;
        this.marketId = marketId;
        this.outcome = outcome;
        this.action = action;
        this.tif = tif;
        this.price = price;
        this.quantity = quantity;
        return this;
    }

    public Command cancelOrder(long accountId, long orderId) {
        this.type = CommandType.CANCEL_ORDER;
        this.accountId = accountId;
        this.orderId = orderId;
        return this;
    }

    public Command admin(CommandType type, long subjectId, long amount) {
        this.type = type;
        this.accountId = subjectId;
        this.marketId = subjectId;
        this.quantity = amount;
        return this;
    }

    public Command resolve(long marketId, Outcome winner) {
        this.type = CommandType.RESOLVE_MARKET;
        this.marketId = marketId;
        this.outcome = winner;
        return this;
    }

    public void copyFrom(Command c) {
        type = c.type;
        accountId = c.accountId;
        marketId = c.marketId;
        clientOrderId = c.clientOrderId;
        orderId = c.orderId;
        outcome = c.outcome;
        action = c.action;
        tif = c.tif;
        price = c.price;
        quantity = c.quantity;
        sequence = c.sequence;
        timestamp = c.timestamp;
        ingressNanos = c.ingressNanos;
        sessionId = c.sessionId;
    }

    /** Execute against the engine. The single dispatch point shared by live processing and journal replay. */
    public void applyTo(MatchingEngine e) {
        long ts = timestamp;
        switch (type) {
            case CREATE_MARKET -> e.createMarket(marketId, ts);
            case CREATE_ACCOUNT -> e.createAccount(accountId, ts);
            case DEPOSIT -> e.deposit(accountId, quantity, ts);
            case WITHDRAW -> e.withdraw(accountId, quantity, ts);
            case PLACE_ORDER -> e.placeOrder(accountId, clientOrderId, marketId, outcome, action, tif, price, quantity, ts);
            case CANCEL_ORDER -> e.cancelOrder(accountId, orderId, ts);
            case HALT_MARKET -> e.haltMarket(marketId, ts);
            case REOPEN_MARKET -> e.reopenMarket(marketId, ts);
            case RESOLVE_MARKET -> e.resolveMarket(marketId, outcome, ts);
        }
    }

    @Override
    public String toString() {
        return "Command{#" + sequence + " " + type + " acct=" + accountId + " mkt=" + marketId + " " + action + " "
                + outcome + " " + tif + " " + quantity + "@" + price + " oid=" + orderId + "}";
    }
}
