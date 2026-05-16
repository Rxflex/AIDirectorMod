package dev.aidirector.tools.impl

import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.memory.QuestStatus
import dev.aidirector.tools.Schemas
import dev.aidirector.tools.Tool
import dev.aidirector.tools.ToolContext
import dev.aidirector.tools.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

class CompleteQuestTool : Tool<CompleteQuestTool.Args>() {

    override val name: String = "complete_quest"

    override val description: String = """
        Mark a quest completed (success or failed). On success, the reward
        item from the quest is given to the player automatically (if set and
        still registered).
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(
        required = listOf("quest_id", "success"),
    ) {
        string("quest_id", "Quest id.", maxLength = 32)
        boolean("success", "true = success, false = failed.")
        string("epilogue", "Optional narration to send the player on completion.", maxLength = 240)
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        val quest = ctx.memory.quests.get(args.questId)
            ?: return ToolResult.Refused("Unknown quest_id '${args.questId}'")
        if (quest.playerUuid != ctx.playerUuid) {
            return ToolResult.Refused("Quest belongs to another player")
        }
        val status = if (args.success) QuestStatus.COMPLETED else QuestStatus.FAILED
        ctx.memory.quests.setStatus(args.questId, status, ctx.nowMs)
        val actions = ServerActionsHolder.require()
        if (args.success && quest.rewardItem != null && actions.isItemRegistered(quest.rewardItem)) {
            actions.giveItem(ctx.playerUuid, quest.rewardItem, 1, customName = null)
        }
        if (!args.epilogue.isNullOrBlank()) {
            actions.sendNarration(
                playerUuid = ctx.playerUuid,
                message = args.epilogue.take(240),
                style = dev.aidirector.actions.NarrationStyle.NARRATOR,
            )
        }
        return ToolResult.Success("Quest '${args.questId}' → ${status.name}")
    }

    @Serializable
    data class Args(
        @SerialName("quest_id") val questId: String,
        @SerialName("success") val success: Boolean,
        @SerialName("epilogue") val epilogue: String? = null,
    )
}
