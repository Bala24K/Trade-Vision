package io.tradevision.engine.account;

import io.tradevision.engine.Prices;

/**
 * One account's holdings in one market.
 *
 * <p>YES and NO inventory are tracked separately, as on Polymarket. Holding one YES and one NO is a
 * riskless claim on exactly $1, so whenever an account holds <i>unlocked</i> units of both, the engine
 * merges them back into cash (see {@code MatchingEngine#mergePairs}). Units committed to resting SELL
 * orders are "locked" and excluded from merging, so a resting sell can never be left uncovered.
 *
 * <p>Cost basis is tracked as total cost (not average price) to keep it exact; average entry price is
 * derived. Realized PnL accrues on sells, merges and settlement. Unrealized PnL depends on a mark price
 * and is computed by readers via {@link #unrealizedPnl(int)}, never stored, because it changes on every
 * tick of the market even when this account does nothing.
 */
public final class Position {
    public final long accountId;
    public final long marketId;
    long yes;
    long no;
    long yesLocked;
    long noLocked;
    long yesCost;
    long noCost;
    long realizedPnl;

    public Position(long accountId, long marketId) {
        this.accountId = accountId;
        this.marketId = marketId;
    }

    public long yes() { return yes; }
    public long no() { return no; }
    public long yesLocked() { return yesLocked; }
    public long noLocked() { return noLocked; }
    public long yesFree() { return yes - yesLocked; }
    public long noFree() { return no - noLocked; }
    public long yesCost() { return yesCost; }
    public long noCost() { return noCost; }
    public long realizedPnl() { return realizedPnl; }

    public boolean isFlat() {
        return yes == 0 && no == 0;
    }

    /** Mark-to-market value of current holdings at a YES mark price (in ticks). */
    public long marketValue(int yesMark) {
        return yes * yesMark + no * (long) Prices.complement(yesMark);
    }

    public long unrealizedPnl(int yesMark) {
        return marketValue(yesMark) - yesCost - noCost;
    }

    /** Average YES entry price in ticks, x1000 for sub-tick precision (e.g. 630500 = 630.5 ticks). */
    public long avgYesPriceMilli() {
        return yes == 0 ? 0 : yesCost * 1000 / yes;
    }

    public long avgNoPriceMilli() {
        return no == 0 ? 0 : noCost * 1000 / no;
    }

    // ---- mutation, restricted to the engine package via Account ----

    void restore(long yes, long no, long yesLocked, long noLocked, long yesCost, long noCost, long realizedPnl) {
        this.yes = yes;
        this.no = no;
        this.yesLocked = yesLocked;
        this.noLocked = noLocked;
        this.yesCost = yesCost;
        this.noCost = noCost;
        this.realizedPnl = realizedPnl;
    }

    @Override
    public String toString() {
        return "Position{acct=" + accountId + " mkt=" + marketId + " yes=" + yes + "(" + yesLocked + " locked) no=" + no
                + "(" + noLocked + " locked) cost=" + yesCost + "/" + noCost + " realized=" + realizedPnl + "}";
    }
}
