package portfolio.cacheevictionlab.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResultsTableTest {
    @TempDir
    Path dir;

    private static final List<HitRateRow> ROWS = List.of(
            new HitRateRow("a", "lookaside", "infinite", "-", "-", 1_000, 900),
            new HitRateRow("a", "lookaside", "lru", "1%", "10", 1_000, 333),
            new HitRateRow("b", "lookaside", "lru", "1%", "20", 50, 0));

    @Test
    void writeThenReadGivesTheSameRows() throws IOException {
        Path file = dir.resolve("sub/results.tsv");
        ResultsTable.write(file, ROWS);
        assertEquals(ROWS, ResultsTable.read(file));
        String text = Files.readString(file);
        assertTrue(text.startsWith(ResultsTable.HEADER + "\n"));
        assertTrue(text.contains("\t1000\t333\t33.3000\n"), text);
    }

    @Test
    void compareReportsChangedMissingAndExtraCellsForTheReplayedTracesOnly() {
        assertEquals(List.of(), ResultsTable.compare(ROWS, ROWS, Set.of("a", "b")));
        List<HitRateRow> changed =
                List.of(ROWS.get(0), new HitRateRow("a", "lookaside", "lru", "1%", "10", 1_000, 334));
        List<String> diffs = ResultsTable.compare(ROWS, changed, Set.of("a"));
        assertEquals(1, diffs.size());
        assertTrue(diffs.get(0).contains("expected 333/1000"), diffs.get(0));
        assertEquals(
                1, ResultsTable.compare(ROWS, List.of(ROWS.get(0)), Set.of("a")).size());
        List<HitRateRow> extra =
                List.of(ROWS.get(0), ROWS.get(1), new HitRateRow("a", "lookaside", "arc", "1%", "10", 1_000, 1));
        assertEquals(1, ResultsTable.compare(ROWS, extra, Set.of("a")).size());
        assertEquals(List.of(), ResultsTable.compare(ROWS, ROWS.subList(0, 2), Set.of("a")), "trace b not replayed");
    }

    @Test
    void readRejectsMalformedFiles() throws IOException {
        Path file = dir.resolve("bad.tsv");
        Files.writeString(file, "not a header\n");
        assertThrows(IllegalArgumentException.class, () -> ResultsTable.read(file));
        Files.writeString(file, ResultsTable.HEADER + "\na\tb\tc\n");
        assertThrows(IllegalArgumentException.class, () -> ResultsTable.read(file));
        Files.writeString(file, ResultsTable.HEADER + "\na\tm\tlru\t1%\t1\t10\t11\t110\n");
        assertThrows(IllegalArgumentException.class, () -> ResultsTable.read(file), "hits above reads");
        assertThrows(IllegalArgumentException.class, () -> new HitRateRow("a", "m", "p", "1%", "1", -1, 0));
        assertEquals(0.0, new HitRateRow("a", "m", "p", "1%", "1", 0, 0).hitRate());
    }
}
