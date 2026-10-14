package portfolio.cacheevictionlab.sim;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import portfolio.cacheevictionlab.policy.Cache;
import portfolio.cacheevictionlab.policy.Policy;
import portfolio.cacheevictionlab.policy.UnboundedCache;
import portfolio.cacheevictionlab.trace.Op;
import portfolio.cacheevictionlab.trace.TwitterTraceReader;

/**
 * Replays one trace through every requested policy and cache size.
 *
 * <p>Two streaming passes: the first counts distinct keys (cache sizes are percentages of that
 * number) and replays a cache that never evicts, the upper bound for every policy; the second feeds
 * each request to all bounded caches at once, so the trace is read twice however many caches run.
 * The trace is never loaded into memory; memory grows with the number of distinct keys only.
 */
public final class Replayer {
    /** Largest number of caches one replay runs side by side. */
    public static final int MAX_LANES = 200;

    private Replayer() {}

    /** Request counts and the upper bound from the first pass. */
    public record Survey(
            long lines,
            long malformed,
            long oversized,
            long[] perOp,
            long distinctKeys,
            long infiniteReads,
            long infiniteHits) {
        /** Valid requests of one kind. */
        public long count(Op op) {
            return perOp[op.ordinal()];
        }

        /** Valid requests in total. */
        public long requests() {
            long total = 0;
            for (long n : perOp) {
                total += n;
            }
            return total;
        }
    }

    /** Everything one replay produced. */
    public record Result(String trace, ReplayMode mode, Survey survey, List<HitRateRow> rows) {}

    /** Opens a trace file for streaming. */
    @FunctionalInterface
    public interface Source {
        /** A fresh stream over the whole trace. */
        InputStream open() throws IOException;
    }

    /** Replays a trace file. */
    public static Result replay(
            String trace, Path file, ReplayMode mode, List<Policy> policies, List<BigDecimal> sizePercents)
            throws IOException {
        return replay(trace, () -> Files.newInputStream(file), mode, policies, sizePercents);
    }

    /** Replays a trace from any source that can be opened twice. */
    public static Result replay(
            String trace, Source source, ReplayMode mode, List<Policy> policies, List<BigDecimal> sizePercents)
            throws IOException {
        if (policies.isEmpty() || sizePercents.isEmpty()) {
            throw new IllegalArgumentException("need at least one policy and one size");
        }
        if ((long) policies.size() * sizePercents.size() > MAX_LANES) {
            throw new IllegalArgumentException("at most " + MAX_LANES + " policy and size combinations per replay");
        }
        Survey survey = survey(source, mode);
        List<Cache> caches = new ArrayList<>();
        List<String> sizes = new ArrayList<>();
        for (BigDecimal percent : sizePercents) {
            int capacity = capacityFor(survey.distinctKeys(), percent);
            for (Policy policy : policies) {
                caches.add(policy.create(capacity));
                sizes.add(label(percent));
            }
        }
        long[] reads = new long[caches.size()];
        long[] hits = new long[caches.size()];
        try (TwitterTraceReader reader = new TwitterTraceReader(source.open())) {
            TwitterTraceReader.Request request = new TwitterTraceReader.Request();
            while (reader.next(request)) {
                for (int i = 0; i < caches.size(); i++) {
                    int outcome = mode.apply(caches.get(i), request.op(), request.key());
                    if (outcome >= 0) {
                        reads[i]++;
                        hits[i] += outcome;
                    }
                }
            }
        }
        List<HitRateRow> rows = new ArrayList<>();
        rows.add(new HitRateRow(
                trace, mode.label(), "infinite", "-", "-", survey.infiniteReads(), survey.infiniteHits()));
        for (int i = 0; i < caches.size(); i++) {
            Cache cache = caches.get(i);
            rows.add(new HitRateRow(
                    trace,
                    mode.label(),
                    cache.name(),
                    sizes.get(i),
                    Integer.toString(cache.capacity()),
                    reads[i],
                    hits[i]));
        }
        return new Result(trace, mode, survey, rows);
    }

    /** First pass: counts requests and distinct keys and replays the never-evicting cache. */
    static Survey survey(Source source, ReplayMode mode) throws IOException {
        long[] perOp = new long[Op.values().length];
        Set<Long> distinct = new HashSet<>();
        UnboundedCache infinite = new UnboundedCache();
        long reads = 0;
        long hits = 0;
        try (TwitterTraceReader reader = new TwitterTraceReader(source.open())) {
            TwitterTraceReader.Request request = new TwitterTraceReader.Request();
            while (reader.next(request)) {
                perOp[request.op().ordinal()]++;
                distinct.add(request.key());
                int outcome = mode.apply(infinite, request.op(), request.key());
                if (outcome >= 0) {
                    reads++;
                    hits += outcome;
                }
            }
            return new Survey(
                    reader.lines(), reader.malformed(), reader.oversized(), perOp, distinct.size(), reads, hits);
        }
    }

    /** Cache entries for a percentage of the distinct keys: rounded half up, at least 1. */
    public static int capacityFor(long distinctKeys, BigDecimal percent) {
        if (percent.signum() <= 0 || percent.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("size must be above 0% and at most 100%, got " + percent + "%");
        }
        long entries = BigDecimal.valueOf(distinctKeys)
                .multiply(percent)
                .divide(BigDecimal.valueOf(100))
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .longValueExact();
        return (int) Math.min(Cache.MAX_CAPACITY, Math.max(1, entries));
    }

    /** Percentage label without trailing zeros, for example {@code 0.1%}. */
    public static String label(BigDecimal percent) {
        return percent.stripTrailingZeros().toPlainString() + "%";
    }
}
