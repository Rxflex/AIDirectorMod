package dev.aidirector.memory

import java.util.UUID

class MobStore internal constructor(private val db: Database) {

    fun add(record: MobRecord) {
        db.write { c ->
            c.prepareStatement(
                "INSERT OR REPLACE INTO mobs (id, entity_uuid, entity_type, role, associated_player_uuid, spawned_at, expires_at) " +
                    "VALUES (?,?,?,?,?,?,?)",
            ).use { stmt ->
                stmt.setString(1, record.id)
                stmt.setString(2, record.entityUuid?.toString())
                stmt.setString(3, record.entityType)
                stmt.setString(4, record.role)
                stmt.setString(5, record.associatedPlayer?.toString())
                stmt.setLong(6, record.spawnedAtMs)
                if (record.expiresAtMs != null) stmt.setLong(7, record.expiresAtMs) else stmt.setNull(7, java.sql.Types.INTEGER)
                stmt.executeUpdate()
            }
        }
    }

    fun get(id: String): MobRecord? = db.select(
        "SELECT * FROM mobs WHERE id = ?",
        prepare = { it.setString(1, id) },
        mapper = ::toRecord,
    ).firstOrNull()

    fun byEntityUuid(entityUuid: UUID): MobRecord? = db.select(
        "SELECT * FROM mobs WHERE entity_uuid = ?",
        prepare = { it.setString(1, entityUuid.toString()) },
        mapper = ::toRecord,
    ).firstOrNull()

    fun expired(nowMs: Long): List<MobRecord> = db.select(
        "SELECT * FROM mobs WHERE expires_at IS NOT NULL AND expires_at < ?",
        prepare = { it.setLong(1, nowMs) },
        mapper = ::toRecord,
    )

    fun remove(id: String) {
        db.write { c ->
            c.prepareStatement("DELETE FROM mobs WHERE id = ?").use { stmt ->
                stmt.setString(1, id)
                stmt.executeUpdate()
            }
        }
    }

    private fun toRecord(rs: java.sql.ResultSet): MobRecord = MobRecord(
        id = rs.getString("id"),
        entityUuid = rs.getString("entity_uuid")?.let { UUID.fromString(it) },
        entityType = rs.getString("entity_type"),
        role = rs.getString("role"),
        associatedPlayer = rs.getString("associated_player_uuid")?.let { UUID.fromString(it) },
        spawnedAtMs = rs.getLong("spawned_at"),
        expiresAtMs = rs.getLong("expires_at").takeIf { !rs.wasNull() },
    )
}

data class MobRecord(
    val id: String,
    val entityUuid: UUID?,
    val entityType: String,
    val role: String,
    val associatedPlayer: UUID?,
    val spawnedAtMs: Long,
    val expiresAtMs: Long?,
)
