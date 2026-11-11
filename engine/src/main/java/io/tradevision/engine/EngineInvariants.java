package io.tradevision.engine;

import io.tradevision.engine.account.Account;
import io.tradevision.engine.account.Position;
import io.tradevision.engine.book.Order;
import io.tradevision.engine.book.OrderBook;
import io.tradevision.engine.book.PriceLevel;
import io.tradevision.engine.util.LongObjectMap;

import java.util.ArrayList;
import java.util.List;

/**
 * Global accounting invariants. Cheap enough to run after every command in tests and the fuzzer,
 * and on demand (e.g. after recovery) in production.
 *
 * <ol>
 *   <li><b>Conservation of money:</b> {@code sum(cash) + PAYOUT * sum(openInterest) == deposits - withdrawals}.
 *       Every outstanding YES/NO pair is backed by exactly $1 that left some account when it was minted.</li>
 *   <li><b>Pair symmetry:</b> per market, total YES held == total NO held == open interest.</li>
 *   <li><b>Reservations match orders:</b> each account's {@code reserved} equals the reserved cash of its
 *       resting BUYs, and each position's locked units equal the remaining quantity of its resting SELLs.</li>
 *   <li><b>No negatives:</b> no negative balance, holding or lock; locks never exceed holdings.</li>
 *   <li><b>Book integrity:</b> level totals equal the sum of their orders; the book is never crossed;
 *       every resting order is in the id index and vice versa.</li>
 * </ol>
 */
public final class EngineInvariants {
    private EngineInvariants() {}

    public static List<String> check(MatchingEngine engine) {
        List<String> errors = new ArrayList<>();
        LongObjectMap<Market> markets = engine.markets();
        LongObjectMap<Account> accounts = engine.accounts();

        LongObjectMap<long[]> expectedReserved = new LongObjectMap<>(); // accountId -> [reserved]
        LongObjectMap<long[]> expectedLocks = new LongObjectMap<>();    // packed(account, market) -> [yesLocked, noLocked]
        int[] restingSeen = {0};

        long totalCash = 0, deposits = 0, openInterest = 0;
        for (long id : accounts.sortedKeys()) {
            Account a = accounts.get(id);
            totalCash += a.cash();
            deposits += a.deposited() - a.withdrawn();
            if (a.available() < 0) errors.add("account " + id + " available < 0: " + a.available());
            if (a.reserved() < 0) errors.add("account " + id + " reserved < 0: " + a.reserved());
        }

        for (long marketId : markets.sortedKeys()) {
            Market m = markets.get(marketId);
            openInterest += m.openInterest();
            OrderBook book = m.book();
            if (book.bestBid() != -1 && book.bestAsk() != -1 && book.bestBid() >= book.bestAsk()) {
                errors.add("market " + marketId + " crossed: " + book.bestBid() + " >= " + book.bestAsk());
            }
            for (Side side : Side.values()) {
                for (int p = book.best(side); p != -1; p = book.nextLevel(side, p)) {
                    PriceLevel level = book.level(side, p);
                    long sum = 0;
                    int count = 0;
                    for (Order o = level.head(); o != null; o = o.next()) {
                        sum += o.remaining;
                        count++;
                        restingSeen[0]++;
                        if (o.remaining <= 0) errors.add("resting order with no quantity: " + o);
                        if (engine.order(o.orderId) != o) errors.add("resting order missing from index: " + o);
                        if (o.action == Action.BUY) {
                            if (o.reservedCash != o.remaining * o.limitPrice) errors.add("bad reservation on " + o);
                            long[] r = expectedReserved.get(o.accountId);
                            if (r == null) expectedReserved.put(o.accountId, r = new long[1]);
                            r[0] += o.reservedCash;
                        } else {
                            long key = o.accountId * 1_000_003L + o.marketId;
                            long[] l = expectedLocks.get(key);
                            if (l == null) expectedLocks.put(key, l = new long[2]);
                            l[o.outcome == Outcome.YES ? 0 : 1] += o.remaining;
                        }
                    }
                    if (sum != level.totalQuantity()) errors.add("level total mismatch at " + marketId + " " + side + "@" + p);
                    if (count != level.orderCount()) errors.add("level count mismatch at " + marketId + " " + side + "@" + p);
                }
            }
            long yes = 0, no = 0;
            for (long id : accounts.sortedKeys()) {
                Position pos = accounts.get(id).position(marketId);
                if (pos == null) continue;
                yes += pos.yes();
                no += pos.no();
            }
            if (yes != m.openInterest() || no != m.openInterest()) {
                errors.add("market " + marketId + " pair asymmetry: yes=" + yes + " no=" + no + " oi=" + m.openInterest());
            }
        }

        if (restingSeen[0] != engine.openOrders().size()) {
            errors.add("order index has " + engine.openOrders().size() + " orders, books have " + restingSeen[0]);
        }

        for (long id : accounts.sortedKeys()) {
            Account a = accounts.get(id);
            long[] r = expectedReserved.get(id);
            long want = r == null ? 0 : r[0];
            if (a.reserved() != want) errors.add("account " + id + " reserved " + a.reserved() + " but orders hold " + want);
            for (long marketId : a.positions().sortedKeys()) {
                Position pos = a.position(marketId);
                long[] l = expectedLocks.get(id * 1_000_003L + marketId);
                long wantYes = l == null ? 0 : l[0], wantNo = l == null ? 0 : l[1];
                if (pos.yesLocked() != wantYes || pos.noLocked() != wantNo) {
                    errors.add("position lock mismatch " + pos + " expected " + wantYes + "/" + wantNo);
                }
                if (pos.yes() < 0 || pos.no() < 0 || pos.yesFree() < 0 || pos.noFree() < 0) {
                    errors.add("negative holding " + pos);
                }
                if (pos.yesFree() > 0 && pos.noFree() > 0) errors.add("unmerged pair " + pos);
                if ((pos.yes() == 0 && pos.yesCost() != 0) || (pos.no() == 0 && pos.noCost() != 0)) {
                    errors.add("cost basis on empty position " + pos);
                }
            }
        }

        long expectedCash = deposits - openInterest * Prices.PAYOUT;
        if (totalCash != expectedCash) {
            errors.add("money not conserved: cash=" + totalCash + " expected=" + expectedCash
                    + " (deposits=" + deposits + ", oi=" + openInterest + ")");
        }
        return errors;
    }

    public static void assertValid(MatchingEngine engine) {
        List<String> errors = check(engine);
        if (!errors.isEmpty()) throw new IllegalStateException("engine invariants violated:\n  " + String.join("\n  ", errors));
    }
}
