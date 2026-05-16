package dev.aidirector.memory

import java.util.UUID

/**
 * One row in the director's event log. Payload is opaque JSON — the schema is
 * defined by the producer (a sensor or tool result). Keep it human-readable;
 * the LLM will see truncated forms of these.
 */
data class EventRecord(
    val id: Long,
    val playerUuid: UUID,
    val kind: String,
    val payloadJson: String,
    val timestampMs: Long,
)

/** Allowed event kinds — keeps producers and consumers in sync. */
object EventKind {
    const val SENSOR_SNAPSHOT = "sensor.snapshot"
    const val TOOL_INVOKED = "tool.invoked"
    const val TOOL_REFUSED = "tool.refused"
    const val TOOL_FAILED = "tool.failed"
    const val PLAYER_JOIN = "player.join"
    const val PLAYER_LEAVE = "player.leave"
    const val PLAYER_DEATH = "player.death"
    const val PLAYER_HURT = "player.hurt"
    const val PLAYER_ADVANCEMENT = "player.advancement"
    const val PLAYER_CHAT = "player.chat"
    const val PLAYER_CHANGE_DIMENSION = "player.change_dimension"
    const val PLAYER_CRAFT = "player.craft"
    const val PLAYER_BREAK_BLOCK = "player.break_block"
    const val DIRECTOR_TICK = "director.tick"
}
