package portfolio.cacheevictionlab.trace;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TracePinTest {
    static final String URL =
            "https://raw.githubusercontent.com/twitter/cache-trace/1c50367/samples/2020Mar/cluster045";
    static final String SHA = "a".repeat(64);

    @TempDir
    Path dir;

    /** Lower-case hex SHA-256 of some bytes. */
    static String sha256(byte[] data) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    @Test
    void theCommittedPinsFileIsValid() throws IOException {
        List<TracePin> pins = TracePin.readAll(Path.of("traces/pins.tsv"));
        assertEquals(5, pins.size());
        for (TracePin pin : pins) {
            assertTrue(
                    pin.url().getPath().contains("/1c503676d6972d4394a360e09162149a6c00f516/"), "pinned to a commit");
            assertEquals("CC-BY-4.0", pin.licence());
            assertTrue(pin.bytes() < 64L << 20, "each trace stays under 64 MiB");
        }
    }

    @Test
    void rejectsUnsafeOrInvalidPins() {
        assertThrows(IllegalArgumentException.class, () -> pin("../etc", URL, 10, SHA));
        assertThrows(IllegalArgumentException.class, () -> pin("Upper", URL, 10, SHA));
        assertThrows(IllegalArgumentException.class, () -> pin("a/b", URL, 10, SHA));
        assertThrows(IllegalArgumentException.class, () -> pin("ok", URL.replace("https", "http"), 10, SHA));
        assertThrows(IllegalArgumentException.class, () -> pin("ok", "https://example.com/trace", 10, SHA));
        assertThrows(IllegalArgumentException.class, () -> pin("ok", URL + "?x=1", 10, SHA));
        assertThrows(IllegalArgumentException.class, () -> pin("ok", URL.replace("raw.", "u:p@raw."), 10, SHA));
        assertThrows(IllegalArgumentException.class, () -> pin("ok", URL.replace(".com/", ".com:8443/"), 10, SHA));
        assertThrows(IllegalArgumentException.class, () -> pin("ok", URL, 0, SHA));
        assertThrows(IllegalArgumentException.class, () -> pin("ok", URL, TracePin.MAX_BYTES + 1, SHA));
        assertThrows(IllegalArgumentException.class, () -> pin("ok", URL, 10, SHA.toUpperCase()));
        assertThrows(IllegalArgumentException.class, () -> pin("ok", URL, 10, "abc"));
        assertThrows(IllegalArgumentException.class, () -> new TracePin("ok", URI.create(URL), 10, SHA, " "));
        assertEquals("ok.csv", pin("ok", URL, 10, SHA).fileName());
    }

    @Test
    void pinsFileRejectsBadRowsDuplicatesAndEmptyFiles() throws IOException {
        Path file = dir.resolve("pins.tsv");
        Files.writeString(file, "name\turl\tbytes\tsha256\tlicence\nx\t" + URL + "\t10\t" + SHA + "\n");
        assertThrows(IllegalArgumentException.class, () -> TracePin.readAll(file));
        Files.writeString(file, "x\t" + URL + "\tten\t" + SHA + "\tCC0\n");
        assertThrows(IllegalArgumentException.class, () -> TracePin.readAll(file));
        String row = "x\t" + URL + "\t10\t" + SHA + "\tCC0\n";
        Files.writeString(file, row + row);
        assertThrows(IllegalArgumentException.class, () -> TracePin.readAll(file));
        Files.writeString(file, "# only a comment\n");
        assertThrows(IllegalArgumentException.class, () -> TracePin.readAll(file));
        Files.writeString(file, "# comment\n\n" + row);
        assertEquals(1, TracePin.readAll(file).size());
    }

    @Test
    void verifyAcceptsOnlyThePinnedBytes() throws Exception {
        byte[] data = "0,k,1,1,1,get,0\n".getBytes(StandardCharsets.UTF_8);
        Path file = dir.resolve("t.csv");
        Files.write(file, data);
        TraceFetcher.verify(pin("t", URL, data.length, sha256(data)), file);
        IOException size = assertThrows(
                IOException.class, () -> TraceFetcher.verify(pin("t", URL, data.length + 1, sha256(data)), file));
        assertTrue(size.getMessage().contains("size"));
        IOException digest =
                assertThrows(IOException.class, () -> TraceFetcher.verify(pin("t", URL, data.length, SHA), file));
        assertTrue(digest.getMessage().contains("SHA-256"));
    }

    @Test
    void fetchReusesAMatchingFileAndNeverOverwritesADifferentOne() throws Exception {
        byte[] data = "0,k,1,1,1,get,0\n".getBytes(StandardCharsets.UTF_8);
        Files.write(dir.resolve("t.csv"), data);
        try (TraceFetcher fetcher = new TraceFetcher()) {
            TraceFetcher.Fetched f = fetcher.fetch(pin("t", URL, data.length, sha256(data)), dir);
            assertFalse(f.downloaded(), "no network access when the file already matches");
            assertEquals(dir.resolve("t.csv"), f.path());
            assertThrows(IOException.class, () -> fetcher.fetch(pin("t", URL, data.length, SHA), dir));
        }
        assertArrayEquals(data, Files.readAllBytes(dir.resolve("t.csv")), "a mismatching file is left untouched");
    }

    /** A pin with a fixed licence. */
    private static TracePin pin(String name, String url, long bytes, String sha) {
        return new TracePin(name, URI.create(url), bytes, sha, "CC-BY-4.0");
    }
}
