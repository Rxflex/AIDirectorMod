package dev.aidirector.tools.impl

import dev.aidirector.actions.ActionOutcome
import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.memory.FactKinds
import dev.aidirector.memory.NpcRecord
import dev.aidirector.memory.NpcStatus
import dev.aidirector.tools.Schemas
import dev.aidirector.tools.Tool
import dev.aidirector.tools.ToolContext
import dev.aidirector.tools.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * Spawns a named villager/wandering-trader as an NPC and persists it. The NPC
 * holds the directorial role + personality blurb in [NpcStore] so future
 * interactions and reflections can reuse them. The personality is also
 * ingested into RAG as an `npc.personality` fact so the LLM can recall it via
 * semantic retrieval much later.
 */
class SpawnNpcTool : Tool<SpawnNpcTool.Args>() {

    override val name: String = "spawn_npc"

    override val description: String = """
        Spawn an NPC near the player. Use to introduce a quest-giver, a
        merchant, a stranded traveller, or any character the world should
        remember. Pick a concise role (e.g. "wandering scholar", "haunted
        miner") and a 1-2 sentence personality — these persist for future
        interactions.
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(
        required = listOf("name", "role", "personality", "x", "y", "z"),
    ) {
        string("name", "Display name (max 32 chars).", maxLength = 32)
        string(
            "entity_type",
            "Allowed: 'minecraft:villager' or 'minecraft:wandering_trader'. Default 'minecraft:villager'.",
            enum = listOf("minecraft:villager", "minecraft:wandering_trader"),
        )
        string("role", "Short role label like 'merchant' or 'priest'.", maxLength = 48)
        string("personality", "1-2 sentence personality + voice.", maxLength = 280)
        number("x", "X coordinate")
        number("y", "Y coordinate")
        number("z", "Z coordinate")
        boolean("invulnerable", "Whether the NPC cannot be killed. Default true.")
        boolean("ambient_movement", "Whether the NPC wanders. Default true.")
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        val type = args.entityType ?: "minecraft:villager"
        val actions = ServerActionsHolder.require()
        val dim = actions.getPlayerDimensionId(ctx.playerUuid)
            ?: return ToolResult.Failed("Cannot determine player dimension")
        val out = actions.spawnNpc(
            ownerPlayer = ctx.playerUuid,
            dimensionId = dim,
            entityType = type,
            customName = args.name.take(32),
            x = args.x, y = args.y, z = args.z,
            invulnerable = args.invulnerable ?: true,
            ambientMovement = args.ambientMovement ?: true,
        )
        if (out !is ActionOutcome.Success || out.entityUuid == null) {
            return when (out) {
                is ActionOutcome.Success -> ToolResult.Failed("Spawn returned success without UUID")
                is ActionOutcome.Failure -> ToolResult.Failed(out.message)
            }
        }
        val npcId = UUID.randomUUID().toString().take(12)
        ctx.memory.npcs.upsert(
            NpcRecord(
                id = npcId,
                entityUuid = out.entityUuid,
                associatedPlayer = ctx.playerUuid,
                name = args.name.take(32),
                role = args.role.take(48),
                personality = args.personality.take(280),
                dimensionId = dim,
                x = args.x.toInt(), y = args.y.toInt(), z = args.z.toInt(),
                status = NpcStatus.ACTIVE,
                createdAtMs = ctx.nowMs,
                lastSeenAtMs = ctx.nowMs,
            ),
        )
        ctx.rag.ingest(
            kind = FactKinds.NPC_PERSONALITY,
            content = "NPC '${args.name}' (${args.role}): ${args.personality}",
            importance = 3,
        )
        return ToolResult.Success("Spawned NPC '${args.name}' as $npcId")
    }

    @Serializable
    data class Args(
        @SerialName("name") val name: String,
        @SerialName("entity_type") val entityType: String? = null,
        @SerialName("role") val role: String,
        @SerialName("personality") val personality: String,
        @SerialName("x") val x: Double,
        @SerialName("y") val y: Double,
        @SerialName("z") val z: Double,
        @SerialName("invulnerable") val invulnerable: Boolean? = null,
        @SerialName("ambient_movement") val ambientMovement: Boolean? = null,
    )
}
