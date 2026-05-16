package dev.aidirector.campaign

import dev.aidirector.config.LlmConfig
import dev.aidirector.guardrails.Guardrails
import dev.aidirector.llm.LlmClient
import dev.aidirector.memory.Memory
import dev.aidirector.rag.Rag
import dev.aidirector.support.FakeConfigService
import dev.aidirector.support.FakeEmbedder
import dev.aidirector.support.FakeSensors
import dev.aidirector.support.Fixtures
import dev.aidirector.support.TestClock
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

class CampaignTest {

    private lateinit var server: MockWebServer
    private val openMemories = mutableListOf<Memory>()
    private val player = UUID.fromString("cccccccc-1111-2222-3333-cccccccccccc")

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        openMemories.forEach { runCatching { it.close() } }
        openMemories.clear()
    }

    @Test
    fun `CampaignStore round-trips state`(@TempDir tmp: Path) {
        val mem = Memory.open(tmp.resolve("c.db"), TestClock()).also { openMemories += it }
        val store = CampaignStore(mem.worldState)
        assertThat(store.exists()).isFalse
        val state = CampaignState(
            premise = "A village forgets its dead.",
            theme = "memory and grief",
            tone = "quiet melancholy",
            acts = listOf(
                CampaignAct("The First Name", "plant unease", listOf("a faded grave"), ActStatus.ACTIVE.wire),
                CampaignAct("The Reckoning", "pay it off", listOf("the dead return"), ActStatus.PENDING.wire),
            ),
            currentActIndex = 0,
            revision = 1,
            updatedAtMs = 1_000,
        )
        store.save(state)
        assertThat(store.exists()).isTrue
        val loaded = store.load()!!
        assertThat(loaded.premise).isEqualTo("A village forgets its dead.")
        assertThat(loaded.acts).hasSize(2)
        assertThat(loaded.currentAct?.title).isEqualTo("The First Name")
    }

    @Test
    fun `Showrunner bootstraps a campaign from LLM JSON`(@TempDir tmp: Path) = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"choices":[{"message":{"role":"assistant","content":
                "Here is the plan: {\"premise\":\"The mine took the miners.\",\"theme\":\"hubris\",\"tone\":\"slow dread\",\"acts\":[{\"title\":\"The Sealed Door\",\"goal\":\"make the player curious about the mine\",\"beats\":[\"a stranger warns them\",\"they find the door\"]},{\"title\":\"The Descent\",\"goal\":\"draw them in\",\"beats\":[\"the door opens\"]}]}"
                }}]}
                """.trimIndent(),
            ),
        )

        val mem = Memory.open(tmp.resolve("s.db"), TestClock()).also { openMemories += it }
        val store = CampaignStore(mem.worldState)
        val showrunner = buildShowrunner(mem, store)

        val report = showrunner.runNow()
        assertThat(report).isInstanceOf(Showrunner.RunReport.Bootstrapped::class.java)

        val state = store.load()!!
        assertThat(state.premise).contains("mine took the miners")
        assertThat(state.acts).hasSize(2)
        // First act becomes ACTIVE, the rest PENDING.
        assertThat(state.acts[0].status).isEqualTo(ActStatus.ACTIVE.wire)
        assertThat(state.acts[1].status).isEqualTo(ActStatus.PENDING.wire)
        assertThat(state.currentActIndex).isEqualTo(0)
        assertThat(state.revision).isEqualTo(1)
    }

    @Test
    fun `Showrunner failure leaves no campaign`(@TempDir tmp: Path) = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"choices":[{"message":{"role":"assistant","content":"sorry, no json here"}}]}""",
        ))
        val mem = Memory.open(tmp.resolve("f.db"), TestClock()).also { openMemories += it }
        val store = CampaignStore(mem.worldState)
        val report = buildShowrunner(mem, store).runNow()
        assertThat(report).isInstanceOf(Showrunner.RunReport.Failed::class.java)
        assertThat(store.exists()).isFalse
    }

    private fun buildShowrunner(mem: Memory, store: CampaignStore): Showrunner {
        val cfg = Fixtures.defaultConfig(baseUrl = server.url("/v1").toString()).let {
            it.copy(director = it.director.copy(campaignEnabled = true))
        }
        val http = OkHttpClient.Builder().callTimeout(Duration.ofSeconds(5)).build()
        val llm = LlmClient(cfg.llm, http)
        val rag = Rag(
            facts = mem.facts, embeddings = FakeEmbedder(), model = "fake",
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        val sensors = FakeSensors(mapOf(player to Fixtures.snapshot(uuid = player)))
        return Showrunner(
            configService = FakeConfigService.of(cfg),
            sensors = sensors,
            memory = mem,
            rag = rag,
            campaignStore = store,
            llm = llm,
            clock = TestClock(),
        )
    }
}
