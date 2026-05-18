package dev.aidirector.phantom

import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.dedup.NarrationDedup
import dev.aidirector.memory.Memory
import dev.aidirector.rag.Rag
import dev.aidirector.support.FakeEmbedder
import dev.aidirector.support.FakeServerActions
import dev.aidirector.support.TestClock
import dev.aidirector.tools.ToolContext
import dev.aidirector.tools.ToolResult
import dev.aidirector.tools.impl.PhantomJoinTool
import dev.aidirector.tools.impl.PhantomLeaveTool
import dev.aidirector.tools.impl.PhantomSayTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class PhantomTest {

    @Test
    fun `registry add, case-insensitive lookup and dedup`() {
        val reg = PhantomRegistry()
        assertThat(reg.add(Phantom("Hollow", UUID.randomUUID(), 1))).isTrue
        // Same name (any case) cannot be added twice.
        assertThat(reg.add(Phantom("hollow", UUID.randomUUID(), 2))).isFalse
        assertThat(reg.get("HOLLOW")).isNotNull
        assertThat(reg.isActive("hollow")).isTrue
        assertThat(reg.remove("Hollow")).isNotNull
        assertThat(reg.get("hollow")).isNull()
    }

    @Test
    fun `registry reports full at the cap`() {
        val reg = PhantomRegistry()
        repeat(PhantomRegistry.MAX_ACTIVE) {
            reg.add(Phantom("ghost$it", UUID.randomUUID(), it.toLong()))
        }
        assertThat(reg.isFull()).isTrue
        assertThat(reg.all()).hasSize(PhantomRegistry.MAX_ACTIVE)
    }

    @Test
    fun `join then say then leave drives the registry and actions`(@TempDir dir: Path) = runTest {
        val actions = FakeServerActions()
        ServerActionsHolder.install(actions)
        withCtx(dir) { ctx ->
            val joined = PhantomJoinTool().execute(PhantomJoinTool.Args(name = "Wanderer_7"), ctx)
            assertThat(joined).isInstanceOf(ToolResult.Success::class.java)
            assertThat(ctx.phantoms.isActive("wanderer_7")).isTrue
            assertThat(actions.calls.filterIsInstance<FakeServerActions.Call.PhantomJoin>()).hasSize(1)

            val said = PhantomSayTool().execute(
                PhantomSayTool.Args(name = "Wanderer_7", message = "you left the door open"),
                ctx,
            )
            assertThat(said).isInstanceOf(ToolResult.Success::class.java)

            val left = PhantomLeaveTool().execute(PhantomLeaveTool.Args(name = "Wanderer_7"), ctx)
            assertThat(left).isInstanceOf(ToolResult.Success::class.java)
            assertThat(ctx.phantoms.isActive("wanderer_7")).isFalse
        }
    }

    @Test
    fun `say is refused for a phantom that never joined`(@TempDir dir: Path) = runTest {
        ServerActionsHolder.install(FakeServerActions())
        withCtx(dir) { ctx ->
            val result = PhantomSayTool().execute(
                PhantomSayTool.Args(name = "NoOne", message = "hello"),
                ctx,
            )
            assertThat(result).isInstanceOf(ToolResult.Refused::class.java)
        }
    }

    @Test
    fun `join is refused for a name that is too short`(@TempDir dir: Path) = runTest {
        ServerActionsHolder.install(FakeServerActions())
        withCtx(dir) { ctx ->
            val result = PhantomJoinTool().execute(PhantomJoinTool.Args(name = "x"), ctx)
            assertThat(result).isInstanceOf(ToolResult.Refused::class.java)
        }
    }

    private suspend fun withCtx(dir: Path, block: suspend (ToolContext) -> Unit) {
        Memory.open(dir.resolve("phantom.db"), TestClock()).use { mem ->
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
