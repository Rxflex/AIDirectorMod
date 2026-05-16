package dev.aidirector.sensors

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Aggregated view of a player at a single point in time. Built by the platform
 * sensor implementation; consumed by the director loop to build LLM prompts.
 *
 * Keep this lean — every field ends up serialized into the prompt, so each one
 * costs tokens. Add only signals the LLM can meaningfully react to.
 */
@Serializable
data class PlayerSnapshot(
    val playerUuid: String,
    val playerName: String,
    val dimensionId: String,
    val biomeId: String,
    val position: Position,
    val lightLevel: Int,
    val timeOfDay: TimeOfDay,
    val weather: Weather,
    val health: Vital,
    val food: Vital,
    val inventorySummary: List<InventoryEntry>,
    val nearbyHostileCount: Int,
    val nearbyPeacefulCount: Int,
    val capturedAtMs: Long,
) {
    companion object {
        fun playerUuidOf(s: PlayerSnapshot): UUID = UUID.fromString(s.playerUuid)
    }
}

@Serializable
data class Position(val x: Int, val y: Int, val z: Int)

@Serializable
enum class TimeOfDay { DAWN, DAY, DUSK, NIGHT }

@Serializable
data class Weather(val raining: Boolean, val thundering: Boolean)

@Serializable
data class Vital(val current: Int, val max: Int) {
    val ratio: Double get() = if (max > 0) current.toDouble() / max else 0.0
}

@Serializable
data class InventoryEntry(val itemId: String, val count: Int)
