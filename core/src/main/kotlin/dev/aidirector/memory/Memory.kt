package dev.aidirector.memory

import dev.aidirector.util.Clock
import java.nio.file.Path
import java.util.UUID

/**
 * Single entry point to the on-disk director state. Holds the [Database] and
 * a typed store for each domain (events, facts, NPCs, quests, mobs,
 * advancements, world state).
 *
 * Lifetime: one instance per world, closed on server stop.
 */
class Memory private constructor(
    val database: Database,
    val events: EventStore,
    val facts: FactStore,
    val npcs: NpcStore,
    val quests: QuestStore,
    val mobs: MobStore,
    val advancements: AdvancementStore,
    val worldState: WorldStateStore,
    val chronicle: ChronicleStore,
) : AutoCloseable {

    /** Convenience: thin wrapper kept for backwards compat with older call sites. */
    fun recordEvent(playerUuid: UUID, kind: String, payloadJson: String) =
        events.record(playerUuid, kind, payloadJson)

    fun recentEvents(playerUuid: UUID, limit: Int) =
        events.recent(playerUuid, limit)

    fun recentEventsByKind(playerUuid: UUID, kinds: Collection<String>, limit: Int) =
        events.recentByKind(playerUuid, kinds, limit)

    fun prune(retentionDays: Int, maxRows: Int): Int {
        val a = events.prune(retentionDays, maxRows)
        val b = facts.prune(retentionDays)
        return a + b
    }

    override fun close() {
        database.close()
    }

    companion object {
        @JvmStatic
        fun open(dbPath: Path, clock: Clock = Clock.System): Memory {
            val db = Database.open(dbPath, clock)
            return Memory(
                database = db,
                events = EventStore(db),
                facts = FactStore(db),
                npcs = NpcStore(db),
                quests = QuestStore(db),
                mobs = MobStore(db),
                advancements = AdvancementStore(db),
                worldState = WorldStateStore(db),
                chronicle = ChronicleStore(db),
            )
        }
    }
}
