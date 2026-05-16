package dev.aidirector.tools.impl

import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.actions.WeatherKind
import dev.aidirector.tools.Schemas
import dev.aidirector.tools.Tool
import dev.aidirector.tools.ToolContext
import dev.aidirector.tools.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

class ModifyWeatherTool : Tool<ModifyWeatherTool.Args>() {

    override val name: String = "modify_weather"

    override val description: String = """
        Change the weather in the player's dimension. Use sparingly — sudden
        storms during a mining trip or fog at dawn can be evocative, but
        repeated weather flips just feel like a bug.
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(
        required = listOf("weather", "duration_seconds"),
    ) {
        string(
            name = "weather",
            description = "'clear' = sunny, 'rain' = rainfall, 'thunder' = thunderstorm.",
            enum = listOf("clear", "rain", "thunder"),
        )
        integer(
            name = "duration_seconds",
            description = "How long the weather lasts (seconds), 60..3600.",
            min = 60,
            max = 3600,
        )
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        val kind = when (args.weather) {
            "clear" -> WeatherKind.CLEAR
            "rain" -> WeatherKind.RAIN
            "thunder" -> WeatherKind.THUNDER
            else -> return ToolResult.Refused("Unknown weather '${args.weather}'")
        }
        val seconds = args.durationSeconds.coerceIn(60, 3600)
        val ticks = seconds * 20
        return ServerActionsHolder.require()
            .modifyWeather(ctx.playerUuid, kind, ticks)
            .toToolResult()
    }

    @Serializable
    data class Args(
        @SerialName("weather") val weather: String,
        @SerialName("duration_seconds") val durationSeconds: Int,
    )
}
