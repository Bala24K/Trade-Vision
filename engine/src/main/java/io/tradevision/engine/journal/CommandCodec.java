package io.tradevision.engine.journal;

import io.tradevision.engine.Action;
import io.tradevision.engine.Command;
import io.tradevision.engine.CommandType;
import io.tradevision.engine.Outcome;
import io.tradevision.engine.TimeInForce;

import java.nio.ByteBuffer;

/**
 * Compact binary encoding of a command body (everything after the record header). Little-endian,
 * fixed field order per type. A PLACE_ORDER body is 40 bytes; JSON would be ~200.
 *
 * <p>Compatibility rule: never reorder or reuse; append new types/fields only.
 */
public final class CommandCodec {
    private CommandCodec() {}

    public static final int MAX_BODY = 64;

    /**
     * Encodes losslessly or not at all. Fixed-width fields (price: 16 bits, quantity: 32 bits) are range-checked
     * <i>before</i> any byte is written; a value that does not fit throws instead of being cast. A cast used to turn
     * an order the engine rejected live (e.g. quantity 2^32+5) into a different, valid order on replay (audit C1).
     * {@code EngineRunner} canonicalizes order fields before publishing, so this is a guard that should never fire.
     */
    public static void encode(Command c, ByteBuffer out) {
        if (c.type == CommandType.PLACE_ORDER) {
            if (c.price < Short.MIN_VALUE || c.price > Short.MAX_VALUE) {
                throw new IllegalArgumentException("price " + c.price + " does not fit the journal's 16-bit field");
            }
            if (c.quantity < Integer.MIN_VALUE || c.quantity > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("quantity " + c.quantity + " does not fit the journal's 32-bit field");
            }
        }
        out.put(c.type.code);
        switch (c.type) {
            case PLACE_ORDER -> {
                out.putLong(c.accountId);
                out.putLong(c.clientOrderId);
                out.putLong(c.marketId);
                out.put((byte) c.outcome.ordinal());
                out.put((byte) c.action.ordinal());
                out.put((byte) c.tif.ordinal());
                out.putShort((short) c.price);  // range-checked above: lossless
                out.putInt((int) c.quantity);    // range-checked above: lossless
            }
            case CANCEL_ORDER -> {
                out.putLong(c.accountId);
                out.putLong(c.orderId);
            }
            case CREATE_ACCOUNT -> out.putLong(c.accountId);
            case DEPOSIT, WITHDRAW -> {
                out.putLong(c.accountId);
                out.putLong(c.quantity);
            }
            case CREATE_MARKET, HALT_MARKET, REOPEN_MARKET -> out.putLong(c.marketId);
            case RESOLVE_MARKET -> {
                out.putLong(c.marketId);
                out.put((byte) c.outcome.ordinal());
            }
        }
    }

    public static void decode(ByteBuffer in, Command c) {
        c.type = CommandType.fromCode(in.get());
        switch (c.type) {
            case PLACE_ORDER -> {
                c.accountId = in.getLong();
                c.clientOrderId = in.getLong();
                c.marketId = in.getLong();
                c.outcome = Outcome.of(in.get());
                c.action = Action.of(in.get());
                c.tif = TimeInForce.of(in.get());
                c.price = in.getShort();
                c.quantity = in.getInt();
            }
            case CANCEL_ORDER -> {
                c.accountId = in.getLong();
                c.orderId = in.getLong();
            }
            case CREATE_ACCOUNT -> c.accountId = in.getLong();
            case DEPOSIT, WITHDRAW -> {
                c.accountId = in.getLong();
                c.quantity = in.getLong();
            }
            case CREATE_MARKET, HALT_MARKET, REOPEN_MARKET -> c.marketId = in.getLong();
            case RESOLVE_MARKET -> {
                c.marketId = in.getLong();
                c.outcome = Outcome.of(in.get());
            }
        }
    }
}
