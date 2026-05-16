package dev.aidirector.memory

import dev.aidirector.AIDirector
import dev.aidirector.util.Clock
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * Owns the SQLite connection and the single write lock. All [Store]s are
 * constructed against a [Database] instance — they never open their own
 * connection. SQLite is a single-writer engine so this keeps the contention
 * model honest.
 */
class Database internal constructor(
    val connection: Connection,
    val clock: Clock,
) : AutoCloseable {

    /** Held for the duration of a write to serialize writers. Readers don't need it (WAL). */
    private val writeLock = Any()

    fun <R> write(block: (Connection) -> R): R = synchronized(writeLock) { block(connection) }

    fun <R> read(block: (Connection) -> R): R = block(connection)

    /**
     * Executes the prepared statement, mapping each row via [mapper]. Closes resources.
     */
    fun <R> select(
        sql: String,
        prepare: (PreparedStatement) -> Unit = {},
        mapper: (ResultSet) -> R,
    ): List<R> {
        connection.prepareStatement(sql).use { stmt ->
            prepare(stmt)
            stmt.executeQuery().use { rs ->
                val out = mutableListOf<R>()
                while (rs.next()) out += mapper(rs)
                return out
            }
        }
    }

    override fun close() {
        try {
            connection.close()
        } catch (e: Exception) {
            AIDirector.log.warn("Error closing DB: ${e.message}")
        }
    }

    companion object {
        @JvmStatic
        fun open(dbPath: Path, clock: Clock = Clock.System): Database {
            Files.createDirectories(dbPath.parent)
            Class.forName("org.sqlite.JDBC")
            val conn = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
            applyPragmas(conn)
            Migrations.apply(conn)
            return Database(conn, clock)
        }

        private fun applyPragmas(conn: Connection) {
            conn.createStatement().use { s ->
                s.execute("PRAGMA journal_mode=WAL;")
                s.execute("PRAGMA synchronous=NORMAL;")
                s.execute("PRAGMA foreign_keys=ON;")
                s.execute("PRAGMA temp_store=MEMORY;")
                s.execute("PRAGMA busy_timeout=5000;")
            }
        }
    }
}
