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

class SetMobTargetTool : Tool<SetMobTargetTool.Args>() {

    override val name: String = "set_mob_target"

    override val description: String = """
        Direct a tracked mob to target the player. Use to trigger an ambush
        moment — note that the mob's own AI may overrule this once it sees
        another threat or loses line of sight.
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
        return ServerActionsHolder.require()
            .setMobTarget(entityUuid, ctx.playerUuid)
            .toToolResult()
    }

    @Serializable
    data class Args(@SerialName("mob_id") val mobId: String)
}
