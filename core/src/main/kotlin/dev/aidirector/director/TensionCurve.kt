package dev.aidirector.director

import dev.aidirector.sensors.PlayerSnapshot
import dev.aidirector.sensors.TimeOfDay
import kotlin.math.max
import kotlin.math.min

/**
 * Maps a snapshot + recent activity to a 0..1 "tension" score that biases the
 * director's prompt. The mapping is deliberately simple and deterministic —
 * the LLM uses tension as one input among many, not as a hard threshold.
 *
 * Higher tension favors quieter actions (a found note, a friendly NPC);
 * lower tension favors atmospheric escalation (storm, distant howl).
 */
object TensionCurve {

    /** [recentlyTookDamage] = true if the player lost HP within the last ~15s. */
    fun compute(snapshot: PlayerSnapshot, recentlyTookDamage: Boolean): Double {
        var t = 0.0
        // Low HP raises tension.
        t += (1.0 - snapshot.health.ratio).coerceIn(0.0, 1.0) * 0.35
        // Hostile mobs nearby raise tension.
        t += min(0.30, snapshot.nearbyHostileCount * 0.06)
        // Night/dusk is more tense than day.
        t += when (snapshot.timeOfDay) {
            TimeOfDay.NIGHT -> 0.15
            TimeOfDay.DUSK -> 0.08
            TimeOfDay.DAWN -> 0.04
            TimeOfDay.DAY -> 0.0
        }
        // Low light raises tension a little.
        t += ((15 - snapshot.lightLevel).coerceAtLeast(0) / 15.0) * 0.10
        // Thunderstorm is dramatic regardless of context.
        if (snapshot.weather.thundering) t += 0.10
        else if (snapshot.weather.raining) t += 0.03
        // Just-took-damage bumps tension immediately.
        if (recentlyTookDamage) t = max(t, 0.65)

        return t.coerceIn(0.0, 1.0)
    }

    /** Human-readable label for the prompt. */
    fun describe(score: Double): String = when {
        score < 0.20 -> "very low — the player has been idle/safe"
        score < 0.40 -> "low — calm exploration"
        score < 0.60 -> "moderate — some friction or unease"
        score < 0.80 -> "high — the player is in real danger or stressed"
        else -> "extreme — combat or near-death"
    }
}
