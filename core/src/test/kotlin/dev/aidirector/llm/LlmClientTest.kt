package dev.aidirector.llm

import dev.aidirector.support.Fixtures
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

class LlmClientTest {

    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(5))
            .build()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun client(maxRetries: Int = 2): LlmClient {
        val cfg = Fixtures.defaultConfig(baseUrl = server.url("/v1").toString()).llm
            .copy(maxRetries = maxRetries)
        return LlmClient(cfg, http)
    }

    @Test
    fun `returns parsed response on 200`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "id": "abc",
                      "model": "test-model",
                      "choices": [{
                        "index": 0,
                        "message": { "role": "assistant", "content": "ok" },
                        "finish_reason": "stop"
                      }]
                    }
                    """.trimIndent(),
                ),
        )
        val res = client().chat(
            ChatRequest(
                model = "test-model",
                messages = listOf(ChatMessage(role = "user", content = "hi")),
            ),
        )
        assertThat(res.choices).hasSize(1)
        assertThat(res.choices[0].message.content).isEqualTo("ok")
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/v1/chat/completions")
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer test-key")
    }

    @Test
    fun `retries on 5xx and succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("upstream"))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"role":"assistant","content":"recovered"}}]}""",
            ),
        )
        val res = client(maxRetries = 3).chat(
            ChatRequest(model = "m", messages = listOf(ChatMessage("user", "x"))),
        )
        assertThat(res.choices[0].message.content).isEqualTo("recovered")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `gives up after maxRetries on transient`() = runTest {
        repeat(5) { server.enqueue(MockResponse().setResponseCode(503)) }
        assertThatThrownBy {
            kotlinx.coroutines.runBlocking {
                client(maxRetries = 1).chat(
                    ChatRequest(model = "m", messages = listOf(ChatMessage("user", "x"))),
                )
            }
        }.isInstanceOf(LlmTransientException::class.java)
        assertThat(server.requestCount).isEqualTo(2) // initial + 1 retry
    }

    @Test
    fun `4xx is non-retryable client error`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"message":"bad token"}}"""),
        )
        assertThatThrownBy {
            kotlinx.coroutines.runBlocking {
                client(maxRetries = 3).chat(
                    ChatRequest(model = "m", messages = listOf(ChatMessage("user", "x"))),
                )
            }
        }.isInstanceOf(LlmClientException::class.java)
            .hasMessageContaining("bad token")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `429 with Retry-After is treated as rate limit`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"role":"assistant","content":"k"}}]}""",
            ),
        )
        val res = client(maxRetries = 2).chat(
            ChatRequest(model = "m", messages = listOf(ChatMessage("user", "x"))),
        )
        assertThat(res.choices[0].message.content).isEqualTo("k")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `decodes tool_calls`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "tool_calls": [{
                        "id": "tc_1",
                        "type": "function",
                        "function": {
                          "name": "send_narration",
                          "arguments": "{\"message\":\"hi\",\"style\":\"narrator\"}"
                        }
                      }]
                    }
                  }]
                }
                """.trimIndent(),
            ),
        )
        val res = client().chat(ChatRequest("m", listOf(ChatMessage("user", "x"))))
        val calls = res.choices[0].message.toolCalls!!
        assertThat(calls).hasSize(1)
        assertThat(calls[0].function.name).isEqualTo("send_narration")
        assertThat(calls[0].function.arguments).contains("narrator")
    }
}
