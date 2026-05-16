package dev.aidirector.tools.impl

import dev.aidirector.actions.ActionOutcome
import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.memory.MobRecord
import dev.aidirector.tools.Schemas
import dev.aidirector.tools.Tool
import dev.aidirector.tools.ToolContext
import dev.aidirector.tools.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.UUID

class SpawnMobTool : Tool<SpawnMobTool.Args>() {

    override val name: String = "spawn_mob"

    override val description: String = """
        Spawn 1-5 of a mob near the given coordinates. Use for ambient
        encounters, planned ambushes, or quest objectives. Mobs spawned this
        way are tracked and can be equipped (`set_mob_equipment`), retargeted
        (`set_mob_target`), or removed (`kill_mob`).
        Banned: wither, ender_dragon, warden, elder_guardian.
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(
        required = listOf("entity_type", "x", "y", "z"),
    ) {
        string("entity_type", "Namespaced entity id, e.g. 'minecraft:zombie'.", maxLength = 96)
        number("x", "X")
        number("y", "Y")
        number("z", "Z")
        integer("count", "How many to spawn (1..5).", min = 1, max = 5)
        string("custom_name", "Optional name for each mob (max 32 chars).", maxLength = 32)
        string("role", "Short label, e.g. 'ambush', 'companion'.", maxLength = 32)
        integer("ttl_seconds", "Auto-despawn after this many seconds (60..3600).", min = 60, max = 3600)
        boolean("hostile_to_player", "Set the player as initial target.")
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        val actions = ServerActionsHolder.require()
        if (!actions.isEntityTypeRegistered(args.entityType)) {
            return ToolResult.Refused("Entity '${args.entityType}' is not registered")
        }
        val dim = actions.getPlayerDimensionId(ctx.playerUuid)
            ?: return ToolResult.Failed("Cannot determine player dimension")
        val count = (args.count ?: 1).coerceIn(1, 5)
        val out = actions.spawnMob(
            dimensionId = dim,
            entityType = args.entityType,
            x = args.x, y = args.y, z = args.z,
            count = count,
            customName = args.customName,
            persistent = args.ttlSeconds != null,
            hostileToPlayer = if (args.hostileToPlayer == true) ctx.playerUuid else null,
        )
        if (out !is ActionOutcome.Success) {
            return ToolResult.Failed((out as ActionOutcome.Failure).message)
        }
        val ttlMs = args.ttlSeconds?.let { ctx.nowMs + it * 1000L }
        for (entityUuid in out.entityUuids) {
            val mobId = UUID.randomUUID().toString().take(12)
            ctx.memory.mobs.add(
                MobRecord(
                    id = mobId,
                    entityUuid = entityUuid,
                    entityType = args.entityType,
                    role = args.role ?: "ambient",
                    associatedPlayer = ctx.playerUuid,
                    spawnedAtMs = ctx.nowMs,
                    expiresAtMs = ttlMs,
                ),
            )
        }
        return ToolResult.Success("Spawned ${out.entityUuids.size} x ${args.entityType}")
    }

    @Serializable
    data class Args(
        @SerialName("entity_type") val entityType: String,
        @SerialName("x") val x: Double,
        @SerialName("y") val y: Double,
        @SerialName("z") val z: Double,
        @SerialName("count") val count: Int? = null,
        @SerialName("custom_name") val customName: String? = null,
        @SerialName("role") val role: String? = null,
        @SerialName("ttl_seconds") val ttlSeconds: Int? = null,
        @SerialName("hostile_to_player") val hostileToPlayer: Boolean? = null,
    )
}
