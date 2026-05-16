package dev.aidirector.tools.impl

import dev.aidirector.actions.PlacedBlock
import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.structure.StructureTemplates
import dev.aidirector.tools.Schemas
import dev.aidirector.tools.Tool
import dev.aidirector.tools.ToolContext
import dev.aidirector.tools.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Stamps a small hand-authored structure into the world from a named template
 * — a cairn, a grave, a shrine, a memorial, a campfire ring, a waystone.
 *
 * The director never composes blocks free-hand (LLMs are poor at spatial
 * layout). It picks a template; the engine places a known-good arrangement.
 * Templates with a sign slot receive the provided sign text.
 *
 * This is a centrepiece "visible artifact" capability — a structure the
 * player physically discovers and can photograph is far more memorable than
 * a line of chat.
 */
class BuildStructureTool : Tool<BuildStructureTool.Args>() {

    override val name: String = "build_structure"

    override val description: String = """
        Build a small permanent structure at coordinates from a fixed template.
        Use for memorable physical landmarks tied to the campaign — a grave for
        a fallen NPC, a shrine at a sacred spot, a memorial, a waystone naming
        a place. Templates: ${StructureTemplates.names.joinToString(", ")}.
        Provide the structure ORIGIN (its base centre). Keep structures away
        from the player's own builds. If the template has a sign, give
        sign_lines (1-4 short lines).
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(
        required = listOf("template", "x", "y", "z"),
    ) {
        string(
            "template",
            "Which structure to build.",
            enum = StructureTemplates.names.toList(),
        )
        integer("x", "Origin X (base centre).")
        integer("y", "Origin Y (ground level the structure sits on).")
        integer("z", "Origin Z (base centre).")
        string(
            "sign_text",
            "Optional. For templates with a sign, the text. Use ' / ' to separate up to 4 lines.",
            maxLength = 360,
        )
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        val template = StructureTemplates.resolve(args.template)
            ?: return ToolResult.Refused(
                "Unknown template '${args.template}'. Allowed: ${StructureTemplates.names.joinToString()}",
            )
        val actions = ServerActionsHolder.require()
        val dim = actions.getPlayerDimensionId(ctx.playerUuid)
            ?: return ToolResult.Failed("Cannot determine dimension")

        val absoluteBlocks = template.blocks.map {
            PlacedBlock(args.x + it.dx, args.y + it.dy, args.z + it.dz, it.blockId)
        }
        val buildOutcome = actions.buildStructure(dim, absoluteBlocks)
        if (!buildOutcome.ok) return ToolResult.Failed(buildOutcome.message)

        // Fill any sign slots. If the template has a sign but no text was given,
        // the structure still stands — just without an inscription.
        val signLines = parseSignLines(args.signText)
        var signsPlaced = 0
        if (signLines.isNotEmpty()) {
            for (slot in template.signSlots) {
                actions.placeSign(
                    dim,
                    args.x + slot.dx, args.y + slot.dy, args.z + slot.dz,
                    signLines,
                )
                signsPlaced++
            }
        }
        return ToolResult.Success(
            "Built '${template.name}' at ${args.x},${args.y},${args.z}" +
                if (signsPlaced > 0) " with an inscribed sign" else "",
        )
    }

    private fun parseSignLines(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split(" / ", "\n")
            .map { it.trim().take(90) }
            .filter { it.isNotEmpty() }
            .take(4)
    }

    @Serializable
    data class Args(
        @SerialName("template") val template: String,
        @SerialName("x") val x: Int,
        @SerialName("y") val y: Int,
        @SerialName("z") val z: Int,
        @SerialName("sign_text") val signText: String? = null,
    )
}
