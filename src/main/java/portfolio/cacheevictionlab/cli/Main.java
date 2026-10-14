package portfolio.cacheevictionlab.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import portfolio.cacheevictionlab.policy.Policy;
import portfolio.cacheevictionlab.sim.HitRateRow;
import portfolio.cacheevictionlab.sim.ReplayMode;
import portfolio.cacheevictionlab.sim.Replayer;
import portfolio.cacheevictionlab.sim.ResultsTable;
import portfolio.cacheevictionlab.trace.Op;
import portfolio.cacheevictionlab.trace.TraceFetcher;
import portfolio.cacheevictionlab.trace.TracePin;

/** Command-line entry point: {@code fetch}, {@code replay} and {@code bench}. */
public final class Main {
    /** Exit code for success. */
    static final int OK = 0;
    /** Exit code for an input, output or network error. */
    static final int IO_ERROR = 1;
    /** Exit code for bad command-line arguments. */
    static final int USAGE = 2;
    /** Exit code when results break an invariant or differ from the expected file. */
    static final int CHECK_FAILED = 3;

    static final String DEFAULT_PINS = "traces/pins.tsv";
    static final String DEFAULT_DIR = "data";
    static final String DEFAULT_SIZES = "0.1,0.5,1,5,10";

    static final String USAGE_TEXT = """
            usage:
              lab fetch [--pins FILE] [--dir DIR] [--only NAME]
                  download the pinned traces (HTTPS, no redirects) and check size and SHA-256;
                  a trace already on disk is re-hashed, never overwritten
              lab replay --trace FILE [--name NAME] [options]
                  replay one Twitter-format trace file
              lab bench [--pins FILE] [--dir DIR] [--only NAME] [options]
                  check every pinned trace on disk against its pin, then replay it
            options:
              --policies LIST   comma-separated, from fifo,lru,lfu,arc,w-tinylfu (default: all)
              --sizes LIST      cache sizes in percent of the trace's distinct keys (default: 0.1,0.5,1,5,10)
              --mode MODE       lookaside (default) or every-request
              --out FILE        write the hit-rate table as tab-separated text
              --expect FILE     compare with a saved table; exit 3 on any difference
            exit codes: 0 ok, 1 input/output error, 2 usage error, 3 check failed
            """;

    private Main() {}

