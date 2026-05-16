package dev.aidirector.rag

import dev.aidirector.AIDirector
import dev.aidirector.llm.Embedder
import dev.aidirector.memory.Fact
import dev.aidirector.memory.FactStore
import dev.aidirector.memory.ScoredFact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wraps [FactStore] with an [EmbeddingClient] to provide RAG. Embeddings are
 * computed lazily — facts can be inserted without one, and a background pass
 * fills them in. Retrieval is by cosine similarity of the query embedding
 * against the stored fact embeddings.
 *
 * The director calls [retrieve] every tick to surface relevant lore; it calls
 * [ingest] when it wants to record a new canon fact (e.g. via the
 * `record_fact` tool).
 */
class Rag(
    private val facts: FactStore,
    private val embeddings: Embedder,
    private val model: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    /** Guards a single background backfill from racing itself. */
    private val backfillLock = Mutex()

    /**
     * Inserts a fact and queues an async embedding fetch. Returns the new fact id.
     */
    fun ingest(kind: String, content: String, importance: Int = 1): Long {
        val id = facts.add(kind, content, importance, embedding = null)
        scope.launch {
            try {
                val vec = embeddings.embed(model, listOf(content), inputType = "passage").first()
                facts.setEmbedding(id, vec)
            } catch (e: Exception) {
                AIDirector.log.warn("Failed to embed fact $id: ${e.message}")
            }
        }
        return id
    }

    /** Like [ingest] but blocks until the embedding is set. Used by tests / sync paths. */
    suspend fun ingestAndEmbed(kind: String, content: String, importance: Int = 1): Long {
        val id = facts.add(kind, content, importance, embedding = null)
        val vec = embeddings.embed(model, listOf(content), inputType = "passage").first()
        facts.setEmbedding(id, vec)
        return id
    }

    /**
     * Fetches top-[k] facts most similar to [query]. Returns an empty list if
     * no facts have embeddings yet. Errors during embedding are logged and
     * an empty list is returned — RAG must never break the tick.
     */
    suspend fun retrieve(query: String, k: Int = DEFAULT_K, minSimilarity: Double = 0.30): List<ScoredFact> {
        if (query.isBlank()) return emptyList()
        val vec = try {
            embeddings.embed(model, listOf(query), inputType = "query").first()
        } catch (e: Exception) {
            AIDirector.log.warn("RAG retrieve embedding failed: ${e.message}")
            return emptyList()
        }
        return facts.topKByCosine(vec, k, minSimilarity)
    }

    /**
     * Backfill embeddings for any facts that lack one. Safe to call repeatedly
     * — only one pass runs at a time. Intended to be invoked by [Reflection]
     * once per cycle.
     */
    suspend fun backfillEmbeddings(batchSize: Int = 16): Int {
        if (!backfillLock.tryLock()) return 0
        try {
            val pending = facts.lackingEmbedding(batchSize)
            if (pending.isEmpty()) return 0
            val texts = pending.map(Fact::content)
            val vectors = embeddings.embed(model, texts, inputType = "passage")
            for ((fact, vec) in pending.zip(vectors)) {
                facts.setEmbedding(fact.id, vec)
            }
            return pending.size
        } catch (e: Exception) {
            AIDirector.log.warn("Backfill failed: ${e.message}")
            return 0
        } finally {
            backfillLock.unlock()
        }
    }

    companion object {
        const val DEFAULT_K = 6
    }
}
