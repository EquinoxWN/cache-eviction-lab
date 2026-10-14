package portfolio.cacheevictionlab.policy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import portfolio.cacheevictionlab.sketch.CountMinSketch;

/** The behaviour that makes each policy what it is, on small hand-checked sequences. */
class PolicyBehaviourTest {
    /** Requests every key in order with get, filling on a miss; returns the misses. */
    private static int misses(Cache c, long... keys) {
        int misses = 0;
        for (long k : keys) {
            if (!c.get(k)) {
                misses++;
                c.put(k);
            }
        }
        return misses;
    }

    @Test
    void fifoIgnoresHitsAndShowsBeladysAnomaly() {
        Cache fifo = new FifoCache(2);
        misses(fifo, 1, 2, 1);
        fifo.put(3);
        assertFalse(fifo.contains(1), "FIFO evicts the oldest insert even though it was just hit");
        // The classic sequence: FIFO misses more with 4 slots than with 3 (Belady, Nelson and Shedler, 1969).
        long[] seq = {1, 2, 3, 4, 1, 2, 5, 1, 2, 3, 4, 5};
        assertEquals(9, misses(new FifoCache(3), seq));
        assertEquals(10, misses(new FifoCache(4), seq));
        assertEquals(10, misses(new LruCache(3), seq));
        assertEquals(8, misses(new LruCache(4), seq));
    }

    @Test
    void lruEvictsTheLeastRecentlyUsedKey() {
        Cache lru = new LruCache(3);
        misses(lru, 1, 2, 3, 1);
        lru.put(4);
        assertFalse(lru.contains(2));
        assertTrue(lru.contains(1) && lru.contains(3) && lru.contains(4));
    }

    @Test
    void lruHitsNeverFallWhenTheCacheGrows() {
        // LRU is a stack algorithm (Mattson et al., 1970): a larger cache always holds a superset.
        SplittableRandom r = new SplittableRandom(7);
        long[] trace = new long[20_000];
        for (int i = 0; i < trace.length; i++) {
            trace[i] = ReferenceModelTest.skewedKey(r, 500);
        }
        int previous = Integer.MAX_VALUE;
        for (int capacity = 1; capacity <= 120; capacity++) {
            int m = misses(new LruCache(capacity), trace);
            assertTrue(m <= previous, "capacity " + capacity + ": " + m + " misses > " + previous);
            previous = m;
        }
    }

    @Test
    void lfuKeepsTheMostUsedKeyAndBreaksTiesByRecency() {
        LfuCache lfu = new LfuCache(3);
        misses(lfu, 1, 1, 1, 2, 2, 3);
        assertEquals(3, lfu.frequency(1));
        assertEquals(2, lfu.frequency(2));
        lfu.put(4); // evicts 3, the only key used once
        assertFalse(lfu.contains(3));
        misses(lfu, 5); // 4 is the only key used once, so it goes before 1 and 2
        assertFalse(lfu.contains(4));
        assertTrue(lfu.contains(1) && lfu.contains(2) && lfu.contains(5));
        LfuCache tie = new LfuCache(2);
        misses(tie, 1, 2, 2, 1);
        tie.put(3); // 1 and 2 both used twice; 2 was used less recently
        assertFalse(tie.contains(2));
        assertTrue(tie.contains(1));
        assertEquals(0, tie.frequency(2));
    }

    @Test
    void arcKeepsKeysSeenTwiceThroughAOneTimeScan() {
        ArcCache arc = new ArcCache(10);
        LruCache lru = new LruCache(10);
        for (long k = 0; k < 5; k++) {
            misses(arc, k, k);
            misses(lru, k, k);
        }
        for (long k = 100; k < 200; k++) {
            misses(arc, k);
            misses(lru, k);
        }
        for (long k = 0; k < 5; k++) {
            assertTrue(arc.contains(k), "ARC lost hot key " + k + " to the scan");
            assertFalse(lru.contains(k), "LRU keeps no hot key through a scan ten times its size");
        }
    }

