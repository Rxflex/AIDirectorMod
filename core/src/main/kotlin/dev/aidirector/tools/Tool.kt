package dev.aidirector.tools

import dev.aidirector.llm.FunctionSpec
import dev.aidirector.llm.ToolSpec
import dev.aidirector.util.DirectorJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * A single capability the director can invoke. Tools own their argument schema
 * and the logic to execute. Subclasses are stateless — they hold only static
 * metadata; per-invocation state lives in [ToolContext].
 */
abstract class Tool<TArgs> {

    /** Stable identifier used in tool_calls. Must match `^[a-z][a-z0-9_]{0,63}$`. */
    abstract val name: String

    /** Short, plain-text description for the LLM. Be concrete about when to use this tool. */
    abstract val description: String

    /** JSON Schema describing [TArgs]. We hand-write these for transparency rather than reflecting. */
    abstract val parametersSchema: JsonObject

    /** Serializer for [TArgs]. Used to parse the JSON-string `arguments` from the model. */
    abstract val serializer: KSerializer<TArgs>

    /** Performs the side effect. Called on a background coroutine; impls must hop to server thread. */
    abstract suspend fun execute(args: TArgs, ctx: ToolContext): ToolResult

    fun spec(): ToolSpec = ToolSpec(function = FunctionSpec(name, description, parametersSchema))

    /** Decodes the model's `arguments` JSON-encoded string into [TArgs]. Throws [ToolArgumentException]. */
    fun decodeArguments(arguments: String): TArgs {
        return try {
            DirectorJson.decodeFromString(serializer, arguments)
        } catch (e: SerializationException) {
            // Echo back the expected schema so the next agent-loop iteration sees what fields
            // were expected. Most retries succeed after this hint reaches the model.
            throw ToolArgumentException(
                "Tool '$name' got invalid arguments: ${e.message}. " +
                    "Expected schema: $parametersSchema",
                e,
            )
        }
    }
}

/**
 * Per-invocation context. Passed to every tool execute().
 *
 * - [playerUuid] — the player the director is reasoning about right now.
 *   For world-level tools (place_block in a far-away chunk) this is still
 *   set to the calling player's UUID so we can route narration / sounds back.
 * - [memory] / [rag] — shared state stores. Tools that persist NPCs, quests,
 *   advancements, or lore facts go through these.
 */
data class ToolContext(
    val playerUuid: UUID,
    val nowMs: Long,
    val memory: dev.aidirector.memory.Memory,
    val rag: dev.aidirector.rag.Rag,
    val narrationDedup: dev.aidirector.dedup.NarrationDedup,
    /** If non-null, the agent loop is currently running inside an NPC dialogue. */
    val activeNpcId: String? = null,
)

sealed interface ToolResult {
    val message: String
    data class Success(override val message: String) : ToolResult
    data class Refused(override val message: String) : ToolResult
    data class Failed(override val message: String) : ToolResult
}

class ToolArgumentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
