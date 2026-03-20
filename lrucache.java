package com.cache;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * A generic, thread-safe LRU (Least Recently Used) cache backed by a
 * doubly-linked list + HashMap for O(1) get and put.
 *
 * Supports optional TTL (time-to-live), cache-loader function, and statistics.
 */
public class LRUCache<K, V> {

    // --- Internal doubly-linked list node ---
    private static class Node<K, V> {
        K key;
        V value;
        long expiresAt; // 0 = never expires
        Node<K, V> prev, next;

        Node(K key, V value, long expiresAt) {
            this.key = key;
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }

    private final int capacity;
    private final long defaultTtlMs;
    private final Function<K, V> loader;

    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head; // dummy head
    private final Node<K, V> tail; // dummy tail

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // Stats
    private long hits, misses, evictions;

    public LRUCache(int capacity) {
        this(capacity, 0, null);
    }

    public LRUCache(int capacity, long defaultTtlMs, Function<K, V> loader) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0");
        this.capacity = capacity;
        this.defaultTtlMs = defaultTtlMs;
        this.loader = loader;
        this.map = new HashMap<>(capacity);

        head = new Node<>(null, null, 0);
        tail = new Node<>(null, null, 0);
        head.next = tail;
        tail.prev = head;
    }

    // --- Public API ---

    public Optional<V> get(K key) {
        lock.writeLock().lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) {
                misses++;
                if (loader != null) {
                    V loaded = loader.apply(key);
                    if (loaded != null) {
                        put(key, loaded);
                        return Optional.of(loaded);
                    }
                }
                return Optional.empty();
            }
            if (isExpired(node)) {
                remove(node);
                map.remove(key);
                misses++;
                return Optional.empty();
            }
            moveToFront(node);
            hits++;
            return Optional.of(node.value);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void put(K key, V value) {
        put(key, value, defaultTtlMs);
    }

    public void put(K key, V value, long ttlMs) {
        lock.writeLock().lock();
        try {
            long expiresAt = ttlMs > 0 ? System.currentTimeMillis() + ttlMs : 0;
            Node<K, V> existing = map.get(key);
            if (existing != null) {
                existing.value = value;
                existing.expiresAt = expiresAt;
                moveToFront(existing);
                return;
            }
            if (map.size() >= capacity) {
                evictLRU();
            }
            Node<K, V> node = new Node<>(key, value, expiresAt);
            map.put(key, node);
            insertAtFront(node);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean containsKey(K key) {
        lock.readLock().lock();
        try {
            Node<K, V> node = map.get(key);
            return node != null && !isExpired(node);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void invalidate(K key) {
        lock.writeLock().lock();
        try {
            Node<K, V> node = map.remove(key);
            if (node != null) remove(node);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            map.clear();
            head.next = tail;
            tail.prev = head;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try { return map.size(); }
        finally { lock.readLock().unlock(); }
    }

    public CacheStats stats() {
        lock.readLock().lock();
        try { return new CacheStats(hits, misses, evictions, map.size(), capacity); }
        finally { lock.readLock().unlock(); }
    }

    // --- Internal helpers ---

    private void insertAtFront(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    private void remove(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private void moveToFront(Node<K, V> node) {
        remove(node);
        insertAtFront(node);
    }

    private void evictLRU() {
        Node<K, V> lru = tail.prev;
        if (lru == head) return;
        remove(lru);
        map.remove(lru.key);
        evictions++;
    }

    private boolean isExpired(Node<K, V> node) {
        return node.expiresAt > 0 && System.currentTimeMillis() > node.expiresAt;
    }

    // --- Stats record ---
    public record CacheStats(long hits, long misses, long evictions, int size, int capacity) {
        public double hitRate() {
            long total = hits + misses;
            return total == 0 ? 0.0 : (double) hits / total * 100;
        }

        @Override
        public String toString() {
            return String.format(
                "CacheStats{hits=%d, misses=%d, hitRate=%.1f%%, evictions=%d, size=%d/%d}",
                hits, misses, hitRate(), evictions, size, capacity);
        }
    }

    // --- Demo ---
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Basic LRU Cache ===");
        LRUCache<Integer, String> cache = new LRUCache<>(3);
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        System.out.println("get(1): " + cache.get(1)); // moves 1 to front
        cache.put(4, "four"); // evicts 2 (LRU)
        System.out.println("get(2): " + cache.get(2)); // empty — evicted
        System.out.println("get(3): " + cache.get(3));
        System.out.println(cache.stats());

        System.out.println("\n=== Cache with TTL (200ms) ===");
        LRUCache<String, String> ttlCache = new LRUCache<>(10, 200, null);
        ttlCache.put("session:abc", "user-42");
        System.out.println("before expiry: " + ttlCache.get("session:abc"));
        Thread.sleep(300);
        System.out.println("after expiry:  " + ttlCache.get("session:abc")); // expired

        System.out.println("\n=== Cache with Loader Function ===");
        LRUCache<String, String> loaderCache = new LRUCache<>(5, 0,
            key -> "computed_value_for_" + key);
        System.out.println("get (auto-loaded): " + loaderCache.get("user:99"));
        System.out.println("get (from cache):  " + loaderCache.get("user:99"));
        System.out.println(loaderCache.stats());
    }
}
