package portfolio.cacheevictionlab.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The command-line tool end to end on a generated Twitter-format trace, as CI and `make bench` run it. */
class EndToEndTest {
    @TempDir
    Path dir;

    private Path trace;

    /** Output of one run. */
    private record Run(int code, String out, String err) {}

    /** Runs the CLI in-process. */
    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(
                List.of(args),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void writeTrace() throws IOException {
        SplittableRandom r = new SplittableRandom(42);
        StringBuilder text = new StringBuilder();
        String[] commands = {"get", "get", "get", "get", "get", "gets", "set", "add", "cas", "delete"};
        for (int i = 0; i < 60_000; i++) {
            int key = r.nextInt(4) == 0 ? r.nextInt(5_000) : r.nextInt(200);
            text.append(i / 1_000).append(",ns:").append(key).append(",8,120,3,");
            text.append(commands[r.nextInt(commands.length)]).append(",0\n");
        }
        text.append("this line is malformed\n");
        trace = dir.resolve("synthetic.csv");
        Files.writeString(trace, text.toString());
    }

    @Test
    void replayPrintsTheMatrixWritesTheTableAndReproducesIt() throws IOException {
        Path table = dir.resolve("out/results.tsv");
        Run first = run("replay", "--trace", trace.toString(), "--out", table.toString());
        assertEquals(0, first.code(), first.err());
        assertTrue(first.out().contains("synthetic: 60,000 requests"), first.out());
        assertTrue(first.out().contains("1 malformed and 0 oversized lines skipped"), first.out());
        assertTrue(first.out().contains("RESULT traces=1 cells=25 above_infinite=0 expected=not-checked"), first.out());
        assertEquals(1 + 1 + 25, Files.readAllLines(table).size(), "header, infinite row, 5 policies x 5 sizes");
        Run second = run("replay", "--trace", trace.toString(), "--expect", table.toString());
        assertEquals(0, second.code(), second.err());
        assertTrue(second.out().contains("expected=match"), second.out());
    }

    @Test
    void aDifferentExpectedTableFailsWithExitThree() throws IOException {
        Path table = dir.resolve("results.tsv");
        assertEquals(
                0,
                run("replay", "--trace", trace.toString(), "--out", table.toString())
                        .code());
        List<String> lines = Files.readAllLines(table);
        String[] f = lines.get(2).split("\t");
        f[6] = Long.toString(Long.parseLong(f[6]) - 1);
        lines.set(2, String.join("\t", f));
        Files.write(table, lines);
        Run run = run("replay", "--trace", trace.toString(), "--expect", table.toString());
        assertEquals(3, run.code());
        assertTrue(run.out().contains("expected=mismatch(1)"), run.out());
        assertTrue(run.err().contains("differs: synthetic/lookaside/"), run.err());
    }

    @Test
    void policiesSizesAndModeOptionsSelectTheGrid() {
        Run run = run(
                "replay",
                "--trace",
                trace.toString(),
                "--name",
                "x",
                "--policies",
                "lru,ARC",
                "--sizes",
                "1,2.5",
                "--mode",
                "every-request");
        assertEquals(0, run.code(), run.err());
        assertTrue(run.out().contains("x: 60,000 requests"), run.out());
        assertTrue(run.out().contains("mode every-request: 60,000 counted reads"), run.out());
        assertTrue(run.out().contains("RESULT traces=1 cells=4 "), run.out());
        assertTrue(run.out().contains("2.5%"), run.out());
    }

    @Test
    void benchChecksEachTraceAgainstItsPinBeforeReplaying() throws Exception {
        Path data = dir.resolve("data");
        Files.createDirectories(data);
        Path copy = data.resolve("tiny.csv");
        Files.copy(trace, copy);
        byte[] bytes = Files.readAllBytes(copy);
        String sha =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Path pins = dir.resolve("pins.tsv");
        Files.writeString(
                pins,
                "name\turl\tbytes\tsha256\tlicence\ntiny\thttps://raw.githubusercontent.com/o/r/abc/tiny\t"
                        + bytes.length + "\t" + sha + "\tCC0\n");
        Run ok = run("bench", "--pins", pins.toString(), "--dir", data.toString(), "--sizes", "1");
        assertEquals(0, ok.code(), ok.err());
        assertTrue(ok.out().contains("RESULT traces=1 cells=5 above_infinite=0"), ok.out());
        Run fetch = run("fetch", "--pins", pins.toString(), "--dir", data.toString());
        assertEquals(0, fetch.code(), fetch.err());
        assertTrue(fetch.out().contains("sha256 ok  already on disk"), fetch.out());
        bytes[10] ^= 1;
        Files.write(copy, bytes);
        Run tampered = run("bench", "--pins", pins.toString(), "--dir", data.toString());
        assertEquals(1, tampered.code());
        assertTrue(tampered.err().contains("SHA-256"), tampered.err());
        Run missing = run(
                "bench",
                "--pins",
                pins.toString(),
                "--dir",
                dir.resolve("nowhere").toString());
        assertEquals(1, missing.code());
        assertTrue(missing.err().contains("no such file"), missing.err());
    }

    @Test
    void usageErrorsExitWithTwoAndFileErrorsWithOne() {
        String t = trace.toString();
        assertEquals(2, run().code());
        assertEquals(2, run("explode").code());
        assertEquals(2, run("replay").code(), "--trace is required");
        assertEquals(2, run("replay", "--trace", t, "--bogus", "1").code());
        assertEquals(2, run("replay", "--trace", t, "--trace", t).code());
        assertEquals(2, run("replay", "--trace", t, "--policies", "lru,mru").code());
        assertEquals(2, run("replay", "--trace", t, "--policies", "lru,lru").code());
        assertEquals(2, run("replay", "--trace", t, "--sizes", "0").code());
        assertEquals(2, run("replay", "--trace", t, "--sizes", "101").code());
        assertEquals(2, run("replay", "--trace", t, "--sizes", "1,,2").code());
        assertEquals(2, run("replay", "--trace", t, "--mode", "write-back").code());
        assertEquals(2, run("replay", "stray").code());
        assertEquals(
                2, run("bench", "--only", "nope", "--pins", "traces/pins.tsv").code());
        assertEquals(
                1,
                run("replay", "--trace", dir.resolve("absent.csv").toString()).code());
        assertEquals(0, run("help").code());
    }
}
