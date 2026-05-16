package dev.aidirector.tools.impl

import dev.aidirector.actions.ActionOutcome
import dev.aidirector.actions.NarrationStyle
import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.tools.Schemas
import dev.aidirector.tools.Tool
import dev.aidirector.tools.ToolContext
import dev.aidirector.tools.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

class SendNarrationTool : Tool<SendNarrationTool.Args>() {

    override val name: String = "send_narration"

    override val description: String = """
        Send a short atmospheric message to the player. Use sparingly to set
        mood, hint at lore, or react to what the player just did. Do NOT use
        for ordinary feedback or to repeat what is obvious from the game UI.
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(
        required = listOf("message", "style"),
    ) {
        string(
            name = "message",
            description = "The text shown to the player. Keep it under 240 characters. No emojis.",
            maxLength = 240,
        )
        string(
            name = "style",
            description = "How the message is delivered. 'narrator' = chat as third-person voice, " +
                "'whisper' = subdued italic in chat, 'announcement' = title overlay on the screen.",
            enum = listOf("narrator", "whisper", "announcement"),
        )
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        val style = when (args.style) {
            "narrator" -> NarrationStyle.NARRATOR
            "whisper" -> NarrationStyle.WHISPER
            "announcement" -> NarrationStyle.ANNOUNCEMENT
            else -> return ToolResult.Refused("Unknown style '${args.style}'")
        }
        val msg = args.message.trim()
        if (msg.isEmpty()) return ToolResult.Refused("Message is empty")
        if (msg.length > 240) return ToolResult.Refused("Message exceeds 240 chars")

        // Hard refuse near-duplicates of anything we said to this player in the last
        // 20 minutes. Tells the agent loop EXACTLY what was too similar so it can
        // try a fundamentally different angle (or back off).
        when (val dup = ctx.narrationDedup.checkAndStore(ctx.playerUuid, msg, ctx.nowMs)) {
            is dev.aidirector.dedup.NarrationDedup.Result.Fresh -> Unit
            is dev.aidirector.dedup.NarrationDedup.Result.TooSimilar -> {
                return ToolResult.Refused(
                    "Refused: too similar (jaccard=${"%.2f".format(dup.similarity)}) to " +
                        "narration sent ${dup.sinceMs / 1000}s ago: \"${dup.previousText.take(120)}\". " +
                        "Pick a wholly different theme, vocabulary, and image — or do nothing.",
                )
            }
        }

        val out = ServerActionsHolder.require().sendNarration(ctx.playerUuid, msg, style)
        return out.toToolResult()
    }

    @Serializable
    data class Args(
        @SerialName("message") val message: String,
        @SerialName("style") val style: String,
    )
}

internal fun ActionOutcome.toToolResult(): ToolResult = when (this) {
    is ActionOutcome.Success -> ToolResult.Success(message)
    is ActionOutcome.Failure -> ToolResult.Failed(message)
}
