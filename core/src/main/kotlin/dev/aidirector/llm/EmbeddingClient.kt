package dev.aidirector.llm

import dev.aidirector.AIDirector
import dev.aidirector.config.LlmConfig
import dev.aidirector.util.DirectorJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
 * Suspending client for OpenAI-compatible `/v1/embeddings`. Supports batched
 * input (NVIDIA NIM accepts up to ~96 strings per call). Returns embeddings
 * in the same order as input.
 */
class EmbeddingClient(
    private val config: LlmConfig,
    private val httpClient: OkHttpClient = defaultHttpClient(config),
) : Embedder {
    // Embeddings may be routed to a different endpoint than chat — some
    // OpenAI-compatible gateways drop the non-standard `input_type` field that
    // NVIDIA's asymmetric embedding models require, so the operator can point
    // embeddings straight at a provider that honours it.
    private val baseUrl = config.effectiveEmbedBaseUrl.trimEnd('/')
    private val apiKey = config.effectiveEmbedApiKey

    /** Returns one embedding per input string, in the input's order. */
    override suspend fun embed(model: String, inputs: List<String>, inputType: String): List<FloatArray> {
        require(inputs.isNotEmpty()) { "inputs must not be empty" }
        require(inputs.size <= MAX_BATCH) { "batch size ${inputs.size} exceeds $MAX_BATCH" }

        val req = EmbeddingRequest(model = model, input = inputs, inputType = inputType)
        val httpRequest = Request.Builder()
            .url("$baseUrl/embeddings")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .header("User-Agent", "AIDirector/${AIDirector.VERSION}")
            // Bytes + bare `application/json` — String.toRequestBody would
            // append `; charset=utf-8`, which the embeddings endpoint rejects
            // with HTTP 415 "Unsupported media type".
            .post(DirectorJson.encodeToString(req).encodeToByteArray().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return executeWithRetry(httpRequest, inputs.size)
    }

    private suspend fun executeWithRetry(request: Request, expectedSize: Int): List<FloatArray> {
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt <= config.maxRetries) {
            try {
                return executeOnce(request, expectedSize)
            } catch (e: LlmRateLimitException) {
                lastError = e
                val sleepMs = e.retryAfterSeconds?.times(1000) ?: backoffMs(attempt)
                AIDirector.log.warn("Embedding rate limited, sleeping ${sleepMs}ms")
                delay(sleepMs)
            } catch (e: LlmTransientException) {
                lastError = e
                if (attempt == config.maxRetries) break
                delay(backoffMs(attempt))
            }
            attempt++
        }
        throw lastError ?: LlmTransientException("Exhausted retries")
    }

    private suspend fun executeOnce(request: Request, expectedSize: Int): List<FloatArray> =
        withContext(Dispatchers.IO) {
            val response = try {
                httpClient.newCall(request).execute()
            } catch (e: IOException) {
                throw LlmTransientException("Embedding HTTP failed: ${e.message}", e)
            }
            response.use { resp -> parseResponse(resp, expectedSize) }
        }

    private fun parseResponse(response: Response, expectedSize: Int): List<FloatArray> {
        val body = response.body?.string().orEmpty()
        when {
            response.isSuccessful -> {
                val decoded = try {
                    DirectorJson.decodeFromString<EmbeddingResponse>(body)
                } catch (e: SerializationException) {
                    throw LlmResponseException("Failed to decode embeddings: ${e.message}", e)
                }
                if (decoded.data.size != expectedSize) {
                    throw LlmResponseException(
                        "Expected $expectedSize embeddings, got ${decoded.data.size}",
                    )
                }
                return decoded.data.sortedBy { it.index }.map { row ->
                    FloatArray(row.embedding.size) { i -> row.embedding[i].toFloat() }
                }
            }
            response.code == 408 || response.code == 429 -> {
                val retryAfter = response.header("Retry-After")?.toLongOrNull()
                throw LlmRateLimitException(retryAfter, body.take(300))
            }
            response.code in 500..599 -> throw LlmTransientException("HTTP ${response.code}: ${body.take(300)}")
            else -> throw LlmClientException(response.code, body.take(300))
        }
    }

    private fun backoffMs(attempt: Int): Long {
        val base = min(30_000L, 250L shl attempt.coerceAtMost(7))
        return base + Random.nextLong(0, base / 4 + 1)
    }

    @Serializable
    private data class EmbeddingRequest(
        val model: String,
        val input: List<String>,
        @SerialName("input_type") val inputType: String? = null,
    )

    @Serializable
    private data class EmbeddingResponse(val data: List<EmbeddingRow>)

    @Serializable
    private data class EmbeddingRow(
        val index: Int = 0,
        val embedding: List<Double>,
    )

    companion object {
        private const val MAX_BATCH = 96
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
