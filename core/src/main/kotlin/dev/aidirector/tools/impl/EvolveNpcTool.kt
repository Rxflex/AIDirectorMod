package dev.aidirector.tools.impl

import dev.aidirector.memory.FactKinds
import dev.aidirector.memory.NpcStatus
import dev.aidirector.tools.Schemas
import dev.aidirector.tools.Tool
import dev.aidirector.tools.ToolContext
import dev.aidirector.tools.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Advances an existing NPC along their character arc. This is the tool that
 * turns a static villager into a *character*: the director marks them missing,
 * mourns them dead, records that they have turned hostile, or simply notes
 * where they stand in their story and how they feel about the player.
 *
 * Use it to pay off a setup — an NPC who warned of danger and then vanishes,
 * a friend who dies between sessions, an ally whom the player wronged. Pair a
 * `dead` transition with `build_structure` (grave / memorial) so the fate is
 * something the player can physically find.
 */
class EvolveNpcTool : Tool<EvolveNpcTool.Args>() {

    override val name: String = "evolve_npc"

    override val description: String = """
        Move an existing NPC along their story. Provide npc_id (from # NPC
        roster). Optionally set a new status — active, missing, dead, hostile —
        and/or update arc_stage (a short note on where they are in their
        story) and relationship (their bond to the player). Only the fields
        you supply change. When you mark an NPC dead, consider build_structure
        with a 'grave' or 'memorial' template so the loss is visible.
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(
        required = listOf("npc_id"),
    ) {
        string("npc_id", "NPC id from # NPC roster.", maxLength = 32)
        string(
            "status",
            "New fate. Omit to leave unchanged.",
            enum = listOf("active", "missing", "dead", "hostile"),
        )
        string(
            "arc_stage",
            "Short note on where this NPC now stands in their story. Omit to leave unchanged.",
            maxLength = 240,
        )
        string(
            "relationship",
            "This NPC's bond to the player, e.g. 'wary ally', 'betrayed friend'. Omit to leave unchanged.",
            maxLength = 80,
        )
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        val npc = ctx.memory.npcs.get(args.npcId)
            ?: return ToolResult.Refused("Unknown npc_id '${args.npcId}'. Spawn the NPC first or pick one from # NPC roster.")
        if (npc.associatedPlayer != null && npc.associatedPlayer != ctx.playerUuid) {
            return ToolResult.Refused("NPC '${args.npcId}' belongs to another player")
        }

        val newStatus = args.status?.let { parseStatus(it) }
            ?: if (args.status != null) {
                return ToolResult.Refused("Unknown status '${args.status}'. Use: active, missing, dead, hostile")
            } else {
                npc.status
            }
        val newArc = args.arcStage?.trim()?.take(240)?.takeIf { it.isNotEmpty() } ?: npc.arcStage
        val newRelationship = args.relationship?.trim()?.take(80)?.takeIf { it.isNotEmpty() } ?: npc.relationship

        val statusChanged = newStatus != npc.status
        val relationshipChanged = newRelationship != npc.relationship

        ctx.memory.npcs.upsert(
            npc.copy(
                status = newStatus,
                arcStage = newArc,
                relationship = newRelationship,
                lastSeenAtMs = ctx.nowMs,
            ),
        )

        // A fate change is canon — record it so RAG recalls it forever, even
        // long after the NPC entity itself has unloaded.
        if (statusChanged && newStatus != NpcStatus.ACTIVE) {
            ctx.rag.ingest(
                kind = FactKinds.NPC_FATE,
                content = "NPC '${npc.name}' (${npc.role}) is now ${newStatus.name.lowercase()}." +
                    if (newArc.isNotEmpty()) " $newArc" else "",
                importance = if (newStatus == NpcStatus.DEAD) 4 else 3,
            )
        }
        if (relationshipChanged) {
            ctx.rag.ingest(
                kind = FactKinds.NPC_RELATIONSHIP,
                content = "NPC '${npc.name}' and the player: $newRelationship.",
                importance = 3,
            )
        }

        val parts = buildList {
            if (statusChanged) add("status -> ${newStatus.name.lowercase()}")
            if (newArc != npc.arcStage) add("arc updated")
            if (relationshipChanged) add("relationship -> $newRelationship")
        }
        return ToolResult.Success(
            if (parts.isEmpty()) {
                "NPC '${npc.name}' unchanged (no new fields supplied)"
            } else {
                "NPC '${npc.name}': ${parts.joinToString(", ")}"
            },
        )
    }

    private fun parseStatus(raw: String): NpcStatus? = when (raw.trim().lowercase()) {
        "active" -> NpcStatus.ACTIVE
        "missing" -> NpcStatus.MISSING
        "dead" -> NpcStatus.DEAD
        "hostile" -> NpcStatus.HOSTILE
        else -> null
    }

    @Serializable
    data class Args(
        @SerialName("npc_id") val npcId: String,
        @SerialName("status") val status: String? = null,
        @SerialName("arc_stage") val arcStage: String? = null,
        @SerialName("relationship") val relationship: String? = null,
    )
}
