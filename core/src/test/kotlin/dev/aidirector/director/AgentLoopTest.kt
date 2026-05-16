package dev.aidirector.director

import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.guardrails.Guardrails
import dev.aidirector.llm.ChatMessage
import dev.aidirector.llm.LlmClient
import dev.aidirector.memory.Memory
import dev.aidirector.rag.Rag
import dev.aidirector.support.FakeEmbedder
import dev.aidirector.support.FakeServerActions
import dev.aidirector.support.Fixtures
import dev.aidirector.support.TestClock
import dev.aidirector.tools.ToolRegistry
import dev.aidirector.tools.impl.PlaySoundTool
import dev.aidirector.tools.impl.SendNarrationTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

class AgentLoopTest {

    private lateinit var server: MockWebServer
    private val player = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val openMemories = mutableListOf<Memory>()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        ServerActionsHolder.install(FakeServerActions())
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        ServerActionsHolder.reset()
        openMemories.forEach { runCatching { it.close() } }
        openMemories.clear()
    }

    @Test
    fun `runs multiple iterations until LLM stops calling tools`(@TempDir tmp: Path) = runTest {
        // Iter 1: emit play_sound. Iter 2: see result, emit send_narration. Iter 3: done.
        server.enqueue(jsonToolCall("play_sound", """{"sound_id":"minecraft:entity.wolf.howl"}"""))
        server.enqueue(jsonToolCall("send_narration", """{"message":"You feel watched.","style":"whisper"}"""))
        server.enqueue(jsonAssistantText("done"))

        val loop = buildLoop(tmp)
        val report = loop.run(
            playerUuid = player,
            nowMs = 1_000,
            initialMessages = listOf(ChatMessage(role = "user", content = "go")),
            model = "test-model",
            temperature = 0.5,
            maxTokens = 256,
        )
        assertThat(report.iterations).isEqualTo(3)
        assertThat(report.toolsExecuted).isEqualTo(2)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `caps at maxIterations even if LLM keeps calling tools`(@TempDir tmp: Path) = runTest {
        // Force the agent to be capped at 2 iterations.
        repeat(5) { server.enqueue(jsonToolCall("send_narration", """{"message":"m","style":"narrator"}""")) }

        val loop = buildLoop(tmp, maxIterations = 2)
        val report = loop.run(
            playerUuid = player,
            nowMs = 1_000,
            initialMessages = listOf(ChatMessage(role = "user", content = "go")),
            model = "m",
            temperature = 0.5,
            maxTokens = 256,
        )
        assertThat(report.iterations).isEqualTo(2)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `LLM error is captured, loop stops gracefully`(@TempDir tmp: Path) = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad"}}"""))
        val loop = buildLoop(tmp)
        val report = loop.run(
            playerUuid = player,
            nowMs = 1_000,
            initialMessages = listOf(ChatMessage(role = "user", content = "go")),
            model = "m",
            temperature = 0.5,
            maxTokens = 256,
        )
        assertThat(report.llmError).isNotNull
        assertThat(report.toolsAttempted).isEqualTo(0)
    }

    private fun jsonToolCall(name: String, args: String): MockResponse =
        MockResponse().setResponseCode(200).setBody(
            """{"choices":[{"message":{"role":"assistant","tool_calls":[
            {"id":"tc_${name}_${System.nanoTime()}","type":"function","function":
            {"name":"$name","arguments":${kotlinx.serialization.json.JsonPrimitive(args)}}}]}}]}""",
        )

    private fun jsonAssistantText(text: String): MockResponse =
        MockResponse().setResponseCode(200).setBody(
            """{"choices":[{"message":{"role":"assistant","content":"$text"}}]}""",
        )

    private fun buildLoop(tmp: Path, maxIterations: Int = 4): AgentLoop {
        val cfg = Fixtures.defaultConfig(baseUrl = server.url("/v1").toString())
        val http = OkHttpClient.Builder().callTimeout(Duration.ofSeconds(5)).build()
        val llm = LlmClient(cfg.llm, http)
        val memory = Memory.open(tmp.resolve("a.db"), TestClock()).also { openMemories += it }
        val rag = Rag(
            facts = memory.facts,
            embeddings = FakeEmbedder(),
            model = "fake",
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        val tools = ToolRegistry(listOf(SendNarrationTool(), PlaySoundTool()))
        val guardrails = Guardrails(cfg.guardrails, TestClock())
        return AgentLoop(
            llm = llm, tools = tools, guardrails = guardrails,
            memory = memory, rag = rag,
            narrationDedup = dev.aidirector.dedup.NarrationDedup(),
            maxIterations = maxIterations, maxToolCallsPerIteration = 3,
        )
    }
}
