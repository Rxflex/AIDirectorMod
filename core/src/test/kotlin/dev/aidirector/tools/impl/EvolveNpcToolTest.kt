package dev.aidirector.tools.impl

import dev.aidirector.dedup.NarrationDedup
import dev.aidirector.memory.Memory
import dev.aidirector.memory.NpcRecord
import dev.aidirector.memory.NpcStatus
import dev.aidirector.rag.Rag
import dev.aidirector.support.FakeEmbedder
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

class EvolveNpcToolTest {

    private val tool = EvolveNpcTool()

    @Test
    fun `unknown npc id is refused`(@TempDir dir: Path) = runTest {
        withCtx(dir) { ctx ->
            val result = tool.execute(EvolveNpcTool.Args(npcId = "ghost"), ctx)
            assertThat(result).isInstanceOf(ToolResult.Refused::class.java)
        }
    }

    @Test
    fun `marking an NPC dead updates status and records a fate fact`(@TempDir dir: Path) = runTest {
        withCtx(dir) { ctx ->
            ctx.memory.npcs.upsert(npc("kasper", ctx.playerUuid))
            val result = tool.execute(
                EvolveNpcTool.Args(
                    npcId = "kasper",
                    status = "dead",
                    arcStage = "fell defending the player at the ravine",
                ),
                ctx,
            )
            assertThat(result).isInstanceOf(ToolResult.Success::class.java)
            val loaded = ctx.memory.npcs.get("kasper")!!
            assertThat(loaded.status).isEqualTo(NpcStatus.DEAD)
            assertThat(loaded.arcStage).contains("ravine")
            // The fate is recorded as canon for RAG.
            assertThat(ctx.memory.facts.all().any { it.kind == "npc.fate" }).isTrue
        }
    }

    @Test
    fun `updating only relationship leaves status untouched`(@TempDir dir: Path) = runTest {
        withCtx(dir) { ctx ->
            ctx.memory.npcs.upsert(npc("kasper", ctx.playerUuid))
            tool.execute(
                EvolveNpcTool.Args(npcId = "kasper", relationship = "betrayed friend"),
                ctx,
            )
            val loaded = ctx.memory.npcs.get("kasper")!!
            assertThat(loaded.status).isEqualTo(NpcStatus.ACTIVE)
            assertThat(loaded.relationship).isEqualTo("betrayed friend")
        }
    }

    @Test
    fun `invalid status string is refused`(@TempDir dir: Path) = runTest {
        withCtx(dir) { ctx ->
            ctx.memory.npcs.upsert(npc("kasper", ctx.playerUuid))
            val result = tool.execute(
                EvolveNpcTool.Args(npcId = "kasper", status = "zombified"),
                ctx,
            )
            assertThat(result).isInstanceOf(ToolResult.Refused::class.java)
        }
    }

    @Test
    fun `an NPC belonging to another player is refused`(@TempDir dir: Path) = runTest {
        withCtx(dir) { ctx ->
            ctx.memory.npcs.upsert(npc("kasper", UUID.randomUUID()))
            val result = tool.execute(EvolveNpcTool.Args(npcId = "kasper", status = "missing"), ctx)
            assertThat(result).isInstanceOf(ToolResult.Refused::class.java)
        }
    }

    private fun npc(id: String, owner: UUID): NpcRecord = NpcRecord(
        id = id,
        entityUuid = UUID.randomUUID(),
        associatedPlayer = owner,
        name = "Kasper",
        role = "scholar",
        personality = "soft-spoken, fond of riddles",
        dimensionId = "minecraft:overworld",
        x = 0, y = 64, z = 0,
        status = NpcStatus.ACTIVE,
        createdAtMs = 1_000,
        lastSeenAtMs = 1_000,
    )

    private suspend fun withCtx(dir: Path, block: suspend (ToolContext) -> Unit) {
        Memory.open(dir.resolve("evolve.db"), TestClock()).use { mem ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val rag = Rag(mem.facts, FakeEmbedder(), model = "fake", scope = scope)
            val ctx = ToolContext(
                playerUuid = UUID.randomUUID(),
                nowMs = 10_000,
                memory = mem,
                rag = rag,
                narrationDedup = NarrationDedup(),
            )
            block(ctx)
        }
    }
}
