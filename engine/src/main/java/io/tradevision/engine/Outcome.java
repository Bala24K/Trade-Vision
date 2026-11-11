package io.tradevision.engine;

public enum Outcome {
    YES, NO;

    private static final Outcome[] VALUES = values();

    public static Outcome of(int ordinal) {
        return VALUES[ordinal];
    }
}
