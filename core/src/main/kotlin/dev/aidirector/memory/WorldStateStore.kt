package dev.aidirector.memory

/** Generic key/value store for world-level singletons (narrative arc, last reflection ts, etc). */
class WorldStateStore internal constructor(private val db: Database) {

    fun put(key: String, value: String) {
        require(key.isNotBlank())
        db.write { c ->
            c.prepareStatement(
                "INSERT INTO world_state (key, value, updated_at) VALUES (?, ?, ?) " +
                    "ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at",
            ).use { stmt ->
                stmt.setString(1, key)
                stmt.setString(2, value)
                stmt.setLong(3, db.clock.nowMs())
                stmt.executeUpdate()
            }
        }
    }

    fun get(key: String): String? = db.select(
        "SELECT value FROM world_state WHERE key = ?",
        prepare = { it.setString(1, key) },
        mapper = { it.getString("value") },
    ).firstOrNull()

    fun getLong(key: String): Long? = get(key)?.toLongOrNull()

    fun all(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        db.read { c ->
            c.prepareStatement("SELECT key, value FROM world_state").use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) out[rs.getString("key")] = rs.getString("value")
                }
            }
        }
        return out
    }
}

object WorldStateKeys {
    const val NARRATIVE_ARC = "narrative.arc"
    const val LAST_REFLECTION_MS = "reflection.last_ms"
    const val LAST_CAMPAIGN_REVIEW_MS = "campaign.last_review_ms"
}
