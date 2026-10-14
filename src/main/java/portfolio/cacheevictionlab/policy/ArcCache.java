package portfolio.cacheevictionlab.policy;

import java.util.HashMap;
import java.util.Map;

/**
 * Adaptive Replacement Cache (Megiddo and Modha, "ARC: A Self-Tuning, Low Overhead Replacement
 * Cache", FAST 2003, Figure 4).
 *
 * <p>Resident keys live in T1 (seen once recently) or T2 (seen at least twice); B1 and B2 remember
 * the keys recently evicted from each ("ghosts", keys only). A miss that hits a ghost list moves the
 * target size {@code p} of T1 towards the list that would have kept it. At most {@code c} keys are
 * resident and at most {@code 2c} are tracked.
 *
 * <p>The paper has a single {@code REQUEST(x)}. Here {@link #get} is its hit case (Case I), and
 * {@link #put} of a key that is not resident runs Cases II to IV, so a get that misses followed by a
 * put is exactly one paper request. A put of a resident key counts as a hit. Two additions for
 * explicit deletes, which the paper does not have: {@link #evict} drops a resident key without
 * making it a ghost, and REPLACE runs only when the cache is full. Without deletes the cache is
 * always full when the paper calls REPLACE, so the behaviour is the paper's.
 */
public final class ArcCache implements Cache {
    private final int capacity;
    private final Map<Long, KeyList.Node> index = new HashMap<>();
    private final KeyList t1 = new KeyList();
    private final KeyList t2 = new KeyList();
    private final KeyList b1 = new KeyList();
    private final KeyList b2 = new KeyList();
    private double p;

    /** Creates an empty cache. */
    public ArcCache(int capacity) {
        this.capacity = Cache.checkCapacity(capacity);
    }

    @Override
    public boolean get(long key) {
        KeyList.Node node = index.get(key);
        if (node == null || !isResident(node)) {
            return false;
        }
        node.owner.remove(node);
        t2.addLast(node);
        return true;
    }

    @Override
    public void put(long key) {
        if (get(key)) {
            return;
        }
        KeyList.Node node = index.get(key);
        if (node != null && node.owner == b1) {
            p = Math.min(capacity, p + (b1.size() >= b2.size() ? 1.0 : (double) b2.size() / b1.size()));
            replaceIfFull(false);
            b1.remove(node);
            t2.addLast(node);
            return;
        }
        if (node != null && node.owner == b2) {
            p = Math.max(0.0, p - (b2.size() >= b1.size() ? 1.0 : (double) b1.size() / b2.size()));
            replaceIfFull(true);
            b2.remove(node);
            t2.addLast(node);
            return;
        }
        if (t1.size() + b1.size() == capacity) {
            if (t1.size() < capacity) {
                drop(b1);
                replaceIfFull(false);
            } else {
                drop(t1);
            }
        } else {
            int tracked = t1.size() + t2.size() + b1.size() + b2.size();
            if (tracked >= capacity) {
                if (tracked == 2 * capacity) {
                    drop(b2);
                }
                replaceIfFull(false);
            }
        }
        node = new KeyList.Node(key);
        t1.addLast(node);
        index.put(key, node);
    }

    @Override
    public boolean evict(long key) {
        KeyList.Node node = index.get(key);
        if (node == null || !isResident(node)) {
            return false;
        }
        node.owner.remove(node);
        index.remove(key);
        return true;
    }

    @Override
    public boolean contains(long key) {
        KeyList.Node node = index.get(key);
        return node != null && isResident(node);
    }

    @Override
    public int size() {
        return t1.size() + t2.size();
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public String name() {
        return "arc";
    }

    /** Current target size of T1; for tests. */
    double target() {
        return p;
    }

    /** Sizes of T1, T2, B1 and B2; for tests. */
    int[] listSizes() {
        return new int[] {t1.size(), t2.size(), b1.size(), b2.size()};
    }

    /** True when the node is in T1 or T2. */
    private boolean isResident(KeyList.Node node) {
        return node.owner == t1 || node.owner == t2;
    }

    /** The paper's REPLACE, run only when every slot is taken. */
    private void replaceIfFull(boolean requestInB2) {
        if (size() < capacity) {
            return;
        }
        boolean fromT1 = !t1.isEmpty() && ((requestInB2 && t1.size() == p) || t1.size() > p);
        if (fromT1 || t2.isEmpty()) {
            b1.addLast(t1.removeFirst());
        } else {
            b2.addLast(t2.removeFirst());
        }
    }

    /** Forgets the oldest node of a list entirely. */
    private void drop(KeyList list) {
        index.remove(list.removeFirst().key);
    }
}
