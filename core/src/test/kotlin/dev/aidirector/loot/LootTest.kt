package dev.aidirector.loot

import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.dedup.NarrationDedup
import dev.aidirector.memory.Memory
import dev.aidirector.phantom.PhantomRegistry
import dev.aidirector.rag.Rag
import dev.aidirector.support.FakeEmbedder
import dev.aidirector.support.FakeServerActions
import dev.aidirector.support.TestClock
import dev.aidirector.tools.ToolContext
import dev.aidirector.tools.ToolResult
import dev.aidirector.tools.impl.GiveLootTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class LootTest {

    @Test
    fun `every bundled loot table loads and parses`() {
        for (id in LootPacks.ids) {
            val table = LootPacks.load(id)
            assertThat(table).describedAs(id).isNotNull
            assertThat(table!!.entries).describedAs(id).isNotEmpty
            assertThat(table.rollsMax).isGreaterThanOrEqualTo(table.rollsMin)
            table.entries.forEach {
                assertThat(it.item).startsWith("minecraft:")
                assertThat(it.weight).isGreaterThan(0)
            }
        }
    }

    @Test
    fun `roller stays within the rolls range and produces valid counts`() {
        val table = LootPacks.load("cursed_chest")!!
        repeat(30) {
            val rolled = LootRoller.roll(table)
            assertThat(rolled.size).isBetween(table.rollsMin, table.rollsMax)
            rolled.forEach {
                assertThat(it.count).isGreaterThanOrEqualTo(1)
                assertThat(it.itemId).startsWith("minecraft:")
            }
        }
    }

    @Test
    fun `unknown loot table id resolves to null`() {
        assertThat(LootPacks.load("dragon_hoard")).isNull()
    }

    @Test
    fun `give_loot rolls a table and gives the items`(@TempDir dir: Path) = runTest {
        val actions = FakeServerActions()
        ServerActionsHolder.install(actions)
        withCtx(dir) { ctx ->
            val result = GiveLootTool().execute(GiveLootTool.Args(table = "travelers_cache"), ctx)
            assertThat(result).isInstanceOf(ToolResult.Success::class.java)
            assertThat(actions.calls.filterIsInstance<FakeServerActions.Call.Item>()).isNotEmpty
        }
    }

    @Test
    fun `give_loot refuses an unknown table`(@TempDir dir: Path) = runTest {
        ServerActionsHolder.install(FakeServerActions())
        withCtx(dir) { ctx ->
            val result = GiveLootTool().execute(GiveLootTool.Args(table = "nonsense"), ctx)
            assertThat(result).isInstanceOf(ToolResult.Refused::class.java)
        }
    }

    private suspend fun withCtx(dir: Path, block: suspend (ToolContext) -> Unit) {
        Memory.open(dir.resolve("loot.db"), TestClock()).use { mem ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val rag = Rag(mem.facts, FakeEmbedder(), model = "fake", scope = scope)
            block(
                ToolContext(
                    playerUuid = UUID.randomUUID(),
                    nowMs = 10_000,
                    memory = mem,
                    rag = rag,
                    narrationDedup = NarrationDedup(),
                    phantoms = PhantomRegistry(),
                ),
            )
        }
    }
}
