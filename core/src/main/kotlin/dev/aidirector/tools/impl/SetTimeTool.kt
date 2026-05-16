package dev.aidirector.tools.impl

import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.actions.TimeLabel
import dev.aidirector.tools.Schemas
import dev.aidirector.tools.Tool
import dev.aidirector.tools.ToolContext
import dev.aidirector.tools.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

class SetTimeTool : Tool<SetTimeTool.Args>() {

    override val name: String = "set_time"

    override val description: String = """
        Skip the world's day cycle to a labelled moment. Use sparingly — a
        sudden midnight is jarring. Reasonable for major story beats only.
        Disabled in default config; the operator must opt in.
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(required = listOf("label")) {
        string(
            "label",
            "Time label.",
            enum = listOf("dawn", "day", "noon", "dusk", "night", "midnight"),
        )
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        val label = when (args.label) {
            "dawn" -> TimeLabel.DAWN
            "day" -> TimeLabel.DAY
            "noon" -> TimeLabel.NOON
            "dusk" -> TimeLabel.DUSK
            "night" -> TimeLabel.NIGHT
            "midnight" -> TimeLabel.MIDNIGHT
            else -> return ToolResult.Refused("Unknown time label '${args.label}'")
        }
        val actions = ServerActionsHolder.require()
        val dim = actions.getPlayerDimensionId(ctx.playerUuid)
            ?: return ToolResult.Failed("Cannot determine dimension")
        return actions.setTime(dim, label).toToolResult()
    }

    @Serializable
    data class Args(@SerialName("label") val label: String)
}
