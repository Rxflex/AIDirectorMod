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
        val payload = DirectorJson.encodeToString(request)
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
        val bodyText = response.body?.string().orEmpty()
        when {
            response.isSuccessful -> {
                val decoded = try {
                    DirectorJson.decodeFromString<ChatResponse>(bodyText)
                } catch (e: SerializationException) {
                    throw LlmResponseException("Failed to decode chat completion: ${e.message}", e)
                }
                // Strip reasoning-model chain-of-thought from message content so
                // no `<think>`/`<thought>` block can leak into a narration, a
                // chronicle book, or a parsed-JSON consumer.
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
            .callTimeout(Duration.ofSeconds(config.timeoutSeconds))
            .readTimeout(Duration.ofSeconds(config.timeoutSeconds))
            .writeTimeout(Duration.ofSeconds(15))
            .retryOnConnectionFailure(false)
            .build()
    }
}
