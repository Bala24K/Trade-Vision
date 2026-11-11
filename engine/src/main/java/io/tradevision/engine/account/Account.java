package io.tradevision.engine.account;

import io.tradevision.engine.util.LongObjectMap;

import java.math.BigInteger;

/**
 * Cash and positions for one trader. All amounts in mills ($0.001).
 *
 * <p>{@code available + reserved} is the account's cash. {@code reserved} is earmarked for resting BUY
 * orders at their limit price, which is the worst case they can cost; this is what makes it impossible
 * for a fill to drive a balance negative. Mutators are deliberately low-level and package-light: the
 * accounting <i>rules</i> live in {@code MatchingEngine} so they sit next to the matching that triggers them.
 */
public final class Account {
    public final long accountId;
    long available;
    long reserved;
    long deposited;
    long withdrawn;
    final LongObjectMap<Position> positions = new LongObjectMap<>(8);

    public Account(long accountId) {
        this.accountId = accountId;
    }

    public long available() { return available; }
    public long reserved() { return reserved; }
    public long cash() { return available + reserved; }
    public long deposited() { return deposited; }
    public long withdrawn() { return withdrawn; }

    public Position position(long marketId) {
        return positions.get(marketId);
    }

    public Position positionOrCreate(long marketId) {
        Position p = positions.get(marketId);
        if (p == null) positions.put(marketId, p = new Position(accountId, marketId));
        return p;
    }

    public LongObjectMap<Position> positions() {
        return positions;
    }

    // ---------------- cash ----------------

    public void deposit(long amount) {
        available += amount;
        deposited += amount;
    }

    public void withdraw(long amount) {
        available -= amount;
        withdrawn += amount;
    }

    public void reserve(long amount) {
        available -= amount;
        reserved += amount;
    }

    /** Release {@code released} from reservation, of which {@code spent} actually leaves the account. */
    public void settleReserved(long released, long spent) {
        reserved -= released;
        available += released - spent;
    }

    public void credit(long amount) {
        available += amount;
    }

    // ---------------- positions ----------------

    public void lockYes(Position p, long qty) { p.yesLocked += qty; }
    public void lockNo(Position p, long qty) { p.noLocked += qty; }
    public void unlockYes(Position p, long qty) { p.yesLocked -= qty; }
    public void unlockNo(Position p, long qty) { p.noLocked -= qty; }

    public void addYes(Position p, long qty, long cost) {
        p.yes += qty;
        p.yesCost += cost;
    }

    public void addNo(Position p, long qty, long cost) {
        p.no += qty;
        p.noCost += cost;
    }

    /** Remove {@code qty} YES units (and their proportional cost basis). @return the cost basis removed. */
    public long removeYes(Position p, long qty) {
        long removed = costShare(p.yesCost, p.yes, qty);
        p.yes -= qty;
        p.yesCost -= removed;
        return removed;
    }

    public long removeNo(Position p, long qty) {
        long removed = costShare(p.noCost, p.no, qty);
        p.no -= qty;
        p.noCost -= removed;
        return removed;
    }

    public void realize(Position p, long pnl) {
        p.realizedPnl += pnl;
    }

    public void restore(long available, long reserved, long deposited, long withdrawn) {
        this.available = available;
        this.reserved = reserved;
        this.deposited = deposited;
        this.withdrawn = withdrawn;
    }

    public void restorePosition(long marketId, long yes, long no, long yesLocked, long noLocked, long yesCost,
                                long noCost, long realizedPnl) {
        positionOrCreate(marketId).restore(yes, no, yesLocked, noLocked, yesCost, noCost, realizedPnl);
    }

    /**
     * Proportional cost basis of {@code qty} out of {@code held} units: {@code cost * qty / held}, rounded
     * down. Closing the whole position always removes the whole remaining cost, so rounding never leaves
     * a residue on a flat position. Falls back to BigInteger only if the product would overflow.
     */
    static long costShare(long cost, long held, long qty) {
        if (qty >= held) return cost;
        long hi = Math.multiplyHigh(cost, qty);
        long lo = cost * qty;
        if ((hi == 0 && lo >= 0) || (hi == -1 && lo < 0)) return lo / held;
        return BigInteger.valueOf(cost).multiply(BigInteger.valueOf(qty)).divide(BigInteger.valueOf(held)).longValueExact();
    }

    @Override
    public String toString() {
        return "Account{" + accountId + " available=" + available + " reserved=" + reserved + "}";
    }
}
