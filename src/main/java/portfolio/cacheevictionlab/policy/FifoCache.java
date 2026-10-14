package portfolio.cacheevictionlab.policy;

import java.util.HashMap;
import java.util.Map;

/** First in, first out: evicts the key inserted longest ago; hits change nothing. */
public final class FifoCache implements Cache {
    private final int capacity;
    private final Map<Long, KeyList.Node> index = new HashMap<>();
    private final KeyList queue = new KeyList();

    /** Creates an empty cache. */
    public FifoCache(int capacity) {
        this.capacity = Cache.checkCapacity(capacity);
    }

    @Override
    public boolean get(long key) {
        return index.containsKey(key);
    }

    @Override
    public void put(long key) {
        if (index.containsKey(key)) {
            return;
        }
        if (queue.size() == capacity) {
            index.remove(queue.removeFirst().key);
        }
        KeyList.Node node = new KeyList.Node(key);
        queue.addLast(node);
        index.put(key, node);
    }

    @Override
    public boolean evict(long key) {
        KeyList.Node node = index.remove(key);
        if (node == null) {
            return false;
        }
        queue.remove(node);
        return true;
    }

    @Override
    public boolean contains(long key) {
        return index.containsKey(key);
    }

    @Override
    public int size() {
        return queue.size();
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public String name() {
        return "fifo";
    }
}
