package portfolio.cacheevictionlab.policy;

import java.util.ArrayList;
import java.util.List;
import portfolio.cacheevictionlab.sketch.CountMinSketch;

/**
 * Brute-force reference models of every policy: plain lists and linear scans, written straight from
 * each policy's definition with no index or intrusive list, so they are easy to check by eye. The
 * randomized tests replay the same operations through a model and the real policy and require the
 * same answers and the same contents after every step.
 */
final class ReferenceCaches {
    private ReferenceCaches() {}

    /** Reference model for a policy. */
    static Cache of(Policy policy, int capacity) {
        return switch (policy) {
            case FIFO -> new Fifo(capacity);
            case LRU -> new Lru(capacity);
            case LFU -> new Lfu(capacity);
            case ARC -> new Arc(capacity);
            case W_TINYLFU -> new WTinyLfu(capacity);
        };
    }

    /** Shared bookkeeping for the list-based models. */
    private abstract static class Base implements Cache {
        final int capacity;

        /** Stores the capacity. */
        Base(int capacity) {
            this.capacity = capacity;
        }

        @Override
        public int capacity() {
            return capacity;
        }

        @Override
        public String name() {
            return "reference";
        }
    }

    /** FIFO: a list in insertion order. */
    static final class Fifo extends Base {
        private final List<Long> queue = new ArrayList<>();

        /** Creates the model. */
        Fifo(int capacity) {
            super(capacity);
        }

        @Override
        public boolean get(long key) {
            return queue.contains(key);
        }

        @Override
        public void put(long key) {
            if (queue.contains(key)) {
                return;
            }
            if (queue.size() == capacity) {
                queue.remove(0);
            }
            queue.add(key);
        }

        @Override
        public boolean evict(long key) {
            return queue.remove(Long.valueOf(key));
        }

        @Override
        public boolean contains(long key) {
            return queue.contains(key);
        }

        @Override
        public int size() {
            return queue.size();
        }
    }

    /** LRU: a list in order of last use. */
    static final class Lru extends Base {
        private final List<Long> order = new ArrayList<>();

        /** Creates the model. */
        Lru(int capacity) {
            super(capacity);
        }

        @Override
        public boolean get(long key) {
            if (!order.remove(Long.valueOf(key))) {
                return false;
            }
            order.add(key);
            return true;
        }

        @Override
        public void put(long key) {
            if (get(key)) {
                return;
            }
            if (order.size() == capacity) {
                order.remove(0);
            }
            order.add(key);
        }

        @Override
        public boolean evict(long key) {
            return order.remove(Long.valueOf(key));
        }

        @Override
        public boolean contains(long key) {
            return order.contains(key);
        }

        @Override
        public int size() {
            return order.size();
        }
    }

    /** LFU: every entry keeps its use count and last-use time; the victim is found by a full scan. */
    static final class Lfu extends Base {
        private final List<long[]> entries = new ArrayList<>(); // {key, uses, lastUse}
        private long clock;

        /** Creates the model. */
        Lfu(int capacity) {
            super(capacity);
        }

        @Override
        public boolean get(long key) {
            for (long[] e : entries) {
                if (e[0] == key) {
                    e[1]++;
                    e[2] = ++clock;
                    return true;
                }
            }
            return false;
        }

        @Override
        public void put(long key) {
            if (get(key)) {
                return;
            }
            if (entries.size() == capacity) {
                long[] victim = entries.get(0);
                for (long[] e : entries) {
                    if (e[1] < victim[1] || (e[1] == victim[1] && e[2] < victim[2])) {
                        victim = e;
                    }
                }
                entries.remove(victim);
            }
            entries.add(new long[] {key, 1, ++clock});
        }

        @Override
        public boolean evict(long key) {
            return entries.removeIf(e -> e[0] == key);
        }

        @Override
        public boolean contains(long key) {
            return entries.stream().anyMatch(e -> e[0] == key);
        }

        @Override
        public int size() {
            return entries.size();
        }
    }

    /** ARC: Figure 4 of Megiddo and Modha (2003) on four lists, LRU end first. */
    static final class Arc extends Base {
        private final List<Long> t1 = new ArrayList<>();
        private final List<Long> t2 = new ArrayList<>();
        private final List<Long> b1 = new ArrayList<>();
        private final List<Long> b2 = new ArrayList<>();
        private double p;

        /** Creates the model. */
        Arc(int capacity) {
            super(capacity);
        }

        @Override
        public boolean get(long key) {
            // Case I: x in T1 or T2, move to the MRU end of T2.
            if (t1.remove(Long.valueOf(key)) || t2.remove(Long.valueOf(key))) {
                t2.add(key);
                return true;
            }
            return false;
        }

