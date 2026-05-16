package dev.aidirector.memory

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Persistent canon/lore facts with optional embeddings. Embeddings are stored
 * as little-endian float32 BLOBs alongside their dimension count; retrieval
 * uses an in-memory cosine top-k over the full set (linear scan).
 *
 * Linear scan is fine up to roughly 10k–50k rows on a typical Minecraft
 * server (a 768-dim embedding is 3 KiB, 10k rows = 30 MiB scanned per query).
 * If a deployment grows past that, swap in sqlite-vec; the public API here
 * is designed not to leak that choice.
 */
class FactStore internal constructor(private val db: Database) {

    fun add(kind: String, content: String, importance: Int = 1, embedding: FloatArray? = null): Long {
        require(kind.isNotBlank())
        require(content.isNotBlank())
        require(importance in 0..10)
        var id = 0L
        db.write { c ->
            c.prepareStatement(
                "INSERT INTO facts (kind, content, importance, embedding, embedding_dim, ts) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
                java.sql.Statement.RETURN_GENERATED_KEYS,
            ).use { stmt ->
                stmt.setString(1, kind)
                stmt.setString(2, content)
                stmt.setInt(3, importance)
                if (embedding != null) {
                    stmt.setBytes(4, encode(embedding))
                    stmt.setInt(5, embedding.size)
                } else {
                    stmt.setNull(4, java.sql.Types.BLOB)
                    stmt.setNull(5, java.sql.Types.INTEGER)
                }
                stmt.setLong(6, db.clock.nowMs())
                stmt.executeUpdate()
                stmt.generatedKeys.use { rs -> if (rs.next()) id = rs.getLong(1) }
            }
        }
        return id
    }

    fun setEmbedding(factId: Long, embedding: FloatArray) {
        db.write { c ->
            c.prepareStatement("UPDATE facts SET embedding = ?, embedding_dim = ? WHERE id = ?").use { stmt ->
                stmt.setBytes(1, encode(embedding))
                stmt.setInt(2, embedding.size)
                stmt.setLong(3, factId)
                stmt.executeUpdate()
            }
        }
    }

    fun all(limit: Int = 1_000): List<Fact> {
        return db.select(
            "SELECT id, kind, content, importance, embedding, embedding_dim, ts FROM facts " +
                "ORDER BY ts DESC LIMIT ?",
            prepare = { it.setInt(1, limit) },
            mapper = ::toFact,
        )
    }

    fun byKind(kind: String, limit: Int = 100): List<Fact> = db.select(
        "SELECT id, kind, content, importance, embedding, embedding_dim, ts FROM facts " +
            "WHERE kind = ? ORDER BY importance DESC, ts DESC LIMIT ?",
        prepare = {
            it.setString(1, kind)
            it.setInt(2, limit)
        },
        mapper = ::toFact,
    )

    /**
     * Highest-importance facts, newest-first within an importance tier. These
     * are "pinned" into every prompt regardless of RAG cosine score — they are
     * the canon backbone (narrative arc, core lore, NPC personalities) that the
     * director must never lose track of.
     */
    fun topByImportance(minImportance: Int, limit: Int): List<Fact> = db.select(
        "SELECT id, kind, content, importance, embedding, embedding_dim, ts FROM facts " +
            "WHERE importance >= ? ORDER BY importance DESC, ts DESC LIMIT ?",
        prepare = {
            it.setInt(1, minImportance)
            it.setInt(2, limit)
        },
        mapper = ::toFact,
    )

    fun lackingEmbedding(limit: Int = 32): List<Fact> = db.select(
        "SELECT id, kind, content, importance, embedding, embedding_dim, ts FROM facts " +
            "WHERE embedding IS NULL ORDER BY ts DESC LIMIT ?",
        prepare = { it.setInt(1, limit) },
        mapper = ::toFact,
    )

