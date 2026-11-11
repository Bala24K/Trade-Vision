package io.tradevision.engine;

import io.tradevision.engine.book.PriceBitSet;
import io.tradevision.engine.util.LongObjectMap;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Randomized differential tests: custom structures must agree with the JDK reference on every operation. */
class DataStructureTest {

    @RepeatedTest(5)
    void priceBitSetMatchesTreeSet(RepetitionInfo info) {
        var rnd = new SplittableRandom(info.getCurrentRepetition());
        var bits = new PriceBitSet();
        var ref = new TreeSet<Integer>();
        for (int i = 0; i < 50_000; i++) {
            int p = rnd.nextInt(1, 1000);
            if (rnd.nextBoolean()) { bits.set(p); ref.add(p); }
            else { bits.clear(p); ref.remove(p); }
            int q = rnd.nextInt(-5, 1100);
            Integer floor = q < 0 ? null : ref.floor(q);
            Integer ceil = q > 1023 ? null : ref.ceiling(q);
            assertEquals(floor == null ? -1 : floor, bits.highestAtOrBelow(q), "floor " + q);
            assertEquals(ceil == null ? -1 : ceil, bits.lowestAtOrAbove(q), "ceiling " + q);
            assertEquals(ref.size(), bits.cardinality());
        }
    }

    @RepeatedTest(5)
    void longObjectMapMatchesHashMap(RepetitionInfo info) {
        var rnd = new SplittableRandom(info.getCurrentRepetition());
        var map = new LongObjectMap<Long>(4);
        var ref = new HashMap<Long, Long>();
        for (int i = 0; i < 200_000; i++) {
            // Narrow key range forces collisions, deletions mid-chain and wrap-around.
            long k = rnd.nextLong(1, 2_000);
            switch (rnd.nextInt(3)) {
                case 0 -> assertEquals(ref.put(k, (long) i), map.put(k, (long) i));
                case 1 -> assertEquals(ref.remove(k), map.remove(k));
                default -> assertEquals(ref.get(k), map.get(k));
            }
            assertEquals(ref.size(), map.size());
        }
        for (Map.Entry<Long, Long> e : ref.entrySet()) assertEquals(e.getValue(), map.get(e.getKey()));
    }
}
