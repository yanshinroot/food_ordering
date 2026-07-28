package com.foodorder.staff.core

import java.util.UUID

/**
 * Generates and caches idempotency/request keys per *logical* action so a
 * retry of the same action reuses the same key, while a genuinely new
 * action (a different order, a different button press) always gets a new
 * one. The cache is small and time-bounded so it can't grow unbounded
 * across a long-running app session.
 */
class IdempotencyKeyProvider(
    private val uuidFactory: () -> String = { UUID.randomUUID().toString() },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val ttlMillis: Long = 5 * 60_000L,
) {
    private data class Entry(val key: String, val createdAt: Long)

    private val cache = LinkedHashMap<String, Entry>()
    private val maxEntries = 200

    /**
     * [logicalActionId] must uniquely identify "this attempt at this
     * operation" — e.g. "accept:482" or "walkin-submit:<draft-uuid>". Calling
     * this again with the same id before it is retired returns the same
     * key; calling [retire] (after a terminal success or a user-initiated
     * new attempt) forces the next call to mint a fresh one.
     */
    @Synchronized
    fun keyFor(logicalActionId: String): String {
        val now = nowMillis()
        cache.entries.removeAll { now - it.value.createdAt > ttlMillis }
        val existing = cache[logicalActionId]
        if (existing != null) return existing.key
        val fresh = uuidFactory()
        if (cache.size >= maxEntries) {
            val oldestKey = cache.entries.minByOrNull { it.value.createdAt }?.key
            if (oldestKey != null) cache.remove(oldestKey)
        }
        cache[logicalActionId] = Entry(fresh, now)
        return fresh
    }

    @Synchronized
    fun retire(logicalActionId: String) {
        cache.remove(logicalActionId)
    }
}
