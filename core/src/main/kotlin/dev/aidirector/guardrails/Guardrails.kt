package dev.aidirector.guardrails

import dev.aidirector.config.GuardrailsConfig
import dev.aidirector.util.Clock
import java.util.UUID

/**
 * High-level guardrail policy. Wraps [RateLimiter] with per-tool bucket names
 * derived from config, and exposes a simple "may the director use tool X for
 * player Y right now?" check.
 *
 * Buckets:
 * - `spawn`   — reserved for the future spawn_npc tool (not in v0.1)
 * - `effect`  — apply_effect
 * - `sound`   — play_sound
 * - `item`    — give_item
 * - `narration` — send_narration
 * - `weather` — modify_weather
 */
class Guardrails(
    cfg: GuardrailsConfig,
    clock: Clock = Clock.System,
) {
    private val limiter: RateLimiter

    init {
        val minute = 60_000L
        val hour = 60 * minute
        val limits = mapOf(
            BUCKET_SPAWN to Limit(cfg.maxSpawnsPerMinute, minute),
            BUCKET_EFFECT to Limit(cfg.maxEffectsPerMinute, minute),
            BUCKET_SOUND to Limit(cfg.maxSoundsPerMinute, minute),
            BUCKET_ITEM to Limit(cfg.maxItemsPerMinute, minute),
            BUCKET_NARRATION to Limit(cfg.maxNarrationsPerMinute, minute),
            BUCKET_WEATHER to Limit(cfg.maxWeatherPerHour, hour),
        )
        limiter = RateLimiter(BucketLimits(limits), clock)
    }

    /** Returns whether the given tool may run now for this player. Consumes the budget if true. */
    fun checkAndRecord(playerUuid: UUID, toolName: String): GuardrailDecision {
        val bucket = bucketFor(toolName)
            ?: return GuardrailDecision.Denied("Tool '$toolName' is not allow-listed for the director")
        return if (limiter.tryAcquire(playerUuid, bucket)) {
            GuardrailDecision.Allowed
        } else {
            GuardrailDecision.Denied("Rate limit reached for bucket '$bucket'")
        }
    }

    fun reset() {
        limiter.reset()
    }

    private fun bucketFor(toolName: String): String? = when (toolName) {
        "send_narration" -> BUCKET_NARRATION
        "play_sound" -> BUCKET_SOUND
        "give_item" -> BUCKET_ITEM
        "apply_effect" -> BUCKET_EFFECT
        "modify_weather" -> BUCKET_WEATHER
        "spawn_npc" -> BUCKET_SPAWN
        else -> null
    }

    companion object {
        const val BUCKET_SPAWN = "spawn"
        const val BUCKET_EFFECT = "effect"
        const val BUCKET_SOUND = "sound"
        const val BUCKET_ITEM = "item"
        const val BUCKET_NARRATION = "narration"
        const val BUCKET_WEATHER = "weather"
    }
}

sealed interface GuardrailDecision {
    data object Allowed : GuardrailDecision
    data class Denied(val reason: String) : GuardrailDecision
}
