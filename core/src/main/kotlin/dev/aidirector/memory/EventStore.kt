package dev.aidirector.memory

import java.util.UUID

class EventStore internal constructor(private val db: Database) {

    fun record(playerUuid: UUID, kind: String, payloadJson: String) {
        require(kind.isNotBlank()) { "Event kind must not be blank" }
        require(payloadJson.length <= MAX_PAYLOAD_BYTES) {
            "Payload size ${payloadJson.length} exceeds $MAX_PAYLOAD_BYTES"
        }
        db.write { c ->
            c.prepareStatement(
                "INSERT INTO events (player_uuid, kind, payload, ts) VALUES (?, ?, ?, ?)",
            ).use { stmt ->
                stmt.setString(1, playerUuid.toString())
                stmt.setString(2, kind)
                stmt.setString(3, payloadJson)
                stmt.setLong(4, db.clock.nowMs())
                stmt.executeUpdate()
            }
        }
    }

    fun recent(playerUuid: UUID, limit: Int): List<EventRecord> {
        require(limit in 1..10_000) { "limit must be 1..10000" }
        return db.select(
            "SELECT id, player_uuid, kind, payload, ts FROM events " +
                "WHERE player_uuid = ? ORDER BY ts DESC LIMIT ?",
            prepare = { stmt ->
                stmt.setString(1, playerUuid.toString())
                stmt.setInt(2, limit)
            },
            mapper = ::toRecord,
        )
    }

    fun recentByKind(playerUuid: UUID, kinds: Collection<String>, limit: Int): List<EventRecord> {
        if (kinds.isEmpty()) return emptyList()
        require(limit in 1..10_000) { "limit must be 1..10000" }
        val placeholders = kinds.joinToString(",") { "?" }
        val sql = "SELECT id, player_uuid, kind, payload, ts FROM events " +
            "WHERE player_uuid = ? AND kind IN ($placeholders) ORDER BY ts DESC LIMIT ?"
        return db.select(
            sql,
            prepare = { stmt ->
                var idx = 1
                stmt.setString(idx++, playerUuid.toString())
                kinds.forEach { stmt.setString(idx++, it) }
                stmt.setInt(idx, limit)
            },
            mapper = ::toRecord,
        )
    }

    fun prune(retentionDays: Int, maxRows: Int): Int {
        require(retentionDays in 1..3650)
        require(maxRows in 100..1_000_000)
        val cutoff = db.clock.nowMs() - retentionDays * 86_400_000L
        var deleted = 0
        db.write { c ->
            c.prepareStatement("DELETE FROM events WHERE ts < ?").use { stmt ->
                stmt.setLong(1, cutoff)
                deleted += stmt.executeUpdate()
            }
            c.prepareStatement(
                "DELETE FROM events WHERE id IN (" +
                    "SELECT id FROM events ORDER BY ts DESC LIMIT -1 OFFSET ?" +
                    ")",
            ).use { stmt ->
                stmt.setInt(1, maxRows)
                deleted += stmt.executeUpdate()
            }
        }
        return deleted
    }

    private fun toRecord(rs: java.sql.ResultSet): EventRecord = EventRecord(
        id = rs.getLong("id"),
        playerUuid = UUID.fromString(rs.getString("player_uuid")),
        kind = rs.getString("kind"),
        payloadJson = rs.getString("payload"),
        timestampMs = rs.getLong("ts"),
    )

    companion object {
        private const val MAX_PAYLOAD_BYTES = 16 * 1024
    }
}
