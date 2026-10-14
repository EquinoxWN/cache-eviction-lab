package portfolio.cacheevictionlab.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.SplittableRandom;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Every policy against its brute-force model on seeded random operation sequences: the same hit or
 * miss for every get, the same answer for every evict, and the same resident keys after every step.
 */
class ReferenceModelTest {
    private static final int[] CAPACITIES = {1, 2, 3, 4, 7, 16, 50, 150};
    private static final int SEQUENCES_PER_CAPACITY = 25;
    private static final int OPS_PER_SEQUENCE = 3_000;

    @ParameterizedTest
    @EnumSource(Policy.class)
    void matchesTheReferenceModelOnRandomGetPutEvictSequences(Policy policy) {
        long ops = 0;
        for (int capacity : CAPACITIES) {
            for (int seq = 0; seq < SEQUENCES_PER_CAPACITY; seq++) {
                SplittableRandom r = new SplittableRandom(31L * capacity + seq);
                // Key universes from below to well above the capacity, skewed so some keys repeat a lot.
                int universe = 1 + r.nextInt(capacity * 4 + 4);
                Cache real = policy.create(capacity);
                Cache model = ReferenceCaches.of(policy, capacity);
                for (int i = 0; i < OPS_PER_SEQUENCE; i++) {
                    long key = skewedKey(r, universe);
                    int what = r.nextInt(100);
                    String step = policy + " c=" + capacity + " seq=" + seq + " op#" + i + " key=" + key;
                    if (what < 55) {
                        assertEquals(model.get(key), real.get(key), step + " get");
                    } else if (what < 95) {
                        model.put(key);
                        real.put(key);
                    } else {
                        assertEquals(model.evict(key), real.evict(key), step + " evict");
                    }
                    assertEquals(model.size(), real.size(), step + " size");
                    // Full content check after every step for small caches, every 25th step for large ones.
                    if (capacity <= 16 || i % 25 == 0 || i == OPS_PER_SEQUENCE - 1) {
                        for (long k = 0; k < universe; k++) {
                            if (model.contains(k) != real.contains(k)) {
                                assertEquals(model.contains(k), real.contains(k), step + " contains " + k);
                            }
                        }
                    }
                    ops++;
                }
            }
        }
        assertEquals((long) CAPACITIES.length * SEQUENCES_PER_CAPACITY * OPS_PER_SEQUENCE, ops);
    }

    @ParameterizedTest
    @EnumSource(Policy.class)
    void matchesTheReferenceModelOnAReadMissFillWorkload(Policy policy) {
        // The replay pattern: get, and put only after a miss; no deletes, so ARC runs the paper exactly.
        for (int capacity : new int[] {10, 100}) {
            SplittableRandom r = new SplittableRandom(capacity);
            Cache real = policy.create(capacity);
            Cache model = ReferenceCaches.of(policy, capacity);
            long hitsReal = 0;
            long hitsModel = 0;
            for (int i = 0; i < 40_000; i++) {
                long key = skewedKey(r, capacity * 20);
                if (real.get(key)) {
                    hitsReal++;
                } else {
                    real.put(key);
                }
                if (model.get(key)) {
                    hitsModel++;
                } else {
                    model.put(key);
                }
                assertEquals(hitsModel, hitsReal, policy + " c=" + capacity + " request " + i);
            }
            assertEquals(model.size(), real.size());
        }
    }

    /** A key in [0, universe) drawn with a heavy head: half the time from the first tenth. */
    static long skewedKey(SplittableRandom r, int universe) {
        int head = Math.max(1, universe / 10);
        return r.nextBoolean() ? r.nextInt(head) : r.nextInt(universe);
    }
}
