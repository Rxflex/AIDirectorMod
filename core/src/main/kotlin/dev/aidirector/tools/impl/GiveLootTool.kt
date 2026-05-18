package dev.aidirector.tools.impl

import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.loot.LootPacks
import dev.aidirector.loot.LootRoller
import dev.aidirector.tools.Schemas
import dev.aidirector.tools.Tool
import dev.aidirector.tools.ToolContext
import dev.aidirector.tools.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Hands the player a themed bundle of items rolled from one of the mod's
 * bundled loot tables — a real reward with weight and variety, far better
 * than handing over a single item with give_item.
 */
class GiveLootTool : Tool<GiveLootTool.Args>() {

    override val name: String = "give_loot"

    override val description: String = """
        Give the player a themed bundle of items rolled from a bundled loot
        table. Tables: ${LootPacks.ids.joinToString()}. Use it as a genuine
        reward or discovery — a found cache, the goods left on a body, the
        remains of a ritual — not as routine handouts.
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(
        required = listOf("table"),
    ) {
        string("table", "Which bundled loot table to roll.", enum = LootPacks.ids)
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        val table = LootPacks.load(args.table)
            ?: return ToolResult.Refused(
                "Unknown loot table '${args.table}'. Allowed: ${LootPacks.ids.joinToString()}",
            )
        val rolled = LootRoller.roll(table)
        if (rolled.isEmpty()) return ToolResult.Failed("Loot table '${table.name}' rolled nothing")

        val actions = ServerActionsHolder.require()
        var given = 0
        for (item in rolled) {
            if (actions.giveItem(ctx.playerUuid, item.itemId, item.count, null).ok) given++
        }
        return if (given > 0) {
            ToolResult.Success("Gave '${table.name}' — $given item stack(s)")
        } else {
            ToolResult.Failed("None of the rolled items from '${table.name}' could be given")
        }
    }

    @Serializable
    data class Args(
        @SerialName("table") val table: String,
    )
}
