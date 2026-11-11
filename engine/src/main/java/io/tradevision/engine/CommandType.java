package io.tradevision.engine;

/** Every state change enters the engine as one of these. The byte code is the journal wire format: append only. */
public enum CommandType {
    CREATE_MARKET(1),
    CREATE_ACCOUNT(2),
    DEPOSIT(3),
    WITHDRAW(4),
    PLACE_ORDER(5),
    CANCEL_ORDER(6),
    HALT_MARKET(7),
    REOPEN_MARKET(8),
    RESOLVE_MARKET(9);

    public final byte code;

    CommandType(int code) {
        this.code = (byte) code;
    }

    private static final CommandType[] BY_CODE = new CommandType[16];

    static {
        for (CommandType t : values()) BY_CODE[t.code] = t;
    }

    public static CommandType fromCode(byte code) {
        CommandType t = code >= 0 && code < BY_CODE.length ? BY_CODE[code] : null;
        if (t == null) throw new IllegalArgumentException("unknown command code " + code);
        return t;
    }
}
