package dev.aidirector.tools.impl

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class MobSceneToolTest {

    @Test
    fun `modify_mob applies modifiers to a valid entity`(@TempDir dir: Path) = runTest {
        val actions = FakeServerActions()
        ServerActionsHolder.install(actions)
        withCtx(dir) { ctx ->
            val result = ModifyMobTool().execute(
                ModifyMobTool.Args(
                    entityUuid = UUID.randomUUID().toString(),
                    glowing = true,
                    scale = 2.5,
                    speedMultiplier = 1.8,
                ),
                ctx,
            )
            assertThat(result).isInstanceOf(ToolResult.Success::class.java)
            assertThat(actions.calls.filterIsInstance<FakeServerActions.Call.MobModify>()).hasSize(1)
        }
    }

    @Test
    fun `modify_mob refuses an invalid uuid`(@TempDir dir: Path) = runTest {
        ServerActionsHolder.install(FakeServerActions())
        withCtx(dir) { ctx ->
            val result = ModifyMobTool().execute(
                ModifyMobTool.Args(entityUuid = "not-a-uuid", glowing = true),
                ctx,
            )
            assertThat(result).isInstanceOf(ToolResult.Refused::class.java)
        }
    }

    @Test
    fun `modify_mob refuses when no modifiers are supplied`(@TempDir dir: Path) = runTest {
        ServerActionsHolder.install(FakeServerActions())
        withCtx(dir) { ctx ->
            val result = ModifyMobTool().execute(
                ModifyMobTool.Args(entityUuid = UUID.randomUUID().toString()),
                ctx,
            )
            assertThat(result).isInstanceOf(ToolResult.Refused::class.java)
        }
    }

    @Test
    fun `dress_scene places an allowed decorative block`(@TempDir dir: Path) = runTest {
        val actions = FakeServerActions()
        ServerActionsHolder.install(actions)
        withCtx(dir) { ctx ->
            val result = DressSceneTool().execute(
                DressSceneTool.Args(block = "minecraft:cobweb", x = 10, y = 64, z = -5),
                ctx,
            )
            assertThat(result).isInstanceOf(ToolResult.Success::class.java)
            assertThat(actions.calls.filterIsInstance<FakeServerActions.Call.Decoration>()).hasSize(1)
        }
    }

    @Test
    fun `dress_scene refuses a block outside the decorative allowlist`(@TempDir dir: Path) = runTest {
        ServerActionsHolder.install(FakeServerActions())
        withCtx(dir) { ctx ->
            val result = DressSceneTool().execute(
                DressSceneTool.Args(block = "minecraft:tnt", x = 0, y = 64, z = 0),
                ctx,
            )
            assertThat(result).isInstanceOf(ToolResult.Refused::class.java)
        }
    }

    private suspend fun withCtx(dir: Path, block: suspend (ToolContext) -> Unit) {
        Memory.open(dir.resolve("mobscene.db"), TestClock()).use { mem ->
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
