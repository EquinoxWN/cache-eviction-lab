package portfolio.cacheevictionlab.policy;

import java.util.HashMap;
import java.util.Map;
import portfolio.cacheevictionlab.sketch.CountMinSketch;

/**
 * Window TinyLFU (Einziger, Friedman and Manes, "TinyLFU: A Highly Efficient Cache Admission
 * Policy", ACM TOS 2017), the policy behind Caffeine.
 *
 * <p>New keys enter a small LRU <em>window</em> (1% of the capacity). A key pushed out of the window
 * competes with the oldest key of the main cache's <em>probation</em> segment: it gets in only if
 * the Count-Min sketch says it was used more often. A hit in probation moves the key to the
 * <em>protected</em> segment (80% of the main cache); keys pushed out of protected go back to
 * probation. The sketch counts every hit and every put, and all its counters are halved after
 * {@code 10 x capacity} counts, so old popularity fades.
 *
 * <p>The sketch is {@link CountMinSketch} from probabilistic-sketches: depth 4, width {@code 4 x
 * capacity} (at least 64, at most the sketch's counter limit), conservative update. With width equal
 * to the capacity, collisions inflated the counts of one-time keys and W-TinyLFU's hit rate was
 * 0.05 to 1.2 points lower on every measured trace and size (docs/results/m1.md, Improve). Unlike
 * Caffeine's 4-bit counters, these 64-bit counters do not saturate at 15 and cost 128 bytes per
 * cache entry; ADR 0003 records that trade-off. The doorkeeper Bloom filter of the paper is left
 * out.
 */
public final class WTinyLfuCache implements Cache {
    /** Share of the main cache given to the protected segment. */
    static final double PROTECTED_SHARE = 0.8;
    /** Sketch counts between two halvings, as a multiple of the capacity. */
    static final int SAMPLE_FACTOR = 10;
    /** Rows of the sketch. */
    static final int SKETCH_DEPTH = 4;
    /** Counters per sketch row, as a multiple of the capacity. */
    static final int SKETCH_WIDTH_FACTOR = 4;

    private final int capacity;
    private final int windowCapacity;
    private final int protectedCapacity;
    private final long sampleSize;
    private final CountMinSketch sketch;
    private final Map<Long, KeyList.Node> index = new HashMap<>();
    private final KeyList window = new KeyList();
    private final KeyList probation = new KeyList();
    private final KeyList protectedSegment = new KeyList();
    private long counted;

    /** Creates an empty cache. */
    public WTinyLfuCache(int capacity) {
        this.capacity = Cache.checkCapacity(capacity);
        this.windowCapacity = windowCapacity(capacity);
        this.protectedCapacity = (int) ((capacity - windowCapacity) * PROTECTED_SHARE);
        this.sampleSize = (long) SAMPLE_FACTOR * capacity;
        this.sketch = new CountMinSketch(SKETCH_DEPTH, sketchWidth(capacity), true);
    }

    /** Sketch row width for a capacity: 4 x capacity, at least 64, within the sketch's counter limit. */
    static int sketchWidth(int capacity) {
        long wanted = Math.max(64L, (long) SKETCH_WIDTH_FACTOR * capacity);
        return (int) Math.min(wanted, CountMinSketch.MAX_COUNTERS / SKETCH_DEPTH);
    }

    /** Window size for a capacity: 1% rounded down, at least one entry. */
    static int windowCapacity(int capacity) {
        return Math.max(1, capacity / 100);
    }

    @Override
    public boolean get(long key) {
        KeyList.Node node = index.get(key);
        if (node == null) {
            return false;
        }
        record(key);
        onHit(node);
        return true;
    }

    @Override
    public void put(long key) {
        if (get(key)) {
            return;
        }
        record(key);
        KeyList.Node node = new KeyList.Node(key);
        window.addLast(node);
        index.put(key, node);
        if (window.size() <= windowCapacity) {
            return;
        }
        KeyList.Node candidate = window.removeFirst();
        if (index.size() <= capacity) {
            probation.addLast(candidate);
            return;
        }
        KeyList.Node victim = probation.isEmpty() ? protectedSegment.first() : probation.first();
        if (victim == null) {
            index.remove(candidate.key);
            return;
        }
        if (sketch.estimate(candidate.key) > sketch.estimate(victim.key)) {
            victim.owner.remove(victim);
            index.remove(victim.key);
            probation.addLast(candidate);
        } else {
            index.remove(candidate.key);
        }
    }

    @Override
    public boolean evict(long key) {
        KeyList.Node node = index.remove(key);
        if (node == null) {
            return false;
        }
        node.owner.remove(node);
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
        return "w-tinylfu";
    }

    /** Sizes of the window, probation and protected segments; for tests. */
    int[] segmentSizes() {
        return new int[] {window.size(), probation.size(), protectedSegment.size()};
    }

    /** Sketch estimate of a key's recent use count; for tests. */
    long frequency(long key) {
        return sketch.estimate(key);
    }

    /** Counts one use in the sketch and halves every counter once the sample is full. */
    private void record(long key) {
        sketch.add(key);
        if (++counted >= sampleSize) {
            sketch.halve();
            counted /= 2;
        }
    }

    /** Updates recency and segment for a resident key that was used. */
    private void onHit(KeyList.Node node) {
        KeyList owner = node.owner;
        if (owner == window || owner == protectedSegment) {
            owner.moveToLast(node);
            return;
        }
        probation.remove(node);
        protectedSegment.addLast(node);
        if (protectedSegment.size() > protectedCapacity) {
            probation.addLast(protectedSegment.removeFirst());
        }
    }
}
