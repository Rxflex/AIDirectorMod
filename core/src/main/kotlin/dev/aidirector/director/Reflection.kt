package dev.aidirector.director

import dev.aidirector.AIDirector
import dev.aidirector.actions.ServerActions
import dev.aidirector.config.ConfigService
import dev.aidirector.llm.ChatMessage
import dev.aidirector.llm.ChatRequest
import dev.aidirector.llm.LlmClient
import dev.aidirector.memory.EventKind
import dev.aidirector.memory.FactKinds
import dev.aidirector.memory.Memory
import dev.aidirector.memory.WorldStateKeys
import dev.aidirector.rag.Rag
import dev.aidirector.sensors.Sensors
import dev.aidirector.util.Clock

/**
 * Periodic background pass. Three jobs:
 *
 * 1. Compose / refresh the world's narrative arc — a 2-3 sentence summary of
 *    "what is happening in this world right now" based on recent events.
 *    Stored in [WorldStateStore] and re-fed into every prompt.
 *
 * 2. Backfill missing embeddings — facts ingested with `embedding = null`
 *    are batched and embedded here, off the critical tick path.
 *
 * 3. Despawn expired director-spawned mobs (TTL hit).
 */
class Reflection(
    private val configService: ConfigService,
    private val sensors: Sensors,
    private val actions: ServerActions,
    private val memory: Memory,
    private val rag: Rag,
    private val llm: LlmClient,
    private val clock: Clock = Clock.System,
) {

    suspend fun runIfDue(): RunReport {
        val cfg = configService.current
        val now = clock.nowMs()
        val last = memory.worldState.getLong(WorldStateKeys.LAST_REFLECTION_MS) ?: 0L
        if (now - last < cfg.director.reflectionIntervalMs) {
            return RunReport.Skipped(now - last, cfg.director.reflectionIntervalMs)
        }
        memory.worldState.put(WorldStateKeys.LAST_REFLECTION_MS, now.toString())
        return runNow()
    }

    suspend fun runNow(): RunReport {
        val now = clock.nowMs()
        val expiredCount = cleanupExpiredMobs(now)
        val embedded = try {
            rag.backfillEmbeddings()
        } catch (e: Exception) {
            AIDirector.log.warn("Backfill failed: ${e.message}")
            0
        }
        val arc = try {
            updateNarrativeArc(now)
        } catch (e: Exception) {
            AIDirector.log.warn("Narrative arc update failed: ${e.message}")
            null
        }
        return RunReport.Completed(despawnedMobs = expiredCount, embeddedFacts = embedded, arc = arc)
    }

    private fun cleanupExpiredMobs(now: Long): Int {
        val expired = memory.mobs.expired(now)
        for (mob in expired) {
            mob.entityUuid?.let { actions.killEntity(it) }
            memory.mobs.remove(mob.id)
        }
        return expired.size
    }

    /**
     * Asks the LLM to update the world's narrative arc, using recent events
     * across all online players. Stored back into [WorldStateStore] and also
     * ingested as a narrative.arc fact so it can be retrieved via RAG.
     */
    private suspend fun updateNarrativeArc(now: Long): String? {
        val cfg = configService.current
        val online = sensors.onlinePlayers()
        if (online.isEmpty()) return null

        val events = online.flatMap { uuid ->
            memory.events.recent(uuid, 12).map { it.kind to it.payloadJson }
        }
        if (events.isEmpty()) return null

        val previousArc = memory.worldState.get(WorldStateKeys.NARRATIVE_ARC) ?: "(none yet)"
        val joined = events.joinToString("\n") { (kind, payload) ->
            "- $kind: ${payload.take(140)}"
        }.take(2_500)

        val reflectionSystem = dev.aidirector.prompt.LanguageDirective.applyTo(
            """
                You are the AI Director reflecting between scenes. Update the
                world's narrative arc in 2-3 sentences. Keep continuity with
                the previous arc; evolve it based on what the player actually
                did. No tool calls. Output the arc text only.
            """.trimIndent(),
            cfg.director.outputLanguage,
        )
        val messages = listOf(
            ChatMessage(role = "system", content = reflectionSystem),
            ChatMessage(
                role = "user",
                content = "Previous arc:\n$previousArc\n\nRecent events:\n$joined",
            ),
        )
        val response = llm.chat(
            ChatRequest(
                model = cfg.llm.model,
                messages = messages,
                tools = null,
                toolChoice = "none",
                temperature = (cfg.llm.temperature - 0.1).coerceAtLeast(0.0),
                maxTokens = 200,
            ),
        )
        val text = response.choices.firstOrNull()?.message?.content?.trim()
            ?.take(MAX_ARC_LENGTH)
            ?.takeIf { it.length >= MIN_ARC_LENGTH } ?: run {
                AIDirector.log.info("Reflection: LLM returned short/empty arc — keeping previous")
                return null
            }
        memory.worldState.put(WorldStateKeys.NARRATIVE_ARC, text)
        rag.ingest(FactKinds.NARRATIVE_ARC, text, importance = 4)
        // Record an event so players' future prompts see the arc transition.
        for (uuid in online) {
            memory.events.record(uuid, EventKind.DIRECTOR_TICK, """{"arc_updated":true}""")
        }
        AIDirector.log.info("Narrative arc updated (${text.length} chars)")
        return text
    }

    sealed interface RunReport {
        data class Skipped(val sinceLastMs: Long, val intervalMs: Long) : RunReport
        data class Completed(val despawnedMobs: Int, val embeddedFacts: Int, val arc: String?) : RunReport
    }

    companion object {
        private const val MAX_ARC_LENGTH = 600
        /** Reject obvious junk like "ok", "...", or empty-ish replies. */
        private const val MIN_ARC_LENGTH = 50
    }
}
