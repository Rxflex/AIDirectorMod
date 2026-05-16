package dev.aidirector.tools.impl

import dev.aidirector.AIDirector
import dev.aidirector.actions.AdvancementFrame
import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.memory.AdvancementRecord
import dev.aidirector.tools.Schemas
import dev.aidirector.tools.Tool
import dev.aidirector.tools.ToolContext
import dev.aidirector.tools.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.UUID

class GrantAdvancementTool : Tool<GrantAdvancementTool.Args>() {

    override val name: String = "grant_advancement"

    override val description: String = """
        Create and grant a one-off advancement on the fly. The toast pops up
        immediately. Use to mark a personal milestone, ironic moment, or
        secret discovery. Be specific — generic titles feel like spam.
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(
        required = listOf("title", "description", "icon_item"),
    ) {
        string("title", "Short title (max 48 chars).", maxLength = 48)
        string("description", "1 sentence description (max 120 chars).", maxLength = 120)
        string("icon_item", "Namespaced item id used as icon.", maxLength = 96)
        string(
            "frame",
            "Display frame: 'task' (default), 'goal' (slightly bigger), 'challenge' (purple/big).",
            enum = listOf("task", "goal", "challenge"),
        )
        boolean("announce_to_chat", "Whether to announce in chat globally.")
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        val actions = ServerActionsHolder.require()
        if (!actions.isItemRegistered(args.iconItem)) {
            return ToolResult.Refused("Icon item '${args.iconItem}' not registered")
        }
        val frame = when (args.frame ?: "task") {
            "task" -> AdvancementFrame.TASK
            "goal" -> AdvancementFrame.GOAL
            "challenge" -> AdvancementFrame.CHALLENGE
            else -> return ToolResult.Refused("Unknown frame '${args.frame}'")
        }
        val id = UUID.randomUUID().toString().take(12)
        val holderId = "${AIDirector.MOD_ID}:dynamic/$id"
        val result = actions.grantAdvancement(
            playerUuid = ctx.playerUuid,
            holderId = holderId,
            title = args.title.take(48),
            description = args.description.take(120),
            iconItem = args.iconItem,
            frame = frame,
            announceToChat = args.announceToChat ?: false,
        ).toToolResult()
        if (result is ToolResult.Success) {
            ctx.memory.advancements.add(
                AdvancementRecord(
                    id = id,
                    holderId = holderId,
                    title = args.title.take(48),
                    description = args.description.take(120),
                    iconItem = args.iconItem,
                    frame = frame.name.lowercase(),
                    grantedTo = ctx.playerUuid.toString(),
                    createdAtMs = ctx.nowMs,
                ),
            )
        }
        return result
    }

    @Serializable
    data class Args(
        @SerialName("title") val title: String,
        @SerialName("description") val description: String,
        @SerialName("icon_item") val iconItem: String,
        @SerialName("frame") val frame: String? = null,
        @SerialName("announce_to_chat") val announceToChat: Boolean? = null,
    )
}
