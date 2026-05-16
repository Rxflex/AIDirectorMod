package dev.aidirector.bootstrap

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-player, per-kind minimum interval between recorded events. Prevents
 * floods (every block break, every tick of damage) from blowing the SQLite
 * event log out of proportion.
 */
object EventThrottle {

    /** Default ms between two recorded events of the same kind for the same player. */
    private const val DEFAULT_GAP_MS = 2_000L
    private val GAP_BY_KIND = mapOf(
        "player.hurt" to 1_500L,
        "player.break_block" to 3_000L,
        "player.craft" to 3_000L,
        "player.chat" to 250L,
    )

    private val last = ConcurrentHashMap<Pair<UUID, String>, Long>()

    fun shouldRecord(playerUuid: UUID, kind: String, nowMs: Long): Boolean {
        val gap = GAP_BY_KIND[kind] ?: DEFAULT_GAP_MS
        val key = playerUuid to kind
        val prev = last[key]
        if (prev != null && nowMs - prev < gap) return false
        last[key] = nowMs
        return true
    }

    fun reset() {
        last.clear()
    }
}