    @Test
    void arcMovesItsTargetTowardsTheGhostListThatWasHit() {
        ArcCache arc = new ArcCache(4);
        // 1 is seen twice (T2); 2, 3, 4 once (T1); 5 forces REPLACE, which moves 2 from T1 to ghost list B1.
        misses(arc, 1, 1, 2, 3, 4, 5);
        assertArrayEquals(new int[] {3, 1, 1, 0}, arc.listSizes());
        assertEquals(0.0, arc.target());
        assertFalse(arc.contains(2));
        // A B1 hit: p grows by 1, REPLACE moves 3 to B1, and 2 comes back into T2.
        misses(arc, 2);
        assertEquals(1.0, arc.target());
        assertTrue(arc.contains(2));
        assertFalse(arc.contains(3));
        assertArrayEquals(new int[] {2, 2, 1, 0}, arc.listSizes());
        // With T1 full and nothing else tracked, a new key drops T1's oldest without a ghost.
        ArcCache full = new ArcCache(2);
        misses(full, 1, 2, 3);
        assertArrayEquals(new int[] {2, 0, 0, 0}, full.listSizes());
    }

    @Test
    void arcKeepsItsListInvariantsUnderRandomOperations() {
        for (int capacity : new int[] {1, 3, 8, 40}) {
            ArcCache arc = new ArcCache(capacity);
            SplittableRandom r = new SplittableRandom(capacity);
            for (int i = 0; i < 50_000; i++) {
                long key = ReferenceModelTest.skewedKey(r, capacity * 5);
                int what = r.nextInt(20);
                if (what == 0) {
                    arc.evict(key);
                } else if (!arc.get(key)) {
                    arc.put(key);
                }
                int[] s = arc.listSizes();
                assertTrue(s[0] + s[1] <= capacity, "resident " + (s[0] + s[1]));
                assertTrue(s[0] + s[2] <= capacity, "|T1| + |B1| = " + (s[0] + s[2]));
                assertTrue(s[0] + s[1] + s[2] + s[3] <= 2 * capacity, "tracked keys above 2c");
                assertTrue(arc.target() >= 0 && arc.target() <= capacity, "p = " + arc.target());
            }
        }
    }

    @Test
    void wTinyLfuRejectsOneHitWondersThatWouldDisplaceFrequentKeys() {
        WTinyLfuCache w = new WTinyLfuCache(200);
        LruCache lru = new LruCache(200);
        for (int round = 0; round < 5; round++) {
            for (long k = 0; k < 150; k++) {
                misses(w, k);
                misses(lru, k);
            }
        }
        for (long k = 10_000; k < 12_000; k++) {
            misses(w, k);
            misses(lru, k);
        }
        int kept = 0;
        for (long k = 0; k < 150; k++) {
            if (w.contains(k)) {
                kept++;
            }
            assertFalse(lru.contains(k));
        }
        // A new key whose four sketch counters all collide with frequent keys is over-counted and can
        // win admission; in this fixed sequence that happens to 2 of the 2,000 new keys.
        assertTrue(kept >= 145, kept + " of 150 frequent keys survived a scan of 2,000 new keys");
        assertTrue(w.frequency(5) >= w.frequency(10_500));
    }

    @Test
    void wTinyLfuSegmentsStayWithinTheirShares() {
        WTinyLfuCache w = new WTinyLfuCache(1_000);
        assertEquals(10, WTinyLfuCache.windowCapacity(1_000));
        assertEquals(1, WTinyLfuCache.windowCapacity(99));
        assertEquals(64, WTinyLfuCache.sketchWidth(1));
        assertEquals(4_000, WTinyLfuCache.sketchWidth(1_000));
        assertEquals(
                CountMinSketch.MAX_COUNTERS / WTinyLfuCache.SKETCH_DEPTH,
                WTinyLfuCache.sketchWidth(Cache.MAX_CAPACITY));
        SplittableRandom r = new SplittableRandom(3);
        for (int i = 0; i < 100_000; i++) {
            long key = ReferenceModelTest.skewedKey(r, 5_000);
            if (!w.get(key)) {
                w.put(key);
            }
            int[] s = w.segmentSizes();
            assertTrue(s[0] <= 10, "window " + s[0]);
            assertTrue(s[2] <= (int) ((1_000 - 10) * WTinyLfuCache.PROTECTED_SHARE), "protected " + s[2]);
            assertEquals(w.size(), s[0] + s[1] + s[2]);
        }
        assertEquals(1_000, w.size());
    }

    @Test
    void wTinyLfuWithOneSlotStillAdmitsNewKeys() {
        WTinyLfuCache w = new WTinyLfuCache(1);
        w.put(1);
        w.put(2);
        assertTrue(w.contains(2));
        assertFalse(w.contains(1));
        assertEquals(1, w.size());
    }
}
