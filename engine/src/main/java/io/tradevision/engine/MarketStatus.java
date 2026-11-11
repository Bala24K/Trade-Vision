package io.tradevision.engine;

public enum MarketStatus {
    /** Accepting and matching orders. */
    OPEN,
    /** Orders may be cancelled but not placed (e.g. news halt, pre-resolution). */
    HALTED,
    /** Final. All orders were cancelled and positions paid out. */
    RESOLVED
}
