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
 * Removes a phantom player from the tab list and broadcasts a normal
 * "<name> left the game" message. Letting a phantom leave — especially right
 * after it spoke — is often more effective than letting it linger.
 */
class PhantomLeaveTool : Tool<PhantomLeaveTool.Args>() {

    override val name: String = "phantom_leave"

    override val description: String = """
        Remove a phantom player. They vanish from the tab list and a normal
        "<name> left the game" message is broadcast. Use to close a phantom
        scene cleanly.
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(
        required = listOf("name"),
    ) {
        string("name", "The phantom's username to remove.", maxLength = 16)
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        val phantom = ctx.phantoms.get(args.name)
            ?: return ToolResult.Refused("No phantom named '${args.name}' is in the player list")

        val outcome = ServerActionsHolder.require().phantomLeave(phantom.uuid, phantom.name)
        ctx.phantoms.remove(phantom.name)
        return if (outcome.ok) {
            ToolResult.Success("Phantom '${phantom.name}' left the server")
        } else {
            ToolResult.Failed(outcome.message)
        }
    }

    @Serializable
    data class Args(
        @SerialName("name") val name: String,
    )
}
