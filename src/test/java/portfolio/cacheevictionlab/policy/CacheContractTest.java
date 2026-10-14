package portfolio.cacheevictionlab.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The Cache contract every policy must keep, checked the same way for each of them. */
class CacheContractTest {
    @ParameterizedTest
    @EnumSource(Policy.class)
    void rejectsCapacityOutsideTheAllowedRange(Policy policy) {
        assertThrows(IllegalArgumentException.class, () -> policy.create(0));
        assertThrows(IllegalArgumentException.class, () -> policy.create(-1));
        assertThrows(IllegalArgumentException.class, () -> policy.create(Cache.MAX_CAPACITY + 1));
        Cache c = policy.create(3);
        assertEquals(3, c.capacity());
        assertEquals(0, c.size());
        assertEquals(policy.label(), c.name());
        assertSame(policy, Policy.fromLabel(policy.label().toUpperCase(java.util.Locale.ROOT)));
    }

    @ParameterizedTest
    @EnumSource(Policy.class)
    void aMissChangesNothingAndAPutKeyIsThenAHit(Policy policy) {
        Cache c = policy.create(4);
        assertFalse(c.get(7));
        assertFalse(c.contains(7));
        assertEquals(0, c.size());
        c.put(7);
        assertTrue(c.contains(7));
        assertTrue(c.get(7));
        c.put(7);
        assertEquals(1, c.size(), "a second put of a resident key must not duplicate it");
    }

    @ParameterizedTest
    @EnumSource(Policy.class)
    void untilFullEveryPutIsKept(Policy policy) {
        Cache c = policy.create(100);
        for (long k = 0; k < 100; k++) {
            c.put(k);
        }
        assertEquals(100, c.size());
        for (long k = 0; k < 100; k++) {
            assertTrue(c.contains(k), "key " + k);
        }
    }

    @ParameterizedTest
    @EnumSource(Policy.class)
    void evictRemovesExactlyThatKey(Policy policy) {
        Cache c = policy.create(5);
        for (long k = 1; k <= 5; k++) {
            c.put(k);
        }
        assertTrue(c.evict(3));
        assertFalse(c.contains(3));
        assertFalse(c.evict(3));
        assertFalse(c.evict(99));
        assertEquals(4, c.size());
        for (long k : new long[] {1, 2, 4, 5}) {
            assertTrue(c.contains(k), "key " + k);
        }
        c.put(3);
        assertTrue(c.contains(3), "a freed slot is reused without evicting another key");
        assertEquals(5, c.size());
    }

    @ParameterizedTest
    @EnumSource(Policy.class)
    void sizeNeverExceedsCapacityAndContainsHasNoSideEffects(Policy policy) {
        for (int capacity : new int[] {1, 2, 9, 64}) {
            SplittableRandom r = new SplittableRandom(capacity);
            Cache probed = policy.create(capacity);
            Cache plain = policy.create(capacity);
            for (int i = 0; i < 20_000; i++) {
                long key = r.nextInt(capacity * 3 + 1);
                int what = r.nextInt(10);
                if (what < 5) {
                    assertEquals(plain.get(key), probed.get(key));
                } else if (what < 9) {
                    plain.put(key);
                    probed.put(key);
                } else {
                    assertEquals(plain.evict(key), probed.evict(key));
                }
                probed.contains(r.nextInt(capacity * 3 + 1));
                assertTrue(probed.size() <= capacity, "size " + probed.size() + " > capacity " + capacity);
                assertEquals(plain.size(), probed.size());
            }
            for (long k = 0; k <= capacity * 3L; k++) {
                assertEquals(plain.contains(k), probed.contains(k), "key " + k);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Policy.class)
    void extremeKeysAreOrdinaryKeys(Policy policy) {
        Cache c = policy.create(3);
        for (long k : new long[] {Long.MIN_VALUE, -1, 0, Long.MAX_VALUE}) {
            c.put(k);
            assertTrue(c.contains(k), "key " + k);
        }
        assertEquals(3, c.size());
    }
}
