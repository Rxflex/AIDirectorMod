package dev.aidirector.paper

import dev.aidirector.sensors.InventoryEntry
import dev.aidirector.sensors.PlayerSnapshot
import dev.aidirector.sensors.Position
import dev.aidirector.sensors.Sensors
import dev.aidirector.sensors.TimeOfDay
import dev.aidirector.sensors.Vital
import dev.aidirector.sensors.Weather
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Monster
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.BoundingBox
import java.util.UUID

class PaperSensors(private val plugin: JavaPlugin) : Sensors {

    private val server get() = plugin.server

    override fun onlinePlayers(): Set<UUID> =
        server.onlinePlayers.mapTo(HashSet()) { it.uniqueId }

    override fun snapshot(playerUuid: UUID): PlayerSnapshot? {
        val player: Player = server.getPlayer(playerUuid) ?: return null
        val loc = player.location
        val world = player.world

        val time = world.time
        val tod = when (((time % 24_000L)).toInt()) {
            in 0..1_000 -> TimeOfDay.DAWN
            in 1_001..11_500 -> TimeOfDay.DAY
            in 11_501..13_000 -> TimeOfDay.DUSK
            else -> TimeOfDay.NIGHT
        }

        val hostileBox = BoundingBox.of(loc, 16.0, 16.0, 16.0)
        val nearby = world.getNearbyEntities(hostileBox)
        val hostile = nearby.count { it is Monster }
        val peaceful = nearby.count { it is LivingEntity && it !is Monster && it !is Player }

        return PlayerSnapshot(
            playerUuid = player.uniqueId.toString(),
            playerName = player.name,
            dimensionId = world.key.toString(),
            biomeId = world.getBiome(loc).key.toString(),
            position = Position(loc.blockX, loc.blockY, loc.blockZ),
            lightLevel = loc.block.lightLevel.toInt(),
            timeOfDay = tod,
            weather = Weather(raining = world.hasStorm(), thundering = world.isThundering),
            health = Vital(player.health.toInt().coerceAtLeast(0), player.maxHealth.toInt()),
            food = Vital(player.foodLevel, 20),
            inventorySummary = inventorySummary(player),
            nearbyHostileCount = hostile,
            nearbyPeacefulCount = peaceful,
            capturedAtMs = System.currentTimeMillis(),
        )
    }

    private fun inventorySummary(player: Player): List<InventoryEntry> {
        val counts = HashMap<String, Int>()
        for (stack in player.inventory.contents) {
            if (stack == null || stack.type == org.bukkit.Material.AIR) continue
            val id = stack.type.key.toString()
            counts.merge(id, stack.amount) { a, b -> a + b }
        }
        return counts.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { InventoryEntry(it.key, it.value) }
    }
}
