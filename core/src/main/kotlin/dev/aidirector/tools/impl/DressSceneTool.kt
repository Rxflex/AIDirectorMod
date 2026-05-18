package dev.aidirector.tools.impl

import dev.aidirector.actions.PlacedBlock
import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.tools.Schemas
import dev.aidirector.tools.Tool
import dev.aidirector.tools.ToolContext
import dev.aidirector.tools.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Places a single decorative, atmospheric block — cobweb, vine, soul torch,
 * candle — to dress a scene. It only ever fills empty air, so it can never
 * damage the player's builds, which is why it is available without the
 * destructive-tools opt-in. Call it several times in a tick to dress a small
 * space (cobwebs across a doorway, candles around a spot).
 */
class DressSceneTool : Tool<DressSceneTool.Args>() {

    override val name: String = "dress_scene"

    override val description: String = """
        Place one atmospheric block into empty space to dress a scene. Never
        overwrites anything — occupied spots are skipped. Use repeatedly in a
        tick to build up a mood (cobwebs in a doorway, candles around a body,
        soul torches down a hall). Block must be one of the listed options.
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(
        required = listOf("block", "x", "y", "z"),
    ) {
        string("block", "The decorative block to place.", enum = ALLOWED.toList())
        integer("x", "Block X coordinate.")
        integer("y", "Block Y coordinate.")
        integer("z", "Block Z coordinate.")
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        val block = args.block.trim().let { if (it.contains(':')) it else "minecraft:$it" }
        if (block !in ALLOWED) {
            return ToolResult.Refused("Block '$block' is not an allowed decoration. Allowed: ${ALLOWED.joinToString()}")
        }
        val actions = ServerActionsHolder.require()
        val dim = actions.getPlayerDimensionId(ctx.playerUuid)
            ?: return ToolResult.Failed("Cannot determine dimension")
        val outcome = actions.placeDecoration(dim, listOf(PlacedBlock(args.x, args.y, args.z, block)))
        return if (outcome.ok) ToolResult.Success(outcome.message) else ToolResult.Failed(outcome.message)
    }

    @Serializable
    data class Args(
        @SerialName("block") val block: String,
        @SerialName("x") val x: Int,
        @SerialName("y") val y: Int,
        @SerialName("z") val z: Int,
    )

    companion object {
        /** Atmospheric blocks only — present in every supported 1.21.x version. */
        val ALLOWED: Set<String> = setOf(
            "minecraft:cobweb",
            "minecraft:vine",
            "minecraft:glow_lichen",
            "minecraft:sculk_vein",
            "minecraft:hanging_roots",
            "minecraft:dead_bush",
            "minecraft:torch",
            "minecraft:soul_torch",
            "minecraft:lantern",
            "minecraft:soul_lantern",
            "minecraft:candle",
            "minecraft:moss_carpet",
            "minecraft:brown_mushroom",
            "minecraft:red_mushroom",
            "minecraft:snow",
        )
    }
}
