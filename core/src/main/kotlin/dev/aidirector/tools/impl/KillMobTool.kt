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

class KillMobTool : Tool<KillMobTool.Args>() {

    override val name: String = "kill_mob"

    override val description: String = """
        Remove a tracked mob from the world without damage rolls or drops.
        Use to clean up after a scripted scene or when a mob is no longer
        narratively relevant.
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(
        required = listOf("mob_id"),
    ) {
        string("mob_id", "Mob id from a previous spawn_mob call.", maxLength = 32)
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        val mob = ctx.memory.mobs.get(args.mobId)
            ?: return ToolResult.Refused("Unknown mob_id '${args.mobId}'")
        val entityUuid = mob.entityUuid ?: return ToolResult.Refused("Mob has no entity_uuid")
        val result = ServerActionsHolder.require().killEntity(entityUuid).toToolResult()
        if (result is ToolResult.Success) {
            ctx.memory.mobs.remove(args.mobId)
        }
        return result
    }

    @Serializable
    data class Args(@SerialName("mob_id") val mobId: String)
}