        @Override
        public void put(long key) {
            if (get(key)) {
                return;
            }
            int c = capacity;
            if (b1.contains(key)) {
                // Case II: adapt p upwards by max(1, |B2|/|B1|).
                double delta = b1.size() >= b2.size() ? 1 : (double) b2.size() / b1.size();
                p = Math.min(p + delta, c);
                replace(false);
                b1.remove(Long.valueOf(key));
                t2.add(key);
                return;
            }
            if (b2.contains(key)) {
                // Case III: adapt p downwards by max(1, |B1|/|B2|).
                double delta = b2.size() >= b1.size() ? 1 : (double) b1.size() / b2.size();
                p = Math.max(p - delta, 0);
                replace(true);
                b2.remove(Long.valueOf(key));
                t2.add(key);
                return;
            }
            // Case IV: a key in no list.
            if (t1.size() + b1.size() == c) {
                if (t1.size() < c) {
                    b1.remove(0);
                    replace(false);
                } else {
                    t1.remove(0);
                }
            } else {
                int total = t1.size() + t2.size() + b1.size() + b2.size();
                if (total >= c) {
                    if (total == 2 * c) {
                        b2.remove(0);
                    }
                    replace(false);
                }
            }
            t1.add(key);
        }

        /** REPLACE(x, p), skipped while there is a free slot (only possible after deletes). */
        private void replace(boolean inB2) {
            if (t1.size() + t2.size() < capacity) {
                return;
            }
            boolean fromT1 = !t1.isEmpty() && ((inB2 && t1.size() == p) || t1.size() > p);
            if (fromT1 || t2.isEmpty()) {
                b1.add(t1.remove(0));
            } else {
                b2.add(t2.remove(0));
            }
        }

        @Override
        public boolean evict(long key) {
            return t1.remove(Long.valueOf(key)) || t2.remove(Long.valueOf(key));
        }

        @Override
        public boolean contains(long key) {
            return t1.contains(key) || t2.contains(key);
        }

        @Override
        public int size() {
            return t1.size() + t2.size();
        }
    }

    /** W-TinyLFU: window, probation and protected lists and a sketch with the same settings. */
    static final class WTinyLfu extends Base {
        private final List<Long> window = new ArrayList<>();
        private final List<Long> probation = new ArrayList<>();
        private final List<Long> protectedList = new ArrayList<>();
        private final CountMinSketch sketch;
        private final int windowCapacity;
        private final int protectedCapacity;
        private long counted;

        /** Creates the model. */
        WTinyLfu(int capacity) {
            super(capacity);
            windowCapacity = Math.max(1, capacity / 100);
            protectedCapacity = (int) ((capacity - windowCapacity) * 0.8);
            sketch = new CountMinSketch(
                    4, (int) Math.min(Math.max(64L, 4L * capacity), CountMinSketch.MAX_COUNTERS / 4), true);
        }

        /** One use counted; halve after ten uses per entry of capacity. */
        private void record(long key) {
            sketch.add(key);
            counted++;
            if (counted >= 10L * capacity) {
                sketch.halve();
                counted /= 2;
            }
        }

        @Override
        public boolean get(long key) {
            Long k = key;
            if (window.remove(k)) {
                record(key);
                window.add(k);
                return true;
            }
            if (protectedList.remove(k)) {
                record(key);
                protectedList.add(k);
                return true;
            }
            if (probation.remove(k)) {
                record(key);
                protectedList.add(k);
                if (protectedList.size() > protectedCapacity) {
                    probation.add(protectedList.remove(0));
                }
                return true;
            }
            return false;
        }

        @Override
        public void put(long key) {
            if (get(key)) {
                return;
            }
            record(key);
            window.add(key);
            if (window.size() <= windowCapacity) {
                return;
            }
            long candidate = window.remove(0);
            if (size() + 1 <= capacity) {
                probation.add(candidate);
                return;
            }
            List<Long> victims = probation.isEmpty() ? protectedList : probation;
            if (victims.isEmpty()) {
                return;
            }
            long victim = victims.get(0);
            if (sketch.estimate(candidate) > sketch.estimate(victim)) {
                victims.remove(0);
                probation.add(candidate);
            }
        }

        @Override
        public boolean evict(long key) {
            Long k = key;
            return window.remove(k) || probation.remove(k) || protectedList.remove(k);
        }

        @Override
        public boolean contains(long key) {
            Long k = key;
            return window.contains(k) || probation.contains(k) || protectedList.contains(k);
        }

        @Override
        public int size() {
            return window.size() + probation.size() + protectedList.size();
        }
    }
}
