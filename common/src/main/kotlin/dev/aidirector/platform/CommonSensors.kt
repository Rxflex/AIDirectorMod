package dev.aidirector.platform

import dev.aidirector.sensors.InventoryEntry
import dev.aidirector.sensors.PlayerSnapshot
import dev.aidirector.sensors.Position
import dev.aidirector.sensors.Sensors
import dev.aidirector.sensors.TimeOfDay
import dev.aidirector.sensors.Vital
import dev.aidirector.sensors.Weather
import dev.aidirector.util.Clock
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.phys.AABB
import java.util.UUID

class CommonSensors(
    private val serverSupplier: () -> MinecraftServer?,
    private val clock: Clock = Clock.System,
) : Sensors {

    private fun server(): MinecraftServer? = serverSupplier()

    override fun onlinePlayers(): Set<UUID> =
        server()?.playerList?.players?.mapTo(HashSet()) { it.uuid } ?: emptySet()

    override fun snapshot(playerUuid: UUID): PlayerSnapshot? {
        val server = server() ?: return null
        val player = server.playerList.getPlayer(playerUuid) ?: return null
        val level = player.serverLevel()

        return PlayerSnapshot(
            playerUuid = player.uuid.toString(),
            playerName = player.gameProfile.name,
            dimensionId = level.dimension().location().toString(),
            biomeId = level.getBiome(player.blockPosition()).unwrapKey()
                .map { it.location().toString() }.orElse("minecraft:unknown"),
            position = Position(player.blockX, player.blockY, player.blockZ),
            lightLevel = level.getRawBrightness(player.blockPosition(), 0),
            timeOfDay = timeOfDay(level),
            weather = Weather(raining = level.isRaining, thundering = level.isThundering),
            health = Vital(current = player.health.toInt().coerceAtLeast(0), max = player.maxHealth.toInt()),
            food = Vital(current = player.foodData.foodLevel, max = 20),
            inventorySummary = inventorySummary(player),
            nearbyHostileCount = countMobs(level, player, MobCategory.MONSTER),
            nearbyPeacefulCount = countMobs(level, player, MobCategory.CREATURE),
            capturedAtMs = clock.nowMs(),
        )
    }

    private fun timeOfDay(level: ServerLevel): TimeOfDay {
        // dayTime ticks: 0 sunrise, 6000 noon, 12000 sunset, 18000 midnight
        val t = (level.dayTime % 24_000L).toInt()
        return when (t) {
            in 0..1_000 -> TimeOfDay.DAWN
            in 1_001..11_500 -> TimeOfDay.DAY
            in 11_501..13_000 -> TimeOfDay.DUSK
            else -> TimeOfDay.NIGHT
        }
    }

    private fun inventorySummary(player: ServerPlayer): List<InventoryEntry> {
        val counts = HashMap<String, Int>()
        for (slot in 0 until player.inventory.containerSize) {
            val stack = player.inventory.getItem(slot)
            if (stack.isEmpty) continue
            val id = BuiltInRegistries.ITEM.getKey(stack.item).toString()
            counts.merge(id, stack.count) { a, b -> a + b }
        }
        return counts.entries
            .sortedByDescending { it.value }
            .take(MAX_INVENTORY_ENTRIES)
            .map { InventoryEntry(it.key, it.value) }
    }

    private fun countMobs(level: ServerLevel, player: ServerPlayer, category: MobCategory): Int {
        val pos = player.position()
        val box = AABB(
            pos.x - SCAN_RADIUS, pos.y - SCAN_RADIUS, pos.z - SCAN_RADIUS,
            pos.x + SCAN_RADIUS, pos.y + SCAN_RADIUS, pos.z + SCAN_RADIUS,
        )
        return level.getEntitiesOfClass(Mob::class.java, box) { it.type.category == category }.size
    }

    companion object {
        private const val MAX_INVENTORY_ENTRIES = 5
        private const val SCAN_RADIUS = 16.0
    }
}
