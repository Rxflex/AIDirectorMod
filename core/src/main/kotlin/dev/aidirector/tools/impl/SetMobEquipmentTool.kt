package dev.aidirector.tools.impl

import dev.aidirector.actions.EquipmentSlotName
import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.tools.Schemas
import dev.aidirector.tools.Tool
import dev.aidirector.tools.ToolContext
import dev.aidirector.tools.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

class SetMobEquipmentTool : Tool<SetMobEquipmentTool.Args>() {

    override val name: String = "set_mob_equipment"

    override val description: String = """
        Give a tracked mob a piece of equipment. The mob_id is one returned
        from a prior `spawn_mob`. Useful to make a zombie carry a torch, a
        skeleton wield a custom bow, etc.
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(
        required = listOf("mob_id", "slot", "item_id"),
    ) {
        string("mob_id", "Mob id from a previous spawn_mob call.", maxLength = 32)
        string(
            "slot",
            "Equipment slot.",
            enum = listOf("mainhand", "offhand", "head", "chest", "legs", "feet"),
        )
        string("item_id", "Namespaced item id.", maxLength = 96)
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        val mob = ctx.memory.mobs.get(args.mobId)
            ?: return ToolResult.Refused("Unknown mob_id '${args.mobId}'")
        val entityUuid = mob.entityUuid
            ?: return ToolResult.Refused("Mob '${args.mobId}' has no entity_uuid")
        val actions = ServerActionsHolder.require()
        if (!actions.isItemRegistered(args.itemId)) {
            return ToolResult.Refused("Item '${args.itemId}' is not registered")
        }
        val slot = when (args.slot) {
            "mainhand" -> EquipmentSlotName.MAINHAND
            "offhand" -> EquipmentSlotName.OFFHAND
            "head" -> EquipmentSlotName.HEAD
            "chest" -> EquipmentSlotName.CHEST
            "legs" -> EquipmentSlotName.LEGS
            "feet" -> EquipmentSlotName.FEET
            else -> return ToolResult.Refused("Unknown slot '${args.slot}'")
        }
        return actions.setMobEquipment(entityUuid, slot, args.itemId).toToolResult()
    }

    @Serializable
    data class Args(
        @SerialName("mob_id") val mobId: String,
        @SerialName("slot") val slot: String,
        @SerialName("item_id") val itemId: String,
    )
}
