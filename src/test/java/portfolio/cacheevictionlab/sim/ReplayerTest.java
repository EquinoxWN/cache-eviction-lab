package portfolio.cacheevictionlab.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import portfolio.cacheevictionlab.policy.Policy;
import portfolio.cacheevictionlab.trace.Op;

class ReplayerTest {
    /** A source over fixed trace text. */
    static Replayer.Source text(String trace) {
        byte[] bytes = trace.getBytes(StandardCharsets.UTF_8);
        return () -> new ByteArrayInputStream(bytes);
    }

    /** A seeded random Twitter-format trace with every command. */
    static String randomTrace(long seed, int requests, int keys) {
        SplittableRandom r = new SplittableRandom(seed);
        String[] commands = {"get", "get", "get", "get", "gets", "set", "add", "cas", "replace", "incr", "delete"};
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < requests; i++) {
            int key = r.nextBoolean() ? r.nextInt(Math.max(1, keys / 20)) : r.nextInt(keys);
            out.append(i / 100)
                    .append(",k")
                    .append(key)
                    .append(",4,100,1,")
                    .append(commands[r.nextInt(commands.length)])
                    .append(",0\n");
        }
        return out.toString();
    }

    @Test
    void handComputedLruReplay() throws IOException {
        // Capacity 50% of 4 distinct keys = 2 entries.
        String trace = String.join(
                "\n",
                "0,a,1,1,1,get,0", // miss, fill: [a]
                "0,b,1,1,1,get,0", // miss: [a b]
                "0,a,1,1,1,get,0", // hit: [b a]
                "0,c,1,1,1,set,0", // write: [a c]
                "0,b,1,1,1,get,0", // miss: [c b]
                "0,c,1,1,1,get,0", // hit: [b c]
                "0,d,1,1,1,add,0", // write: [c d]
                "0,c,1,1,1,delete,0", // write: [d]
                "0,c,1,1,1,get,0", // miss: [d c]
                "0,d,1,1,1,gets,0"); // hit
        Replayer.Result result = Replayer.replay(
                "t", text(trace), ReplayMode.LOOKASIDE, List.of(Policy.LRU), List.of(new BigDecimal("50")));
        HitRateRow lru = result.rows().get(1);
        assertEquals("2", lru.capacity());
        assertEquals("50%", lru.sizePercent());
        assertEquals(7, lru.reads());
        assertEquals(3, lru.hits());
        HitRateRow infinite = result.rows().get(0);
        assertEquals("infinite", infinite.policy());
        assertEquals(7, infinite.reads());
        assertEquals(4, infinite.hits()); // a b a* b* c* c(deleted, miss) d*: hits a, b, c, d
        assertEquals(4, result.survey().distinctKeys());
        assertEquals(10, result.survey().requests());
        assertEquals(1, result.survey().count(Op.DELETE));
    }

    @Test
    void capacitiesArePercentagesOfDistinctKeysRoundedHalfUpAndAtLeastOne() {
        assertEquals(1, Replayer.capacityFor(10, new BigDecimal("0.1")));
        assertEquals(103, Replayer.capacityFor(102_534, new BigDecimal("0.1")));
        assertEquals(513, Replayer.capacityFor(102_534, new BigDecimal("0.5")));
        assertEquals(1_025, Replayer.capacityFor(102_534, BigDecimal.ONE));
        assertEquals(10_253, Replayer.capacityFor(102_534, BigDecimal.TEN));
        assertEquals(3, Replayer.capacityFor(5, new BigDecimal("50")));
        assertThrows(IllegalArgumentException.class, () -> Replayer.capacityFor(10, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> Replayer.capacityFor(10, new BigDecimal("100.1")));
        assertEquals("0.1%", Replayer.label(new BigDecimal("0.10")));
        assertEquals("10%", Replayer.label(new BigDecimal("1E+1")));
    }

    @Test
    void replayingAllCachesInOnePassEqualsReplayingEachAlone() throws IOException {
        String trace = randomTrace(1, 30_000, 2_000);
        List<BigDecimal> sizes = List.of(new BigDecimal("1"), new BigDecimal("10"));
        List<Policy> all = Arrays.asList(Policy.values());
        Replayer.Result together = Replayer.replay("t", text(trace), ReplayMode.LOOKASIDE, all, sizes);
        int row = 1;
        for (BigDecimal size : sizes) {
            for (Policy p : all) {
                Replayer.Result alone =
                        Replayer.replay("t", text(trace), ReplayMode.LOOKASIDE, List.of(p), List.of(size));
                assertEquals(alone.rows().get(1), together.rows().get(row++), p + " at " + size);
            }
        }
    }

    @Test
    void policyOrderDoesNotChangeAnyResult() throws IOException {
        String trace = randomTrace(2, 20_000, 1_000);
        List<Policy> order = new ArrayList<>(Arrays.asList(Policy.values()));
        List<BigDecimal> sizes = List.of(new BigDecimal("5"));
        Replayer.Result a = Replayer.replay("t", text(trace), ReplayMode.EVERY_REQUEST, order, sizes);
        Collections.reverse(order);
        Replayer.Result b = Replayer.replay("t", text(trace), ReplayMode.EVERY_REQUEST, order, sizes);
        for (HitRateRow row : a.rows()) {
            assertTrue(b.rows().contains(row), row.cell());
        }
    }

    @Test
    void noBoundedPolicyEverBeatsTheNeverEvictingCache() throws IOException {
        for (long seed = 0; seed < 6; seed++) {
            for (ReplayMode mode : ReplayMode.values()) {
                Replayer.Result result = Replayer.replay(
                        "t",
                        text(randomTrace(seed, 15_000, 3_000)),
                        mode,
                        Arrays.asList(Policy.values()),
                        List.of(
                                new BigDecimal("0.5"),
                                new BigDecimal("5"),
                                new BigDecimal("50"),
                                new BigDecimal("100")));
                HitRateRow infinite = result.rows().get(0);
                for (HitRateRow r : result.rows()) {
                    assertEquals(infinite.reads(), r.reads(), r.cell());
                    assertTrue(r.hits() <= infinite.hits(), r.cell() + " " + r.hits() + " > " + infinite.hits());
                }
            }
        }
    }

    @Test
    void aCacheAsLargeAsTheKeySpaceHitsLikeTheNeverEvictingCache() throws IOException {
        // At 100% of the distinct keys no policy ever has to evict or reject a key.
        String trace = randomTrace(9, 20_000, 500);
        Replayer.Result result = Replayer.replay(
                "t", text(trace), ReplayMode.LOOKASIDE, Arrays.asList(Policy.values()), List.of(new BigDecimal("100")));
        long infinite = result.rows().get(0).hits();
        for (HitRateRow r : result.rows()) {
            assertEquals(infinite, r.hits(), r.cell());
        }
    }

    @Test
    void rejectsEmptyOrHugeExperimentGrids() {
        Replayer.Source source = text("0,a,1,1,1,get,0\n");
        List<BigDecimal> one = List.of(BigDecimal.ONE);
        assertThrows(
                IllegalArgumentException.class,
                () -> Replayer.replay("t", source, ReplayMode.LOOKASIDE, List.of(), one));
        List<BigDecimal> many = new ArrayList<>();
        for (int i = 1; i <= 41; i++) {
            many.add(BigDecimal.valueOf(i));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> Replayer.replay("t", source, ReplayMode.LOOKASIDE, Arrays.asList(Policy.values()), many));
    }
}
