package io.tradevision.engine;

import io.tradevision.engine.journal.CommandCodec;
import io.tradevision.engine.journal.Journal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The journal must never record a command different from the one the engine executed (audit finding C1). */
class CommandCodecTest {
    @TempDir
    Path tmp;

    static Command roundTrip(Command c) {
        ByteBuffer buf = ByteBuffer.allocate(CommandCodec.MAX_BODY).order(ByteOrder.LITTLE_ENDIAN);
        CommandCodec.encode(c, buf);
        buf.flip();
        Command out = new Command();
        CommandCodec.decode(buf, out);
        return out;
    }

    static void assertSameFields(Command a, Command b) {
        assertEquals(a.type, b.type);
        switch (a.type) {
            case PLACE_ORDER -> {
                assertEquals(a.accountId, b.accountId);
                assertEquals(a.clientOrderId, b.clientOrderId);
                assertEquals(a.marketId, b.marketId);
                assertEquals(a.outcome, b.outcome);
                assertEquals(a.action, b.action);
                assertEquals(a.tif, b.tif);
                assertEquals(a.price, b.price, "price");
                assertEquals(a.quantity, b.quantity, "quantity");
            }
            case CANCEL_ORDER -> {
                assertEquals(a.accountId, b.accountId);
                assertEquals(a.orderId, b.orderId);
            }
            case DEPOSIT, WITHDRAW -> {
                assertEquals(a.accountId, b.accountId);
                assertEquals(a.quantity, b.quantity);
            }
            case CREATE_ACCOUNT -> assertEquals(a.accountId, b.accountId);
            case CREATE_MARKET, HALT_MARKET, REOPEN_MARKET -> assertEquals(a.marketId, b.marketId);
            case RESOLVE_MARKET -> {
                assertEquals(a.marketId, b.marketId);
                assertEquals(a.outcome, b.outcome);
            }
        }
    }

    @Test
    void roundTripIsExactAtEveryBoundary() {
        List<Command> cases = new ArrayList<>();
        for (int price : new int[] {Prices.MIN_PRICE, 2, 500, Prices.MAX_PRICE - 1, Prices.MAX_PRICE}) {
            for (long qty : new long[] {1, 2, Integer.MAX_VALUE / 2, Prices.MAX_QUANTITY - 1, Prices.MAX_QUANTITY}) {
                for (Outcome o : Outcome.values()) {
                    for (Action a : Action.values()) {
                        for (TimeInForce t : TimeInForce.values()) {
                            cases.add(new Command().placeOrder(Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE, o, a, t, price, qty));
                        }
                    }
                }
            }
        }
        cases.add(new Command().cancelOrder(Long.MAX_VALUE, Long.MAX_VALUE));
        cases.add(new Command().admin(CommandType.CREATE_ACCOUNT, Long.MAX_VALUE, 0));
        cases.add(new Command().admin(CommandType.DEPOSIT, 1, Long.MAX_VALUE));
        cases.add(new Command().admin(CommandType.WITHDRAW, 1, 1));
        cases.add(new Command().admin(CommandType.CREATE_MARKET, Long.MAX_VALUE, 0));
        cases.add(new Command().admin(CommandType.HALT_MARKET, 7, 0));
        cases.add(new Command().admin(CommandType.REOPEN_MARKET, 7, 0));
        cases.add(new Command().resolve(7, Outcome.NO));
        for (Command c : cases) assertSameFields(c, roundTrip(c));
    }

    @Test
    void invalidButRepresentableValuesRoundTripExactly() {
        // The engine must see on replay exactly the invalid value it rejected live, so these must not be altered.
        for (int price : new int[] {0, -1, Prices.PAYOUT, Short.MAX_VALUE, Short.MIN_VALUE}) {
            Command c = new Command().placeOrder(1, 1, 1, Outcome.YES, Action.BUY, TimeInForce.GTC, price, 10);
            assertSameFields(c, roundTrip(c));
        }
        for (long qty : new long[] {0, -1, Prices.MAX_QUANTITY + 1, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            Command c = new Command().placeOrder(1, 1, 1, Outcome.YES, Action.BUY, TimeInForce.GTC, 500, qty);
            assertSameFields(c, roundTrip(c));
        }
    }

    @Test
    void encodeRefusesValuesItCannotRepresentInsteadOfTruncating() {
        long[] badQty = {(1L << 32) + 5, -(1L << 32) + 5, Integer.MAX_VALUE + 1L, Integer.MIN_VALUE - 1L, Long.MAX_VALUE, Long.MIN_VALUE};
        for (long q : badQty) {
            Command c = new Command().placeOrder(1, 1, 1, Outcome.YES, Action.BUY, TimeInForce.GTC, 500, q);
            ByteBuffer buf = ByteBuffer.allocate(CommandCodec.MAX_BODY);
            assertThrows(IllegalArgumentException.class, () -> CommandCodec.encode(c, buf), "quantity " + q);
        }
        int[] badPrice = {65_536 + 500, Short.MAX_VALUE + 1, Short.MIN_VALUE - 1, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int p : badPrice) {
            Command c = new Command().placeOrder(1, 1, 1, Outcome.YES, Action.BUY, TimeInForce.GTC, p, 10);
            ByteBuffer buf = ByteBuffer.allocate(CommandCodec.MAX_BODY);
            assertThrows(IllegalArgumentException.class, () -> CommandCodec.encode(c, buf), "price " + p);
        }
    }

    @Test
    void journalNeverRecordsATruncatedCommand() {
        // The audit's reproduction, at the journal level: appending an unrepresentable order must fail loudly
        // rather than write a different, valid-looking order.
        var j = new Journal(tmp.resolve("j"), 1 << 20, Journal.SyncPolicy.OS_CACHE);
        Command c = new Command().placeOrder(7, 1, 1, Outcome.YES, Action.BUY, TimeInForce.GTC, 500, (1L << 32) + 5);
        c.sequence = 1;
        assertThrows(IllegalArgumentException.class, () -> j.append(c));
        assertEquals(0, j.lastSequence(), "nothing was journaled");
        j.close();
        assertEquals(0, Journal.replay(tmp.resolve("j"), 0, x -> {}));
    }
}
