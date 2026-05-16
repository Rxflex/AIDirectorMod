package dev.aidirector.interaction

import dev.aidirector.AIDirector
import dev.aidirector.config.ConfigService
import dev.aidirector.director.AgentLoop
import dev.aidirector.llm.ChatMessage
import dev.aidirector.memory.EventKind
import dev.aidirector.memory.Memory
import dev.aidirector.memory.NpcRecord
import dev.aidirector.memory.ScoredFact
import dev.aidirector.rag.Rag
import dev.aidirector.sensors.Sensors
import dev.aidirector.util.Clock
import dev.aidirector.util.DirectorJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Runs a focused agent loop scoped to an NPC's personality the moment the
 * player right-clicks them. Output is delivered through normal tools
 * (typically [SendNarrationTool] in WHISPER style) so the player sees the
 * NPC "speak" via chat. The dialogue is constrained: the NPC must stay in
 * voice, may not give overpowered loot, and never breaks the fourth wall.
 */
class NpcDialogue(
    private val configService: ConfigService,
    private val sensors: Sensors,
    private val memory: Memory,
    private val rag: Rag,
    private val agentLoop: AgentLoop,
    private val clock: Clock = Clock.System,
) {

    /** Per-NPC cooldown so spam-right-clicking doesn't fire six dialogue agents in a row. */
    private val lastInteractionAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val cooldownMs = 60_000L

    /** Returns null if the NPC is on cooldown (caller should still suppress vanilla UI). */
    suspend fun handle(playerUuid: UUID, npc: NpcRecord): AgentLoop.AgentReport? {
        val now = clock.nowMs()
        val last = lastInteractionAtMs[npc.id] ?: 0L
        if (now - last < cooldownMs) {
            dev.aidirector.AIDirector.log.info(
                "NPC dialogue skipped (cooldown): npc={} sinceLast={}s",
                npc.id, (now - last) / 1000,
            )
            return null
        }
        lastInteractionAtMs[npc.id] = now
        val snapshot = sensors.snapshot(playerUuid)
        val playerName = snapshot?.playerName ?: "the player"
        val activeQuests = memory.quests.activeForPlayer(playerUuid, limit = 6)
            .filter { it.npcId == null || it.npcId == npc.id }
        val recent = memory.events.recent(playerUuid, 15)
        val retrieved = try {
            rag.retrieve(
                query = "${npc.name} ${npc.role} ${npc.personality} talks with $playerName",
                k = configService.current.director.ragMaxRetrieved,
                minSimilarity = 0.20,
            )
        } catch (e: Exception) {
            AIDirector.log.warn("NPC dialogue RAG failed: ${e.message}")
            emptyList()
        }

        memory.events.record(
            playerUuid,
            EventKind.SENSOR_SNAPSHOT,
            DirectorJson.encodeToString<JsonObject>(buildJsonObject {
                put("npc_interact", npc.id)
                put("npc_name", npc.name)
            }),
        )

        val messages = buildMessages(npc, playerName, activeQuests, recent, retrieved)
        val cfg = configService.current
        val report = agentLoop.run(
            playerUuid = playerUuid,
            nowMs = now,
            initialMessages = messages,
            model = cfg.llm.model,
            temperature = (cfg.llm.temperature + 0.1).coerceAtMost(2.0),
            maxTokens = cfg.llm.maxTokens,
        )
        // Bump the interaction tally + last_seen so the NPC keeps a history
        // the next dialogue (and the director) can lean on for continuity.
        memory.npcs.recordInteraction(npc.id, now)
        return report
    }

    private fun buildMessages(
        npc: NpcRecord,
        playerName: String,
        activeQuests: List<dev.aidirector.memory.QuestRecord>,
        recent: List<dev.aidirector.memory.EventRecord>,
        retrieved: List<ScoredFact>,
    ): List<ChatMessage> {
        val arcLine = npc.arcStage.takeIf { it.isNotBlank() }
            ?.let { "STORY SO FAR: ${dev.aidirector.prompt.PromptSafety.sanitize(it, 240)}" }
            ?: "STORY SO FAR: (this is a fresh character)"
        val meetingLine = when (npc.interactionCount) {
            0 -> "This is the FIRST time the player has spoken with you — react accordingly."
            else -> "You have spoken with the player ${npc.interactionCount} time(s) before — do not greet them as a stranger."
        }
        val baseSystem = """
            You are roleplaying as the NPC '${dev.aidirector.prompt.PromptSafety.sanitize(npc.name, 32)}' in a Minecraft world.
            ROLE: ${dev.aidirector.prompt.PromptSafety.sanitize(npc.role, 48)}
            PERSONALITY: ${dev.aidirector.prompt.PromptSafety.sanitize(npc.personality, 280)}
            RELATIONSHIP TO PLAYER: ${dev.aidirector.prompt.PromptSafety.sanitize(npc.relationship, 80)}
            $arcLine
            $meetingLine

            Rules:
            1. Stay strictly in this NPC's voice. Never mention game systems,
               coordinates, or that you are an LLM.
            2. Speak in 1-2 sentences max. Whisper-tone narration is fine.
            3. You may use tools — send_narration (style="whisper") for spoken
               lines, assign_quest if it fits the conversation, give_lore_note
               for written keepsakes. Do not use combat or weather tools.
            4. If the player has an active quest from you, react to its state.
               If complete-enough, call complete_quest with a fitting epilogue.
            5. Reference retrieved memory for continuity. If you said something
               earlier, remember it.
        """.trimIndent()
        val system = dev.aidirector.prompt.LanguageDirective.applyTo(
            baseSystem,
            configService.current.director.outputLanguage,
        )

        val questText = if (activeQuests.isEmpty()) "(none)" else activeQuests.joinToString("\n") {
            "- [${it.id}] ${it.title}: ${it.description}"
        }
        val recentText = if (recent.isEmpty()) "(none)" else recent.take(10).joinToString("\n") {
            "- ${it.kind}: ${it.payloadJson.take(120)}"
        }
        val retrievedText = if (retrieved.isEmpty()) "(none)" else retrieved.joinToString("\n") {
            "- ${it.fact.kind}: ${it.fact.content.take(180)}"
        }

        val user = """
            $playerName has just approached you.

            Quests with this NPC (or unattached, possibly yours):
            $questText

            Recent player events:
            $recentText

            Retrieved canon (RAG):
            $retrievedText

            Speak now. Aim for one to two sentences of in-voice dialogue, via send_narration with style "whisper".
        """.trimIndent()

        return listOf(
            ChatMessage(role = "system", content = system),
            ChatMessage(role = "user", content = user),
        )
    }
}
