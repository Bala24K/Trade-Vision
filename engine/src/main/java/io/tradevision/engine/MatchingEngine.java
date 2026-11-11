package io.tradevision.engine;

import io.tradevision.engine.account.Account;
import io.tradevision.engine.account.Position;
import io.tradevision.engine.book.Order;
import io.tradevision.engine.book.OrderBook;
import io.tradevision.engine.book.OrderPool;
import io.tradevision.engine.book.PriceLevel;
import io.tradevision.engine.util.LongObjectMap;

/**
 * The exchange core: order books, matching, pre-trade risk, and position accounting for every market,
 * as one <b>single-threaded deterministic state machine</b>.
 *
 * <h2>Design rules</h2>
 * <ul>
 *   <li><b>Single writer.</b> Exactly one thread calls into this class. There are no locks, no CAS, no
 *       volatile fields: correctness comes from ownership, not synchronization (ADR-0002).</li>
 *   <li><b>Deterministic.</b> Same command sequence in, same state and event sequence out. Ids come from
 *       counters, time comes from the command, iteration over hash maps is never observable. This is what
 *       makes journal replay a valid recovery mechanism (ADR-0003).</li>
 *   <li><b>No allocation on the hot path.</b> Orders are pooled, maps are primitive-keyed, events are
 *       delivered as callbacks over live objects rather than as new event objects.</li>
 *   <li><b>Risk before matching.</b> A BUY reserves {@code quantity * limit} up front; a SELL locks the
 *       units it sells. A fill can therefore never overdraw an account, by construction.</li>
 * </ul>
 *
 * <h2>Matching rules</h2>
 * Price-time priority. Trades execute at the <i>maker's</i> price (the taker receives any improvement).
 * Self-trade prevention cancels the resting order (cancel-oldest). See docs/matching-engine.md.
 */
public final class MatchingEngine {
    private final LongObjectMap<Market> markets = new LongObjectMap<>(64);
    private final LongObjectMap<Account> accounts = new LongObjectMap<>(1024);
    private final LongObjectMap<Order> orders;
    private final OrderPool pool;
    private EngineListener listener;

    private long orderSeq;
    private long tradeSeq;

    public MatchingEngine(EngineListener listener) {
        this(listener, 1 << 16);
    }

    public MatchingEngine(EngineListener listener, int pooledOrders) {
        this(listener, pooledOrders, 1 << 16);
    }

    /**
     * @param expectedOpenOrders pre-sizes the order-id index. Growing a hash table means a stop-the-world
     *        rehash on the engine thread (measured at ~25 ms at 1M orders), so production sizes this for peak.
     */
    public MatchingEngine(EngineListener listener, int pooledOrders, int expectedOpenOrders) {
        this.listener = listener;
        this.pool = new OrderPool(pooledOrders);
        this.orders = new LongObjectMap<>(expectedOpenOrders);
    }

    public void setListener(EngineListener listener) {
        this.listener = listener;
    }

    // =====================================================================================
    // Administrative commands
    // =====================================================================================

    public boolean createMarket(long marketId, long ts) {
        if (marketId <= 0 || markets.containsKey(marketId)) {
            listener.onCommandRejected(CommandType.CREATE_MARKET, marketId, RejectReason.DUPLICATE_MARKET, ts);
            return false;
        }
        markets.put(marketId, new Market(marketId));
        listener.onMarketCreated(marketId, ts);
        return true;
    }

    public boolean createAccount(long accountId, long ts) {
        if (accountId <= 0 || accounts.containsKey(accountId)) {
            listener.onCommandRejected(CommandType.CREATE_ACCOUNT, accountId, RejectReason.DUPLICATE_ACCOUNT, ts);
            return false;
        }
        accounts.put(accountId, new Account(accountId));
        listener.onAccountCreated(accountId, ts);
        return true;
    }

    public boolean deposit(long accountId, long amount, long ts) {
        Account a = accounts.get(accountId);
        if (a == null) return rejectCommand(CommandType.DEPOSIT, accountId, RejectReason.UNKNOWN_ACCOUNT, ts);
        if (amount <= 0) return rejectCommand(CommandType.DEPOSIT, accountId, RejectReason.INVALID_AMOUNT, ts);
        a.deposit(amount);
        listener.onBalance(a, ts);
        return true;
    }

