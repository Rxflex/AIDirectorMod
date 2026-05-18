package dev.aidirector.llm

import dev.aidirector.AIDirector
import dev.aidirector.config.LlmConfig
import dev.aidirector.util.DirectorJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Duration
import kotlin.math.min
import kotlin.random.Random

/**
 * Suspending client for OpenAI-compatible chat completions. Handles retry with
 * exponential backoff + jitter on transient failures and 429s.
 *
 * Thread-safe — a single instance is shared for the lifetime of the server.
 */
class LlmClient(
    private val config: LlmConfig,
    private val httpClient: OkHttpClient = defaultHttpClient(config),
) {
    private val baseUrl = config.baseUrl.trimEnd('/')

    suspend fun chat(request: ChatRequest): ChatResponse {
        // Always stream. A streamed response keeps data flowing chunk by chunk,
        // so the read-timeout (idle gap) replaces a single total-call timeout —
        // a long reasoning generation no longer trips a deadline.
        val payload = DirectorJson.encodeToString(request.copy(stream = true))
        val httpRequest = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Accept", "application/json")
            .header("User-Agent", "AIDirector/${AIDirector.VERSION}")
            // Send the body as bytes with a bare `application/json` type.
            // String.toRequestBody would append `; charset=utf-8`, which some
            // strict endpoints reject with HTTP 415.
            .post(payload.encodeToByteArray().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return executeWithRetry(httpRequest)
    }

    private suspend fun executeWithRetry(request: Request): ChatResponse {
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt <= config.maxRetries) {
            try {
                return executeOnce(request)
            } catch (e: LlmRateLimitException) {
                lastError = e
                val sleepMs = e.retryAfterSeconds?.times(1000)
                    ?: backoffMs(attempt)
                AIDirector.log.warn("LLM rate limited, sleeping ${sleepMs}ms (attempt ${attempt + 1}/${config.maxRetries + 1})")
                delay(sleepMs)
            } catch (e: LlmTransientException) {
                lastError = e
                if (attempt == config.maxRetries) break
                val sleepMs = backoffMs(attempt)
                AIDirector.log.warn("LLM transient error: ${e.message}, retry in ${sleepMs}ms (attempt ${attempt + 1}/${config.maxRetries + 1})")
                delay(sleepMs)
            }
            attempt++
        }
        throw lastError ?: LlmTransientException("Exhausted retries with no recorded error")
    }

    private suspend fun executeOnce(request: Request): ChatResponse = withContext(Dispatchers.IO) {
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw LlmTransientException("HTTP call failed: ${e.message}", e)
        }
        response.use { resp -> handleResponse(resp) }
    }

    private fun handleResponse(response: Response): ChatResponse {
        if (response.isSuccessful) {
            // A streamed reply is text/event-stream; a non-streaming endpoint
            // (or a test mock) returns one JSON body — handle both.
            val contentType = response.header("Content-Type").orEmpty()
            return if (contentType.contains("event-stream", ignoreCase = true)) {
                readStream(response)
            } else {
                parseWholeBody(response.body?.string().orEmpty())
            }
        }
        val bodyText = response.body?.string().orEmpty()
        when {
            response.code == 408 || response.code == 429 -> {
                val retryAfter = response.header("Retry-After")?.toLongOrNull()
                throw LlmRateLimitException(retryAfter, extractErrorMessage(bodyText))
            }
            response.code in 500..599 -> {
                throw LlmTransientException("HTTP ${response.code}: ${extractErrorMessage(bodyText)}")
            }
            else -> {
                throw LlmClientException(response.code, extractErrorMessage(bodyText))
            }
        }
    }

    /** Parses a single, non-streamed JSON chat completion body. */
    private fun parseWholeBody(bodyText: String): ChatResponse {
        val decoded = try {
            DirectorJson.decodeFromString<ChatResponse>(bodyText)
        } catch (e: SerializationException) {
            throw LlmResponseException("Failed to decode chat completion: ${e.message}", e)
        }
        return decoded.copy(
            choices = decoded.choices.map { choice ->
                choice.copy(
                    message = choice.message.copy(
                        content = ReasoningFilter.strip(choice.message.content),
                    ),
                )
            },
        )
    }

    /**
     * Consumes a Server-Sent-Events stream and reassembles it into one
     * [ChatResponse]. `content` deltas are concatenated; `tool_calls` deltas
     * are accumulated per `index` (some providers split a call's `arguments`
     * across many chunks, so this never assumes one chunk per call).
     */
    private fun readStream(response: Response): ChatResponse {
        val source = response.body?.source()
            ?: throw LlmResponseException("Streaming response had no body")
        val content = StringBuilder()
        val tools = sortedMapOf<Int, StreamingToolCall>()
        var finishReason: String? = null
        var usage: Usage? = null

        while (true) {
            val line = try {
                source.readUtf8Line()
            } catch (e: IOException) {
                throw LlmTransientException("Streaming read failed: ${e.message}", e)
            } ?: break
            if (line.isBlank() || !line.startsWith("data:")) continue
            val payload = line.substring(5).trim()
            if (payload == "[DONE]") break
            val chunk = try {
                DirectorJson.decodeFromString<ChatCompletionChunk>(payload)
            } catch (e: SerializationException) {
                continue // a single malformed chunk must not abort the stream
            }
            chunk.usage?.let { usage = it }
            val choice = chunk.choices.firstOrNull() ?: continue
            choice.finishReason?.let { finishReason = it }
            choice.delta.content?.let { content.append(it) }
            choice.delta.toolCalls?.forEach { tc ->
                val acc = tools.getOrPut(tc.index) { StreamingToolCall() }
                tc.id?.let { acc.id = it }
                tc.function?.name?.let { acc.name = it }
                tc.function?.arguments?.let { acc.arguments.append(it) }
            }
        }

        val toolCalls = tools.values
            .filter { it.name.isNotBlank() }
            .map { ToolCall(id = it.id, function = ToolCallFunction(it.name, it.arguments.toString())) }
            .ifEmpty { null }
        val message = ChatMessage(
            role = "assistant",
            content = ReasoningFilter.strip(content.toString())?.takeIf { it.isNotEmpty() },
            toolCalls = toolCalls,
        )
        return ChatResponse(
            choices = listOf(Choice(index = 0, message = message, finishReason = finishReason)),
            usage = usage,
        )
    }

    /** Mutable accumulator for one streamed tool call. */
    private class StreamingToolCall {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }

    private fun extractErrorMessage(body: String): String {
        if (body.isBlank()) return "(empty body)"
        return try {
            DirectorJson.decodeFromString<ApiErrorEnvelope>(body).error?.message ?: body.take(500)
        } catch (_: SerializationException) {
            body.take(500)
        }
    }

    private fun backoffMs(attempt: Int): Long {
        // 250ms, 500ms, 1s, 2s, 4s ... capped at 30s. Plus up to 25% jitter.
        val base = min(30_000L, 250L shl attempt.coerceAtMost(7))
        val jitter = Random.nextLong(0, base / 4 + 1)
        return base + jitter
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        fun defaultHttpClient(config: LlmConfig): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            // No total call timeout — a streamed reply can legitimately run
            // long. readTimeout (the idle gap between chunks) catches a stream
            // that has actually stalled.
            .callTimeout(Duration.ZERO)
            .readTimeout(Duration.ofSeconds(config.timeoutSeconds))
            .writeTimeout(Duration.ofSeconds(15))
            .retryOnConnectionFailure(false)
            .build()
    }
}
