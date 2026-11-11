package io.tradevision.engine;

/**
 * Side of the <b>unified YES book</b>.
 *
 * <p>Every market has exactly one order book, quoted in YES prices. The four user intents collapse onto
 * its two sides, which is what lets a NO buyer trade against a YES buyer (minting a new pair):
 * <pre>
 *   BUY  YES @ p  -> BID @ p          SELL YES @ p  -> ASK @ p
 *   SELL NO  @ q  -> BID @ 1000 - q   BUY  NO  @ q  -> ASK @ 1000 - q
 * </pre>
 */
public enum Side {
    BID, ASK;

    public static Side of(Outcome outcome, Action action) {
        boolean wantsYesExposure = (outcome == Outcome.YES) == (action == Action.BUY);
        return wantsYesExposure ? BID : ASK;
    }

    public static int bookPrice(Outcome outcome, int limitPrice) {
        return outcome == Outcome.YES ? limitPrice : Prices.complement(limitPrice);
    }

    public Side opposite() {
        return this == BID ? ASK : BID;
    }
}
