package dev.aidirector.dedup

import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Lexical near-duplicate detector for player-facing narration. Maintains a
 * short ring per player; rejects text whose Jaccard similarity (on
 * character trigrams of normalised content) crosses [threshold] against any
 * recent entry within [maxAgeMs].
 *
 * Trigram Jaccard catches the cases we actually see in practice — the LLM
 * paraphrasing the same line in different word order, swapping "wind"
 * for "breeze", etc. — without LLM calls or per-tick latency cost. ~5µs
 * per comparison on a typical CPU.
 */
class NarrationDedup(
    private val threshold: Double = 0.40,
    private val maxAgeMs: Long = 20 * 60_000L,
    private val maxPerPlayer: Int = 12,
) {
    private data class Entry(val text: String, val tokens: Set<String>, val tsMs: Long)

    private val perPlayer = ConcurrentHashMap<UUID, ArrayDeque<Entry>>()

    /**
     * Returns [Result.Fresh] and records the text if it is sufficiently new
     * compared to recent entries; returns [Result.TooSimilar] without
     * recording it otherwise. The caller should refuse to deliver the
     * narration in the second case.
     */
    fun checkAndStore(playerUuid: UUID, text: String, nowMs: Long): Result {
        val candidate = trigrams(text)
        if (candidate.isEmpty()) {
            // Too short / empty after normalisation — let it through, store nothing.
            return Result.Fresh
        }
        val deque = perPlayer.computeIfAbsent(playerUuid) { ArrayDeque() }
        synchronized(deque) {
            while (deque.peekFirst()?.let { nowMs - it.tsMs > maxAgeMs } == true) {
                deque.pollFirst()
            }
            for (entry in deque) {
                val sim = jaccard(candidate, entry.tokens)
                if (sim >= threshold) {
                    return Result.TooSimilar(entry.text, sim, nowMs - entry.tsMs)
                }
            }
            deque.addLast(Entry(text, candidate, nowMs))
            while (deque.size > maxPerPlayer) deque.pollFirst()
        }
        return Result.Fresh
    }

    /** Test-only. */
    internal fun reset() {
        perPlayer.clear()
    }

    private fun trigrams(text: String): Set<String> {
        // Normalise: lowercase, drop punctuation, collapse whitespace.
        val cleaned = text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.length < 3) return emptySet()
        val out = HashSet<String>(cleaned.length - 2)
        for (i in 0..cleaned.length - 3) out += cleaned.substring(i, i + 3)
        return out
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var inter = 0
        val smaller = if (a.size <= b.size) a else b
        val larger = if (a.size <= b.size) b else a
        for (x in smaller) if (x in larger) inter++
        val union = a.size + b.size - inter
        return inter.toDouble() / union
    }

    sealed interface Result {
        data object Fresh : Result
        data class TooSimilar(val previousText: String, val similarity: Double, val sinceMs: Long) : Result
    }
}
