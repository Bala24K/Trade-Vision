package io.tradevision.engine;

import io.tradevision.engine.book.Order;
import io.tradevision.engine.sim.RandomWorkload;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Property-based fuzzing: drive the engine with random order flow (all four intents, every TIF, cancels,
 * halts, resolutions) and check every global invariant after every single command.
 */
class EngineFuzzTest {
    static final int MARKETS = 3, ACCOUNTS = 12;

    static final class Counter implements EngineListener {
        long trades, volume, rejects;
        final EnumMap<RejectReason, Integer> reasons = new EnumMap<>(RejectReason.class);

        @Override
        public void onTrade(long tradeId, Market m, int price, long qty, Order maker, Order taker, long ts) {
            trades++;
            volume += qty;
        }

        @Override
        public void onOrderRejected(long a, long c, long m, RejectReason r, long ts) {
            rejects++;
            reasons.merge(r, 1, Integer::sum);
        }
    }

    @RepeatedTest(8)
    void invariantsHoldAfterEveryCommand(RepetitionInfo info) {
        var counter = new Counter();
        var engine = new MatchingEngine(counter);
        var w = new RandomWorkload(info.getCurrentRepetition() * 7919L, MARKETS, ACCOUNTS);
        int[] step = {0};
        w.setup(c -> c.applyTo(engine));
        for (int i = 0; i < 15_000; i++) {
            step[0] = i;
            w.step(engine, c -> {
                c.applyTo(engine);
                var errors = EngineInvariants.check(engine);
                if (!errors.isEmpty()) throw new AssertionError("step " + step[0] + " " + c + ":\n" + String.join("\n", errors));
            });
        }
        assertTrue(counter.trades > 1_000,
                "workload must actually trade, got " + counter.trades + " trades, " + counter.rejects + " rejects " + counter.reasons);
    }

    @Test
    void sameInputProducesSameState() {
        long[] digests = new long[2];
        for (int run = 0; run < 2; run++) {
            var engine = new MatchingEngine(EngineListener.NO_OP);
            var w = new RandomWorkload(42, MARKETS, ACCOUNTS);
            w.setup(c -> c.applyTo(engine));
            for (int i = 0; i < 100_000; i++) w.step(engine, c -> c.applyTo(engine));
            digests[run] = EngineDigest.of(engine);
        }
        assertEquals(digests[0], digests[1]);
    }
}
