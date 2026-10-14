package portfolio.cacheevictionlab.policy;

/**
 * Intrusive doubly linked list of cache entries: the head is the oldest (least recently used or
 * first inserted) entry and the tail the newest. Every operation is O(1).
 */
final class KeyList {
    /** One resident or ghost entry; it remembers which list holds it. */
    static class Node {
        final long key;
        Node prev;
        Node next;
        KeyList owner;

        /** Creates a detached node for a key. */
        Node(long key) {
            this.key = key;
        }
    }

    private Node head;
    private Node tail;
    private int size;

    /** Appends a detached node at the tail (newest end). */
    void addLast(Node node) {
        if (node.owner != null) {
            throw new IllegalStateException("node is already in a list");
        }
        node.owner = this;
        node.prev = tail;
        node.next = null;
        if (tail == null) {
            head = node;
        } else {
            tail.next = node;
        }
        tail = node;
        size++;
    }

    /** Unlinks a node that this list holds. */
    void remove(Node node) {
        if (node.owner != this) {
            throw new IllegalStateException("node is not in this list");
        }
        if (node.prev == null) {
            head = node.next;
        } else {
            node.prev.next = node.next;
        }
        if (node.next == null) {
            tail = node.prev;
        } else {
            node.next.prev = node.prev;
        }
        node.prev = null;
        node.next = null;
        node.owner = null;
        size--;
    }

    /** Moves a node of this list to the tail. */
    void moveToLast(Node node) {
        remove(node);
        addLast(node);
    }

    /** Oldest node, or null when empty. */
    Node first() {
        return head;
    }

    /** Unlinks and returns the oldest node; the list must not be empty. */
    Node removeFirst() {
        Node node = head;
        if (node == null) {
            throw new IllegalStateException("list is empty");
        }
        remove(node);
        return node;
    }

    /** Number of nodes. */
    int size() {
        return size;
    }

    /** True when the list holds no nodes. */
    boolean isEmpty() {
        return size == 0;
    }
}
