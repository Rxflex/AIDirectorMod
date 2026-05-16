package dev.aidirector.memory

import java.util.UUID

class NpcStore internal constructor(private val db: Database) {

    fun upsert(npc: NpcRecord) {
        db.write { c ->
            c.prepareStatement(
                """
                INSERT INTO npcs (
                    id, entity_uuid, associated_player_uuid, name, role, personality,
                    dimension_id, x, y, z, status, created_at, last_seen_at,
                    arc_stage, relationship, interaction_count
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                    entity_uuid = excluded.entity_uuid,
                    associated_player_uuid = excluded.associated_player_uuid,
                    name = excluded.name,
                    role = excluded.role,
                    personality = excluded.personality,
                    dimension_id = excluded.dimension_id,
                    x = excluded.x, y = excluded.y, z = excluded.z,
                    status = excluded.status,
                    last_seen_at = excluded.last_seen_at,
                    arc_stage = excluded.arc_stage,
                    relationship = excluded.relationship,
                    interaction_count = excluded.interaction_count
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, npc.id)
                stmt.setString(2, npc.entityUuid?.toString())
                stmt.setString(3, npc.associatedPlayer?.toString())
                stmt.setString(4, npc.name)
                stmt.setString(5, npc.role)
                stmt.setString(6, npc.personality)
                stmt.setString(7, npc.dimensionId)
                stmt.setInt(8, npc.x)
                stmt.setInt(9, npc.y)
                stmt.setInt(10, npc.z)
                stmt.setString(11, npc.status.name)
                stmt.setLong(12, npc.createdAtMs)
                stmt.setLong(13, npc.lastSeenAtMs)
                stmt.setString(14, npc.arcStage)
                stmt.setString(15, npc.relationship)
                stmt.setInt(16, npc.interactionCount)
                stmt.executeUpdate()
            }
        }
    }

    fun get(id: String): NpcRecord? = db.select(
        "SELECT * FROM npcs WHERE id = ?",
        prepare = { it.setString(1, id) },
        mapper = ::toRecord,
    ).firstOrNull()

    fun byEntityUuid(entityUuid: UUID): NpcRecord? = db.select(
        "SELECT * FROM npcs WHERE entity_uuid = ?",
        prepare = { it.setString(1, entityUuid.toString()) },
        mapper = ::toRecord,
    ).firstOrNull()

    fun activeForPlayer(playerUuid: UUID, limit: Int = 20): List<NpcRecord> = db.select(
        "SELECT * FROM npcs WHERE associated_player_uuid = ? AND status = ? ORDER BY last_seen_at DESC LIMIT ?",
        prepare = {
            it.setString(1, playerUuid.toString())
            it.setString(2, NpcStatus.ACTIVE.name)
            it.setInt(3, limit)
        },
        mapper = ::toRecord,
    )

    /**
     * The full cast for a player — active *and* recently-resolved NPCs (dead,
     * missing, turned hostile). The director needs the fates in context to
     * honour an arc: build a grave for the dead, mourn the missing, fear the
     * turned. DESPAWNED entries are excluded — they carry no story weight.
     */
    fun rosterForPlayer(playerUuid: UUID, limit: Int = 20): List<NpcRecord> = db.select(
        "SELECT * FROM npcs WHERE associated_player_uuid = ? AND status <> ? ORDER BY last_seen_at DESC LIMIT ?",
        prepare = {
            it.setString(1, playerUuid.toString())
            it.setString(2, NpcStatus.DESPAWNED.name)
            it.setInt(3, limit)
        },
        mapper = ::toRecord,
    )

    fun all(): List<NpcRecord> = db.select(
        "SELECT * FROM npcs",
        mapper = ::toRecord,
    )

    fun markDead(id: String) {
        db.write { c ->
            c.prepareStatement(
                "UPDATE npcs SET status = ?, last_seen_at = ? WHERE id = ?",
            ).use { stmt ->
                stmt.setString(1, NpcStatus.DEAD.name)
                stmt.setLong(2, db.clock.nowMs())
                stmt.setString(3, id)
                stmt.executeUpdate()
            }
        }
    }

    /** Bumps the interaction tally and last-seen — call after a dialogue turn. */
    fun recordInteraction(id: String, nowMs: Long) {
        db.write { c ->
            c.prepareStatement(
                "UPDATE npcs SET interaction_count = interaction_count + 1, last_seen_at = ? WHERE id = ?",
            ).use { stmt ->
                stmt.setLong(1, nowMs)
                stmt.setString(2, id)
                stmt.executeUpdate()
            }
        }
    }

    private fun toRecord(rs: java.sql.ResultSet): NpcRecord = NpcRecord(
        id = rs.getString("id"),
        entityUuid = rs.getString("entity_uuid")?.let { UUID.fromString(it) },
        associatedPlayer = rs.getString("associated_player_uuid")?.let { UUID.fromString(it) },
        name = rs.getString("name"),
        role = rs.getString("role"),
        personality = rs.getString("personality"),
        dimensionId = rs.getString("dimension_id"),
        x = rs.getInt("x"),
        y = rs.getInt("y"),
        z = rs.getInt("z"),
        status = NpcStatus.valueOf(rs.getString("status")),
        createdAtMs = rs.getLong("created_at"),
        lastSeenAtMs = rs.getLong("last_seen_at"),
        arcStage = rs.getString("arc_stage") ?: "",
        relationship = rs.getString("relationship") ?: "stranger",
        interactionCount = rs.getInt("interaction_count"),
    )
}

data class NpcRecord(
    val id: String,
    val entityUuid: UUID?,
    val associatedPlayer: UUID?,
    val name: String,
    val role: String,
    val personality: String,
    val dimensionId: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val status: NpcStatus,
    val createdAtMs: Long,
    val lastSeenAtMs: Long,
    /** Free-text note on where this NPC stands in their story — the director owns it. */
    val arcStage: String = "",
    /** This NPC's bond to the player: e.g. "stranger", "wary ally", "betrayed friend". */
    val relationship: String = "stranger",
    /** How many dialogue turns the player has had with this NPC. */
    val interactionCount: Int = 0,
)

/**
 * The fate of a tracked NPC. The director moves NPCs through these via the
 * `evolve_npc` tool to give a character a real arc.
 *  - [ACTIVE]    present in the world, interactable.
 *  - [MISSING]   vanished from the story — may return, or be found dead.
 *  - [DEAD]      gone; warrants a grave / memorial and mourning.
 *  - [HOSTILE]   turned against the player; a former friend now an enemy.
 *  - [DESPAWNED] the entity unloaded with no narrative weight (bookkeeping only).
 */
enum class NpcStatus { ACTIVE, MISSING, DEAD, HOSTILE, DESPAWNED }
