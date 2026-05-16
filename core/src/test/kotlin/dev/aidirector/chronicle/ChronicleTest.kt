package dev.aidirector.chronicle

import dev.aidirector.campaign.CampaignStore
import dev.aidirector.llm.LlmClient
import dev.aidirector.memory.ChronicleEntry
import dev.aidirector.memory.EventKind
import dev.aidirector.memory.Memory
import dev.aidirector.rag.Rag
import dev.aidirector.support.FakeConfigService
import dev.aidirector.support.FakeEmbedder
import dev.aidirector.support.FakeServerActions
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

class ChronicleTest {

    private lateinit var server: MockWebServer
    private val openMemories = mutableListOf<Memory>()
    private val player = UUID.fromString("dddddddd-1111-2222-3333-dddddddddddd")

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
    fun `chronicle is skipped when disabled`(@TempDir tmp: Path) = runTest {
        val (mem, _) = openMem(tmp, "d.db")
        seedSession(mem, clock = TestClock())
        val report = build(mem, enabled = false).writeForPlayer(player)
        assertThat(report).isEqualTo(Chronicle.RunReport.Disabled)
    }

    @Test
    fun `a near-empty session writes nothing`(@TempDir tmp: Path) = runTest {
        val (mem, clock) = openMem(tmp, "q.db")
        // Only a join — nothing meaningful happened.
        mem.events.record(player, EventKind.PLAYER_JOIN, """{"name":"Bjorn"}""")
        val report = build(mem, enabled = true, clock = clock).writeForPlayer(player)
        assertThat(report).isInstanceOf(Chronicle.RunReport.TooQuiet::class.java)
    }

    @Test
    fun `a real session produces a stored, ingested entry`(@TempDir tmp: Path) = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"role":"assistant","content":"The Hollow Beneath\n\nBjorn descended into the dark and did not climb out the same. The stone kept the shape of his fear."}}]}""",
            ),
        )
        val (mem, clock) = openMem(tmp, "w.db")
        seedSession(mem, clock)
        val report = build(mem, enabled = true, clock = clock).writeForPlayer(player)

        assertThat(report).isInstanceOf(Chronicle.RunReport.Written::class.java)
        assertThat((report as Chronicle.RunReport.Written).title).isEqualTo("The Hollow Beneath")
        val stored = mem.chronicle.recentForPlayer(player)
        assertThat(stored).hasSize(1)
        assertThat(stored.first().body).contains("descended into the dark")
        // The entry is canon for RAG continuity.
        assertThat(mem.facts.all().any { it.kind == "lore" && it.content.contains("Chronicle") }).isTrue
    }

    @Test
    fun `pending entries are delivered as a book and then marked delivered`(@TempDir tmp: Path) = runTest {
        val (mem, clock) = openMem(tmp, "del.db")
        mem.chronicle.add(entry(mem, "e1"))
        mem.chronicle.add(entry(mem, "e2"))
        val actions = FakeServerActions()
        val delivered = build(mem, enabled = true, clock = clock, actions = actions)
            .deliverPendingForPlayer(player)

        assertThat(delivered).isEqualTo(2)
        assertThat(actions.calls.filterIsInstance<FakeServerActions.Call.Book>()).hasSize(1)
        assertThat(mem.chronicle.undelivered(player)).isEmpty()
        // A second delivery has nothing left to hand over.
        assertThat(build(mem, enabled = true, clock = clock, actions = actions).deliverPendingForPlayer(player))
            .isEqualTo(0)
    }

    private fun entry(mem: Memory, id: String) = ChronicleEntry(
        id = id,
        playerUuid = player,
        title = "Day of $id",
        body = "Something quiet happened, and the world noted it.",
        sessionStartMs = 1_000,
        sessionEndMs = 2_000,
        createdAtMs = 2_000,
    )

    private fun seedSession(mem: Memory, clock: TestClock) {
        mem.events.record(player, EventKind.PLAYER_JOIN, """{"name":"Bjorn"}""")
        clock.advance(1_000)
        mem.events.record(player, EventKind.PLAYER_CHAT, """{"text":"is anyone there?","from":"Bjorn"}""")
        clock.advance(1_000)
        mem.events.record(player, EventKind.PLAYER_HURT, """{"source":"fall","amount":4.0}""")
        clock.advance(1_000)
        mem.events.record(player, EventKind.PLAYER_DEATH, """{"name":"Bjorn","source":"cave_spider"}""")
        clock.advance(1_000)
    }

    private fun openMem(tmp: Path, name: String): Pair<Memory, TestClock> {
        val clock = TestClock()
        val mem = Memory.open(tmp.resolve(name), clock).also { openMemories += it }
        return mem to clock
    }

    private fun build(
        mem: Memory,
        enabled: Boolean,
        clock: TestClock = TestClock(),
        actions: FakeServerActions = FakeServerActions(),
    ): Chronicle {
        val cfg = Fixtures.defaultConfig(baseUrl = server.url("/v1").toString()).let {
            it.copy(director = it.director.copy(chronicleEnabled = enabled))
        }
        val http = OkHttpClient.Builder().callTimeout(Duration.ofSeconds(5)).build()
        val rag = Rag(
            facts = mem.facts, embeddings = FakeEmbedder(), model = "fake",
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        return Chronicle(
            configService = FakeConfigService.of(cfg),
            memory = mem,
            campaignStore = CampaignStore(mem.worldState),
            actions = actions,
            rag = rag,
            llm = LlmClient(cfg.llm, http),
            clock = clock,
        )
    }
}
