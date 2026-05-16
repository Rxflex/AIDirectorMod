package dev.aidirector.tools.impl

import dev.aidirector.memory.FactKinds
import dev.aidirector.tools.Schemas
import dev.aidirector.tools.Tool
import dev.aidirector.tools.ToolContext
import dev.aidirector.tools.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Lets the director write a fact into long-term memory. Facts persist and are
 * retrieved by semantic similarity in future ticks — they are the canon that
 * keeps a world's lore consistent.
 *
 * Examples:
 *   {"kind":"lore","content":"The mine east of spawn was sealed by the old
 *   miners after the 'mossglass beasts' broke through.","importance":4}
 */
class RecordFactTool : Tool<RecordFactTool.Args>() {

    override val name: String = "record_fact"

    override val description: String = """
        Save a canon fact to long-term memory. Use to anchor lore that should
        survive across sessions, to remember a player trait worth referencing,
        or to commit an NPC relationship.
        importance: 0 trivial, 5 critical. High-importance facts survive pruning.
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(
        required = listOf("kind", "content"),
    ) {
        string(
            "kind",
            "Category. 'lore' / 'npc.personality' / 'npc.relationship' / 'narrative.arc' / " +
                "'player.trait' / 'world.event'.",
            enum = listOf(
                FactKinds.LORE,
                FactKinds.NPC_PERSONALITY,
                FactKinds.NPC_RELATIONSHIP,
                FactKinds.NARRATIVE_ARC,
                FactKinds.PLAYER_TRAIT,
                FactKinds.WORLD_EVENT,
            ),
        )
        string(
            "content",
            "The fact itself, one or two sentences. Max 500 chars.",
            maxLength = 500,
        )
        integer("importance", "0..5, default 1.", min = 0, max = 5)
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        if (args.content.isBlank()) return ToolResult.Refused("Empty fact content")
        val id = ctx.rag.ingest(args.kind, args.content.take(500), args.importance ?: 1)
        return ToolResult.Success("Recorded fact $id")
    }

    @Serializable
    data class Args(
        @SerialName("kind") val kind: String,
        @SerialName("content") val content: String,
        @SerialName("importance") val importance: Int? = null,
    )
}
