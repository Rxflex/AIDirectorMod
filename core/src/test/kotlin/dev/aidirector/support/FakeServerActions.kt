package dev.aidirector.support

import dev.aidirector.actions.ActionOutcome
import dev.aidirector.actions.AdvancementFrame
import dev.aidirector.actions.BossBarColor
import dev.aidirector.actions.BossBarOverlay
import dev.aidirector.actions.EquipmentSlotName
import dev.aidirector.actions.NarrationStyle
import dev.aidirector.actions.ServerActions
import dev.aidirector.actions.TimeLabel
import dev.aidirector.actions.WeatherKind
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class FakeServerActions(
    private val knownItems: Set<String> = setOf("minecraft:bread", "minecraft:diamond"),
    private val knownSounds: Set<String> = setOf("minecraft:entity.wolf.howl"),
    private val knownEffects: Set<String> = setOf("minecraft:regeneration", "minecraft:slowness"),
    private val knownEntityTypes: Set<String> = setOf("minecraft:villager", "minecraft:zombie"),
    private val knownBlocks: Set<String> = setOf("minecraft:stone", "minecraft:torch"),
    private val knownParticles: Set<String> = setOf("minecraft:soul_fire_flame", "minecraft:smoke"),
) : ServerActions {

    val calls: MutableList<Call> = CopyOnWriteArrayList()

    sealed interface Call {
        data class Narration(val player: UUID, val message: String, val style: NarrationStyle) : Call
        data class Sound(val player: UUID, val soundId: String, val volume: Float, val pitch: Float) : Call
        data class Item(val player: UUID, val itemId: String, val count: Int, val name: String?) : Call
        data class Effect(val player: UUID, val effectId: String, val durationTicks: Int, val amplifier: Int) : Call
        data class WeatherChange(val player: UUID, val kind: WeatherKind, val durationTicks: Int) : Call
        data class Particle(val player: UUID, val particleId: String, val count: Int) : Call
        data class Lightning(val dimensionId: String, val cosmetic: Boolean) : Call
        data class BossBar(val player: UUID, val name: String, val durationSeconds: Int) : Call
        data class Book(val player: UUID, val title: String, val pages: Int) : Call
        data class Advancement(val player: UUID, val title: String, val frame: AdvancementFrame) : Call
        data class NpcSpawn(val ownerPlayer: UUID?, val name: String, val entityType: String) : Call
        data class MobSpawn(val entityType: String, val count: Int) : Call
        data class MobEquip(val entityUuid: UUID, val slot: EquipmentSlotName, val itemId: String) : Call
        data class MobTarget(val entityUuid: UUID, val targetPlayer: UUID) : Call
        data class EntityKill(val entityUuid: UUID) : Call
        data class TimeSet(val dimensionId: String, val label: TimeLabel) : Call
        data class BlockPlace(val dimensionId: String, val blockId: String) : Call
        data class Teleport(val player: UUID, val x: Double, val y: Double, val z: Double) : Call
        data class SignPlace(val dimensionId: String, val lines: List<String>) : Call
        data class TreasureMap(val player: UUID, val targetX: Int, val targetZ: Int, val label: String) : Call
        data class Structure(val dimensionId: String, val blockCount: Int) : Call
        data class PhantomJoin(val name: String) : Call
        data class PhantomSay(val name: String, val message: String) : Call
        data class PhantomLeave(val name: String) : Call
    }

    override fun isPlayerOnline(playerUuid: UUID): Boolean = true
    override fun getPlayerDimensionId(playerUuid: UUID): String = "minecraft:overworld"
    override fun getPlayerPosition(playerUuid: UUID): IntArray = intArrayOf(0, 64, 0)

    override fun sendNarration(playerUuid: UUID, message: String, style: NarrationStyle): ActionOutcome {
        calls += Call.Narration(playerUuid, message, style)
        return ActionOutcome.Success("ok")
    }

    override fun playSound(playerUuid: UUID, soundId: String, volume: Float, pitch: Float): ActionOutcome {
        calls += Call.Sound(playerUuid, soundId, volume, pitch)
        return ActionOutcome.Success("ok")
    }

    override fun spawnParticle(
        playerUuid: UUID, particleId: String,
        x: Double, y: Double, z: Double, count: Int,
        deltaX: Double, deltaY: Double, deltaZ: Double, speed: Double,
    ): ActionOutcome {
        calls += Call.Particle(playerUuid, particleId, count)
        return ActionOutcome.Success("ok")
    }

    override fun strikeLightning(dimensionId: String, x: Double, y: Double, z: Double, cosmeticOnly: Boolean): ActionOutcome {
        calls += Call.Lightning(dimensionId, cosmeticOnly)
        return ActionOutcome.Success("ok")
    }

    override fun createBossBar(
        playerUuid: UUID, name: String,
        color: BossBarColor, overlay: BossBarOverlay, durationSeconds: Int,
    ): ActionOutcome {
        calls += Call.BossBar(playerUuid, name, durationSeconds)
        return ActionOutcome.Success("ok")
    }

    override fun giveItem(playerUuid: UUID, itemId: String, count: Int, customName: String?): ActionOutcome {
        calls += Call.Item(playerUuid, itemId, count, customName)
        return ActionOutcome.Success("ok")
    }

    override fun giveBook(playerUuid: UUID, title: String, author: String, pages: List<String>): ActionOutcome {
        calls += Call.Book(playerUuid, title, pages.size)
        return ActionOutcome.Success("ok")
    }

    override fun applyEffect(
        playerUuid: UUID, effectId: String,
        durationTicks: Int, amplifier: Int, showParticles: Boolean,
    ): ActionOutcome {
        calls += Call.Effect(playerUuid, effectId, durationTicks, amplifier)
        return ActionOutcome.Success("ok")
    }

    override fun grantAdvancement(
        playerUuid: UUID, holderId: String,
        title: String, description: String, iconItem: String,
        frame: AdvancementFrame, announceToChat: Boolean,
    ): ActionOutcome {
        calls += Call.Advancement(playerUuid, title, frame)
        return ActionOutcome.Success("ok")
    }

    override fun modifyWeather(playerUuid: UUID, weather: WeatherKind, durationTicks: Int): ActionOutcome {
        calls += Call.WeatherChange(playerUuid, weather, durationTicks)
        return ActionOutcome.Success("ok")
    }

    override fun setTime(dimensionId: String, label: TimeLabel): ActionOutcome {
        calls += Call.TimeSet(dimensionId, label)
        return ActionOutcome.Success("ok")
    }

    override fun placeBlock(dimensionId: String, x: Int, y: Int, z: Int, blockId: String): ActionOutcome {
        calls += Call.BlockPlace(dimensionId, blockId)
        return ActionOutcome.Success("ok")
    }

    override fun placeSign(dimensionId: String, x: Int, y: Int, z: Int, lines: List<String>): ActionOutcome {
        calls += Call.SignPlace(dimensionId, lines)
        return ActionOutcome.Success("ok")
    }

    override fun giveTreasureMap(playerUuid: UUID, targetX: Int, targetZ: Int, label: String): ActionOutcome {
        calls += Call.TreasureMap(playerUuid, targetX, targetZ, label)
        return ActionOutcome.Success("ok")
    }

    override fun buildStructure(
        dimensionId: String,
        blocks: List<dev.aidirector.actions.PlacedBlock>,
    ): ActionOutcome {
        calls += Call.Structure(dimensionId, blocks.size)
        return ActionOutcome.Success("ok")
    }

    override fun teleportPlayer(playerUuid: UUID, x: Double, y: Double, z: Double, dimensionId: String?): ActionOutcome {
        calls += Call.Teleport(playerUuid, x, y, z)
        return ActionOutcome.Success("ok")
    }

    override fun spawnNpc(
        ownerPlayer: UUID?, dimensionId: String, entityType: String,
        customName: String, x: Double, y: Double, z: Double,
        invulnerable: Boolean, ambientMovement: Boolean,
    ): ActionOutcome {
        val uuid = UUID.randomUUID()
        calls += Call.NpcSpawn(ownerPlayer, customName, entityType)
        return ActionOutcome.Success("ok", entityUuid = uuid)
    }

    override fun spawnMob(
        dimensionId: String, entityType: String,
        x: Double, y: Double, z: Double, count: Int,
        customName: String?, persistent: Boolean, hostileToPlayer: UUID?,
    ): ActionOutcome {
        val uuids = (1..count).map { UUID.randomUUID() }
        calls += Call.MobSpawn(entityType, count)
        return ActionOutcome.Success("ok", entityUuid = uuids.firstOrNull(), entityUuids = uuids)
    }

    override fun setMobEquipment(entityUuid: UUID, slot: EquipmentSlotName, itemId: String): ActionOutcome {
        calls += Call.MobEquip(entityUuid, slot, itemId)
        return ActionOutcome.Success("ok")
    }

    override fun setMobTarget(entityUuid: UUID, targetPlayer: UUID): ActionOutcome {
        calls += Call.MobTarget(entityUuid, targetPlayer)
        return ActionOutcome.Success("ok")
    }

    override fun killEntity(entityUuid: UUID): ActionOutcome {
        calls += Call.EntityKill(entityUuid)
        return ActionOutcome.Success("ok")
    }

    override fun phantomJoin(phantomUuid: UUID, name: String): ActionOutcome {
        calls += Call.PhantomJoin(name)
        return ActionOutcome.Success("ok")
    }

    override fun phantomSay(name: String, message: String): ActionOutcome {
        calls += Call.PhantomSay(name, message)
        return ActionOutcome.Success("ok")
    }

    override fun phantomLeave(phantomUuid: UUID, name: String): ActionOutcome {
        calls += Call.PhantomLeave(name)
        return ActionOutcome.Success("ok")
    }

    override fun isItemRegistered(itemId: String): Boolean = itemId in knownItems
    override fun isSoundRegistered(soundId: String): Boolean = soundId in knownSounds
    override fun isEffectRegistered(effectId: String): Boolean = effectId in knownEffects
    override fun isEntityTypeRegistered(entityTypeId: String): Boolean = entityTypeId in knownEntityTypes
    override fun isBlockRegistered(blockId: String): Boolean = blockId in knownBlocks
    override fun isParticleRegistered(particleId: String): Boolean = particleId in knownParticles
}