    public boolean withdraw(long accountId, long amount, long ts) {
        Account a = accounts.get(accountId);
        if (a == null) return rejectCommand(CommandType.WITHDRAW, accountId, RejectReason.UNKNOWN_ACCOUNT, ts);
        if (amount <= 0) return rejectCommand(CommandType.WITHDRAW, accountId, RejectReason.INVALID_AMOUNT, ts);
        if (amount > a.available()) return rejectCommand(CommandType.WITHDRAW, accountId, RejectReason.INSUFFICIENT_FUNDS, ts);
        a.withdraw(amount);
        listener.onBalance(a, ts);
        return true;
    }

    public boolean haltMarket(long marketId, long ts) {
        return setStatus(CommandType.HALT_MARKET, marketId, MarketStatus.OPEN, MarketStatus.HALTED, ts);
    }

    public boolean reopenMarket(long marketId, long ts) {
        return setStatus(CommandType.REOPEN_MARKET, marketId, MarketStatus.HALTED, MarketStatus.OPEN, ts);
    }

    private boolean setStatus(CommandType cmd, long marketId, MarketStatus from, MarketStatus to, long ts) {
        Market m = markets.get(marketId);
        if (m == null) return rejectCommand(cmd, marketId, RejectReason.UNKNOWN_MARKET, ts);
        if (m.status != from) {
            return rejectCommand(cmd, marketId,
                    m.status == MarketStatus.RESOLVED ? RejectReason.MARKET_RESOLVED : RejectReason.MARKET_NOT_OPEN, ts);
        }
        m.status = to;
        listener.onMarketStatus(m, ts);
        return true;
    }

    /**
     * Resolve a market: cancel every resting order (releasing reservations and locks), then pay each
     * winning contract {@link Prices#PAYOUT} and zero all positions. Accounts settle in id order so the
     * event stream is deterministic.
     */
    public boolean resolveMarket(long marketId, Outcome winner, long ts) {
        Market m = markets.get(marketId);
        if (m == null) return rejectCommand(CommandType.RESOLVE_MARKET, marketId, RejectReason.UNKNOWN_MARKET, ts);
        if (m.status == MarketStatus.RESOLVED) {
            return rejectCommand(CommandType.RESOLVE_MARKET, marketId, RejectReason.MARKET_RESOLVED, ts);
        }
        OrderBook book = m.book;
        for (Side side : Side.values()) {
            for (int p = book.best(side); p != -1; p = book.best(side)) {
                cancelResting(m, book.level(side, p).head(), CancelReason.MARKET_RESOLVED, ts);
            }
        }
        for (long accountId : accounts.sortedKeys()) {
            Account a = accounts.get(accountId);
            Position pos = a.position(marketId);
            if (pos == null || pos.isFlat()) continue;
            long winning = winner == Outcome.YES ? pos.yes() : pos.no();
            long payout = winning * Prices.PAYOUT;
            long costRemoved = a.removeYes(pos, pos.yes()) + a.removeNo(pos, pos.no());
            a.credit(payout);
            a.realize(pos, payout - costRemoved);
            listener.onSettlement(pos, payout, ts);
            listener.onPosition(pos, ts);
            listener.onBalance(a, ts);
        }
        m.openInterest = 0;
        m.winner = winner;
        m.status = MarketStatus.RESOLVED;
        listener.onMarketStatus(m, ts);
        return true;
    }

    // =====================================================================================
    // Trading commands
    // =====================================================================================

