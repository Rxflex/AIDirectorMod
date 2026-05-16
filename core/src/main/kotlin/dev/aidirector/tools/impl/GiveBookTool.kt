package dev.aidirector.tools.impl

import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.memory.FactKinds
import dev.aidirector.tools.Tool
import dev.aidirector.tools.ToolContext
import dev.aidirector.tools.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class GiveBookTool : Tool<GiveBookTool.Args>() {

    override val name: String = "give_lore_note"

    override val description: String = """
        Hand the player a written book. Use for lore drops, NPC letters, or
        found-journal moments. Keep the prose grounded and short — readers
        skim. The full text is also ingested as a 'lore' fact for future
        consistency.
    """.trimIndent()

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put(
            "properties",
            buildJsonObject {
                put(
                    "title",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("Book title (max 32 chars)."))
                        put("maxLength", JsonPrimitive(32))
                    },
                )
                put(
                    "author",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("Author name (max 32 chars)."))
                        put("maxLength", JsonPrimitive(32))
                    },
                )
                put(
                    "pages",
                    buildJsonObject {
                        put("type", JsonPrimitive("array"))
                        put(
                            "items",
                            buildJsonObject {
                                put("type", JsonPrimitive("string"))
                                put("maxLength", JsonPrimitive(1024))
                            },
                        )
                        put("description", JsonPrimitive("1..50 pages, each up to 1024 chars."))
                        put("minItems", JsonPrimitive(1))
                        put("maxItems", JsonPrimitive(50))
                    },
                )
            },
        )
        put("required", JsonArray(listOf("title", "author", "pages").map { JsonPrimitive(it) }))
        put("additionalProperties", JsonPrimitive(false))
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        if (args.pages.isEmpty()) return ToolResult.Refused("Book needs at least one page")
        if (args.pages.size > 50) return ToolResult.Refused("Book exceeds 50-page cap")
        val result = ServerActionsHolder.require()
            .giveBook(ctx.playerUuid, args.title, args.author, args.pages)
            .toToolResult()
        if (result is ToolResult.Success) {
            ctx.rag.ingest(
                kind = FactKinds.LORE,
                content = "Book '${args.title}' by ${args.author}: ${args.pages.joinToString(" ").take(800)}",
                importance = 4,
            )
        }
        return result
    }

    @Serializable
    data class Args(
        @SerialName("title") val title: String,
        @SerialName("author") val author: String,
        @SerialName("pages") val pages: List<String>,
    )
}
