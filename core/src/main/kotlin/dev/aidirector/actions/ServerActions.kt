package dev.aidirector.actions

import java.util.UUID

/**
 * Side-effecting operations the director can request. All implementations
 * must be safe to call from any thread — they marshal onto the MC server
 * thread internally where required.
 *
 * Return type is always [ActionOutcome]. For methods that produce entities,
 * the success outcome's `entityUuid`/`entityUuids` carry the spawned UUIDs so
 * the caller can persist them in the appropriate store.
 */
interface ServerActions {

    fun isPlayerOnline(playerUuid: UUID): Boolean
    fun getPlayerDimensionId(playerUuid: UUID): String?
    fun getPlayerPosition(playerUuid: UUID): IntArray?

    // ---- Atmosphere -----------------------------------------------------

    fun sendNarration(playerUuid: UUID, message: String, style: NarrationStyle): ActionOutcome
    fun playSound(playerUuid: UUID, soundId: String, volume: Float, pitch: Float): ActionOutcome
    fun spawnParticle(
        playerUuid: UUID,
        particleId: String,
        x: Double, y: Double, z: Double,
        count: Int,
        deltaX: Double, deltaY: Double, deltaZ: Double,
        speed: Double,
    ): ActionOutcome

    fun strikeLightning(dimensionId: String, x: Double, y: Double, z: Double, cosmeticOnly: Boolean): ActionOutcome

    fun createBossBar(
        playerUuid: UUID,
        name: String,
        color: BossBarColor,
        overlay: BossBarOverlay,
        durationSeconds: Int,
    ): ActionOutcome

    // ---- Player effects / gifts -----------------------------------------

    fun giveItem(playerUuid: UUID, itemId: String, count: Int, customName: String?): ActionOutcome

    fun giveBook(
        playerUuid: UUID,
        title: String,
        author: String,
        pages: List<String>,
    ): ActionOutcome

    fun applyEffect(
        playerUuid: UUID,
        effectId: String,
        durationTicks: Int,
        amplifier: Int,
        showParticles: Boolean,
    ): ActionOutcome

    fun grantAdvancement(
        playerUuid: UUID,
        holderId: String,
        title: String,
        description: String,
        iconItem: String,
        frame: AdvancementFrame,
        announceToChat: Boolean,
    ): ActionOutcome

    // ---- World ----------------------------------------------------------

    fun modifyWeather(playerUuid: UUID, weather: WeatherKind, durationTicks: Int): ActionOutcome
    fun setTime(dimensionId: String, label: TimeLabel): ActionOutcome
    fun placeBlock(dimensionId: String, x: Int, y: Int, z: Int, blockId: String): ActionOutcome

    /** Places a standing sign with up to 4 lines of text — a permanent, readable artifact. */
    fun placeSign(dimensionId: String, x: Int, y: Int, z: Int, lines: List<String>): ActionOutcome

    /**
     * Stamps a batch of blocks into the world in one operation — used by the
     * template-based structure builder. Each [PlacedBlock] carries absolute
     * coordinates. Denylisted blocks are skipped silently.
     */
    fun buildStructure(dimensionId: String, blocks: List<PlacedBlock>): ActionOutcome

    /**
     * Gives the player a REAL filled map centred on the target coordinates, with
     * a custom name. Unlike a raw `minecraft:map` (a blank crafting ingredient),
     * this is immediately readable and shows the terrain around the target.
     */
    fun giveTreasureMap(playerUuid: UUID, targetX: Int, targetZ: Int, label: String): ActionOutcome
    fun teleportPlayer(
        playerUuid: UUID,
        x: Double, y: Double, z: Double,
        dimensionId: String?,
    ): ActionOutcome

    // ---- Entities -------------------------------------------------------

    fun spawnNpc(
        ownerPlayer: UUID?,
        dimensionId: String,
        entityType: String,
        customName: String,
        x: Double, y: Double, z: Double,
        invulnerable: Boolean,
        ambientMovement: Boolean,
    ): ActionOutcome

    fun spawnMob(
        dimensionId: String,
        entityType: String,
        x: Double, y: Double, z: Double,
        count: Int,
        customName: String?,
        persistent: Boolean,
        hostileToPlayer: UUID?,
    ): ActionOutcome

    fun setMobEquipment(entityUuid: UUID, slot: EquipmentSlotName, itemId: String): ActionOutcome
    fun setMobTarget(entityUuid: UUID, targetPlayer: UUID): ActionOutcome
    fun killEntity(entityUuid: UUID): ActionOutcome

    // ---- Phantom player -------------------------------------------------
    // A fake player that exists only in the tab list and chat — no entity in
    // the world. Server-side; clients install nothing. Built for horror.

    /** Adds [name] to every online player's tab list and broadcasts a join message. */
    fun phantomJoin(phantomUuid: UUID, name: String): ActionOutcome

    /** Broadcasts a chat line attributed to the phantom: `<name> message`. */
    fun phantomSay(name: String, message: String): ActionOutcome

    /** Removes the phantom from the tab list and broadcasts a leave message. */
    fun phantomLeave(phantomUuid: UUID, name: String): ActionOutcome

    // ---- Registry checks -----------------------------------------------

    fun isItemRegistered(itemId: String): Boolean
    fun isSoundRegistered(soundId: String): Boolean
    fun isEffectRegistered(effectId: String): Boolean
    fun isEntityTypeRegistered(entityTypeId: String): Boolean
    fun isBlockRegistered(blockId: String): Boolean
    fun isParticleRegistered(particleId: String): Boolean
}

/** One absolute-coordinate block placement for [ServerActions.buildStructure]. */
data class PlacedBlock(val x: Int, val y: Int, val z: Int, val blockId: String)

enum class NarrationStyle { NARRATOR, WHISPER, ANNOUNCEMENT, SUBTITLE }

enum class WeatherKind { CLEAR, RAIN, THUNDER }

enum class TimeLabel { DAY, NOON, NIGHT, MIDNIGHT, DAWN, DUSK }

enum class BossBarColor { PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE }

enum class BossBarOverlay { PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12, NOTCHED_20 }

enum class EquipmentSlotName { MAINHAND, OFFHAND, HEAD, CHEST, LEGS, FEET }

enum class AdvancementFrame { TASK, GOAL, CHALLENGE }

sealed interface ActionOutcome {
    val message: String
    val ok: Boolean
    data class Success(
        override val message: String,
        val entityUuid: UUID? = null,
        val entityUuids: List<UUID> = emptyList(),
    ) : ActionOutcome {
        override val ok: Boolean get() = true
    }
    data class Failure(override val message: String) : ActionOutcome {
        override val ok: Boolean get() = false
    }
}
