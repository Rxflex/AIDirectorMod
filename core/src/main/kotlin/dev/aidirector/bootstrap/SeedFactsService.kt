package dev.aidirector.bootstrap

import dev.aidirector.AIDirector
import dev.aidirector.memory.FactKinds
import dev.aidirector.memory.Memory
import dev.aidirector.memory.WorldStateKeys
import dev.aidirector.rag.Rag
import java.security.MessageDigest

/**
 * One-shot ingester for operator-authored canon. Reads `director.seed_facts`
 * from config, hashes the content, and only re-ingests when the hash
 * changes — so editing the config swaps the facts in cleanly, but every
 * subsequent reload is a no-op.
 */
object SeedFactsService {

    private const val MARKER_KEY = "seed_facts.content_hash"

    suspend fun ingestIfNeeded(memory: Memory, rag: Rag, seedFacts: List<String>) {
        if (seedFacts.isEmpty()) return
        val hash = sha256(seedFacts.joinToString("\n"))
        if (memory.worldState.get(MARKER_KEY) == hash) {
            AIDirector.log.info("Seed facts unchanged — skipping")
            return
        }
        var count = 0
        for (fact in seedFacts) {
            try {
                rag.ingestAndEmbed(FactKinds.LORE, fact, importance = 4)
                count++
            } catch (e: Exception) {
                AIDirector.log.warn("Seed fact ingestion failed for '${fact.take(60)}…': ${e.message}")
            }
        }
        memory.worldState.put(MARKER_KEY, hash)
        AIDirector.log.info("Ingested $count seed facts into RAG")
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
