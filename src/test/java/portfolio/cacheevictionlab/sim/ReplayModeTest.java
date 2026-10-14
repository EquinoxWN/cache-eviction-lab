package portfolio.cacheevictionlab.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import portfolio.cacheevictionlab.policy.Cache;
import portfolio.cacheevictionlab.policy.LruCache;
import portfolio.cacheevictionlab.trace.Op;

class ReplayModeTest {
    private static final ReplayMode L = ReplayMode.LOOKASIDE;

    @Test
    void lookasideReadsCountAndFillOnAMiss() {
        Cache c = new LruCache(2);
        assertEquals(0, L.apply(c, Op.READ, 1));
        assertTrue(c.contains(1));
        assertEquals(1, L.apply(c, Op.READ, 1));
    }

    @Test
    void lookasideWritesFollowMemcachedSemanticsAndAreNotCounted() {
        Cache c = new LruCache(3);
        assertEquals(-1, L.apply(c, Op.SET, 1));
        assertTrue(c.contains(1), "set stores");
        assertEquals(-1, L.apply(c, Op.UPDATE, 2));
        assertFalse(c.contains(2), "replace/cas/append/prepend/incr/decr need a cached key");
        assertEquals(-1, L.apply(c, Op.ADD, 2));
        assertTrue(c.contains(2), "add stores an absent key");
        L.apply(c, Op.SET, 3);
        // 1 is the least recent; an update of 1 refreshes it, an add of 1 must not.
        L.apply(c, Op.ADD, 1);
        L.apply(c, Op.SET, 4);
        assertFalse(c.contains(1), "add of a cached key is a no-op, so it did not refresh 1");
        L.apply(c, Op.UPDATE, 2);
        L.apply(c, Op.SET, 5);
        assertTrue(c.contains(2), "an update refreshes the key");
        assertEquals(-1, L.apply(c, Op.DELETE, 2));
        assertFalse(c.contains(2), "delete removes");
    }

    @Test
    void everyRequestModeTreatsEveryCommandAsARead() {
        for (Op op : Op.values()) {
            Cache c = new LruCache(2);
            assertEquals(0, ReplayMode.EVERY_REQUEST.apply(c, op, 9), op.name());
            assertEquals(1, ReplayMode.EVERY_REQUEST.apply(c, op, 9), op.name());
        }
    }

    @Test
    void labelsRoundTrip() {
        for (ReplayMode m : ReplayMode.values()) {
            assertSame(m, ReplayMode.fromLabel(m.label().toUpperCase(java.util.Locale.ROOT)));
        }
        assertThrows(IllegalArgumentException.class, () -> ReplayMode.fromLabel("write-through"));
    }
}
