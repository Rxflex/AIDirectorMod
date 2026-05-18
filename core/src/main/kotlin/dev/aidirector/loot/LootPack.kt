package dev.aidirector.loot

import dev.aidirector.AIDirector
import dev.aidirector.util.DirectorJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random

/** One weighted possibility in a [LootTable]. */
@Serializable
data class LootDrop(
    val item: String,
    val weight: Int = 1,
    val min: Int = 1,
    val max: Int = 1,
)

/**
 * A themed bundle of loot the director can hand to a player. These are simple
 * JSON files bundled in the mod jar (`/loot/<id>.json`) — the mod's own
 * content pack — and rolled in pure Kotlin, so they behave identically on
 * every loader and every Minecraft version with no dependency on Minecraft's
 * loot-table API.
 */
@Serializable
data class LootTable(
    val name: String,
    @SerialName("rolls_min") val rollsMin: Int = 1,
    @SerialName("rolls_max") val rollsMax: Int = 1,
    val entries: List<LootDrop>,
)

/** One item produced by rolling a [LootTable]. */
data class RolledItem(val itemId: String, val count: Int)

object LootRoller {
    fun roll(table: LootTable, random: Random = Random.Default): List<RolledItem> {
        val entries = table.entries.filter { it.weight > 0 }
        if (entries.isEmpty()) return emptyList()
        val lo = table.rollsMin.coerceAtLeast(0)
        val hi = table.rollsMax.coerceAtLeast(lo)
        val rolls = if (hi > lo) random.nextInt(lo, hi + 1) else lo
        val totalWeight = entries.sumOf { it.weight }
        val out = ArrayList<RolledItem>(rolls)
        repeat(rolls) {
            var pick = random.nextInt(totalWeight)
            val drop = entries.first { e -> pick -= e.weight; pick < 0 }
            val count = if (drop.max > drop.min) random.nextInt(drop.min, drop.max + 1) else drop.min
            out += RolledItem(drop.item, count.coerceAtLeast(1))
        }
        return out
    }
}

/** The bundled loot tables, loaded from jar resources on first use. */
object LootPacks {

    /** Stable ids — also the `/loot/<id>.json` resource names. */
    val ids: List<String> = listOf(
        "cursed_chest",
        "grave_goods",
        "travelers_cache",
        "ritual_remains",
    )

    private val cache = HashMap<String, LootTable?>()

    fun load(id: String): LootTable? = cache.getOrPut(id) {
        val key = id.trim().lowercase()
        if (key !in ids) return@getOrPut null
        val stream = LootPacks::class.java.getResourceAsStream("/loot/$key.json")
        if (stream == null) {
            AIDirector.log.warn("Bundled loot table '/loot/$key.json' not found")
            return@getOrPut null
        }
        try {
            stream.use { DirectorJson.decodeFromString<LootTable>(it.readBytes().decodeToString()) }
        } catch (e: Exception) {
            AIDirector.log.warn("Failed to parse loot table '$key': ${e.message}")
            null
        }
    }
}
