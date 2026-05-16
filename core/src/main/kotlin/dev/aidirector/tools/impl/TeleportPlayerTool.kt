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

class TeleportPlayerTool : Tool<TeleportPlayerTool.Args>() {

    override val name: String = "teleport_player"

    override val description: String = """
        Teleport the player to coordinates. Use only when narratively
        justified (forcible scene change, escape from a trap, a magical
        scroll triggering). Disabled by default in config.
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(required = listOf("x", "y", "z")) {
        number("x", "X")
        number("y", "Y")
        number("z", "Z")
        string("dimension_id", "Optional target dimension id.", maxLength = 64)
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        return ServerActionsHolder.require()
            .teleportPlayer(ctx.playerUuid, args.x, args.y, args.z, args.dimensionId)
            .toToolResult()
    }

    @Serializable
    data class Args(
        @SerialName("x") val x: Double,
        @SerialName("y") val y: Double,
        @SerialName("z") val z: Double,
        @SerialName("dimension_id") val dimensionId: String? = null,
    )
}
