package io.tradevision.engine;

import io.tradevision.engine.account.Account;
import io.tradevision.engine.account.Position;
import io.tradevision.engine.book.Order;
import io.tradevision.engine.book.OrderBook;

/**
 * 64-bit fingerprint of the complete engine state, walked in a canonical order (sorted ids, book in
 * price-time order). Two engines with equal digests hold equal state. Used to prove that
 * snapshot + journal replay reconstructs exactly the state that was live before a crash.
 */
public final class EngineDigest {
    private long h = 0xcbf29ce484222325L;

    private EngineDigest() {}

    private void mix(long v) {
        h ^= v;
        h *= 0x100000001b3L;
        h ^= h >>> 29;
    }

    public static long of(MatchingEngine e) {
        EngineDigest d = new EngineDigest();
        d.mix(e.lastOrderId());
        d.mix(e.lastTradeId());
        for (long id : e.markets().sortedKeys()) {
            Market m = e.market(id);
            d.mix(id);
            d.mix(m.status().ordinal());
            d.mix(m.winner() == null ? -1 : m.winner().ordinal());
            d.mix(m.lastTradePrice());
            d.mix(m.volume());
            d.mix(m.notional());
            d.mix(m.tradeCount());
            d.mix(m.openInterest());
            OrderBook book = m.book();
            for (Side side : Side.values()) {
                for (int p = book.best(side); p != -1; p = book.nextLevel(side, p)) {
                    for (Order o = book.level(side, p).head(); o != null; o = o.next()) {
                        d.mix(o.orderId);
                        d.mix(o.clientOrderId);
                        d.mix(o.accountId);
                        d.mix(o.outcome.ordinal() * 31L + o.action.ordinal() * 7L + o.tif.ordinal());
                        d.mix(o.limitPrice);
                        d.mix(o.quantity);
                        d.mix(o.remaining);
                        d.mix(o.reservedCash);
                        d.mix(o.timestamp);
                    }
                }
            }
        }
        for (long id : e.accounts().sortedKeys()) {
            Account a = e.account(id);
            d.mix(id);
            d.mix(a.available());
            d.mix(a.reserved());
            d.mix(a.deposited());
            d.mix(a.withdrawn());
            for (long mid : a.positions().sortedKeys()) {
                Position p = a.position(mid);
                d.mix(mid);
                d.mix(p.yes());
                d.mix(p.no());
                d.mix(p.yesLocked());
                d.mix(p.noLocked());
                d.mix(p.yesCost());
                d.mix(p.noCost());
                d.mix(p.realizedPnl());
            }
        }
        return d.h;
    }
}
