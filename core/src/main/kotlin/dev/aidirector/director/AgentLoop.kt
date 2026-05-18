package dev.aidirector.director

import dev.aidirector.AIDirector
import dev.aidirector.guardrails.GuardrailDecision
import dev.aidirector.guardrails.Guardrails
import dev.aidirector.llm.ChatMessage
import dev.aidirector.llm.ChatRequest
import dev.aidirector.llm.LlmClient
import dev.aidirector.llm.LlmException
import dev.aidirector.llm.ToolCall
import dev.aidirector.memory.EventKind
import dev.aidirector.memory.Memory
import dev.aidirector.rag.Rag
import dev.aidirector.tools.Tool
import dev.aidirector.tools.ToolArgumentException
import dev.aidirector.tools.ToolContext
import dev.aidirector.tools.ToolRegistry
import dev.aidirector.tools.ToolResult
import dev.aidirector.util.DirectorJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * ReAct-style agent loop. Runs up to [maxIterations] rounds:
 *
 *   1. Send the current message list (system + user + prior tool turns) to
 *      the LLM with the tool registry.
 *   2. Append the assistant message.
 *   3. For each tool_call, guardrail-check + execute. Append a `role:"tool"`
 *      message carrying the result so the next iteration sees what happened.
 *   4. Break early if the assistant returns no tool_calls.
 *
 * The loop stops as soon as the LLM has nothing more to say. The director's
 * job after this returns is just to record the per-call outcomes into
 * [Memory] for future ticks to read.
 */
class AgentLoop(
    private val llm: LlmClient,
    private val tools: ToolRegistry,
    private val guardrails: Guardrails,
    private val memory: Memory,
    private val rag: Rag,
    private val narrationDedup: dev.aidirector.dedup.NarrationDedup,
    private val phantoms: dev.aidirector.phantom.PhantomRegistry,
    private val maxIterations: Int,
    private val maxToolCallsPerIteration: Int,
) {
    init {
        require(maxIterations in 1..8) { "maxIterations must be 1..8" }
        require(maxToolCallsPerIteration in 1..10) { "maxToolCallsPerIteration must be 1..10" }
    }

    data class AgentReport(
        val playerUuid: UUID,
        val iterations: Int,
        val toolsAttempted: Int,
        val toolsExecuted: Int,
        val toolsRefused: Int,
        val toolsFailed: Int,
        val finalAssistantText: String?,
        val llmError: String?,
    )

    suspend fun run(
        playerUuid: UUID,
        nowMs: Long,
        initialMessages: List<ChatMessage>,
        model: String,
        temperature: Double,
        maxTokens: Int,
    ): AgentReport {
        val messages = initialMessages.toMutableList()
        var iterations = 0
        var attempted = 0
        var executed = 0
        var refused = 0
        var failed = 0
        var llmError: String? = null
        var finalText: String? = null

        for (iter in 1..maxIterations) {
            iterations = iter
            val response = try {
                llm.chat(
                    ChatRequest(
                        model = model,
                        messages = messages,
                        tools = tools.specs(),
                        toolChoice = if (iter == maxIterations) "none" else "auto",
                        temperature = temperature,
                        maxTokens = maxTokens,
                    ),
                )
            } catch (e: LlmException) {
                AIDirector.log.warn("Agent loop iter=$iter LLM error: ${e.message}")
                llmError = e.message
                break
            }
            val choice = response.choices.firstOrNull()
            if (choice == null) {
                AIDirector.log.warn("LLM returned no choices on iter=$iter")
                break
            }
            val assistant = choice.message
            finalText = assistant.content

            // Normalise the model's tool calls before they enter the
            // conversation. Two things go wrong with some models / gateways:
            //  - a tool call with a blank function name cannot be dispatched
            //    and cannot be echoed back (a strict gateway rejects an empty
            //    function_response.name);
            //  - blank or duplicate tool-call ids stop a Gemini-style gateway
            //    matching the tool result to the call, so it cannot fill in
            //    the function_response.name either.
            // So: drop blank-named calls, cap to the per-iteration limit, then
            // give every survivor a unique, non-empty id. The assistant
            // message we store lists EXACTLY the calls we answer, 1:1.
            val rawCalls = assistant.toolCalls.orEmpty()
            val named = rawCalls.filter { it.function.name.isNotBlank() }
            if (named.size != rawCalls.size) {
                AIDirector.log.warn(
                    "Dropped {} tool call(s) with a blank function name on iter={}",
                    rawCalls.size - named.size, iter,
                )
            }
            val callsThisIter = named.take(maxToolCallsPerIteration)
                .mapIndexed { i, call -> call.copy(id = "aidir_${iter}_$i") }

            messages += assistant.copy(toolCalls = callsThisIter.ifEmpty { null })

            if (callsThisIter.isEmpty()) break

            for (call in callsThisIter) {
                attempted++
                val result = dispatch(playerUuid, nowMs, call)
                when (result) {
                    is ToolResult.Success -> executed++
                    is ToolResult.Refused -> refused++
                    is ToolResult.Failed -> failed++
                }
                memory.recordEvent(
                    playerUuid,
                    when (result) {
                        is ToolResult.Success -> EventKind.TOOL_INVOKED
                        is ToolResult.Refused -> EventKind.TOOL_REFUSED
                        is ToolResult.Failed -> EventKind.TOOL_FAILED
                    },
                    toolPayload(call, result.message),
                )
                messages += ChatMessage(
                    role = "tool",
                    content = result.message.take(MAX_TOOL_RESULT_LENGTH),
                    toolCallId = call.id,
                    name = call.function.name,
                )
            }
        }

        return AgentReport(
            playerUuid = playerUuid,
            iterations = iterations,
            toolsAttempted = attempted,
            toolsExecuted = executed,
            toolsRefused = refused,
            toolsFailed = failed,
            finalAssistantText = finalText,
            llmError = llmError,
        )
    }

    private suspend fun dispatch(playerUuid: UUID, nowMs: Long, call: ToolCall): ToolResult {
        val tool = tools[call.function.name]
            ?: return ToolResult.Refused("Unknown tool '${call.function.name}'")
        when (val g = guardrails.checkAndRecord(playerUuid, call.function.name)) {
            GuardrailDecision.Allowed -> Unit
            is GuardrailDecision.Denied -> return ToolResult.Refused("Guardrail: ${g.reason}")
        }
        val ctx = ToolContext(playerUuid, nowMs, memory, rag, narrationDedup, phantoms)
        return try {
            executeChecked(tool, call.function.arguments, ctx)
        } catch (e: ToolArgumentException) {
            ToolResult.Refused(e.message ?: "Invalid arguments")
        } catch (e: Exception) {
            AIDirector.log.warn("Tool '${tool.name}' threw: ${e.message}")
            ToolResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun <T> executeChecked(tool: Tool<T>, argumentsJson: String, ctx: ToolContext): ToolResult {
        val args = tool.decodeArguments(argumentsJson)
        return tool.execute(args, ctx)
    }

    private fun toolPayload(call: ToolCall, message: String): String =
        DirectorJson.encodeToString<JsonObject>(buildJsonObject {
            put("tool", call.function.name)
            put("args", call.function.arguments)
            put("result", message)
        })

    companion object {
        private const val MAX_TOOL_RESULT_LENGTH = 600
    }
}
