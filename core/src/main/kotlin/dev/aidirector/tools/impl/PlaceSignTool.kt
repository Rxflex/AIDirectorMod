package dev.aidirector.tools.impl

import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.tools.Tool
import dev.aidirector.tools.ToolContext
import dev.aidirector.tools.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Places a readable wooden sign at exact coordinates. A sign is a permanent,
 * screenshot-able artifact — the right tool for a short carved message at a
 * grave, a trail marker, a warning, a place name.
 */
class PlaceSignTool : Tool<PlaceSignTool.Args>() {

    override val name: String = "place_sign"

    override val description: String = """
        Place a wooden sign with up to 4 short lines of text at exact
        coordinates. Use for permanent in-world markers — a name on a grave,
        a warning at a cave mouth, a trail marker, the name of a place.
        Keep each line very short (signs are narrow — ~15 characters read
        comfortably). For longer prose use give_lore_note instead.
    """.trimIndent()

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put(
            "properties",
            buildJsonObject {
                put("x", buildJsonObject { put("type", JsonPrimitive("integer")); put("description", JsonPrimitive("X")) })
                put("y", buildJsonObject { put("type", JsonPrimitive("integer")); put("description", JsonPrimitive("Y")) })
                put("z", buildJsonObject { put("type", JsonPrimitive("integer")); put("description", JsonPrimitive("Z")) })
                put(
                    "lines",
                    buildJsonObject {
                        put("type", JsonPrimitive("array"))
                        put("items", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("maxLength", JsonPrimitive(90))
                        })
                        put("description", JsonPrimitive("1-4 short lines of sign text."))
                        put("minItems", JsonPrimitive(1))
                        put("maxItems", JsonPrimitive(4))
                    },
                )
            },
        )
        put("required", JsonArray(listOf("x", "y", "z", "lines").map { JsonPrimitive(it) }))
        put("additionalProperties", JsonPrimitive(false))
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        if (args.lines.isEmpty()) return ToolResult.Refused("Sign needs at least one line")
        if (args.lines.size > 4) return ToolResult.Refused("Sign holds at most 4 lines")
        val actions = ServerActionsHolder.require()
        val dim = actions.getPlayerDimensionId(ctx.playerUuid)
            ?: return ToolResult.Failed("Cannot determine dimension")
        return actions.placeSign(dim, args.x, args.y, args.z, args.lines).toToolResult()
    }

    @Serializable
    data class Args(
        @SerialName("x") val x: Int,
        @SerialName("y") val y: Int,
        @SerialName("z") val z: Int,
        @SerialName("lines") val lines: List<String>,
    )
}
