package dev.aidirector.campaign

import dev.aidirector.AIDirector
import dev.aidirector.config.ConfigService
import dev.aidirector.llm.ChatMessage
import dev.aidirector.llm.ChatRequest
import dev.aidirector.llm.LlmClient
import dev.aidirector.memory.EventKind
import dev.aidirector.memory.FactKinds
import dev.aidirector.memory.Memory
import dev.aidirector.memory.WorldStateKeys
import dev.aidirector.prompt.LanguageDirective
import dev.aidirector.rag.Rag
import dev.aidirector.sensors.Sensors
import dev.aidirector.util.Clock
import dev.aidirector.util.DirectorJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * The campaign's author. Runs on a slow cadence — much slower than the
 * per-tick director — and is responsible for INTENT:
 *
 *  - bootstrap: on first run, design a multi-act campaign from the world,
 *    the modpack profile, and what the player has done so far;
 *  - review: thereafter, check whether the current act's goal has been met,
 *    advance the act pointer, revise upcoming beats so the plan stays
 *    responsive to how the player actually played.
 *
 * The per-tick [dev.aidirector.director.Director] only READS the resulting
 * [CampaignState]; it never writes it. This separation is what stops the
 * story from drifting — a fast reactive loop cannot also hold a long plan.
 */
class Showrunner(
    private val configService: ConfigService,
    private val sensors: Sensors,
    private val memory: Memory,
    private val rag: Rag,
    private val campaignStore: CampaignStore,
    private val llm: LlmClient,
    private val clock: Clock = Clock.System,
) {

    suspend fun runIfDue(): RunReport {
        val cfg = configService.current
        if (!cfg.director.campaignEnabled) return RunReport.Disabled
        val now = clock.nowMs()
        val last = memory.worldState.getLong(WorldStateKeys.LAST_CAMPAIGN_REVIEW_MS) ?: 0L
        // Bootstrap as soon as possible; review only on the configured interval.
        if (campaignStore.exists() && now - last < cfg.director.campaignReviewIntervalMs) {
            return RunReport.Skipped(now - last, cfg.director.campaignReviewIntervalMs)
        }
        memory.worldState.put(WorldStateKeys.LAST_CAMPAIGN_REVIEW_MS, now.toString())
        return runNow()
    }

    suspend fun runNow(): RunReport {
        val existing = campaignStore.load()
        return if (existing == null) bootstrap() else review(existing)
    }

    // ------------------------------------------------------------------

    private suspend fun bootstrap(): RunReport {
        val cfg = configService.current
        val online = sensors.onlinePlayers()
        // We can bootstrap even with nobody online, but a snapshot gives flavour.
        val snapshot = online.firstOrNull()?.let { sensors.snapshot(it) }
        val recentEvents = online.flatMap { memory.events.recent(it, 20) }
            .joinToString("\n") { "- ${it.kind}: ${it.payloadJson.take(120)}" }
            .ifBlank { "(the world is brand new — the player has barely begun)" }

        val system = LanguageDirective.applyTo(
            BOOTSTRAP_SYSTEM,
            cfg.director.outputLanguage,
        )
        val user = buildString {
            cfg.director.modpackProfile?.takeIf { it.isNotBlank() }?.let {
                appendLine("MODPACK / SETTING PROFILE:")
                appendLine(it)
                appendLine()
            }
            snapshot?.let {
                appendLine("The player right now: ${it.playerName}, in ${it.biomeId}, " +
                    "${it.timeOfDay.name.lowercase()}, health ${it.health.current}/${it.health.max}.")
            }
            appendLine()
            appendLine("Recent events:")
            appendLine(recentEvents)
            appendLine()
            append(JSON_INSTRUCTION)
        }

        val plan = requestPlan(system, user) ?: return RunReport.Failed("LLM did not return a usable plan")
        val state = plan.toState(currentActIndex = 0, revision = 1, nowMs = clock.nowMs())
            .let { activateFirstAct(it) }
        campaignStore.save(state)
        // The campaign premise becomes a top-importance lore fact for RAG continuity.
        rag.ingest(FactKinds.NARRATIVE_ARC, "Campaign premise: ${state.premise}", importance = 5)
        memory.worldState.put(WorldStateKeys.NARRATIVE_ARC, state.currentAct?.goal ?: state.premise)
        AIDirector.log.info("Campaign bootstrapped: ${state.acts.size} acts, premise=${state.premise.take(80)}")
        return RunReport.Bootstrapped(state)
    }

    private suspend fun review(current: CampaignState): RunReport {
        val cfg = configService.current
        val online = sensors.onlinePlayers()
        if (online.isEmpty()) return RunReport.Skipped(0, cfg.director.campaignReviewIntervalMs)

        val recentEvents = online.flatMap { memory.events.recent(it, 25) }
            .sortedByDescending { it.timestampMs }
            .take(40)
            .joinToString("\n") { "- ${it.kind}: ${it.payloadJson.take(120)}" }

        val system = LanguageDirective.applyTo(REVIEW_SYSTEM, cfg.director.outputLanguage)
        val user = buildString {
            appendLine("CURRENT CAMPAIGN (revision ${current.revision}):")
            appendLine(DirectorJson.encodeToString(current))
            appendLine()
            appendLine("Current act index: ${current.currentActIndex} (\"${current.currentAct?.title}\")")
            appendLine()
            appendLine("What the player actually did since the last review:")
            appendLine(recentEvents.ifBlank { "(little of note)" })
            appendLine()
            append(REVIEW_INSTRUCTION)
        }

        val plan = requestPlan(system, user) ?: return RunReport.Failed("LLM did not return a usable plan")
        // Pick the act index: first ACTIVE act, else first non-DONE, else last.
        val nextIndex = plan.acts.indexOfFirst { ActStatus.fromWire(it.status) == ActStatus.ACTIVE }
            .takeIf { it >= 0 }
            ?: plan.acts.indexOfFirst { ActStatus.fromWire(it.status) != ActStatus.DONE }
                .takeIf { it >= 0 }
            ?: (plan.acts.size - 1).coerceAtLeast(0)

        val state = plan.toState(
            currentActIndex = nextIndex,
            revision = current.revision + 1,
            nowMs = clock.nowMs(),
        ).let { activateFirstAct(it) }
        campaignStore.save(state)
        memory.worldState.put(WorldStateKeys.NARRATIVE_ARC, state.currentAct?.goal ?: state.premise)
        for (uuid in online) {
            memory.events.record(uuid, EventKind.DIRECTOR_TICK, """{"campaign_revised":${state.revision}}""")
        }
        AIDirector.log.info(
            "Campaign revised → rev=${state.revision} act=${state.currentActIndex} '${state.currentAct?.title}'",
        )
        return RunReport.Revised(state)
    }

    // ------------------------------------------------------------------

    /** Ensures exactly one act is ACTIVE — the one at [CampaignState.currentActIndex]. */
    private fun activateFirstAct(state: CampaignState): CampaignState {
        val acts = state.acts.mapIndexed { i, act ->
            val status = when {
                i < state.currentActIndex -> ActStatus.DONE.wire
                i == state.currentActIndex -> ActStatus.ACTIVE.wire
                else -> ActStatus.PENDING.wire
            }
            act.copy(status = status)
        }
        return state.copy(acts = acts)
    }

    private suspend fun requestPlan(system: String, user: String): CampaignPlan? {
        val cfg = configService.current
        val response = try {
            llm.chat(
                ChatRequest(
                    model = cfg.llm.model,
                    messages = listOf(
                        ChatMessage(role = "system", content = system),
                        ChatMessage(role = "user", content = user),
                    ),
                    tools = null,
                    toolChoice = "none",
                    temperature = (cfg.llm.temperature + 0.15).coerceAtMost(2.0),
                    maxTokens = cfg.llm.maxTokens,
                ),
            )
        } catch (e: Exception) {
            AIDirector.log.warn("Showrunner LLM call failed: ${e.message}")
            return null
        }
        val raw = response.choices.firstOrNull()?.message?.content ?: return null
        val json = extractJsonObject(raw) ?: run {
            AIDirector.log.warn("Showrunner: no JSON object in LLM reply")
            return null
        }
        return try {
            DirectorJson.decodeFromString<CampaignPlan>(json).sanitised()
        } catch (e: SerializationException) {
            AIDirector.log.warn("Showrunner: plan JSON invalid: ${e.message}")
            null
        }
    }

    /** Extracts the first balanced {...} block — models often wrap JSON in prose or fences. */
    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    sealed interface RunReport {
        data object Disabled : RunReport
        data class Skipped(val sinceLastMs: Long, val intervalMs: Long) : RunReport
        data class Bootstrapped(val state: CampaignState) : RunReport
        data class Revised(val state: CampaignState) : RunReport
        data class Failed(val reason: String) : RunReport
    }

    companion object {
        private val BOOTSTRAP_SYSTEM = """
            You are the SHOWRUNNER for a Minecraft world — the author behind an
            AI game director. Design a slow-burning, multi-act campaign that
            will unfold over many hours of play.

            Principles:
            - 3 to 5 acts. Each act is a CHAPTER, not a quest. It should take
              an hour or more of real play to move through.
            - The story must be grounded in survival Minecraft — no chosen-one
              prophecies, no save-the-world. Think: a haunted mine, a vanished
              neighbour, a slow corruption, a debt owed to a stranger.
            - Acts escalate. Early acts plant; later acts pay off.
            - Each act has a director-facing GOAL (what the director must make
              the player feel/notice) and 3-6 concrete BEATS (specific stage-able
              moments: an NPC appears, a structure is found, a storm breaks).
            - The campaign should be able to END. The final act resolves it.
            - Do NOT railroad. Beats are intentions, not scripts.

            Output ONE JSON object, no prose around it, shaped exactly:
            {"premise": "...", "theme": "...", "tone": "...",
             "acts": [{"title": "...", "goal": "...", "beats": ["...", "..."]}]}
        """.trimIndent()

        private val REVIEW_SYSTEM = """
            You are the SHOWRUNNER reviewing an in-progress campaign. You are
            given the current plan and what the player actually did recently.

            Your job:
            - Decide whether the current act's goal has been substantially met.
              If so, mark it "done" and mark the next act "active".
            - Revise the upcoming acts' beats so they respond to how the player
              actually played — keep what still fits, rewrite what no longer
              does, never contradict events that already happened.
            - You may keep everything the same if the act is still mid-progress.
            - Never delete the premise/theme. Keep total acts ≤ 7.

            Output ONE JSON object only, same shape as the original plan, with
            each act now carrying a "status" field of "pending"/"active"/"done".
        """.trimIndent()

        private val JSON_INSTRUCTION =
            "Design the campaign now. Output ONLY the JSON object."

        private val REVIEW_INSTRUCTION =
            "Output the revised campaign JSON now — same shape, every act with a \"status\"."
    }
}

