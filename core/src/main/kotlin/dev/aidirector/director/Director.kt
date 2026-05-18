package dev.aidirector.director

import dev.aidirector.AIDirector
import dev.aidirector.config.ConfigService
import dev.aidirector.memory.EventKind
import dev.aidirector.memory.Memory
import dev.aidirector.memory.NpcRecord
import dev.aidirector.memory.QuestRecord
import dev.aidirector.memory.WorldStateKeys
import dev.aidirector.rag.Rag
import dev.aidirector.sensors.PlayerSnapshot
import dev.aidirector.sensors.Sensors
import dev.aidirector.sensors.TimeOfDay
import dev.aidirector.util.Clock
import dev.aidirector.util.DirectorJson
import kotlinx.serialization.encodeToString
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Per-tick orchestrator. Builds the prompt, delegates the multi-step
 * LLM/tool dispatch to [AgentLoop], and writes outcomes to memory.
 *
 * Skip gates (in order):
 *   1. DISABLED / PAUSED            — operator switches
 *   2. NO_SNAPSHOT                  — player can't be observed
 *   3. THROTTLED                    — within min_seconds_between_llm_calls
 *   4. COOLDOWN                     — N ticks since last action haven't passed
 *   5. NO_SIGNIFICANT_CHANGE        — snapshot/events show nothing notable
 *                                     since last LLM call
 *
 * The director records a SENSOR_SNAPSHOT row even when it skips the LLM, so
 * the event log stays complete and reflection still sees activity.
 */
