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

class StrikeLightningTool : Tool<StrikeLightningTool.Args>() {

    override val name: String = "strike_lightning"

    override val description: String = """
        Strike lightning at coordinates. By default the strike is cosmetic
        (no damage, no fire). Use for dramatic accents — a stormy moment,
        a sudden reveal. Set cosmetic=false ONLY for major story beats.
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(
        required = listOf("x", "y", "z"),
    ) {
        number("x", "X")
        number("y", "Y")
        number("z", "Z")
        boolean("cosmetic", "If true (default) no damage / no fire.")
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        val actions = ServerActionsHolder.require()
        val dim = actions.getPlayerDimensionId(ctx.playerUuid)
            ?: return ToolResult.Failed("Cannot determine dimension")
        return actions.strikeLightning(dim, args.x, args.y, args.z, args.cosmetic ?: true).toToolResult()
    }

    @Serializable
    data class Args(
        @SerialName("x") val x: Double,
        @SerialName("y") val y: Double,
        @SerialName("z") val z: Double,
        @SerialName("cosmetic") val cosmetic: Boolean? = null,
    )
}
