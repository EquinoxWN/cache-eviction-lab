package portfolio.cacheevictionlab.policy;

import java.util.HashSet;
import java.util.Set;

/**
 * A cache that never evicts: the upper bound on the hit rate of any policy replaying the same
 * trace, since every bounded cache only ever holds a subset of its keys.
 */
public final class UnboundedCache implements Cache {
    private final Set<Long> keys = new HashSet<>();

    @Override
    public boolean get(long key) {
        return keys.contains(key);
    }

    @Override
    public void put(long key) {
        keys.add(key);
    }

    @Override
    public boolean evict(long key) {
        return keys.remove(key);
    }

    @Override
    public boolean contains(long key) {
        return keys.contains(key);
    }

    @Override
    public int size() {
        return keys.size();
    }

    @Override
    public int capacity() {
        return Integer.MAX_VALUE;
    }

    @Override
    public String name() {
        return "infinite";
    }
}
