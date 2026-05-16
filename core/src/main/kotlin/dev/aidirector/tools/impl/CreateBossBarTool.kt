package dev.aidirector.tools.impl

import dev.aidirector.actions.BossBarColor
import dev.aidirector.actions.BossBarOverlay
import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.tools.Schemas
import dev.aidirector.tools.Tool
import dev.aidirector.tools.ToolContext
import dev.aidirector.tools.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

class CreateBossBarTool : Tool<CreateBossBarTool.Args>() {

    override val name: String = "create_boss_bar"

    override val description: String = """
        Show a temporary boss bar on the player's screen. Use to signal a
        countdown, danger window, or thematic presence ("The shadows are
        watching"). Bar auto-fills down and disappears after duration.
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(
        required = listOf("name", "duration_seconds"),
    ) {
        string("name", "Bar label, max 48 chars.", maxLength = 48)
        string(
            "color",
            "Bar color.",
            enum = listOf("pink", "blue", "red", "green", "yellow", "purple", "white"),
        )
        string(
            "overlay",
            "Style.",
            enum = listOf("progress", "notched_6", "notched_10", "notched_12", "notched_20"),
        )
        integer("duration_seconds", "5..600 seconds.", min = 5, max = 600)
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        val color = when (args.color ?: "purple") {
            "pink" -> BossBarColor.PINK
            "blue" -> BossBarColor.BLUE
            "red" -> BossBarColor.RED
            "green" -> BossBarColor.GREEN
            "yellow" -> BossBarColor.YELLOW
            "purple" -> BossBarColor.PURPLE
            "white" -> BossBarColor.WHITE
            else -> BossBarColor.PURPLE
        }
        val overlay = when (args.overlay ?: "progress") {
            "progress" -> BossBarOverlay.PROGRESS
            "notched_6" -> BossBarOverlay.NOTCHED_6
            "notched_10" -> BossBarOverlay.NOTCHED_10
            "notched_12" -> BossBarOverlay.NOTCHED_12
            "notched_20" -> BossBarOverlay.NOTCHED_20
            else -> BossBarOverlay.PROGRESS
        }
        return ServerActionsHolder.require()
            .createBossBar(ctx.playerUuid, args.name.take(48), color, overlay, args.durationSeconds)
            .toToolResult()
    }

    @Serializable
    data class Args(
        @SerialName("name") val name: String,
        @SerialName("color") val color: String? = null,
        @SerialName("overlay") val overlay: String? = null,
        @SerialName("duration_seconds") val durationSeconds: Int,
    )
}
