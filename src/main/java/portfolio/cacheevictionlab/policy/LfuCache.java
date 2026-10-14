package portfolio.cacheevictionlab.policy;

import java.util.HashMap;
import java.util.Map;

/**
 * Least frequently used, with ties broken by least recent use.
 *
 * <p>Frequencies count hits and refreshes while a key is resident and are forgotten on eviction
 * ("in-cache" LFU). Keys sit in buckets of equal frequency kept in a linked list sorted by
 * frequency (Shah, Mitra and Matani, "An O(1) algorithm for implementing the LFU cache eviction
 * scheme", 2010), so every operation is O(1). Within a bucket, keys are ordered by the time they
 * reached that frequency, which is their last use, so the bucket head is the least recently used.
 */
public final class LfuCache implements Cache {
    /** Resident entry with its use count. */
    private static final class Entry extends KeyList.Node {
        Bucket bucket;

        /** Creates a detached entry. */
        Entry(long key) {
            super(key);
        }
    }

    /** All keys with one frequency, plus links to the neighbouring frequencies. */
    private static final class Bucket {
        final long frequency;
        final KeyList keys = new KeyList();
        Bucket lower;
        Bucket higher;

        /** Creates an empty bucket. */
        Bucket(long frequency) {
            this.frequency = frequency;
        }
    }

    private final int capacity;
    private final Map<Long, Entry> index = new HashMap<>();
    private Bucket lowest;

    /** Creates an empty cache. */
    public LfuCache(int capacity) {
        this.capacity = Cache.checkCapacity(capacity);
    }

    @Override
    public boolean get(long key) {
        Entry entry = index.get(key);
        if (entry == null) {
            return false;
        }
        promote(entry);
        return true;
    }

    @Override
    public void put(long key) {
        if (get(key)) {
            return;
        }
        if (index.size() == capacity) {
            Entry victim = (Entry) lowest.keys.first();
            detach(victim);
            index.remove(victim.key);
        }
        Entry entry = new Entry(key);
        Bucket target = lowest;
        if (target == null || target.frequency != 1) {
            target = new Bucket(1);
            target.higher = lowest;
            if (lowest != null) {
                lowest.lower = target;
            }
            lowest = target;
        }
        entry.bucket = target;
        target.keys.addLast(entry);
        index.put(key, entry);
    }

    @Override
    public boolean evict(long key) {
        Entry entry = index.remove(key);
        if (entry == null) {
            return false;
        }
        detach(entry);
        return true;
    }

    @Override
    public boolean contains(long key) {
        return index.containsKey(key);
    }

    @Override
    public int size() {
        return index.size();
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public String name() {
        return "lfu";
    }

    /** Use count of a resident key, or 0; for tests. */
    long frequency(long key) {
        Entry entry = index.get(key);
        return entry == null ? 0 : entry.bucket.frequency;
    }

    /** Moves an entry to the bucket one frequency higher. */
    private void promote(Entry entry) {
        Bucket from = entry.bucket;
        Bucket to = from.higher;
        if (to == null || to.frequency != from.frequency + 1) {
            to = new Bucket(from.frequency + 1);
            to.lower = from;
            to.higher = from.higher;
            if (from.higher != null) {
                from.higher.lower = to;
            }
            from.higher = to;
        }
        detach(entry);
        entry.bucket = to;
        to.keys.addLast(entry);
    }

    /** Removes an entry from its bucket and drops the bucket if it became empty. */
    private void detach(Entry entry) {
        Bucket bucket = entry.bucket;
        bucket.keys.remove(entry);
        entry.bucket = null;
        if (!bucket.keys.isEmpty()) {
            return;
        }
        if (bucket.lower == null) {
            lowest = bucket.higher;
        } else {
            bucket.lower.higher = bucket.higher;
        }
        if (bucket.higher != null) {
            bucket.higher.lower = bucket.lower;
        }
    }
}