    /**
     * Place a limit order. A market order is an {@link TimeInForce#IOC} order at the worst price
     * ({@link Prices#MAX_PRICE} to buy), whose reservation is refunded down to what it actually paid.
     *
     * @param limitPrice in the order's own outcome terms: "BUY NO @ 370" means pay at most 37.0¢ per NO.
     * @return the new order id, or -1 if rejected
     */
    public long placeOrder(long accountId, long clientOrderId, long marketId, Outcome outcome, Action action,
                           TimeInForce tif, int limitPrice, long quantity, long ts) {
        Market m = markets.get(marketId);
        if (m == null) return rejectOrder(accountId, clientOrderId, marketId, RejectReason.UNKNOWN_MARKET, ts);
        if (m.status != MarketStatus.OPEN) {
            return rejectOrder(accountId, clientOrderId, marketId, RejectReason.MARKET_NOT_OPEN, ts);
        }
        Account a = accounts.get(accountId);
        if (a == null) return rejectOrder(accountId, clientOrderId, marketId, RejectReason.UNKNOWN_ACCOUNT, ts);
        if (!Prices.isValidPrice(limitPrice)) {
            return rejectOrder(accountId, clientOrderId, marketId, RejectReason.INVALID_PRICE, ts);
        }
        if (quantity <= 0 || quantity > Prices.MAX_QUANTITY) {
            return rejectOrder(accountId, clientOrderId, marketId, RejectReason.INVALID_QUANTITY, ts);
        }

        Side side = Side.of(outcome, action);
        int bookPrice = Side.bookPrice(outcome, limitPrice);
        OrderBook book = m.book;

        if (tif == TimeInForce.POST_ONLY && book.crosses(side, bookPrice)) {
            return rejectOrder(accountId, clientOrderId, marketId, RejectReason.POST_ONLY_WOULD_CROSS, ts);
        }
        if (tif == TimeInForce.FOK && fillableQuantity(book, side, bookPrice, accountId, quantity) < quantity) {
            return rejectOrder(accountId, clientOrderId, marketId, RejectReason.FOK_NOT_FILLABLE, ts);
        }

        // ---- pre-trade risk: reserve cash for buys, lock inventory for sells ----
        Position pos = null;
        long reserve = 0;
        if (action == Action.BUY) {
            reserve = quantity * limitPrice;
            if (reserve > a.available()) {
                return rejectOrder(accountId, clientOrderId, marketId, RejectReason.INSUFFICIENT_FUNDS, ts);
            }
        } else {
            pos = a.position(marketId);
            long free = pos == null ? 0 : (outcome == Outcome.YES ? pos.yesFree() : pos.noFree());
            if (free < quantity) {
                return rejectOrder(accountId, clientOrderId, marketId, RejectReason.INSUFFICIENT_POSITION, ts);
            }
        }

        Order o = pool.acquire();
        o.orderId = ++orderSeq;
        o.clientOrderId = clientOrderId;
        o.accountId = accountId;
        o.marketId = marketId;
        o.outcome = outcome;
        o.action = action;
        o.tif = tif;
        o.side = side;
        o.limitPrice = limitPrice;
        o.bookPrice = bookPrice;
        o.quantity = quantity;
        o.remaining = quantity;
        o.timestamp = ts;

        if (action == Action.BUY) {
            a.reserve(reserve);
            o.reservedCash = reserve;
        } else if (outcome == Outcome.YES) {
            a.lockYes(pos, quantity);
        } else {
            a.lockNo(pos, quantity);
        }
        listener.onOrderAccepted(o, ts);
        if (action == Action.BUY) listener.onBalance(a, ts);
        else listener.onPosition(pos, ts);

        match(m, o, ts);

        long orderId = o.orderId;
        if (o.remaining == 0) {
            listener.onOrderFilled(o, ts);
            pool.release(o);
        } else if (tif == TimeInForce.GTC || tif == TimeInForce.POST_ONLY) {
            book.add(o);
            orders.put(orderId, o);
            listener.onBookLevel(marketId, side, bookPrice, book.quantityAt(side, bookPrice), ts);
        } else {
            releaseRemainder(m, o, ts);
            listener.onOrderCanceled(o, CancelReason.IOC_REMAINDER, ts);
            pool.release(o);
        }
        return orderId;
    }

    public boolean cancelOrder(long accountId, long orderId, long ts) {
        Order o = orders.get(orderId);
        if (o == null) {
            listener.onCancelRejected(accountId, orderId, RejectReason.UNKNOWN_ORDER, ts);
            return false;
        }
        if (o.accountId != accountId) {
            listener.onCancelRejected(accountId, orderId, RejectReason.NOT_ORDER_OWNER, ts);
            return false;
        }
        cancelResting(markets.get(o.marketId), o, CancelReason.USER_REQUESTED, ts);
        return true;
    }

