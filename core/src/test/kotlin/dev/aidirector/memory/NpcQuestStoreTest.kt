package dev.aidirector.memory

import dev.aidirector.support.TestClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class NpcQuestStoreTest {

    @Test
    fun `NpcStore upsert + lookup + status changes`(@TempDir dir: Path) {
        Memory.open(dir.resolve("n.db"), TestClock()).use { mem ->
            val player = UUID.randomUUID()
            val entity = UUID.randomUUID()
            mem.npcs.upsert(npc("kasper", entity, player, NpcStatus.ACTIVE))
            assertThat(mem.npcs.byEntityUuid(entity)?.name).isEqualTo("Kasper")
            assertThat(mem.npcs.activeForPlayer(player)).hasSize(1)
            mem.npcs.markDead("kasper")
            assertThat(mem.npcs.activeForPlayer(player)).isEmpty()
            assertThat(mem.npcs.get("kasper")?.status).isEqualTo(NpcStatus.DEAD)
        }
    }

    @Test
    fun `NpcStore arc columns persist and roster includes resolved fates`(@TempDir dir: Path) {
        Memory.open(dir.resolve("arc.db"), TestClock()).use { mem ->
            val player = UUID.randomUUID()
            mem.npcs.upsert(
                npc("kasper", UUID.randomUUID(), player, NpcStatus.ACTIVE).copy(
                    arcStage = "warned the player about the deep mine",
                    relationship = "wary ally",
                    interactionCount = 2,
                ),
            )
            val loaded = mem.npcs.get("kasper")!!
            assertThat(loaded.arcStage).isEqualTo("warned the player about the deep mine")
            assertThat(loaded.relationship).isEqualTo("wary ally")
            assertThat(loaded.interactionCount).isEqualTo(2)

            // A dead NPC drops out of activeForPlayer but stays in the roster.
            mem.npcs.upsert(loaded.copy(status = NpcStatus.DEAD))
            assertThat(mem.npcs.activeForPlayer(player)).isEmpty()
            assertThat(mem.npcs.rosterForPlayer(player)).hasSize(1)

            // A despawned NPC carries no story weight — excluded from the roster.
            mem.npcs.upsert(loaded.copy(status = NpcStatus.DESPAWNED))
            assertThat(mem.npcs.rosterForPlayer(player)).isEmpty()
        }
    }

    @Test
    fun `recordInteraction bumps the tally`(@TempDir dir: Path) {
        Memory.open(dir.resolve("intc.db"), TestClock()).use { mem ->
            val player = UUID.randomUUID()
            mem.npcs.upsert(npc("kasper", UUID.randomUUID(), player, NpcStatus.ACTIVE))
            mem.npcs.recordInteraction("kasper", nowMs = 5_000)
            mem.npcs.recordInteraction("kasper", nowMs = 6_000)
            val loaded = mem.npcs.get("kasper")!!
            assertThat(loaded.interactionCount).isEqualTo(2)
            assertThat(loaded.lastSeenAtMs).isEqualTo(6_000L)
        }
    }

    @Test
    fun `QuestStore lifecycle`(@TempDir dir: Path) {
        Memory.open(dir.resolve("q.db"), TestClock()).use { mem ->
            val player = UUID.randomUUID()
            mem.quests.create(
                QuestRecord(
                    id = "q1",
                    playerUuid = player,
                    npcId = null,
                    title = "Find the candle",
                    description = "A wax candle was lost in the spruce woods.",
                    objectivesJson = """[{"text":"find candle","done":false}]""",
                    rewardItem = "minecraft:bread",
                    status = QuestStatus.ACTIVE,
                    createdAtMs = 1_000,
                    completedAtMs = null,
                ),
            )
            assertThat(mem.quests.activeForPlayer(player)).hasSize(1)
            mem.quests.setObjectives("q1", """[{"text":"find candle","done":true}]""")
            mem.quests.setStatus("q1", QuestStatus.COMPLETED, completedAtMs = 2_000)
            assertThat(mem.quests.activeForPlayer(player)).isEmpty()
            assertThat(mem.quests.get("q1")?.status).isEqualTo(QuestStatus.COMPLETED)
            assertThat(mem.quests.get("q1")?.completedAtMs).isEqualTo(2_000L)
        }
    }

    @Test
    fun `MobStore TTL expiry`(@TempDir dir: Path) {
        Memory.open(dir.resolve("m.db"), TestClock()).use { mem ->
            mem.mobs.add(
                MobRecord(
                    id = "m1",
                    entityUuid = UUID.randomUUID(),
                    entityType = "minecraft:zombie",
                    role = "ambush",
                    associatedPlayer = null,
                    spawnedAtMs = 1_000,
                    expiresAtMs = 5_000,
                ),
            )
            assertThat(mem.mobs.expired(4_999)).isEmpty()
            assertThat(mem.mobs.expired(5_001)).hasSize(1)
            mem.mobs.remove("m1")
            assertThat(mem.mobs.expired(10_000)).isEmpty()
        }
    }

    @Test
    fun `WorldStateStore put-get with overwrite`(@TempDir dir: Path) {
        Memory.open(dir.resolve("w.db"), TestClock()).use { mem ->
            mem.worldState.put("arc", "v1")
            assertThat(mem.worldState.get("arc")).isEqualTo("v1")
            mem.worldState.put("arc", "v2")
            assertThat(mem.worldState.get("arc")).isEqualTo("v2")
            assertThat(mem.worldState.all()).containsEntry("arc", "v2")
        }
    }

    private fun npc(
        id: String,
        entity: UUID,
        player: UUID,
        status: NpcStatus,
    ): NpcRecord = NpcRecord(
        id = id,
        entityUuid = entity,
        associatedPlayer = player,
        name = "Kasper",
        role = "scholar",
        personality = "soft-spoken, fond of riddles",
        dimensionId = "minecraft:overworld",
        x = 0, y = 64, z = 0,
        status = status,
        createdAtMs = 1_000,
        lastSeenAtMs = 1_000,
    )
}
