package dev.aidirector.memory

class AdvancementStore internal constructor(private val db: Database) {

    fun add(record: AdvancementRecord) {
        db.write { c ->
            c.prepareStatement(
                "INSERT OR REPLACE INTO advancements (id, holder_id, title, description, icon_item, frame, granted_to, created_at) " +
                    "VALUES (?,?,?,?,?,?,?,?)",
            ).use { stmt ->
                stmt.setString(1, record.id)
                stmt.setString(2, record.holderId)
                stmt.setString(3, record.title)
                stmt.setString(4, record.description)
                stmt.setString(5, record.iconItem)
                stmt.setString(6, record.frame)
                stmt.setString(7, record.grantedTo)
                stmt.setLong(8, record.createdAtMs)
                stmt.executeUpdate()
            }
        }
    }

    fun all(): List<AdvancementRecord> = db.select(
        "SELECT * FROM advancements ORDER BY created_at DESC",
        mapper = ::toRecord,
    )

    fun grantedToPlayer(playerUuid: String): List<AdvancementRecord> = db.select(
        "SELECT * FROM advancements WHERE granted_to = ? ORDER BY created_at DESC",
        prepare = { it.setString(1, playerUuid) },
        mapper = ::toRecord,
    )

    private fun toRecord(rs: java.sql.ResultSet): AdvancementRecord = AdvancementRecord(
        id = rs.getString("id"),
        holderId = rs.getString("holder_id"),
        title = rs.getString("title"),
        description = rs.getString("description"),
        iconItem = rs.getString("icon_item"),
        frame = rs.getString("frame"),
        grantedTo = rs.getString("granted_to"),
        createdAtMs = rs.getLong("created_at"),
    )
}

data class AdvancementRecord(
    val id: String,
    val holderId: String,
    val title: String,
    val description: String,
    val iconItem: String,
    val frame: String,
    val grantedTo: String,
    val createdAtMs: Long,
)
