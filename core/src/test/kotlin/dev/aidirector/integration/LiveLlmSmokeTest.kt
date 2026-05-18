package dev.aidirector.integration

import dev.aidirector.config.LlmConfig
import dev.aidirector.llm.ChatMessage
import dev.aidirector.llm.ChatRequest
import dev.aidirector.llm.EmbeddingClient
import dev.aidirector.llm.LlmClient
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end smoke test against a real OpenAI-compatible endpoint. Skipped
 * unless `AIDIRECTOR_LLM_API_KEY` is in the environment. Useful as a manual
 * "does my key work, does the model emit tool calls" check before deploying.
 *
 * Run from CLI:
 *     AIDIRECTOR_LLM_API_KEY=nvapi-... \
 *     AIDIRECTOR_LLM_MODEL=meta/llama-3.3-70b-instruct \
 *     ./gradlew :common:test --tests "dev.aidirector.integration.LiveLlmSmokeTest"
 */
class LiveLlmSmokeTest {

    private val apiKey: String? = System.getenv("AIDIRECTOR_LLM_API_KEY")
    private val baseUrl: String = System.getenv("AIDIRECTOR_LLM_BASE_URL")
        ?: "https://integrate.api.nvidia.com/v1"
    private val chatModel: String = System.getenv("AIDIRECTOR_LLM_MODEL")
        ?: "meta/llama-3.3-70b-instruct"
    private val embedModel: String = System.getenv("AIDIRECTOR_LLM_EMBED_MODEL")
        ?: "nvidia/nv-embedqa-e5-v5"

    private fun cfg(): LlmConfig = LlmConfig(
        baseUrl = baseUrl,
        apiKey = apiKey!!,
        model = chatModel,
        embedModel = embedModel,
        embedBaseUrl = null,
        embedApiKey = null,
        timeoutSeconds = 60,
        maxRetries = 1,
        temperature = 0.2,
        maxTokens = 200,
    )

    @Test
    fun `chat returns non-empty content`() = runBlocking {
        assumeTrue(apiKey != null, "AIDIRECTOR_LLM_API_KEY not set — skipping live LLM smoke test")
        val client = LlmClient(cfg())
        val resp = client.chat(
            ChatRequest(
                model = chatModel,
                messages = listOf(
                    ChatMessage(role = "system", content = "You are concise."),
                    ChatMessage(role = "user", content = "Reply with the word 'pong' and nothing else."),
                ),
                temperature = 0.0,
                maxTokens = 16,
            ),
        )
        val text = resp.choices.firstOrNull()?.message?.content
        assertThat(text).isNotBlank
    }

    @Test
    fun `embeddings return a non-empty vector`() = runBlocking {
        assumeTrue(apiKey != null, "AIDIRECTOR_LLM_API_KEY not set — skipping live LLM smoke test")
        val client = EmbeddingClient(cfg())
        val vec = client.embed(embedModel, listOf("the haunted well")).first()
        assertThat(vec.size).isGreaterThan(64)
        // Some component must be non-zero.
        assertThat(vec.any { it != 0f }).isTrue
    }
}
