package portfolio.cacheevictionlab.policy;

/**
 * A bounded set of 64-bit keys managed by one eviction policy.
 *
 * <p>Capacity counts entries, not bytes. The replay harness only talks to this interface, so every
 * policy can be swapped in without changing the harness:
 *
 * <ul>
 *   <li>{@link #get} is a lookup: on a hit the policy updates its state (recency, frequency); a miss
 *       changes nothing.
 *   <li>{@link #put} admits a key or refreshes a resident one. If the cache is full the policy evicts
 *       a victim, or (for an admission policy) may reject the new key itself.
 *   <li>{@link #evict} removes one key on request, for example when the trace deletes it.
 * </ul>
 *
 * <p>After every call {@code size() <= capacity()}. Implementations are not thread-safe.
 */
public interface Cache {
    /** Largest capacity any policy accepts, 16,777,216 entries, to stop accidental huge caches. */
    int MAX_CAPACITY = 1 << 24;

    /** Looks a key up; true on a hit. */
    boolean get(long key);

    /** Admits or refreshes a key, evicting as the policy decides. */
    void put(long key);

    /** Removes a key if resident; true when it was. */
    boolean evict(long key);

    /** True when the key is resident; never changes policy state. */
    boolean contains(long key);

    /** Number of resident keys. */
    int size();

    /** Maximum number of resident keys. */
    int capacity();

    /** Short policy name used in reports. */
    String name();

    /** Rejects a capacity outside 1 to {@link #MAX_CAPACITY}. */
    static int checkCapacity(int capacity) {
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("capacity must be between 1 and " + MAX_CAPACITY + ", got " + capacity);
        }
        return capacity;
    }
}
