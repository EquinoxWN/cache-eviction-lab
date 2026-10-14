package portfolio.cacheevictionlab.sim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Reads, writes and compares hit-rate tables as tab-separated text. */
public final class ResultsTable {
    /** Header line of every results file. */
    public static final String HEADER = "trace\tmode\tpolicy\tsize_pct\tcapacity\treads\thits\thit_rate_pct";
    /** Largest results file accepted, in lines. */
    static final int MAX_LINES = 100_000;

    private ResultsTable() {}

    /** Formats rows with the header; hit rates as percentages with four decimals. */
    public static String format(List<HitRateRow> rows) {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (HitRateRow r : rows) {
            out.append(String.join(
                            "\t",
                            r.trace(),
                            r.mode(),
                            r.policy(),
                            r.sizePercent(),
                            r.capacity(),
                            Long.toString(r.reads()),
                            Long.toString(r.hits()),
                            String.format(Locale.ROOT, "%.4f", 100.0 * r.hitRate())))
                    .append('\n');
        }
        return out.toString();
    }

    /** Writes rows to a file, replacing it. */
    public static void write(Path file, List<HitRateRow> rows) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, format(rows), StandardCharsets.UTF_8);
    }

    /** Parses a results file written by {@link #write}. */
    public static List<HitRateRow> read(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).equals(HEADER)) {
            throw new IllegalArgumentException(file + ": first line must be the results header");
        }
        if (lines.size() > MAX_LINES) {
            throw new IllegalArgumentException(file + ": more than " + MAX_LINES + " lines");
        }
        List<HitRateRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            String[] f = lines.get(i).split("\t", -1);
            if (f.length != 8) {
                throw new IllegalArgumentException(file + ":" + (i + 1) + ": expected 8 tab-separated fields");
            }
            try {
                rows.add(new HitRateRow(f[0], f[1], f[2], f[3], f[4], Long.parseLong(f[5]), Long.parseLong(f[6])));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(file + ":" + (i + 1) + ": " + e.getMessage(), e);
            }
        }
        return rows;
    }

    /**
     * Differences between expected and actual rows for the traces in {@code traces}: missing or extra
     * cells, and cells whose capacity, reads or hits differ. Empty means an exact match.
     */
    public static List<String> compare(List<HitRateRow> expected, List<HitRateRow> actual, Set<String> traces) {
        Map<String, HitRateRow> want = new LinkedHashMap<>();
        for (HitRateRow r : expected) {
            if (traces.contains(r.trace())) {
                want.put(r.cell(), r);
            }
        }
        List<String> diffs = new ArrayList<>();
        for (HitRateRow r : actual) {
            HitRateRow w = want.remove(r.cell());
            if (w == null) {
                diffs.add(r.cell() + ": not in the expected results");
            } else if (!w.capacity().equals(r.capacity()) || w.reads() != r.reads() || w.hits() != r.hits()) {
                diffs.add(r.cell() + ": expected " + w.hits() + "/" + w.reads() + " at capacity " + w.capacity()
                        + ", got " + r.hits() + "/" + r.reads() + " at capacity " + r.capacity());
            }
        }
        for (String cell : want.keySet()) {
            diffs.add(cell + ": expected but not replayed");
        }
        return diffs;
    }
}
