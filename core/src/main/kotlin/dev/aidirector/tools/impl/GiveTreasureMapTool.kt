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

/**
 * Gives the player a REAL, readable filled map centred on a target location.
 *
 * This is the correct tool for "a map to the ruins" / "a treasure map". Do NOT
 * use give_item with `minecraft:map` — that hands over a blank crafting
 * ingredient, not a usable map. This tool produces a `filled_map` the player
 * can open immediately, showing the terrain around the target.
 */
class GiveTreasureMapTool : Tool<GiveTreasureMapTool.Args>() {

    override val name: String = "give_treasure_map"

    override val description: String = """
        Give the player a real, readable map centred on a location — a map to
        ruins, a hidden cache, an NPC's old home, the sealed mine. The map
        opens immediately and shows the terrain around the target coordinates.
        Use this instead of give_item for anything map-like; a raw minecraft:map
        is blank and useless.
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(
        required = listOf("target_x", "target_z", "label"),
    ) {
        integer("target_x", "World X coordinate the map should be centred on.")
        integer("target_z", "World Z coordinate the map should be centred on.")
        string("label", "Custom display name for the map item, e.g. 'Map to the Old Mine'.", maxLength = 48)
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        val label = args.label.trim().ifBlank { "Worn Map" }
        return ServerActionsHolder.require()
            .giveTreasureMap(ctx.playerUuid, args.targetX, args.targetZ, label)
            .toToolResult()
    }

    @Serializable
    data class Args(
        @SerialName("target_x") val targetX: Int,
        @SerialName("target_z") val targetZ: Int,
        @SerialName("label") val label: String,
    )
}
