package dev.aidirector.memory

import java.util.UUID

/**
 * Persists the session chronicle — one in-fiction journal entry per play
 * session, written when a player logs out. Entries accumulate into a readable
 * history of the world; undelivered ones are handed to the player as a written
 * book on their next login.
 */
class ChronicleStore internal constructor(private val db: Database) {

    fun add(entry: ChronicleEntry) {
        db.write { c ->
            c.prepareStatement(
                """
                INSERT INTO chronicle (
                    id, player_uuid, title, body, session_start, session_end,
                    created_at, delivered
                ) VALUES (?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, entry.id)
                stmt.setString(2, entry.playerUuid.toString())
                stmt.setString(3, entry.title)
                stmt.setString(4, entry.body)
                stmt.setLong(5, entry.sessionStartMs)
                stmt.setLong(6, entry.sessionEndMs)
                stmt.setLong(7, entry.createdAtMs)
                stmt.setInt(8, if (entry.delivered) 1 else 0)
                stmt.executeUpdate()
            }
        }
    }

    fun recentForPlayer(playerUuid: UUID, limit: Int = 20): List<ChronicleEntry> = db.select(
        "SELECT * FROM chronicle WHERE player_uuid = ? ORDER BY created_at DESC LIMIT ?",
        prepare = {
            it.setString(1, playerUuid.toString())
            it.setInt(2, limit)
        },
        mapper = ::toEntry,
    )

    /** Undelivered entries, oldest first — ready to be handed over as a book. */
    fun undelivered(playerUuid: UUID, limit: Int = 16): List<ChronicleEntry> = db.select(
        "SELECT * FROM chronicle WHERE player_uuid = ? AND delivered = 0 ORDER BY created_at ASC LIMIT ?",
        prepare = {
            it.setString(1, playerUuid.toString())
            it.setInt(2, limit)
        },
        mapper = ::toEntry,
    )

    fun markDelivered(ids: Collection<String>) {
        if (ids.isEmpty()) return
        db.write { c ->
            c.prepareStatement("UPDATE chronicle SET delivered = 1 WHERE id = ?").use { stmt ->
                for (id in ids) {
                    stmt.setString(1, id)
                    stmt.executeUpdate()
                }
            }
        }
    }

    fun count(playerUuid: UUID): Int = db.select(
        "SELECT COUNT(*) AS n FROM chronicle WHERE player_uuid = ?",
        prepare = { it.setString(1, playerUuid.toString()) },
        mapper = { it.getInt("n") },
    ).firstOrNull() ?: 0

    private fun toEntry(rs: java.sql.ResultSet): ChronicleEntry = ChronicleEntry(
        id = rs.getString("id"),
        playerUuid = UUID.fromString(rs.getString("player_uuid")),
        title = rs.getString("title"),
        body = rs.getString("body"),
        sessionStartMs = rs.getLong("session_start"),
        sessionEndMs = rs.getLong("session_end"),
        createdAtMs = rs.getLong("created_at"),
        delivered = rs.getInt("delivered") != 0,
    )
}

data class ChronicleEntry(
    val id: String,
    val playerUuid: UUID,
    val title: String,
    val body: String,
    val sessionStartMs: Long,
    val sessionEndMs: Long,
    val createdAtMs: Long,
    val delivered: Boolean = false,
)
