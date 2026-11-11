package io.tradevision.engine;

import io.tradevision.engine.book.Order;
import io.tradevision.engine.journal.Journal;
import io.tradevision.engine.runtime.EngineRunner;
import io.tradevision.engine.runtime.SequencedListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Commands go through the real runner (ring → journaler ‖ matcher), including invalid ones the engine must reject.
 * After a restart, replaying the journal must produce exactly the same state and the same accept/reject decisions.
 * Regression test for audit finding C1, whose reproduction is {@link #auditReproduction}.
 */
class ReplayEqualsLiveTest {
    @TempDir
    Path tmp;

    /** Records every order decision so live and replay can be compared one-for-one. */
    static final class Decisions implements SequencedListener {
        final List<String> log = Collections.synchronizedList(new ArrayList<>());

        @Override public void onOrderAccepted(Order o, long ts) {
            log.add("ACCEPT coid=" + o.clientOrderId + " px=" + o.limitPrice + " qty=" + o.quantity);
        }

        @Override public void onOrderRejected(long accountId, long clientOrderId, long marketId, RejectReason reason, long ts) {
            log.add("REJECT coid=" + clientOrderId + " " + reason);
        }
    }

    interface Script {
        void run(EngineRunner r);
    }

    private void assertReplayEqualsLive(Script script) throws Exception {
        Path j = tmp.resolve("journal"), s = tmp.resolve("snap");
        var live = new Decisions();
        var engine = new MatchingEngine(live);
        var runner = new EngineRunner(engine, new Journal(j, 1 << 20, Journal.SyncPolicy.OS_CACHE), live,
                System::currentTimeMillis, j, s, 0, 1024, 64).start();
        runner.submit(c -> c.admin(CommandType.CREATE_MARKET, 1, 0));
        runner.submit(c -> c.admin(CommandType.CREATE_ACCOUNT, 7, 0));
        runner.submit(c -> c.admin(CommandType.DEPOSIT, 7, 10_000_000)); // $10,000
        script.run(runner);
        runner.stop();

        var replay = new Decisions();
        var rec = EngineSnapshot.recover(s, j, replay, EngineListener.NO_OP);
        assertEquals(live.log, replay.log, "replay made the same accept/reject decisions as live");
        assertEquals(engine.openOrders().size(), rec.engine().openOrders().size(), "open orders");
        assertEquals(engine.account(7).reserved(), rec.engine().account(7).reserved(), "reserved cash");
        assertEquals(EngineDigest.of(engine), EngineDigest.of(rec.engine()), "state digest");
    }

    @Test
    void auditReproduction() throws Exception {
        // Exactly the case reproduced in the audit: live rejects both, replay used to accept both.
        assertReplayEqualsLive(r -> {
            r.submit(c -> c.placeOrder(7, 1, 1, Outcome.YES, Action.BUY, TimeInForce.GTC, 500, (1L << 32) + 5));
            r.submit(c -> c.placeOrder(7, 2, 1, Outcome.YES, Action.BUY, TimeInForce.GTC, 65_536 + 500, 3));
        });
    }

    @Test
    void everyOutOfRangeInputIsRejectedIdenticallyOnReplay() throws Exception {
        long[] badQty = {0, -1, Prices.MAX_QUANTITY + 1, (1L << 32) + 1, -(1L << 32) + 5, Integer.MAX_VALUE + 1L,
                Long.MAX_VALUE, Long.MIN_VALUE};
        int[] badPrice = {0, -1, Prices.PAYOUT, 65_536 + 1, 65_536 + 999, Short.MAX_VALUE + 1, Integer.MAX_VALUE,
                Integer.MIN_VALUE};
        assertReplayEqualsLive(r -> {
            long coid = 100;
            for (long q : badQty) {
                long id = coid++;
                r.submit(c -> c.placeOrder(7, id, 1, Outcome.YES, Action.BUY, TimeInForce.GTC, 500, q));
                r.submit(c -> c.placeOrder(7, id + 1000, 1, Outcome.NO, Action.SELL, TimeInForce.IOC, 500, q));
            }
            for (int p : badPrice) {
                long id = coid++;
                r.submit(c -> c.placeOrder(7, id, 1, Outcome.NO, Action.BUY, TimeInForce.GTC, p, 3));
            }
            // Both fields invalid at once.
            r.submit(c -> c.placeOrder(7, 999, 1, Outcome.YES, Action.BUY, TimeInForce.FOK, 70_000, (1L << 33) + 2));
        });
    }

    @Test
    void boundaryValuesAreAcceptedAndReplayIdentically() throws Exception {
        assertReplayEqualsLive(r -> {
            r.submit(c -> c.placeOrder(7, 1, 1, Outcome.YES, Action.BUY, TimeInForce.GTC, Prices.MIN_PRICE, 1));
            r.submit(c -> c.placeOrder(7, 2, 1, Outcome.YES, Action.BUY, TimeInForce.GTC, Prices.MAX_PRICE, 1));
            r.submit(c -> c.placeOrder(7, 3, 1, Outcome.NO, Action.BUY, TimeInForce.GTC, Prices.MIN_PRICE, 1));
            // Largest legal quantity: valid input, rejected only for funds (needs 1e9 mills x price).
            r.submit(c -> c.placeOrder(7, 4, 1, Outcome.YES, Action.BUY, TimeInForce.GTC, 1, Prices.MAX_QUANTITY));
        });
    }

    @Test
    void mixedValidAndInvalidFlowReplaysIdentically() throws Exception {
        assertReplayEqualsLive(r -> {
            r.submit(c -> c.admin(CommandType.CREATE_ACCOUNT, 8, 0));
            r.submit(c -> c.admin(CommandType.DEPOSIT, 8, 10_000_000));
            for (int i = 0; i < 200; i++) {
                final int n = i;
                long acct = 7 + (n & 1);
                int px = 400 + (n % 50);
                if (n % 7 == 0) {
                    r.submit(c -> c.placeOrder(acct, n, 1, Outcome.YES, Action.BUY, TimeInForce.GTC, px + 65_536, 2)); // invalid
                } else if (n % 11 == 0) {
                    r.submit(c -> c.placeOrder(acct, n, 1, Outcome.NO, Action.BUY, TimeInForce.GTC, 1000 - px, (1L << 32) + n)); // invalid
                } else {
                    r.submit(c -> c.placeOrder(acct, n, 1, n % 2 == 0 ? Outcome.YES : Outcome.NO, Action.BUY,
                            TimeInForce.GTC, n % 2 == 0 ? px : 1000 - px, 1 + n % 5));
                }
            }
        });
    }
}
