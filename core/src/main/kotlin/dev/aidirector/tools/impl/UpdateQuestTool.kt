package dev.aidirector.tools.impl

import dev.aidirector.tools.Schemas
import dev.aidirector.tools.Tool
import dev.aidirector.tools.ToolContext
import dev.aidirector.tools.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

class UpdateQuestTool : Tool<UpdateQuestTool.Args>() {

    override val name: String = "update_quest"

    override val description: String = """
        Update the objectives JSON of an active quest. Use to record partial
        progress that the director has observed (e.g. one of three items
        gathered). Do not change titles or descriptions here.
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(
        required = listOf("quest_id", "objectives"),
    ) {
        string("quest_id", "Quest id from a previous assign_quest call.", maxLength = 32)
        string("objectives", "New JSON-encoded objectives array.", maxLength = 1024)
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        val quest = ctx.memory.quests.get(args.questId)
            ?: return ToolResult.Refused("Unknown quest_id '${args.questId}'")
        if (quest.playerUuid != ctx.playerUuid) {
            return ToolResult.Refused("Quest belongs to another player")
        }
        ctx.memory.quests.setObjectives(args.questId, args.objectives.take(1024))
        return ToolResult.Success("Quest '${args.questId}' updated")
    }

    @Serializable
    data class Args(
        @SerialName("quest_id") val questId: String,
        @SerialName("objectives") val objectives: String,
    )
}
