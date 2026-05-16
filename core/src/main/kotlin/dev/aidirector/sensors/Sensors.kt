package dev.aidirector.sensors

import java.util.UUID

/**
 * Read-side counterpart to [dev.aidirector.actions.ServerActions]. Platform
 * implementations expose game state to the common director loop. Like
 * ServerActions, implementations must be safe to call from any thread —
 * marshal onto the server thread internally.
 */
interface Sensors {
    /** Returns null if the player is offline or no snapshot can be built. */
    fun snapshot(playerUuid: UUID): PlayerSnapshot?

    /** Returns currently-online player UUIDs. */
    fun onlinePlayers(): Set<UUID>
}

object SensorsHolder {
    @Volatile
    private var instance: Sensors? = null

    fun install(sensors: Sensors) {
        instance = sensors
    }

    fun require(): Sensors = instance
        ?: throw IllegalStateException(
            "Sensors not installed. Platform module must call SensorsHolder.install() during init.",
        )

    internal fun reset() {
        instance = null
    }
}
