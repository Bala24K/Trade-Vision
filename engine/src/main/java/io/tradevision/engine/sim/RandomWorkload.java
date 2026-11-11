package io.tradevision.engine.sim;

import io.tradevision.engine.Action;
import io.tradevision.engine.Command;
import io.tradevision.engine.CommandType;
import io.tradevision.engine.MarketStatus;
import io.tradevision.engine.MatchingEngine;
import io.tradevision.engine.Outcome;
import io.tradevision.engine.TimeInForce;
import io.tradevision.engine.account.Position;
import io.tradevision.engine.book.Order;

import java.util.SplittableRandom;
import java.util.function.Consumer;

/**
 * Seeded, reproducible random order flow covering every command type, all four YES/NO intents and every
 * time-in-force. Used by the fuzzer, the recovery tests and the benchmarks, so all three exercise the
 * same realistic mix (~60% orders, ~33% cancels, plus funding, halts and resolutions).
 *
 * <p>It peeks at engine state (open orders, free inventory) to keep the flow plausible, so the sink must
 * apply each command synchronously before the next {@link #step} call.
 */
public final class RandomWorkload {
    private final SplittableRandom rnd;
    private final int markets;
    private final int accounts;
    private final long[] active;
    private final Command cmd = new Command();
    private long ts;
    private long coid;
    private long nextMarketId = 1;

    public RandomWorkload(long seed, int markets, int accounts) {
        this.rnd = new SplittableRandom(seed);
        this.markets = markets;
        this.accounts = accounts;
        this.active = new long[markets];
    }

    private void emit(Consumer<Command> sink) {
        cmd.timestamp = ++ts;
        sink.accept(cmd);
    }

    public void setup(Consumer<Command> sink) {
        for (int m = 0; m < markets; m++) {
            active[m] = nextMarketId++;
            cmd.admin(CommandType.CREATE_MARKET, active[m], 0);
            emit(sink);
        }
        for (int a = 1; a <= accounts; a++) {
            cmd.admin(CommandType.CREATE_ACCOUNT, a, 0);
            emit(sink);
            cmd.admin(CommandType.DEPOSIT, a, rnd.nextLong(5_000_000, 50_000_000));
            emit(sink);
        }
    }

    public void step(MatchingEngine view, Consumer<Command> sink) {
        int r = rnd.nextInt(1000);
        long acct = rnd.nextLong(1, accounts + 1);
        int slot = rnd.nextInt(markets);
        long market = active[slot];
        if (view.market(market).status() == MarketStatus.HALTED && rnd.nextInt(8) == 0) {
            cmd.admin(CommandType.REOPEN_MARKET, market, 0); // halts are short-lived, like real news halts
            emit(sink);
            return;
        }
        if (r < 600) {
            // Prices cluster around a per-market fair value so books actually cross.
            int fair = 300 + (slot % 4) * 150;
            int price = Math.clamp(fair + rnd.nextInt(-60, 61), 1, 999);
            Outcome outcome = rnd.nextBoolean() ? Outcome.YES : Outcome.NO;
            if (outcome == Outcome.NO) price = Math.clamp(1000 - price, 1, 999);
            Action action = rnd.nextInt(3) == 0 ? Action.SELL : Action.BUY;
            TimeInForce tif = TimeInForce.of(rnd.nextInt(10) < 7 ? 0 : rnd.nextInt(4));
            long qty = rnd.nextLong(1, 120);
            if (action == Action.SELL) {
                Position pos = view.account(acct).position(market);
                long free = pos == null ? 0 : (outcome == Outcome.YES ? pos.yesFree() : pos.noFree());
                if (free > 0 && rnd.nextInt(4) != 0) qty = rnd.nextLong(1, free + 1);
            }
            cmd.placeOrder(acct, ++coid, market, outcome, action, tif, price, qty);
            emit(sink);
        } else if (r < 930) {
            var open = view.openOrders();
            if (!open.isEmpty()) {
                long[] ids = open.sortedKeys();
                long id = ids[rnd.nextInt(ids.length)];
                Order o = view.order(id);
                cmd.cancelOrder(rnd.nextInt(10) == 0 ? acct : o.accountId, id);
                emit(sink);
            }
        } else if (r < 960) {
            cmd.admin(CommandType.DEPOSIT, acct, rnd.nextLong(1, 1_000_000));
            emit(sink);
        } else if (r < 985) {
            cmd.admin(CommandType.WITHDRAW, acct, rnd.nextLong(1, 200_000));
            emit(sink);
        } else if (r < 995) {
            cmd.admin(CommandType.HALT_MARKET, market, 0);
            emit(sink);
        } else if (rnd.nextInt(10) == 0) {
            cmd.resolve(market, rnd.nextBoolean() ? Outcome.YES : Outcome.NO);
            emit(sink);
            active[slot] = nextMarketId++;
            cmd.admin(CommandType.CREATE_MARKET, active[slot], 0); // replace the resolved market
            emit(sink);
        }
    }
}
