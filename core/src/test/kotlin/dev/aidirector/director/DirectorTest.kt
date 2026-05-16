package dev.aidirector.director

import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.config.DirectorConfig
import dev.aidirector.guardrails.Guardrails
import dev.aidirector.llm.LlmClient
import dev.aidirector.memory.Memory
import dev.aidirector.rag.Rag
import dev.aidirector.support.FakeConfigService
import dev.aidirector.support.FakeEmbedder
import dev.aidirector.support.FakeSensors
import dev.aidirector.support.FakeServerActions
import dev.aidirector.support.Fixtures
import dev.aidirector.support.TestClock
import dev.aidirector.tools.ToolRegistry
import dev.aidirector.tools.impl.ApplyEffectTool
import dev.aidirector.tools.impl.GiveItemTool
import dev.aidirector.tools.impl.ModifyWeatherTool
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

class DirectorTest {

    private lateinit var server: MockWebServer
    private val player = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val openMemories = mutableListOf<Memory>()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        ServerActionsHolder.reset()
        openMemories.forEach { runCatching { it.close() } }
        openMemories.clear()
    }

    @Test
    fun `dispatches a narration tool call`(@TempDir tmp: Path) = runTest {
        val actions = FakeServerActions()
        ServerActionsHolder.install(actions)

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"choices":[{"message":{"role":"assistant","tool_calls":[
                {"id":"tc","type":"function","function":{"name":"send_narration",
                "arguments":"{\"message\":\"A cold wind blows.\",\"style\":\"narrator\"}"}}]}}]}
                """.trimIndent(),
            ),
        )
        // Second iteration: assistant finishes with no tool calls.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"role":"assistant","content":""}}]}""",
            ),
        )

        val report = buildDirector(tmp).tickPlayer(player)

        assertThat(report.toolsExecuted).isEqualTo(1)
        assertThat(actions.calls).hasSize(1)
        val narration = actions.calls[0] as FakeServerActions.Call.Narration
        assertThat(narration.message).isEqualTo("A cold wind blows.")
    }

    @Test
    fun `respects per-player throttle`(@TempDir tmp: Path) = runTest {
        ServerActionsHolder.install(FakeServerActions())
        // First call goes through: no tools, just an empty content reply.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""",
            ),
        )
        val director = buildDirector(tmp)
        val first = director.tickPlayer(player)
        assertThat(first.skipped).isNull()
        val second = director.tickPlayer(player)
        assertThat(second.skipped).isEqualTo(Director.SkipReason.THROTTLED)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `unknown tool is refused`(@TempDir tmp: Path) = runTest {
        ServerActionsHolder.install(FakeServerActions())
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"role":"assistant","tool_calls":[
                {"id":"tc","type":"function","function":{"name":"ghost_tool","arguments":"{}"}}]}}]}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"role":"assistant","content":""}}]}""",
            ),
        )
        val report = buildDirector(tmp).tickPlayer(player)
        assertThat(report.toolsExecuted).isEqualTo(0)
        assertThat(report.toolsRefused).isEqualTo(1)
    }

    @Test
    fun `caps tool calls per iteration`(@TempDir tmp: Path) = runTest {
        ServerActionsHolder.install(FakeServerActions())
        val toolCalls = (1..5).joinToString(",") { i ->
            """{"id":"tc$i","type":"function","function":{"name":"send_narration","arguments":"{\"message\":\"m$i\",\"style\":\"narrator\"}"}}"""
        }
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"role":"assistant","tool_calls":[$toolCalls]}}]}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"role":"assistant","content":""}}]}""",
            ),
        )
        val report = buildDirector(tmp).tickPlayer(player)
        // Config caps at 3 calls per iteration.
        assertThat(report.toolsAttempted).isEqualTo(3)
        assertThat(report.toolsExecuted).isEqualTo(3)
    }

    @Test
    fun `skips when disabled in config`(@TempDir tmp: Path) = runTest {
        ServerActionsHolder.install(FakeServerActions())
        val cfg = Fixtures.defaultConfig(baseUrl = server.url("/v1").toString()).let {
            it.copy(director = it.director.copy(enabled = false))
        }
        val director = buildDirector(tmp, cfg)
        val report = director.tickPlayer(player)
        assertThat(report.skipped).isEqualTo(Director.SkipReason.DISABLED)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `give_item refused when item not registered`(@TempDir tmp: Path) = runTest {
        ServerActionsHolder.install(FakeServerActions(knownItems = emptySet()))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"role":"assistant","tool_calls":[
                {"id":"tc","type":"function","function":{"name":"give_item","arguments":"{\"item_id\":\"minecraft:bread\"}"}}]}}]}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"role":"assistant","content":""}}]}""",
            ),
        )
        val report = buildDirector(tmp).tickPlayer(player)
        assertThat(report.toolsRefused).isEqualTo(1)
        assertThat(report.toolsExecuted).isEqualTo(0)
    }

    private fun buildDirector(
        tmp: Path,
        cfg: DirectorConfig = Fixtures.defaultConfig(baseUrl = server.url("/v1").toString()),
        clock: TestClock = TestClock(),
    ): Director {
        val configService = FakeConfigService.of(cfg)
        val http = OkHttpClient.Builder().callTimeout(Duration.ofSeconds(5)).build()
        val llm = LlmClient(cfg.llm, http)
        val sensors = FakeSensors(mapOf(player to Fixtures.snapshot(uuid = player)))
        val memory = Memory.open(tmp.resolve("d.db"), clock).also { openMemories += it }
        val rag = Rag(
            facts = memory.facts,
            embeddings = FakeEmbedder(),
            model = "fake",
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        val guardrails = Guardrails(cfg.guardrails, clock)
        val tools = ToolRegistry(
            listOf(
                SendNarrationTool(),
                PlaySoundTool(),
                GiveItemTool { cfg.guardrails.itemRarityCap },
                ApplyEffectTool(),
                ModifyWeatherTool(),
            ),
        )
        val agentLoop = AgentLoop(
            llm = llm,
            tools = tools,
            guardrails = guardrails,
            memory = memory,
            rag = rag,
            narrationDedup = dev.aidirector.dedup.NarrationDedup(),
            maxIterations = cfg.director.maxAgentIterations,
            maxToolCallsPerIteration = cfg.director.maxToolCallsPerIteration,
        )
        return Director(
            configService = configService,
            sensors = sensors,
            memory = memory,
            rag = rag,
            agentLoop = agentLoop,
            campaignStore = dev.aidirector.campaign.CampaignStore(memory.worldState),
            clock = clock,
        )
    }
}