    // =====================================================================================
    // Matching
    // =====================================================================================

    private void match(Market m, Order taker, long ts) {
        OrderBook book = m.book;
        Side opposite = taker.side.opposite();
        boolean takerIsBid = taker.side == Side.BID;

        while (taker.remaining > 0) {
            int price = book.best(opposite);
            if (price == -1 || (takerIsBid ? price > taker.bookPrice : price < taker.bookPrice)) break;

            PriceLevel level = book.level(opposite, price);
            Order maker = level.head();

            if (maker.accountId == taker.accountId) {
                cancelResting(m, maker, CancelReason.SELF_TRADE_PREVENTION, ts);
                continue;
            }

            long qty = Math.min(taker.remaining, maker.remaining);
            book.reduce(maker, qty);
            taker.remaining -= qty;

            m.lastTradePrice = price;
            m.volume += qty;
            m.notional += qty * price;
            m.tradeCount++;
            if (maker.action == Action.BUY && taker.action == Action.BUY) m.openInterest += qty;       // mint pairs
            else if (maker.action == Action.SELL && taker.action == Action.SELL) m.openInterest -= qty; // burn pairs

            listener.onTrade(++tradeSeq, m, price, qty, maker, taker, ts);
            applyFill(m, maker, price, qty, ts);
            applyFill(m, taker, price, qty, ts);

            if (maker.remaining == 0) {
                book.remove(maker);
                orders.remove(maker.orderId);
                listener.onOrderFilled(maker, ts);
                pool.release(maker);
            }
            listener.onBookLevel(m.marketId, opposite, price, book.quantityAt(opposite, price), ts);
        }
    }

    /**
     * Apply one fill to one side's account. {@code bookPrice} is the YES trade price; the order's own
     * outcome price is its complement for NO orders.
     */
    private void applyFill(Market m, Order o, int bookPrice, long qty, long ts) {
        Account a = accounts.get(o.accountId);
        Position pos = a.positionOrCreate(o.marketId);
        int px = o.outcome == Outcome.YES ? bookPrice : Prices.complement(bookPrice);
        long value = qty * px;

        if (o.action == Action.BUY) {
            long release = qty * o.limitPrice;
            o.reservedCash -= release;
            a.settleReserved(release, value); // refunds (limit - px) * qty price improvement
            if (o.outcome == Outcome.YES) a.addYes(pos, qty, value);
            else a.addNo(pos, qty, value);
        } else {
            long costRemoved;
            if (o.outcome == Outcome.YES) {
                a.unlockYes(pos, qty);
                costRemoved = a.removeYes(pos, qty);
            } else {
                a.unlockNo(pos, qty);
                costRemoved = a.removeNo(pos, qty);
            }
            a.credit(value);
            a.realize(pos, value - costRemoved);
        }
        mergePairs(m, a, pos);
        listener.onPosition(pos, ts);
        listener.onBalance(a, ts);
    }

    /**
     * One YES plus one NO always pays exactly $1. Merge free (unlocked) pairs back into cash so capital is
     * never trapped in a riskless position. This is also what lets "BUY NO" act as "close my YES".
     */
    private void mergePairs(Market m, Account a, Position pos) {
        long k = Math.min(pos.yesFree(), pos.noFree());
        if (k <= 0) return;
        long cost = a.removeYes(pos, k) + a.removeNo(pos, k);
        long value = k * Prices.PAYOUT;
        a.credit(value);
        a.realize(pos, value - cost);
        m.openInterest -= k;
    }

    private void cancelResting(Market m, Order o, CancelReason reason, long ts) {
        OrderBook book = m.book;
        book.remove(o);
        orders.remove(o.orderId);
        releaseRemainder(m, o, ts);
        listener.onOrderCanceled(o, reason, ts);
        listener.onBookLevel(m.marketId, o.side, o.bookPrice, book.quantityAt(o.side, o.bookPrice), ts);
        pool.release(o);
    }

