package dev.aidirector.support

import dev.aidirector.sensors.PlayerSnapshot
import dev.aidirector.sensors.Sensors
import java.util.UUID

class FakeSensors(private val snapshots: Map<UUID, PlayerSnapshot>) : Sensors {
    override fun snapshot(playerUuid: UUID): PlayerSnapshot? = snapshots[playerUuid]
    override fun onlinePlayers(): Set<UUID> = snapshots.keys
}
