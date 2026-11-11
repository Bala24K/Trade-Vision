package io.tradevision.engine;

public enum TimeInForce {
    /** Good-til-cancelled: match what crosses, rest the remainder. */
    GTC,
    /** Immediate-or-cancel: match what crosses, cancel the remainder. Market orders are IOC at the worst price. */
    IOC,
    /** Fill-or-kill: execute the full quantity immediately or reject without touching the book. */
    FOK,
    /** Maker-only: reject if the order would cross on arrival. */
    POST_ONLY;

    private static final TimeInForce[] VALUES = values();

    public static TimeInForce of(int ordinal) {
        return VALUES[ordinal];
    }
}