    /** Give back the risk held by an order's unfilled remainder. */
    private void releaseRemainder(Market m, Order o, long ts) {
        Account a = accounts.get(o.accountId);
        if (o.action == Action.BUY) {
            a.settleReserved(o.reservedCash, 0);
            o.reservedCash = 0;
            listener.onBalance(a, ts);
        } else {
            Position pos = a.position(o.marketId);
            if (o.outcome == Outcome.YES) a.unlockYes(pos, o.remaining);
            else a.unlockNo(pos, o.remaining);
            long before = a.available();
            mergePairs(m, a, pos); // newly unlocked units may now pair up
            listener.onPosition(pos, ts);
            if (a.available() != before) listener.onBalance(a, ts);
        }
    }

    /** Quantity an incoming order could execute right now, ignoring the account's own orders (STP removes those). */
    private static long fillableQuantity(OrderBook book, Side side, int bookPrice, long accountId, long wanted) {
        Side opposite = side.opposite();
        long total = 0;
        for (int p = book.best(opposite); p != -1; p = book.nextLevel(opposite, p)) {
            if (side == Side.BID ? p > bookPrice : p < bookPrice) break;
            for (Order o = book.level(opposite, p).head(); o != null; o = o.next()) {
                if (o.accountId != accountId) {
                    total += o.remaining;
                    if (total >= wanted) return total;
                }
            }
        }
        return total;
    }

    private long rejectOrder(long accountId, long clientOrderId, long marketId, RejectReason reason, long ts) {
        listener.onOrderRejected(accountId, clientOrderId, marketId, reason, ts);
        return -1;
    }

    private boolean rejectCommand(CommandType cmd, long subjectId, RejectReason reason, long ts) {
        listener.onCommandRejected(cmd, subjectId, reason, ts);
        return false;
    }

    // =====================================================================================
    // Read access (engine thread only; other threads consume events, never this state)
    // =====================================================================================

    public Market market(long marketId) { return markets.get(marketId); }
    public Account account(long accountId) { return accounts.get(accountId); }
    public Order order(long orderId) { return orders.get(orderId); }
    public LongObjectMap<Market> markets() { return markets; }
    public LongObjectMap<Account> accounts() { return accounts; }
    public LongObjectMap<Order> openOrders() { return orders; }
    public long lastOrderId() { return orderSeq; }
    public long lastTradeId() { return tradeSeq; }

    // ---- snapshot restore hooks (used by io.tradevision.engine.journal.Snapshot) ----

    void restoreCounters(long orderSeq, long tradeSeq) {
        this.orderSeq = orderSeq;
        this.tradeSeq = tradeSeq;
    }

    Market restoreMarket(long marketId, MarketStatus status, Outcome winner, int lastTradePrice, long volume,
                         long notional, long tradeCount, long openInterest) {
        Market m = new Market(marketId);
        m.status = status;
        m.winner = winner;
        m.lastTradePrice = lastTradePrice;
        m.volume = volume;
        m.notional = notional;
        m.tradeCount = tradeCount;
        m.openInterest = openInterest;
        markets.put(marketId, m);
        return m;
    }

    Account restoreAccount(long accountId) {
        Account a = new Account(accountId);
        accounts.put(accountId, a);
        return a;
    }

    /** Re-insert a resting order exactly as it was (no matching, no risk checks: the snapshot already reflects them). */
    void restoreOrder(long orderId, long clientOrderId, long accountId, long marketId, Outcome outcome, Action action,
                      TimeInForce tif, int limitPrice, long quantity, long remaining, long reservedCash, long ts) {
        Order o = pool.acquire();
        o.orderId = orderId;
        o.clientOrderId = clientOrderId;
        o.accountId = accountId;
        o.marketId = marketId;
        o.outcome = outcome;
        o.action = action;
        o.tif = tif;
        o.side = Side.of(outcome, action);
        o.limitPrice = limitPrice;
        o.bookPrice = Side.bookPrice(outcome, limitPrice);
        o.quantity = quantity;
        o.remaining = remaining;
        o.reservedCash = reservedCash;
        o.timestamp = ts;
        markets.get(marketId).book.add(o);
        orders.put(orderId, o);
    }
}
