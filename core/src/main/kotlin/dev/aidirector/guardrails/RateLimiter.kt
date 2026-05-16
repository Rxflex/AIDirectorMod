package dev.aidirector.guardrails

import dev.aidirector.util.Clock
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Sliding-window rate limiter, per (player, bucket) key. Each `tryAcquire`
 * call records an attempt at "now" and reports whether the bucket fits inside
 * its cap over the trailing window.
 *
 * Buckets and caps are configured externally via [BucketLimits]; the limiter
 * itself is policy-agnostic and easy to unit-test.
 */
class RateLimiter(
    private val limits: BucketLimits,
    private val clock: Clock = Clock.System,
) {
    private val state = ConcurrentHashMap<Key, ArrayDeque<Long>>()

    /**
     * Returns true and records the event if it fits in the window; returns
     * false and does NOT record otherwise.
     */
    fun tryAcquire(playerUuid: UUID, bucket: String): Boolean {
        val limit = limits.limitFor(bucket)
            ?: throw IllegalArgumentException("No limit configured for bucket '$bucket'")
        val now = clock.nowMs()
        val cutoff = now - limit.windowMs

        val key = Key(playerUuid, bucket)
        val deque = state.computeIfAbsent(key) { ArrayDeque() }

        synchronized(deque) {
            while (deque.peekFirst()?.let { it < cutoff } == true) {
                deque.pollFirst()
            }
            if (deque.size >= limit.max) return false
            deque.addLast(now)
            return true
        }
    }

    /** Diagnostic — current count in the window. */
    fun currentCount(playerUuid: UUID, bucket: String): Int {
        val limit = limits.limitFor(bucket) ?: return 0
        val now = clock.nowMs()
        val cutoff = now - limit.windowMs
        val deque = state[Key(playerUuid, bucket)] ?: return 0
        synchronized(deque) {
            while (deque.peekFirst()?.let { it < cutoff } == true) {
                deque.pollFirst()
            }
            return deque.size
        }
    }

    fun reset() {
        state.clear()
    }

    private data class Key(val playerUuid: UUID, val bucket: String)
}

/** A single limit: at most [max] events in any rolling window of [windowMs]. */
data class Limit(val max: Int, val windowMs: Long) {
    init {
        require(max >= 0) { "max must be >= 0" }
        require(windowMs > 0) { "windowMs must be > 0" }
    }
}

class BucketLimits(private val byName: Map<String, Limit>) {
    fun limitFor(bucket: String): Limit? = byName[bucket]
    val knownBuckets: Set<String> get() = byName.keys
}
