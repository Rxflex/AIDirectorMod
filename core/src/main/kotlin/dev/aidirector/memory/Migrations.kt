package dev.aidirector.memory

import java.sql.Connection

internal object Migrations {

    /** Idempotent — re-runs are safe; each `CREATE IF NOT EXISTS`. */
    fun apply(conn: Connection) {
        conn.createStatement().use { s ->
            // events: time-ordered log per player
            s.execute(
                """
                CREATE TABLE IF NOT EXISTS events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_uuid TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    ts INTEGER NOT NULL
                );
                """.trimIndent(),
            )
            s.execute("CREATE INDEX IF NOT EXISTS idx_events_player_ts ON events(player_uuid, ts DESC);")
            s.execute("CREATE INDEX IF NOT EXISTS idx_events_kind_ts ON events(kind, ts DESC);")

            // facts: long-term canon, embeddings stored as BLOB of little-endian floats
            s.execute(
                """
                CREATE TABLE IF NOT EXISTS facts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    kind TEXT NOT NULL,
                    content TEXT NOT NULL,
                    importance INTEGER NOT NULL DEFAULT 1,
                    embedding BLOB,
                    embedding_dim INTEGER,
                    ts INTEGER NOT NULL
                );
                """.trimIndent(),
            )
            s.execute("CREATE INDEX IF NOT EXISTS idx_facts_kind_ts ON facts(kind, ts DESC);")
            s.execute("CREATE INDEX IF NOT EXISTS idx_facts_importance ON facts(importance DESC);")

            // npcs: tracked NPC entities (typically a villager + tag)
            s.execute(
                """
                CREATE TABLE IF NOT EXISTS npcs (
                    id TEXT PRIMARY KEY,
                    entity_uuid TEXT,
                    associated_player_uuid TEXT,
                    name TEXT NOT NULL,
                    role TEXT NOT NULL,
                    personality TEXT NOT NULL,
                    dimension_id TEXT NOT NULL,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    last_seen_at INTEGER NOT NULL
                );
                """.trimIndent(),
            )
            s.execute("CREATE INDEX IF NOT EXISTS idx_npcs_player ON npcs(associated_player_uuid);")
            s.execute("CREATE INDEX IF NOT EXISTS idx_npcs_status ON npcs(status);")

            // quests
            s.execute(
                """
                CREATE TABLE IF NOT EXISTS quests (
                    id TEXT PRIMARY KEY,
                    player_uuid TEXT NOT NULL,
                    npc_id TEXT,
                    title TEXT NOT NULL,
                    description TEXT NOT NULL,
                    objectives_json TEXT NOT NULL,
                    reward_item TEXT,
                    status TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    completed_at INTEGER
                );
                """.trimIndent(),
            )
            s.execute("CREATE INDEX IF NOT EXISTS idx_quests_player_status ON quests(player_uuid, status);")

            // mobs: director-spawned tracked mobs
            s.execute(
                """
                CREATE TABLE IF NOT EXISTS mobs (
                    id TEXT PRIMARY KEY,
                    entity_uuid TEXT,
                    entity_type TEXT NOT NULL,
                    role TEXT NOT NULL,
                    associated_player_uuid TEXT,
                    spawned_at INTEGER NOT NULL,
                    expires_at INTEGER
                );
                """.trimIndent(),
            )
            s.execute("CREATE INDEX IF NOT EXISTS idx_mobs_expires ON mobs(expires_at);")

            // dynamic advancements
            s.execute(
                """
                CREATE TABLE IF NOT EXISTS advancements (
                    id TEXT PRIMARY KEY,
                    holder_id TEXT NOT NULL UNIQUE,
                    title TEXT NOT NULL,
                    description TEXT NOT NULL,
                    icon_item TEXT NOT NULL,
                    frame TEXT NOT NULL,
                    granted_to TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                );
                """.trimIndent(),
            )

            // world_state: generic key/value for narrative arc, last_reflection_ts, etc.
            s.execute(
                """
                CREATE TABLE IF NOT EXISTS world_state (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                );
                """.trimIndent(),
            )

            // chronicle: in-fiction session journal entries written on logout
            s.execute(
                """
                CREATE TABLE IF NOT EXISTS chronicle (
                    id TEXT PRIMARY KEY,
                    player_uuid TEXT NOT NULL,
                    title TEXT NOT NULL,
                    body TEXT NOT NULL,
                    session_start INTEGER NOT NULL,
                    session_end INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    delivered INTEGER NOT NULL DEFAULT 0
                );
                """.trimIndent(),
            )
            s.execute("CREATE INDEX IF NOT EXISTS idx_chronicle_player ON chronicle(player_uuid, created_at DESC);")
        }

        // --- additive schema upgrades for existing world databases ---
        // NPC character-arc columns: shipped after the npcs table existed, so
        // applied as idempotent ALTERs (a fresh DB already has them too).
        addColumnIfMissing(conn, "npcs", "arc_stage", "TEXT NOT NULL DEFAULT ''")
        addColumnIfMissing(conn, "npcs", "relationship", "TEXT NOT NULL DEFAULT 'stranger'")
        addColumnIfMissing(conn, "npcs", "interaction_count", "INTEGER NOT NULL DEFAULT 0")
    }

    /** `ALTER TABLE ADD COLUMN` is not `IF NOT EXISTS`-able — probe first. */
    private fun addColumnIfMissing(conn: Connection, table: String, column: String, definition: String) {
        val exists = conn.createStatement().use { s ->
            s.executeQuery("PRAGMA table_info($table)").use { rs ->
                generateSequence { if (rs.next()) rs.getString("name") else null }
                    .any { it.equals(column, ignoreCase = true) }
            }
        }
        if (!exists) {
            conn.createStatement().use { it.execute("ALTER TABLE $table ADD COLUMN $column $definition") }
        }
    }
}