/** Wire shape for the LLM's plan JSON — decoupled from the persisted [CampaignState]. */
@Serializable
private data class CampaignPlan(
    val premise: String = "",
    val theme: String = "",
    val tone: String = "",
    val acts: List<PlanAct> = emptyList(),
) {
    fun sanitised(): CampaignPlan = copy(
        premise = premise.take(800),
        theme = theme.take(300),
        tone = tone.take(120),
        acts = acts.take(CampaignState.MAX_ACTS).map { it.sanitised() },
    )

    fun toState(currentActIndex: Int, revision: Int, nowMs: Long): CampaignState = CampaignState(
        premise = premise.ifBlank { "An unwritten story waits in this world." },
        theme = theme.ifBlank { "discovery" },
        tone = tone.ifBlank { "quiet wonder" },
        acts = acts.map {
            CampaignAct(
                title = it.title,
                goal = it.goal,
                beats = it.beats,
                status = it.status.ifBlank { ActStatus.PENDING.wire },
            )
        },
        currentActIndex = currentActIndex.coerceIn(0, (acts.size - 1).coerceAtLeast(0)),
        revision = revision,
        updatedAtMs = nowMs,
    )
}

@Serializable
private data class PlanAct(
    val title: String = "Untitled act",
    val goal: String = "",
    val beats: List<String> = emptyList(),
    val status: String = "pending",
) {
    fun sanitised(): PlanAct = copy(
        title = title.take(80),
        goal = goal.take(400),
        beats = beats.take(CampaignState.MAX_BEATS_PER_ACT).map { it.take(240) },
    )
}
