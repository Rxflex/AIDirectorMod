package dev.aidirector.structure

/**
 * Hand-authored, small, robust building templates. LLMs are poor at spatial
 * composition — so the director never places blocks free-hand to "build"
 * something. Instead it picks a named template here, and the engine stamps a
 * known-good arrangement into the world.
 *
 * Coordinates are RELATIVE to the structure origin (the coordinate the
 * director chooses). Every template is designed so that:
 *  - sign slots always have a solid block directly beneath them;
 *  - nothing exceeds a 5×5×5 footprint (cheap, never griefs a base);
 *  - all blocks are vanilla and decorative.
 */
object StructureTemplates {

    fun resolve(name: String): StructureTemplate? = byName[name.lowercase()]

    val names: Set<String> get() = byName.keys

    private fun b(dx: Int, dy: Int, dz: Int, id: String) = RelBlock(dx, dy, dz, id)
    private fun s(dx: Int, dy: Int, dz: Int) = RelSign(dx, dy, dz)

    private val byName: Map<String, StructureTemplate> = listOf(

        StructureTemplate(
            name = "cairn",
            description = "A weathered pile of mossy stones — a wordless trail marker or burial mound.",
            blocks = listOf(
                b(0, 0, 0, "minecraft:cobblestone"),
                b(1, 0, 0, "minecraft:cobblestone"),
                b(-1, 0, 0, "minecraft:cobblestone"),
                b(0, 0, 1, "minecraft:cobblestone"),
                b(0, 0, -1, "minecraft:mossy_cobblestone"),
                b(0, 1, 0, "minecraft:mossy_cobblestone"),
                b(1, 1, 0, "minecraft:mossy_cobblestone"),
                b(0, 1, 1, "minecraft:cobblestone"),
                b(0, 2, 0, "minecraft:mossy_cobblestone"),
            ),
            signSlots = emptyList(),
        ),

        StructureTemplate(
            name = "grave",
            description = "A low earthen mound with a single sign at its head — somewhere, someone is buried.",
            blocks = listOf(
                b(0, 0, 0, "minecraft:coarse_dirt"),
                b(1, 0, 0, "minecraft:coarse_dirt"),
                b(-1, 0, 0, "minecraft:coarse_dirt"),
                b(0, 0, 1, "minecraft:podzol"),
                b(0, 0, -1, "minecraft:podzol"),
                b(0, 1, 0, "minecraft:coarse_dirt"),
            ),
            // Sign stands on the podzol at (0,0,1).
            signSlots = listOf(s(0, 1, 1)),
        ),

        StructureTemplate(
            name = "shrine",
            description = "A small stone pillar crowned with a lantern — a quiet place of reverence.",
            blocks = listOf(
                b(0, 0, 0, "minecraft:stone_bricks"),
                b(1, 0, 0, "minecraft:stone_brick_slab"),
                b(-1, 0, 0, "minecraft:stone_brick_slab"),
                b(0, 0, 1, "minecraft:stone_brick_slab"),
                b(0, 0, -1, "minecraft:stone_brick_slab"),
                b(0, 1, 0, "minecraft:chiseled_stone_bricks"),
                b(0, 2, 0, "minecraft:stone_bricks"),
                b(0, 3, 0, "minecraft:lantern"),
            ),
            signSlots = emptyList(),
        ),

        StructureTemplate(
            name = "memorial",
            description = "A short cobblestone wall flanked by torches with a sign — a public marker of something lost.",
            blocks = listOf(
                b(-1, 0, 0, "minecraft:cobblestone"),
                b(0, 0, 0, "minecraft:cobblestone"),
                b(1, 0, 0, "minecraft:cobblestone"),
                b(-1, 1, 0, "minecraft:cobblestone"),
                b(0, 1, 0, "minecraft:cobblestone"),
                b(1, 1, 0, "minecraft:cobblestone"),
                b(-1, 2, 0, "minecraft:torch"),
                b(1, 2, 0, "minecraft:torch"),
            ),
            // Sign stands on the centre cobblestone at (0,1,0).
            signSlots = listOf(s(0, 2, 0)),
        ),

        StructureTemplate(
            name = "campfire_ring",
            description = "A lit campfire ringed by log seats — the trace of travellers who rested here.",
            blocks = listOf(
                b(0, 0, 0, "minecraft:campfire"),
                b(2, 0, 0, "minecraft:oak_log"),
                b(-2, 0, 0, "minecraft:oak_log"),
                b(0, 0, 2, "minecraft:oak_log"),
                b(0, 0, -2, "minecraft:oak_log"),
            ),
            signSlots = emptyList(),
        ),

        StructureTemplate(
            name = "waystone",
            description = "A carved stone-brick column topped with a sign — names a place, points a direction.",
            blocks = listOf(
                b(0, 0, 0, "minecraft:stone_bricks"),
                b(0, 1, 0, "minecraft:stone_bricks"),
                b(0, 2, 0, "minecraft:chiseled_stone_bricks"),
                b(1, 0, 0, "minecraft:mossy_stone_bricks"),
                b(-1, 0, 0, "minecraft:mossy_stone_bricks"),
            ),
            // Sign stands on top of the chiseled block at (0,2,0).
            signSlots = listOf(s(0, 3, 0)),
        ),
    ).associateBy { it.name }
}

data class StructureTemplate(
    val name: String,
    val description: String,
    val blocks: List<RelBlock>,
    val signSlots: List<RelSign>,
)

data class RelBlock(val dx: Int, val dy: Int, val dz: Int, val blockId: String)

data class RelSign(val dx: Int, val dy: Int, val dz: Int)
