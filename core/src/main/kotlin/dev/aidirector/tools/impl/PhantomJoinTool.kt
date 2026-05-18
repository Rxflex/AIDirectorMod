package dev.aidirector.tools.impl

import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.phantom.Phantom
import dev.aidirector.phantom.PhantomRegistry
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
 * Adds a phantom "player" to the server — a name that appears in the tab list
 * and produces a believable "joined the game" line, with no entity in the
 * world. A purely server-side horror device: the player sees someone arrive
 * who was never really there.
 */
class PhantomJoinTool : Tool<PhantomJoinTool.Args>() {

    override val name: String = "phantom_join"

    override val description: String = """
        Make a phantom player join the server. The name appears in the tab
        list and a normal "<name> joined the game" message is broadcast — but
        there is no body in the world. Use for dread: an unknown player who
        should not exist. Follow up with phantom_say to let them speak and
        phantom_leave to remove them. Pick an unsettling, plausible username.
    """.trimIndent()

    override val parametersSchema: JsonObject = Schemas.obj(
        required = listOf("name"),
    ) {
        string("name", "The phantom's username (3-16 chars, letters/digits/underscore).", maxLength = 16)
    }

    override val serializer: KSerializer<Args> = Args.serializer()

    override suspend fun execute(args: Args, ctx: ToolContext): ToolResult {
        val clean = args.name.filter { it.isLetterOrDigit() || it == '_' }.take(16)
        if (clean.length < 3) {
            return ToolResult.Refused("Phantom name must be 3-16 chars of letters, digits, or underscore")
        }
        if (ctx.phantoms.isActive(clean)) {
            return ToolResult.Refused("A phantom named '$clean' is already in the player list")
        }
        if (ctx.phantoms.isFull()) {
            return ToolResult.Refused(
                "Too many phantoms already present (max ${PhantomRegistry.MAX_ACTIVE}) — call phantom_leave first",
            )
        }
        val uuid = UUID.randomUUID()
        val outcome = ServerActionsHolder.require().phantomJoin(uuid, clean)
        if (!outcome.ok) return ToolResult.Failed(outcome.message)

        ctx.phantoms.add(Phantom(name = clean, uuid = uuid, joinedAtMs = ctx.nowMs))
        return ToolResult.Success("Phantom '$clean' joined the server")
    }

    @Serializable
    data class Args(
        @SerialName("name") val name: String,
    )
}