    /**
     * Returns the top-[k] facts with embeddings whose cosine similarity to
     * [queryEmbedding] is highest. Facts without an embedding are skipped.
     */
    fun topKByCosine(queryEmbedding: FloatArray, k: Int, minSimilarity: Double = 0.0): List<ScoredFact> {
        require(k in 1..1_000)
        if (queryEmbedding.isEmpty()) return emptyList()
        val queryNorm = norm(queryEmbedding)
        if (queryNorm == 0.0) return emptyList()

        val scored = mutableListOf<ScoredFact>()
        db.read { c ->
            c.prepareStatement(
                "SELECT id, kind, content, importance, embedding, embedding_dim, ts FROM facts WHERE embedding IS NOT NULL",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val embeddingBytes = rs.getBytes("embedding") ?: continue
                        val dim = rs.getInt("embedding_dim")
                        if (dim != queryEmbedding.size) continue
                        val candidate = decode(embeddingBytes, dim)
                        val sim = cosine(queryEmbedding, candidate, queryNorm)
                        if (sim >= minSimilarity) {
                            scored += ScoredFact(
                                fact = Fact(
                                    id = rs.getLong("id"),
                                    kind = rs.getString("kind"),
                                    content = rs.getString("content"),
                                    importance = rs.getInt("importance"),
                                    embedding = null,
                                    embeddingDim = dim,
                                    timestampMs = rs.getLong("ts"),
                                ),
                                similarity = sim,
                            )
                        }
                    }
                }
            }
        }
        return scored.sortedByDescending { it.similarity }.take(k)
    }

    /** Removes facts older than [retentionDays] (importance 0..2 only — high-importance facts survive). */
    fun prune(retentionDays: Int): Int {
        val cutoff = db.clock.nowMs() - retentionDays * 86_400_000L
        var deleted = 0
        db.write { c ->
            c.prepareStatement("DELETE FROM facts WHERE ts < ? AND importance < 3").use { stmt ->
                stmt.setLong(1, cutoff)
                deleted = stmt.executeUpdate()
            }
        }
        return deleted
    }

    private fun toFact(rs: java.sql.ResultSet): Fact = Fact(
        id = rs.getLong("id"),
        kind = rs.getString("kind"),
        content = rs.getString("content"),
        importance = rs.getInt("importance"),
        embedding = rs.getBytes("embedding")?.let { decode(it, rs.getInt("embedding_dim")) },
        embeddingDim = rs.getInt("embedding_dim").takeIf { it > 0 },
        timestampMs = rs.getLong("ts"),
    )

    companion object {
        internal fun encode(v: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in v) buf.putFloat(f)
            return buf.array()
        }

        internal fun decode(bytes: ByteArray, dim: Int): FloatArray {
            val out = FloatArray(dim)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until dim) out[i] = buf.float
            return out
        }

        internal fun norm(v: FloatArray): Double {
            var sum = 0.0
            for (f in v) sum += f.toDouble() * f
            return kotlin.math.sqrt(sum)
        }

        internal fun cosine(a: FloatArray, b: FloatArray, normA: Double): Double {
            val normB = norm(b)
            if (normB == 0.0) return 0.0
            var dot = 0.0
            for (i in a.indices) dot += a[i].toDouble() * b[i]
            return dot / (normA * normB)
        }
    }
}

data class Fact(
    val id: Long,
    val kind: String,
    val content: String,
    val importance: Int,
    val embedding: FloatArray?,
    val embeddingDim: Int?,
    val timestampMs: Long,
) {
    override fun equals(other: Any?): Boolean = other is Fact && id == other.id
    override fun hashCode(): Int = id.hashCode()
}

data class ScoredFact(val fact: Fact, val similarity: Double)

object FactKinds {
    const val LORE = "lore"
    const val NPC_PERSONALITY = "npc.personality"
    const val NPC_RELATIONSHIP = "npc.relationship"
    const val NPC_FATE = "npc.fate"
    const val NARRATIVE_ARC = "narrative.arc"
    const val PLAYER_TRAIT = "player.trait"
    const val WORLD_EVENT = "world.event"
}
