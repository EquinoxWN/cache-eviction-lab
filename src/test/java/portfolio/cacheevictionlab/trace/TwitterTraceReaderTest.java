package portfolio.cacheevictionlab.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import portfolio.cacheevictionlab.sketch.Murmur3;

class TwitterTraceReaderTest {
    /** Reads every valid request of a text trace. */
    static List<long[]> readAll(TwitterTraceReader reader) throws IOException {
        List<long[]> out = new ArrayList<>();
        TwitterTraceReader.Request r = new TwitterTraceReader.Request();
        while (reader.next(r)) {
            out.add(new long[] {r.op().ordinal(), r.key()});
        }
        return out;
    }

    /** A reader over UTF-8 text. */
    static TwitterTraceReader reader(String text) {
        return new TwitterTraceReader(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** The key hash the reader should produce. */
    static long hash(String key) {
        return Murmur3.hash128(key.getBytes(StandardCharsets.UTF_8)).h1();
    }

    @Test
    void parsesTheTwitterFormatAndHashesTheKey() throws IOException {
        TwitterTraceReader r =
                reader("0,q:q:1:8WTwl7huJeQ,17,249,1,get,0\n" + "3,nz:u:eeW511W3dcH3de3d15ec,24,0,2,set,600\n");
        List<long[]> requests = readAll(r);
        assertEquals(2, requests.size());
        assertEquals(Op.READ.ordinal(), requests.get(0)[0]);
        assertEquals(hash("q:q:1:8WTwl7huJeQ"), requests.get(0)[1]);
        assertEquals(Op.SET.ordinal(), requests.get(1)[0]);
        assertEquals(hash("nz:u:eeW511W3dcH3de3d15ec"), requests.get(1)[1]);
        assertEquals(2, r.lines());
        assertEquals(0, r.malformed());
    }

    @Test
    void mapsEveryTwemcacheCommandToItsGroup() {
        Map<String, Op> expected = Map.ofEntries(
                Map.entry("get", Op.READ),
                Map.entry("gets", Op.READ),
                Map.entry("set", Op.SET),
                Map.entry("add", Op.ADD),
                Map.entry("replace", Op.UPDATE),
                Map.entry("cas", Op.UPDATE),
                Map.entry("append", Op.UPDATE),
                Map.entry("prepend", Op.UPDATE),
                Map.entry("incr", Op.UPDATE),
                Map.entry("decr", Op.UPDATE),
                Map.entry("delete", Op.DELETE));
        expected.forEach((command, op) -> {
            byte[] b = command.getBytes(StandardCharsets.US_ASCII);
            assertEquals(op, Op.fromCommand(b, 0, b.length), command);
        });
        for (String bad : new String[] {"GET", "fetch", "", "deletes", "get ", "touch"}) {
            byte[] b = bad.getBytes(StandardCharsets.US_ASCII);
            assertNull(Op.fromCommand(b, 0, b.length), "'" + bad + "'");
        }
    }

    @Test
    void aCommaInsideTheKeyDoesNotShiftTheOtherFields() throws IOException {
        List<long[]> requests = readAll(reader("5,a,b,c,3,10,7,delete,0\n"));
        assertEquals(1, requests.size());
        assertEquals(Op.DELETE.ordinal(), requests.get(0)[0]);
        assertEquals(hash("a,b,c"), requests.get(0)[1]);
    }

    @Test
    void malformedLinesAreSkippedAndCounted() throws IOException {
        String text = String.join(
                "\n",
                "1,k1,2,3,4,get,0",
                "1,k2,2,3,4,get", // six fields
                "x,k3,2,3,4,get,0", // non-numeric timestamp
                "1,,2,3,4,get,0", // empty key
                "1,k4,2,3,4,GET,0", // unknown command
                "1,k5,2,-3,4,get,0", // negative size
                "1,k6,2,3,4,get,", // empty ttl
                "1,k7,2,3,4,get,12345678901234567890", // 20 digits
                "",
                "1,k8,2,3,4,gets,0");
        TwitterTraceReader r = reader(text);
        List<long[]> requests = readAll(r);
        assertEquals(2, requests.size());
        assertEquals(hash("k1"), requests.get(0)[1]);
        assertEquals(hash("k8"), requests.get(1)[1]);
        assertEquals(10, r.lines());
        assertEquals(8, r.malformed());
        assertEquals(0, r.oversized());
    }

    @Test
    void oversizedLinesAreSkippedWithoutLosingTheNextLine() throws IOException {
        String longKey = "k".repeat(TwitterTraceReader.MAX_LINE_BYTES);
        TwitterTraceReader r = reader("1," + longKey + ",2,3,4,get,0\n1,short,2,3,4,get,0\n");
        List<long[]> requests = readAll(r);
        assertEquals(1, requests.size());
        assertEquals(hash("short"), requests.get(0)[1]);
        assertEquals(1, r.oversized());
        String exact = "1," + "k".repeat(TwitterTraceReader.MAX_LINE_BYTES - 14) + ",2,3,4,get,0";
        assertEquals(TwitterTraceReader.MAX_LINE_BYTES, exact.length());
        assertEquals(1, readAll(reader(exact)).size(), "a line of exactly the limit is accepted");
    }

    @Test
    void handlesCrlfAndAMissingFinalNewline() throws IOException {
        List<long[]> requests = readAll(reader("1,a,1,1,1,get,0\r\n2,b,1,1,1,add,0"));
        assertEquals(2, requests.size());
        assertEquals(hash("a"), requests.get(0)[1]);
        assertEquals(Op.ADD.ordinal(), requests.get(1)[0]);
        assertFalse(reader("").next(new TwitterTraceReader.Request()));
    }

    @Test
    void sameKeySameHashDifferentKeysDifferentHashes() throws IOException {
        List<long[]> requests = readAll(reader("1,user:1,1,1,1,get,0\n2,user:2,1,1,1,get,0\n3,user:1,1,1,1,get,0\n"));
        assertEquals(requests.get(0)[1], requests.get(2)[1]);
        assertNotEquals(requests.get(0)[1], requests.get(1)[1]);
    }

    @Test
    void readsAcrossBufferBoundaries() throws IOException {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            text.append(i).append(",key-").append(i % 777).append(",5,10,1,get,0\n");
        }
        assertTrue(text.length() > 1 << 17);
        TwitterTraceReader r = reader(text.toString());
        List<long[]> requests = readAll(r);
        assertEquals(20_000, requests.size());
        assertEquals(hash("key-" + (19_999 % 777)), requests.get(19_999)[1]);
        assertEquals(0, r.malformed());
    }
}
