package dev.aidirector.llm

import dev.aidirector.support.Fixtures
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

class LlmStreamingTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun client(): LlmClient {
        val cfg = Fixtures.defaultConfig(baseUrl = server.url("/v1").toString())
        val http = OkHttpClient.Builder().callTimeout(Duration.ofSeconds(5)).build()
        return LlmClient(cfg.llm, http)
    }

    private fun sse(vararg events: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(events.joinToString("\n\n", postfix = "\n\ndata: [DONE]\n\n") { "data: $it" })

    private val request = ChatRequest(
        model = "test-model",
        messages = listOf(ChatMessage(role = "user", content = "hi")),
    )

    @Test
    fun `reassembles streamed content deltas`() = runTest {
        server.enqueue(
            sse(
                """{"choices":[{"index":0,"delta":{"role":"assistant","content":"The cold "}}]}""",
                """{"choices":[{"index":0,"delta":{"content":"wind rises."}}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
            ),
        )
        val response = client().chat(request)
        assertThat(response.choices.first().message.content).isEqualTo("The cold wind rises.")
    }

    @Test
    fun `reassembles a tool call whose arguments arrive in fragments`() = runTest {
        server.enqueue(
            sse(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"send_narration","arguments":"{\"mess"}}]}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"age\":\"hi\"}"}}]}}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
            ),
        )
        val response = client().chat(request)
        val call = response.choices.first().message.toolCalls?.singleOrNull()
        assertThat(call).isNotNull
        assertThat(call!!.function.name).isEqualTo("send_narration")
        assertThat(call.function.arguments).isEqualTo("""{"message":"hi"}""")
    }

    @Test
    fun `strips an inline reasoning block from streamed content`() = runTest {
        server.enqueue(
            sse(
                """{"choices":[{"index":0,"delta":{"content":"<think>plan</think>The door creaks."}}]}""",
            ),
        )
        val response = client().chat(request)
        assertThat(response.choices.first().message.content).isEqualTo("The door creaks.")
    }

    @Test
    fun `still parses a non-streaming JSON response`() = runTest {
        // An endpoint that ignores stream=true and returns one JSON body.
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"index":0,"message":{"role":"assistant","content":"plain reply"}}]}""",
            ),
        )
        val response = client().chat(request)
        assertThat(response.choices.first().message.content).isEqualTo("plain reply")
    }
}
