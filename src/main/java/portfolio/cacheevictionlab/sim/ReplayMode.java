package portfolio.cacheevictionlab.sim;

import java.util.Locale;
import portfolio.cacheevictionlab.policy.Cache;
import portfolio.cacheevictionlab.trace.Op;

/** How trace requests drive a cache; ADR 0002 explains the choice of the default. */
public enum ReplayMode {
    /**
     * A look-aside cache in front of a store, as Twemcache is used: reads count towards the hit rate
     * and fill the cache on a miss; {@code set} stores, {@code add} stores only if absent, updates
     * change only a cached key, {@code delete} removes it. Writes are not counted as hits or misses.
     */
    LOOKASIDE("lookaside"),
    /** Every request, whatever its command, is a read that fills on a miss (the usual simulator shortcut). */
    EVERY_REQUEST("every-request");

    private final String label;

    /** Binds the command-line name. */
    ReplayMode(String label) {
        this.label = label;
    }

    /** Name used in reports and on the command line. */
    public String label() {
        return label;
    }

    /** Looks a mode up by its name, ignoring case. */
    public static ReplayMode fromLabel(String label) {
        String wanted = label.toLowerCase(Locale.ROOT);
        for (ReplayMode mode : values()) {
            if (mode.label.equals(wanted)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("unknown mode: " + label + " (lookaside or every-request)");
    }

    /** Applies one request to a cache; returns 1 for a read hit, 0 for a read miss, -1 for a write. */
    public int apply(Cache cache, Op op, long key) {
        if (this == EVERY_REQUEST || op == Op.READ) {
            if (cache.get(key)) {
                return 1;
            }
            cache.put(key);
            return 0;
        }
        switch (op) {
            case SET -> cache.put(key);
            case ADD -> {
                if (!cache.contains(key)) {
                    cache.put(key);
                }
            }
            case UPDATE -> {
                if (cache.contains(key)) {
                    cache.put(key);
                }
            }
            case DELETE -> cache.evict(key);
            case READ -> throw new IllegalStateException("handled above");
        }
        return -1;
    }
}
