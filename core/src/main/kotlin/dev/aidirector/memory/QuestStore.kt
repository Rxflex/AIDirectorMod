package dev.aidirector.memory

import java.util.UUID

class QuestStore internal constructor(private val db: Database) {

    fun create(record: QuestRecord) {
        db.write { c ->
            c.prepareStatement(
                """
                INSERT INTO quests (
                    id, player_uuid, npc_id, title, description, objectives_json,
                    reward_item, status, created_at, completed_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, record.id)
                stmt.setString(2, record.playerUuid.toString())
                stmt.setString(3, record.npcId)
                stmt.setString(4, record.title)
                stmt.setString(5, record.description)
                stmt.setString(6, record.objectivesJson)
                stmt.setString(7, record.rewardItem)
                stmt.setString(8, record.status.name)
                stmt.setLong(9, record.createdAtMs)
                if (record.completedAtMs != null) stmt.setLong(10, record.completedAtMs) else stmt.setNull(10, java.sql.Types.INTEGER)
                stmt.executeUpdate()
            }
        }
    }

    fun get(id: String): QuestRecord? = db.select(
        "SELECT * FROM quests WHERE id = ?",
        prepare = { it.setString(1, id) },
        mapper = ::toRecord,
    ).firstOrNull()

    fun activeForPlayer(playerUuid: UUID, limit: Int = 20): List<QuestRecord> = db.select(
        "SELECT * FROM quests WHERE player_uuid = ? AND status = ? ORDER BY created_at DESC LIMIT ?",
        prepare = {
            it.setString(1, playerUuid.toString())
            it.setString(2, QuestStatus.ACTIVE.name)
            it.setInt(3, limit)
        },
        mapper = ::toRecord,
    )

    fun history(playerUuid: UUID, limit: Int = 20): List<QuestRecord> = db.select(
        "SELECT * FROM quests WHERE player_uuid = ? ORDER BY created_at DESC LIMIT ?",
        prepare = {
            it.setString(1, playerUuid.toString())
            it.setInt(2, limit)
        },
        mapper = ::toRecord,
    )

    fun setStatus(id: String, status: QuestStatus, completedAtMs: Long? = null) {
        db.write { c ->
            c.prepareStatement(
                "UPDATE quests SET status = ?, completed_at = ? WHERE id = ?",
            ).use { stmt ->
                stmt.setString(1, status.name)
                if (completedAtMs != null) stmt.setLong(2, completedAtMs) else stmt.setNull(2, java.sql.Types.INTEGER)
                stmt.setString(3, id)
                stmt.executeUpdate()
            }
        }
    }

    fun setObjectives(id: String, objectivesJson: String) {
        db.write { c ->
            c.prepareStatement("UPDATE quests SET objectives_json = ? WHERE id = ?").use { stmt ->
                stmt.setString(1, objectivesJson)
                stmt.setString(2, id)
                stmt.executeUpdate()
            }
        }
    }

    private fun toRecord(rs: java.sql.ResultSet): QuestRecord = QuestRecord(
        id = rs.getString("id"),
        playerUuid = UUID.fromString(rs.getString("player_uuid")),
        npcId = rs.getString("npc_id"),
        title = rs.getString("title"),
        description = rs.getString("description"),
        objectivesJson = rs.getString("objectives_json"),
        rewardItem = rs.getString("reward_item"),
        status = QuestStatus.valueOf(rs.getString("status")),
        createdAtMs = rs.getLong("created_at"),
        completedAtMs = rs.getLong("completed_at").takeIf { !rs.wasNull() },
    )
}

data class QuestRecord(
    val id: String,
    val playerUuid: UUID,
    val npcId: String?,
    val title: String,
    val description: String,
    val objectivesJson: String,
    val rewardItem: String?,
    val status: QuestStatus,
    val createdAtMs: Long,
    val completedAtMs: Long?,
)

enum class QuestStatus { ACTIVE, COMPLETED, FAILED, ABANDONED }
