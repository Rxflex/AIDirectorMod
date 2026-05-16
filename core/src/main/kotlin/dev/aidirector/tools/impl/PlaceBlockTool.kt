package dev.aidirector.tools.impl

import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.tools.Schemas
import dev.aidirector.tools.Tool
import dev.aidirector.tools.ToolContext
import dev.aidirector.tools.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

class PlaceBlockTool : Tool<PlaceBlockTool.Args>() {

    override val name: String = "place_block"

    override val description: String = """
        Place a decorative block at exact coordinates. Use for scene-setting
        (a candle on a stone, a wool banner left behind, soul lantern at a
        crypt). Destructive blocks (TNT, lava, command blocks, bedrock) are
        denylisted. Avoid placing inside players or modifying near their base
        without strong narrative reason.
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(
        required = listOf("block_id", "x", "y", "z"),
    ) {
        string("block_id", "Namespaced block id.", maxLength = 96)
        integer("x", "X")
        integer("y", "Y")
        integer("z", "Z")
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        val actions = ServerActionsHolder.require()
        if (!actions.isBlockRegistered(args.blockId)) {
            return ToolResult.Refused("Block '${args.blockId}' not registered")
        }
        val dim = actions.getPlayerDimensionId(ctx.playerUuid)
            ?: return ToolResult.Failed("Cannot determine dimension")
        return actions.placeBlock(dim, args.x, args.y, args.z, args.blockId).toToolResult()
    }

    @Serializable
    data class Args(
        @SerialName("block_id") val blockId: String,
        @SerialName("x") val x: Int,
        @SerialName("y") val y: Int,
        @SerialName("z") val z: Int,
    )
}
