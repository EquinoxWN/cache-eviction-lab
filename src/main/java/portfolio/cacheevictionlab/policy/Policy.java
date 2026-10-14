package portfolio.cacheevictionlab.policy;

import java.util.Locale;
import java.util.function.IntFunction;

/** Every bounded policy the harness can replay, by report name. */
public enum Policy {
    FIFO("fifo", FifoCache::new),
    LRU("lru", LruCache::new),
    LFU("lfu", LfuCache::new),
    ARC("arc", ArcCache::new),
    W_TINYLFU("w-tinylfu", WTinyLfuCache::new);

    private final String label;
    private final IntFunction<Cache> factory;

    /** Binds a report name to a constructor. */
    Policy(String label, IntFunction<Cache> factory) {
        this.label = label;
        this.factory = factory;
    }

    /** Creates an empty cache of this policy. */
    public Cache create(int capacity) {
        return factory.apply(capacity);
    }

    /** Name used in reports and on the command line. */
    public String label() {
        return label;
    }

    /** Looks a policy up by its report name, ignoring case. */
    public static Policy fromLabel(String label) {
        String wanted = label.toLowerCase(Locale.ROOT);
        for (Policy policy : values()) {
            if (policy.label.equals(wanted)) {
                return policy;
            }
        }
        throw new IllegalArgumentException("unknown policy: " + label);
    }
}