    /** Runs the command and exits with its status. */
    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        System.exit(run(Arrays.asList(args), out, err));
    }

    /** Runs one command and returns the exit code (testable without exiting the JVM). */
    static int run(List<String> args, PrintStream out, PrintStream err) {
        if (args.isEmpty()) {
            err.print(USAGE_TEXT);
            return USAGE;
        }
        List<String> rest = args.subList(1, args.size());
        try {
            return switch (args.get(0)) {
                case "fetch" -> fetch(rest, out);
                case "replay" -> replay(rest, out, err);
                case "bench" -> bench(rest, out, err);
                case "help", "--help", "-h" -> {
                    out.print(USAGE_TEXT);
                    yield OK;
                }
                default -> throw new Args.UsageException("unknown command '" + args.get(0) + "'");
            };
        } catch (Args.UsageException e) {
            err.println("error: " + e.getMessage());
            err.print(USAGE_TEXT);
            return USAGE;
        } catch (NoSuchFileException e) {
            err.println("error: no such file: " + e.getFile());
            return IO_ERROR;
        } catch (IOException | IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            return IO_ERROR;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("error: interrupted");
            return IO_ERROR;
        }
    }

    /** {@code fetch}: downloads or re-checks the pinned traces. */
    private static int fetch(List<String> rest, PrintStream out) throws IOException, InterruptedException {
        Args a = new Args(rest, Set.of("pins", "dir", "only"));
        List<TracePin> pins = selected(a);
        Path dir = Path.of(a.option("dir", DEFAULT_DIR));
        try (TraceFetcher fetcher = new TraceFetcher()) {
            for (TracePin pin : pins) {
                TraceFetcher.Fetched f = fetcher.fetch(pin, dir);
                out.printf(
                        Locale.ROOT,
                        "%-12s %,13d bytes  sha256 ok  %s  %s%n",
                        pin.name(),
                        pin.bytes(),
                        f.downloaded() ? "downloaded" : "already on disk",
                        f.path());
            }
        }
        return OK;
    }

    /** {@code replay}: one trace file, no pin. */
    private static int replay(List<String> rest, PrintStream out, PrintStream err) throws IOException {
        Args a = new Args(rest, Set.of("trace", "name", "policies", "sizes", "mode", "out", "expect"));
        Path file = Path.of(a.required("trace"));
        String name = a.option("name", traceName(file));
        Settings s = settings(a);
        long start = System.nanoTime();
        Replayer.Result result = Replayer.replay(name, file, s.mode, s.policies, s.sizes);
        print(result, s, (System.nanoTime() - start) / 1e9, out);
        return finish(List.of(result), a, out, err);
    }

    /** {@code bench}: every pinned trace on disk, each checked against its pin first. */
    private static int bench(List<String> rest, PrintStream out, PrintStream err) throws IOException {
        Args a = new Args(rest, Set.of("pins", "dir", "only", "policies", "sizes", "mode", "out", "expect"));
        List<TracePin> pins = selected(a);
        Path dir = Path.of(a.option("dir", DEFAULT_DIR));
        Settings s = settings(a);
        List<Replayer.Result> results = new ArrayList<>();
        for (TracePin pin : pins) {
            Path file = dir.resolve(pin.fileName());
            TraceFetcher.verify(pin, file);
            long start = System.nanoTime();
            Replayer.Result result = Replayer.replay(pin.name(), file, s.mode, s.policies, s.sizes);
            print(result, s, (System.nanoTime() - start) / 1e9, out);
            results.add(result);
        }
        return finish(results, a, out, err);
    }

    /** Parsed replay options. */
    private record Settings(ReplayMode mode, List<Policy> policies, List<BigDecimal> sizes) {}

    /** Reads the options shared by replay and bench. */
    private static Settings settings(Args a) {
        List<Policy> policies = new ArrayList<>();
        String all = String.join(
                ",", Arrays.stream(Policy.values()).map(Policy::label).toList());
        try {
            for (String label : a.list("policies", all)) {
                policies.add(Policy.fromLabel(label));
            }
            return new Settings(
                    ReplayMode.fromLabel(a.option("mode", "lookaside")),
                    policies,
                    a.percentages("sizes", DEFAULT_SIZES));
        } catch (IllegalArgumentException e) {
            throw new Args.UsageException(e.getMessage());
        }
    }

    /** The pins to work on: all, or the one named by --only. */
    private static List<TracePin> selected(Args a) throws IOException {
        List<TracePin> pins = TracePin.readAll(Path.of(a.option("pins", DEFAULT_PINS)));
        String only = a.option("only");
        if (only == null) {
            return pins;
        }
        for (TracePin pin : pins) {
            if (pin.name().equals(only)) {
                return List.of(pin);
            }
        }
        throw new Args.UsageException("no pinned trace named '" + only + "'");
    }

    /** Trace name from a file name, without its extension. */
    private static String traceName(Path file) {
        Path fileName = file.getFileName();
        String name = fileName == null ? "trace" : fileName.toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Prints one trace's summary and its size-by-policy hit-rate matrix, best per size starred. */
    private static void print(Replayer.Result result, Settings s, double seconds, PrintStream out) {
        Replayer.Survey v = result.survey();
        out.printf(
                Locale.ROOT,
                "%s: %,d requests (%,d reads, %,d set, %,d add, %,d update, %,d delete), %,d distinct keys,"
                        + " %,d malformed and %,d oversized lines skipped%n",
                result.trace(),
                v.requests(),
                v.count(Op.READ),
                v.count(Op.SET),
                v.count(Op.ADD),
                v.count(Op.UPDATE),
                v.count(Op.DELETE),
                v.distinctKeys(),
                v.malformed(),
                v.oversized());
        HitRateRow infinite = result.rows().get(0);
        out.printf(
                Locale.ROOT,
                "  mode %s: %,d counted reads; a cache that never evicts hits %.2f%%%n",
                result.mode().label(),
                infinite.reads(),
                100 * infinite.hitRate());
        StringBuilder header = new StringBuilder(String.format(Locale.ROOT, "  %-7s %9s", "size", "entries"));
        for (Policy p : s.policies) {
            header.append(String.format(Locale.ROOT, " %10s ", p.label()));
        }
        out.println(header.toString().stripTrailing());
        Map<String, List<HitRateRow>> bySize = new LinkedHashMap<>();
        for (HitRateRow r : result.rows().subList(1, result.rows().size())) {
            bySize.computeIfAbsent(r.sizePercent(), k -> new ArrayList<>()).add(r);
        }
        for (List<HitRateRow> row : bySize.values()) {
            long best = row.stream().mapToLong(HitRateRow::hits).max().orElse(0);
            StringBuilder line = new StringBuilder(String.format(
                    Locale.ROOT,
                    "  %-7s %,9d",
                    row.get(0).sizePercent(),
                    Long.parseLong(row.get(0).capacity())));
            for (HitRateRow r : row) {
                line.append(String.format(Locale.ROOT, " %9.2f%%%s", 100 * r.hitRate(), r.hits() == best ? "*" : " "));
            }
            out.println(line.toString().stripTrailing());
        }
        out.printf(Locale.ROOT, "  (* best at that size; replayed in %.1f s, one run)%n%n", seconds);
    }

    /** Checks invariants, writes and compares tables, and prints the RESULT line. */
    private static int finish(List<Replayer.Result> results, Args a, PrintStream out, PrintStream err)
            throws IOException {
        List<HitRateRow> rows = new ArrayList<>();
        Set<String> traces = new LinkedHashSet<>();
        int aboveInfinite = 0;
        Map<String, Integer> wins = new TreeMap<>();
        for (Replayer.Result result : results) {
            traces.add(result.trace());
            rows.addAll(result.rows());
            HitRateRow infinite = result.rows().get(0);
            Map<String, List<HitRateRow>> bySize = new LinkedHashMap<>();
            for (HitRateRow r : result.rows().subList(1, result.rows().size())) {
                if (r.hits() > infinite.hits() || r.reads() != infinite.reads()) {
                    aboveInfinite++;
                    err.println("invariant broken: " + r.cell() + " " + r.hits() + "/" + r.reads()
                            + " vs never-evicting " + infinite.hits() + "/" + infinite.reads());
                }
                bySize.computeIfAbsent(r.sizePercent(), k -> new ArrayList<>()).add(r);
            }
            for (List<HitRateRow> row : bySize.values()) {
                long best = row.stream().mapToLong(HitRateRow::hits).max().orElse(0);
                for (HitRateRow r : row) {
                    if (r.hits() == best) {
                        wins.merge(r.policy(), 1, Integer::sum);
                    }
                }
            }
        }
        String out1 = a.option("out");
        if (out1 != null) {
            ResultsTable.write(Path.of(out1), rows);
        }
        String expected = "not-checked";
        String expectFile = a.option("expect");
        if (expectFile != null) {
            List<String> diffs = ResultsTable.compare(ResultsTable.read(Path.of(expectFile)), rows, traces);
            diffs.stream().limit(20).forEach(d -> err.println("differs: " + d));
            expected = diffs.isEmpty() ? "match" : "mismatch(" + diffs.size() + ")";
        }
        StringBuilder winText = new StringBuilder();
        wins.forEach((policy, n) -> winText.append(winText.isEmpty() ? "" : ",")
                .append(policy)
                .append(':')
                .append(n));
        long cells = rows.size() - results.size();
        out.printf(
                Locale.ROOT,
                "RESULT traces=%d cells=%d above_infinite=%d expected=%s best_at_size=%s%n",
                results.size(),
                cells,
                aboveInfinite,
                expected,
                winText);
        return aboveInfinite == 0 && !expected.startsWith("mismatch") ? OK : CHECK_FAILED;
    }
}
