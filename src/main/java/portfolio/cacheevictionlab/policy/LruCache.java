package portfolio.cacheevictionlab.policy;

import java.util.HashMap;
import java.util.Map;

/** Least recently used: every hit or refresh moves the key to the newest end; evicts the oldest. */
public final class LruCache implements Cache {
    private final int capacity;
    private final Map<Long, KeyList.Node> index = new HashMap<>();
    private final KeyList order = new KeyList();

    /** Creates an empty cache. */
    public LruCache(int capacity) {
        this.capacity = Cache.checkCapacity(capacity);
    }

    @Override
    public boolean get(long key) {
        KeyList.Node node = index.get(key);
        if (node == null) {
            return false;
        }
        order.moveToLast(node);
        return true;
    }

    @Override
    public void put(long key) {
        if (get(key)) {
            return;
        }
        if (order.size() == capacity) {
            index.remove(order.removeFirst().key);
        }
        KeyList.Node node = new KeyList.Node(key);
        order.addLast(node);
        index.put(key, node);
    }

    @Override
    public boolean evict(long key) {
        KeyList.Node node = index.remove(key);
        if (node == null) {
            return false;
        }
        order.remove(node);
        return true;
    }

    @Override
    public boolean contains(long key) {
        return index.containsKey(key);
    }

    @Override
    public int size() {
        return order.size();
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public String name() {
        return "lru";
    }
}
