package io.tradevision.bench;

import java.util.SplittableRandom;

/**
 * A pre-generated, replayable stream of book operations so benchmarks measure the book, not the RNG.
 *
 * <p>Shape loosely follows real venues: most activity sits within a few ticks of a slowly random-walking
 * mid, ~45% of operations are cancels (on real exchanges the large majority of orders are cancelled, not
 * filled), and ~10% of placements are marketable and cross the spread.
 */
final class OrderTape {
    static final byte PLACE = 0, CANCEL = 1;

    final byte[] op;
    final boolean[] bid;
    final int[] price;
    final long[] qty;
    final long[] id;
    final int length;

    OrderTape(int length, long seed) {
        this.length = length;
        op = new byte[length];
        bid = new boolean[length];
        price = new int[length];
        qty = new long[length];
        id = new long[length];
        var rnd = new SplittableRandom(seed);
        long[] live = new long[length];
        int liveCount = 0;
        long nextId = 1;
        double mid = 500;
        for (int i = 0; i < length; i++) {
            mid = Math.clamp(mid + rnd.nextGaussian() * 0.3, 50, 950);
            if (liveCount > 0 && rnd.nextInt(100) < 45) {
                int k = rnd.nextInt(liveCount);
                op[i] = CANCEL;
                id[i] = live[k];
                live[k] = live[--liveCount];
            } else {
                op[i] = PLACE;
                boolean b = rnd.nextBoolean();
                bid[i] = b;
                boolean aggressive = rnd.nextInt(100) < 10;
                int offset = (int) Math.abs(rnd.nextGaussian() * 8) + 1;
                int p = (int) mid + (b ? -offset : offset);
                if (aggressive) p = (int) mid + (b ? 5 : -5);
                price[i] = Math.clamp(p, 1, 999);
                qty[i] = 1 + rnd.nextInt(100);
                id[i] = nextId++;
                live[liveCount++] = id[i];
            }
        }
    }
}
