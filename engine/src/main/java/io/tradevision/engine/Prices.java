package io.tradevision.engine;

/**
 * Fixed-point price and cash conventions.
 *
 * <p>A binary contract pays exactly $1.00 if its outcome happens. Prices are therefore probabilities,
 * expressed as integer <b>ticks</b> of $0.001: a price of 630 means 63.0¢. Valid limit prices are
 * {@code 1..999} — 0 and 1000 are not tradable because a contract at $0 or $1 is already resolved.
 *
 * <p>Cash is held in the same unit (1 = $0.001, a "mill"), so {@code price * quantity} is always an
 * exact integer and no floating point ever touches money. A long can hold ~9.2e18 mills, and we cap a
 * single order at {@link #MAX_QUANTITY} contracts, so {@code MAX_QUANTITY * PAYOUT} cannot overflow.
 */
public final class Prices {
    /** Cash value of one winning contract, in mills ($1.000). */
    public static final int PAYOUT = 1000;
    public static final int MIN_PRICE = 1;
    public static final int MAX_PRICE = PAYOUT - 1;
    public static final long MAX_QUANTITY = 1_000_000_000L;

    private Prices() {}

    public static boolean isValidPrice(int price) {
        return price >= MIN_PRICE && price <= MAX_PRICE;
    }

    /** A NO contract priced at {@code p} is economically a YES contract priced at {@code PAYOUT - p}. */
    public static int complement(int price) {
        return PAYOUT - price;
    }

    public static String format(long mills) {
        long abs = Math.abs(mills);
        return (mills < 0 ? "-" : "") + "$" + (abs / 1000) + "." + String.format("%03d", abs % 1000);
    }
}
