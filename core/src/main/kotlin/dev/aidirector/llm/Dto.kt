package dev.aidirector.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** OpenAI-compatible chat completions wire types — only the subset we use. */

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec>? = null,
    @SerialName("tool_choice")
    val toolChoice: String? = null,
    val temperature: Double = 0.7,
    @SerialName("max_tokens")
    val maxTokens: Int = 1024,
    val stream: Boolean = false,
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
    val name: String? = null,
)

@Serializable
data class ToolSpec(
    val type: String = "function",
    val function: FunctionSpec,
)

@Serializable
data class FunctionSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
data class ChatResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
)

@Serializable
data class Choice(
    val index: Int = 0,
    val message: ChatMessage,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolCallFunction,
)

@Serializable
data class ToolCallFunction(
    val name: String,
    /** OpenAI returns this as a JSON-encoded string, not a structured object. */
    val arguments: String,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

@Serializable
data class ApiErrorEnvelope(val error: ApiErrorBody? = null)

@Serializable
data class ApiErrorBody(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)