class Director(
    private val configService: ConfigService,
    private val sensors: Sensors,
    private val memory: Memory,
    private val rag: Rag,
    private val agentLoop: AgentLoop,
    private val campaignStore: dev.aidirector.campaign.CampaignStore,
    private val clock: Clock = Clock.System,
    private val promptBuilder: PromptBuilder = PromptBuilder(
        systemPromptOverride = configService.current.director.systemPromptOverride,
        outputLanguage = configService.current.director.outputLanguage,
        modpackProfile = configService.current.director.modpackProfile,
        maxPromptChars = configService.current.director.contextBudgetChars,
        preset = DirectorPreset.fromWireOrDefault(configService.current.director.directorPreset),
    ),
) {
    private val lastCallAtMs = ConcurrentHashMap<UUID, Long>()
    private val lastSnapshot = ConcurrentHashMap<UUID, PlayerSnapshot>()
    private val cooldownRemaining = ConcurrentHashMap<UUID, Int>()

    /** Tension from the most recent evaluated tick — drives the adaptive throttle. */
    private val lastTension = ConcurrentHashMap<UUID, Double>()

    @Volatile
    var paused: Boolean = false

    data class TickReport(
        val playerUuid: UUID,
        val skipped: SkipReason? = null,
        val agentReport: AgentLoop.AgentReport? = null,
    ) {
        val toolsExecuted: Int get() = agentReport?.toolsExecuted ?: 0
        val toolsAttempted: Int get() = agentReport?.toolsAttempted ?: 0
        val toolsRefused: Int get() = agentReport?.toolsRefused ?: 0
        val toolsFailed: Int get() = agentReport?.toolsFailed ?: 0
        val llmError: String? get() = agentReport?.llmError
    }

    enum class SkipReason {
        DISABLED, PAUSED, NO_SNAPSHOT, THROTTLED, COOLDOWN, NO_SIGNIFICANT_CHANGE
    }

    suspend fun tickPlayer(playerUuid: UUID, ignoreThrottle: Boolean = false): TickReport {
        val cfg = configService.current
        if (!cfg.director.enabled) return TickReport(playerUuid, SkipReason.DISABLED)
        if (paused) return TickReport(playerUuid, SkipReason.PAUSED)

        val now = clock.nowMs()
        if (!ignoreThrottle) {
            val last = lastCallAtMs[playerUuid] ?: 0L
            // Adaptive throttle: when the previous tick saw high tension, the
            // director is allowed back in sooner — pressure should be answered
            // promptly, calm moments left alone.
            val tense = (lastTension[playerUuid] ?: 0.0) >= TENSION_HIGH
            // The tense floor is clamped to the calm floor — it only ever
            // shortens the wait, never lengthens it.
            val floorSeconds =
                if (tense) {
                    minOf(cfg.director.minSecondsBetweenLlmCallsTense, cfg.director.minSecondsBetweenLlmCalls)
                } else {
                    cfg.director.minSecondsBetweenLlmCalls
                }
            if (now - last < floorSeconds * 1000) return TickReport(playerUuid, SkipReason.THROTTLED)
        }

        val snapshot = sensors.snapshot(playerUuid)
            ?: return TickReport(playerUuid, SkipReason.NO_SNAPSHOT)

        memory.events.record(playerUuid, EventKind.SENSOR_SNAPSHOT, DirectorJson.encodeToString(snapshot))

        // Cooldown after a previous action — let dread breathe.
        if (!ignoreThrottle) {
            val remaining = cooldownRemaining[playerUuid] ?: 0
            if (remaining > 0) {
                cooldownRemaining[playerUuid] = remaining - 1
                lastSnapshot[playerUuid] = snapshot
                return TickReport(playerUuid, SkipReason.COOLDOWN)
            }
        }

        // Significance gate — skip the LLM unless something notable has happened
        // since the last evaluation. Saves tokens AND keeps the world silent on
        // ordinary mining/walking ticks.
        if (!ignoreThrottle && cfg.director.requireSignificantChange) {
            val prev = lastSnapshot[playerUuid]
            val hadNotableEvent = hasNotableEventSince(playerUuid, lastCallAtMs[playerUuid] ?: 0L)
            val notable = prev == null || isSignificantChange(prev, snapshot) || hadNotableEvent
            if (!notable) {
                lastSnapshot[playerUuid] = snapshot
                return TickReport(playerUuid, SkipReason.NO_SIGNIFICANT_CHANGE)
            }
        }

        lastCallAtMs[playerUuid] = now
        lastSnapshot[playerUuid] = snapshot

        val tension = TensionCurve.compute(snapshot, recentlyTookDamage(playerUuid, now))
        lastTension[playerUuid] = tension
        val recent = memory.events.recent(playerUuid, cfg.director.recentEventsShown)
        val activeNpcs = memory.npcs.rosterForPlayer(playerUuid, limit = MAX_NPCS_IN_PROMPT)
        val activeQuests = memory.quests.activeForPlayer(playerUuid, limit = MAX_QUESTS_IN_PROMPT)
        val arc = memory.worldState.get(WorldStateKeys.NARRATIVE_ARC)

        // Pinned canon backbone: highest-importance facts are ALWAYS present,
        // regardless of RAG cosine score — these never get "lost in the middle".
        val pinned = if (cfg.director.pinnedFactsCount > 0) {
            memory.facts.topByImportance(minImportance = 4, limit = cfg.director.pinnedFactsCount)
                .map { dev.aidirector.memory.ScoredFact(it, similarity = 1.0) }
        } else {
            emptyList()
        }
        // RAG detail layer: relevance-ranked facts for the current moment.
        val ragRetrieved = if (cfg.director.ragEnabled && cfg.director.ragMaxRetrieved > 0) {
            try {
                rag.retrieve(
                    query = buildRetrievalQuery(snapshot, activeNpcs, activeQuests),
                    k = cfg.director.ragMaxRetrieved,
                )
            } catch (e: Exception) {
                AIDirector.log.warn("RAG retrieval failed: ${e.message}")
                emptyList()
            }
        } else {
            emptyList()
        }
        // Pinned facts first, then RAG details, de-duplicated by fact id.
        val retrieved = (pinned + ragRetrieved).distinctBy { it.fact.id }

        val campaign = if (cfg.director.campaignEnabled) campaignStore.load() else null

        val messages = promptBuilder.build(
            PromptInput(
                snapshot = snapshot,
                tensionScore = tension,
                recentEvents = recent,
                activeNpcs = activeNpcs,
                activeQuests = activeQuests,
                narrativeArc = arc,
                retrievedFacts = retrieved,
                campaign = campaign,
                nowMs = now,
            ),
        )

        val report = agentLoop.run(
            playerUuid = playerUuid,
            nowMs = now,
            initialMessages = messages,
            model = cfg.llm.model,
            temperature = cfg.llm.temperature,
            maxTokens = cfg.llm.maxTokens,
        )
        // If the director actually did something, start the cooldown.
        if (report.toolsExecuted > 0) {
            cooldownRemaining[playerUuid] = cfg.director.cooldownTicksAfterAction
        }
        return TickReport(playerUuid, agentReport = report)
    }

    /**
     * True if the player's situation changed meaningfully between [prev] and
     * [curr]. The intent is "would a human director care about this delta?".
     */
    private fun isSignificantChange(prev: PlayerSnapshot, curr: PlayerSnapshot): Boolean {
        if (prev.dimensionId != curr.dimensionId) return true
        if (prev.biomeId != curr.biomeId) return true
        if (prev.timeOfDay != curr.timeOfDay && transitionsThroughDuskOrDawn(prev.timeOfDay, curr.timeOfDay)) return true
        if (prev.weather.thundering != curr.weather.thundering) return true
        if (prev.weather.raining != curr.weather.raining) return true
        if (prev.health.current - curr.health.current >= 3) return true
        if (prev.food.current - curr.food.current >= 6) return true
        if (curr.nearbyHostileCount - prev.nearbyHostileCount >= 2) return true
        if (prev.lightLevel >= 8 && curr.lightLevel <= 4) return true // entered darkness
        if (abs(prev.position.y - curr.position.y) >= 20) return true // big elevation change
        return false
    }

    private fun transitionsThroughDuskOrDawn(prev: TimeOfDay, curr: TimeOfDay): Boolean {
        // Day→Dusk, Dusk→Night, Night→Dawn, Dawn→Day all qualify.
        val ord = mapOf(TimeOfDay.DAY to 0, TimeOfDay.DUSK to 1, TimeOfDay.NIGHT to 2, TimeOfDay.DAWN to 3)
        return ord[prev] != ord[curr]
    }

    /** True if a noteworthy event arrived after [sinceMs]. */
    private fun hasNotableEventSince(playerUuid: UUID, sinceMs: Long): Boolean {
        val kinds = listOf(
            EventKind.PLAYER_HURT,
            EventKind.PLAYER_DEATH,
            EventKind.PLAYER_ADVANCEMENT,
            EventKind.PLAYER_CHAT,
            EventKind.PLAYER_CHANGE_DIMENSION,
        )
        val rows = memory.events.recentByKind(playerUuid, kinds, limit = 5)
        return rows.any { it.timestampMs > sinceMs }
    }

    private fun buildRetrievalQuery(
        snapshot: PlayerSnapshot,
        activeNpcs: List<NpcRecord>,
        activeQuests: List<QuestRecord>,
    ): String = buildString {
        append("Player ${snapshot.playerName} in ${snapshot.biomeId} during ${snapshot.timeOfDay.name.lowercase()}.")
        if (activeNpcs.isNotEmpty()) {
            append(" NPCs nearby: ${activeNpcs.take(3).joinToString { it.name + " (" + it.role + ")" }}.")
        }
        if (activeQuests.isNotEmpty()) {
            append(" Active quests: ${activeQuests.take(3).joinToString { it.title }}.")
        }
    }

    private fun recentlyTookDamage(playerUuid: UUID, nowMs: Long): Boolean {
        val rows = memory.events.recentByKind(
            playerUuid,
            listOf(EventKind.PLAYER_HURT, EventKind.PLAYER_DEATH),
            limit = 1,
        )
        return rows.any { nowMs - it.timestampMs < 15_000L }
    }

    companion object {
        private const val MAX_NPCS_IN_PROMPT = 12
        private const val MAX_QUESTS_IN_PROMPT = 12

        /** Tension at or above this counts as "high" — matches the system prompt. */
        private const val TENSION_HIGH = 0.55
    }
}
