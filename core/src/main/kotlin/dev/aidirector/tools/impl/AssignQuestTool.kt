package dev.aidirector.tools.impl

import dev.aidirector.memory.QuestRecord
import dev.aidirector.memory.QuestStatus
import dev.aidirector.tools.Schemas
import dev.aidirector.tools.Tool
import dev.aidirector.tools.ToolContext
import dev.aidirector.tools.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.UUID

class AssignQuestTool : Tool<AssignQuestTool.Args>() {

    override val name: String = "assign_quest"

    override val description: String = """
        Create a quest for the player. The quest is persisted so the LLM can
        reference it in future ticks and decide when it has been completed
        (call `complete_quest`). Objectives are free-form text the LLM owns
        — there is no script-based progress detector.
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(
        required = listOf("title", "description", "objectives"),
    ) {
        string("title", "Quest title (max 64).", maxLength = 64)
        string("description", "1-2 sentences of context (max 280).", maxLength = 280)
        string("objectives", "JSON-encoded array of objective strings.", maxLength = 1024)
        string("npc_id", "Optional npc_id of the quest-giver.", maxLength = 32)
        string("reward_item", "Optional namespaced item id rewarded on completion.", maxLength = 96)
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        if (args.title.isBlank()) return ToolResult.Refused("Empty title")
        if (args.npcId != null) {
            ctx.memory.npcs.get(args.npcId)
                ?: return ToolResult.Refused("Unknown npc_id '${args.npcId}'")
        }
        val id = UUID.randomUUID().toString().take(12)
        ctx.memory.quests.create(
            QuestRecord(
                id = id,
                playerUuid = ctx.playerUuid,
                npcId = args.npcId,
                title = args.title.take(64),
                description = args.description.take(280),
                objectivesJson = args.objectives.take(1024),
                rewardItem = args.rewardItem,
                status = QuestStatus.ACTIVE,
                createdAtMs = ctx.nowMs,
                completedAtMs = null,
            ),
        )
        return ToolResult.Success("Quest '$id' assigned: ${args.title}")
    }

    @Serializable
    data class Args(
        @SerialName("title") val title: String,
        @SerialName("description") val description: String,
        @SerialName("objectives") val objectives: String,
        @SerialName("npc_id") val npcId: String? = null,
        @SerialName("reward_item") val rewardItem: String? = null,
    )
}
