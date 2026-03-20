# java-cache-lru

A generic, thread-safe **LRU (Least Recently Used) cache** built from scratch in Java. O(1) get and put using a HashMap + doubly-linked list. Includes TTL support, cache-loader function, and hit/miss statistics.

## Features

- ✅ O(1) get and put — HashMap + doubly-linked list
- ✅ Thread-safe — `ReentrantReadWriteLock` for concurrent access
- ✅ Optional **TTL** (time-to-live) per entry or globally
- ✅ Optional **cache-loader** function (compute on miss)
- ✅ `Optional<V>` return type — no null surprises
- ✅ Cache statistics: hits, misses, hit rate, evictions
- ✅ Zero dependencies

## Quick Start

```bash
git clone https://github.com/yourusername/java-cache-lru
cd java-cache-lru
./mvnw exec:java -Dexec.mainClass="com.cache.LRUCache"
```

## Usage

### Basic Cache

```java
LRUCache<Integer, String> cache = new LRUCache<>(3);
cache.put(1, "one");
cache.put(2, "two");
cache.put(3, "three");

cache.get(1); // "one" — moves 1 to front (most recently used)
cache.put(4, "four"); // evicts 2 (least recently used)

cache.get(2); // Optional.empty() — was evicted
```

### With TTL

```java
// Entries expire after 5 minutes
LRUCache<String, String> sessions = new LRUCache<>(1000, 5 * 60 * 1000L, null);
sessions.put("session:xyz", "user-42");

// 6 minutes later...
sessions.get("session:xyz"); // Optional.empty() — expired
```

### With Cache Loader

```java
// Automatically compute value on cache miss
LRUCache<Long, User> users = new LRUCache<>(500, 0,
    userId -> userRepository.findById(userId));

users.get(42L); // fetches from DB on first access, cached after
users.get(42L); // returns from cache
```

### Statistics

```java
CacheStats stats = cache.stats();
System.out.println(stats);
// CacheStats{hits=150, misses=12, hitRate=92.6%, evictions=8, size=100/200}
```

## How It Works

```
HashMap<K, Node>  → O(1) lookup by key
Doubly-linked list → O(1) move-to-front on access, O(1) evict tail

put(key):
  ├─ key exists? → update + move to front
  ├─ at capacity? → evict tail (LRU), insert new node at head
  └─ else → insert new node at head

get(key):
  ├─ miss / expired? → optional loader, return empty
  └─ hit → move to front, return value
```

## Tech Stack

- **Java** 21 (Records, Optional)
- **Maven**
- No external dependencies

## License

MIT
