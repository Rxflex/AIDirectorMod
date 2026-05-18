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

/**
 * Makes an already-joined phantom player speak a line in public chat,
 * formatted exactly like a real player message: `<name> message`. The most
 * unsettling phantom moments are a single short, wrong line.
 */
class PhantomSayTool : Tool<PhantomSayTool.Args>() {

    override val name: String = "phantom_say"

    override val description: String = """
        Make a phantom player (one already joined via phantom_join) say a line
        in public chat. It looks identical to a real player's message. Keep it
        short and wrong-feeling — phantoms unsettle most in one quiet sentence.
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(
        required = listOf("name", "message"),
    ) {
        string("name", "The phantom's username (must already be joined).", maxLength = 16)
        string("message", "The chat line to speak.", maxLength = 256)
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        val phantom = ctx.phantoms.get(args.name)
            ?: return ToolResult.Refused(
                "No phantom named '${args.name}' is in the player list — call phantom_join first",
            )
        val message = args.message.trim()
        if (message.isEmpty()) return ToolResult.Refused("Phantom message is empty")

        val outcome = ServerActionsHolder.require().phantomSay(phantom.name, message.take(256))
        return if (outcome.ok) {
            ToolResult.Success("Phantom '${phantom.name}' spoke")
        } else {
            ToolResult.Failed(outcome.message)
        }
    }

    @Serializable
    data class Args(
        @SerialName("name") val name: String,
        @SerialName("message") val message: String,
    )
}
