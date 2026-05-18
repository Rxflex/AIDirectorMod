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

class DirectorSignificanceGateTest {

    private lateinit var server: MockWebServer
    private val player = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd")
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
    fun `repeated identical ticks skip the LLM after the first`(@TempDir tmp: Path) = runTest {
        // Two LLM responses queued — but only one should be consumed.
        repeat(2) {
            server.enqueue(MockResponse().setResponseCode(200).setBody(emptyAssistantBody))
        }

        val cfg = significanceCfg()
        val director = buildDirector(tmp, cfg)

        // First tick: no prior snapshot → LLM fires.
        val r1 = director.tickPlayer(player)
        assertThat(r1.skipped).isNull()

        // Second tick with the SAME snapshot, after the throttle window: gate skips.
        advanceClockPast(cfg)
        val r2 = director.tickPlayer(player)
        assertThat(r2.skipped).isEqualTo(Director.SkipReason.NO_SIGNIFICANT_CHANGE)

        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `damage event reopens the gate`(@TempDir tmp: Path) = runTest {
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(200).setBody(emptyAssistantBody))
        }
        val cfg = significanceCfg()
        val director = buildDirector(tmp, cfg)

        director.tickPlayer(player)
        advanceClockPast(cfg)
        director.tickPlayer(player) // skipped — nothing changed
        // Player gets hurt.
        memoryHandle.events.record(
            player,
            dev.aidirector.memory.EventKind.PLAYER_HURT,
            """{"source":"zombie.attack","amount":3.0}""",
        )
        advanceClockPast(cfg)
        val r3 = director.tickPlayer(player)
        assertThat(r3.skipped).isNull() // LLM fires again because of PLAYER_HURT

        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `cooldown blocks ticks immediately after an action`(@TempDir tmp: Path) = runTest {
        // First call: emits a tool. After that, cooldown=2 should skip next 2 evaluations.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"role":"assistant","tool_calls":[
                {"id":"tc","type":"function","function":{"name":"send_narration",
                "arguments":"{\"message\":\"hello\",\"style\":\"narrator\"}"}}]}}]}""",
            ),
        )
        // Iteration 2 of the agent loop — must respond, no further tool calls.
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyAssistantBody))
        // A potential later response if the gate were broken.
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyAssistantBody))

        val cfg = significanceCfg(cooldown = 2)
        val director = buildDirector(tmp, cfg)

        val r1 = director.tickPlayer(player)
        assertThat(r1.toolsExecuted).isEqualTo(1)

        advanceClockPast(cfg)
        val r2 = director.tickPlayer(player)
        assertThat(r2.skipped).isEqualTo(Director.SkipReason.COOLDOWN)

        advanceClockPast(cfg)
        val r3 = director.tickPlayer(player)
        assertThat(r3.skipped).isEqualTo(Director.SkipReason.COOLDOWN)

        // After cooldown expires, the significance gate takes over again.
        advanceClockPast(cfg)
        val r4 = director.tickPlayer(player)
        assertThat(r4.skipped).isEqualTo(Director.SkipReason.NO_SIGNIFICANT_CHANGE)
    }

    // ------------------------------------------------------------------

    private val emptyAssistantBody =
        """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""

    private val clock = TestClock()
    private lateinit var memoryHandle: Memory

    private fun significanceCfg(cooldown: Int = 0): DirectorConfig {
        val base = Fixtures.defaultConfig(baseUrl = server.url("/v1").toString())
        return base.copy(
            director = base.director.copy(
                requireSignificantChange = true,
                cooldownTicksAfterAction = cooldown,
                minSecondsBetweenLlmCalls = 1,
            ),
        )
    }

    private fun advanceClockPast(cfg: DirectorConfig) {
        clock.advance(cfg.director.minSecondsBetweenLlmCalls * 1000 + 100)
    }

    private fun buildDirector(tmp: Path, cfg: DirectorConfig): Director {
        val configService = FakeConfigService.of(cfg)
        val http = OkHttpClient.Builder().callTimeout(Duration.ofSeconds(5)).build()
        val llm = LlmClient(cfg.llm, http)
        val snapshot = Fixtures.snapshot(uuid = player)
        val sensors = FakeSensors(mapOf(player to snapshot))
        val memory = Memory.open(tmp.resolve("d.db"), clock).also {
            openMemories += it
            memoryHandle = it
        }
        val rag = Rag(
            facts = memory.facts,
            embeddings = FakeEmbedder(),
            model = "fake",
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        val guardrails = Guardrails(cfg.guardrails, clock)
        val tools = ToolRegistry(listOf(SendNarrationTool()))
        val phantoms = dev.aidirector.phantom.PhantomRegistry()
        val agentLoop = AgentLoop(
            llm = llm, tools = tools, guardrails = guardrails,
            memory = memory, rag = rag,
            narrationDedup = dev.aidirector.dedup.NarrationDedup(),
            phantoms = phantoms,
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
            phantoms = phantoms,
            clock = clock,
        )
    }
}
