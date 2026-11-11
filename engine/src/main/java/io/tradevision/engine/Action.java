package io.tradevision.engine;

public enum Action {
    BUY, SELL;

    private static final Action[] VALUES = values();

    public static Action of(int ordinal) {
        return VALUES[ordinal];
    }
}
