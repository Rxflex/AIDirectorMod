package dev.aidirector.chronicle

import dev.aidirector.AIDirector
import dev.aidirector.actions.ServerActions
import dev.aidirector.campaign.CampaignStore
import dev.aidirector.config.ConfigService
import dev.aidirector.llm.ChatMessage
import dev.aidirector.llm.ChatRequest
import dev.aidirector.llm.LlmClient
import dev.aidirector.memory.ChronicleEntry
import dev.aidirector.memory.EventKind
import dev.aidirector.memory.EventRecord
import dev.aidirector.memory.FactKinds
import dev.aidirector.memory.Memory
import dev.aidirector.memory.WorldStateKeys
import dev.aidirector.prompt.LanguageDirective
import dev.aidirector.prompt.PromptSafety
import dev.aidirector.rag.Rag
import dev.aidirector.util.DirectorJson
import dev.aidirector.util.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * The session chronicler. When a player logs out it composes a short
 * in-fiction journal entry recording their session — past-tense, evocative,
 * written as the world's own memory. Entries are persisted and, on the
 * player's next login, handed to them as a written book they can keep and
 * re-read. Over many sessions the chronicle becomes a real history of the
 * world, and every entry is also ingested into RAG so the director's future
 * narration stays consistent with what was already told.
 */
class Chronicle(
    private val configService: ConfigService,
    private val memory: Memory,
    private val campaignStore: CampaignStore,
    private val actions: ServerActions,
    private val rag: Rag,
    private val llm: LlmClient,
    private val clock: Clock = Clock.System,
) {

    /**
     * Composes and stores a chronicle entry for the player's just-ended
     * session. Called on logout. Safe to call when nothing interesting
     * happened — it simply reports [RunReport.TooQuiet] and writes nothing.
     */
    suspend fun writeForPlayer(playerUuid: UUID): RunReport {
        val cfg = configService.current
        if (!cfg.director.chronicleEnabled) return RunReport.Disabled
        val now = clock.nowMs()

        val scanned = memory.events.recent(playerUuid, SESSION_EVENT_SCAN)
        if (scanned.isEmpty()) return RunReport.TooQuiet(0)

        // The session runs from the most recent PLAYER_JOIN up to now. Events
        // arrive newest-first; everything above (and including) that join row
        // belongs to this session.
        val joinIdx = scanned.indexOfFirst { it.kind == EventKind.PLAYER_JOIN }
        val session = if (joinIdx >= 0) scanned.subList(0, joinIdx + 1) else scanned
        val sessionStart = session.lastOrNull()?.timestampMs ?: now

        val meaningful = session.filter { it.kind in MEANINGFUL_KINDS }
        if (meaningful.size < MIN_MEANINGFUL_EVENTS) return RunReport.TooQuiet(meaningful.size)

        val playerName = extractPlayerName(session) ?: "the wanderer"
        val entry = try {
            compose(playerUuid, playerName, meaningful, sessionStart, now, cfg.director.outputLanguage)
        } catch (e: Exception) {
            AIDirector.log.warn("Chronicle composition failed: ${e.message}")
            return RunReport.Failed(e.message ?: "unknown error")
        } ?: return RunReport.Failed("LLM returned an empty entry")

        memory.chronicle.add(entry)
        runCatching {
            rag.ingest(
                FactKinds.LORE,
                "Chronicle — ${entry.title}: ${entry.body.take(280)}",
                importance = 4,
            )
        }
        AIDirector.log.info("Chronicle entry written for {} ('{}')", playerName, entry.title)
        return RunReport.Written(entry.title)
    }

    /**
     * Hands the player any chronicle entries they have not yet received, as a
     * single written book. Called shortly after login. Returns the number of
     * entries delivered.
     */
    fun deliverPendingForPlayer(playerUuid: UUID): Int {
        val cfg = configService.current
        if (!cfg.director.chronicleEnabled) return 0
        val pending = memory.chronicle.undelivered(playerUuid, limit = MAX_BOOK_ENTRIES)
        if (pending.isEmpty()) return 0
        if (!actions.isPlayerOnline(playerUuid)) return 0

        // A Minecraft written-book page does not scroll — text past its ~13
        // lines is simply lost. Paginate each entry ourselves; every entry
        // starts on a fresh page.
        val pages = ArrayList<String>()
        for (entry in pending) {
            if (pages.size >= MAX_BOOK_PAGES) break
            pages += paginate("« ${entry.title} »\n\n${entry.body}")
        }
        val bookPages = pages.take(MAX_BOOK_PAGES)
        val outcome = actions.giveBook(
            playerUuid = playerUuid,
            title = "The Chronicle",
            author = "an unseen hand",
            pages = bookPages,
        )
        if (!outcome.ok) {
            AIDirector.log.warn("Chronicle delivery failed: ${outcome.message}")
            return 0
        }
        memory.chronicle.markDelivered(pending.map { it.id })
        AIDirector.log.info("Delivered {} chronicle entr{} to player", pending.size, if (pending.size == 1) "y" else "ies")
        return pending.size
    }

    private suspend fun compose(
        playerUuid: UUID,
        playerName: String,
        meaningful: List<EventRecord>,
        sessionStartMs: Long,
        sessionEndMs: Long,
        outputLanguage: String,
    ): ChronicleEntry? {
        val cfg = configService.current
        val campaign = campaignStore.load()
        val arc = memory.worldState.get(WorldStateKeys.NARRATIVE_ARC)
        val roster = memory.npcs.rosterForPlayer(playerUuid, limit = 8)

        val eventLines = meaningful
            .asReversed() // chronological for the narrator
            .take(MAX_EVENTS_IN_PROMPT)
            .joinToString("\n") { "- ${it.kind}: ${PromptSafety.sanitize(it.payloadJson, 160)}" }
        val campaignLine = campaign?.let {
            "Campaign: ${PromptSafety.sanitize(it.premise, 240)} | tone: ${PromptSafety.sanitize(it.tone, 60)} | " +
                "current act: ${PromptSafety.sanitize(it.currentAct?.title ?: "—", 60)}"
        } ?: "Campaign: (none)"
        val arcLine = arc?.let { "World arc: ${PromptSafety.sanitize(it, 320)}" } ?: "World arc: (none yet)"
        val rosterLine = if (roster.isEmpty()) {
            "Known characters: (none)"
        } else {
            "Known characters: " + roster.joinToString("; ") {
                "${PromptSafety.sanitize(it.name, 32)} (${it.status.name.lowercase()})"
            }
        }

        val system = LanguageDirective.applyTo(
            """
            You are the unseen chronicler of a Minecraft world — its living
            memory. Write ONE short journal entry recording the play session
            just ended, as in-world history or legend.

            Rules:
            - Past tense. Evocative, literary, in-fiction. 2 short paragraphs,
              no more than ~110 words total.
            - Never mention game mechanics, coordinates, UI, accounts, or that
              this is a game. Refer to the player as '$playerName' (or 'the
              wanderer'). Treat deaths as grave moments, not respawns.
            - Stay consistent with the campaign, the world arc, and the known
              characters' fates. Do not invent characters or places that are
              not implied by the events.
            - Output EXACTLY this shape: the first line is a short evocative
              title (max 8 words, no quotation marks, no 'Title:' prefix),
              then one blank line, then the entry.
            """.trimIndent(),
            outputLanguage,
        )
        val user = """
            $campaignLine
            $arcLine
            $rosterLine

            What happened this session (chronological):
            $eventLines

            Write the chronicle entry now.
        """.trimIndent()

        val response = llm.chat(
            ChatRequest(
                model = cfg.llm.model,
                messages = listOf(
                    ChatMessage(role = "system", content = system),
                    ChatMessage(role = "user", content = user),
                ),
                tools = null,
                toolChoice = "none",
                temperature = (cfg.llm.temperature + 0.1).coerceAtMost(2.0),
                // Generous: a reasoning model spends most of the budget on its
                // (stripped) chain-of-thought before writing the short entry.
                maxTokens = cfg.llm.maxTokens,
            ),
        )
        val raw = response.choices.firstOrNull()?.message?.content?.trim()
            ?.takeIf { it.length >= MIN_ENTRY_CHARS } ?: return null
        val (title, body) = splitTitleAndBody(raw)
        return ChronicleEntry(
            id = UUID.randomUUID().toString().take(12),
            playerUuid = playerUuid,
            title = title,
            body = body,
            sessionStartMs = sessionStartMs,
            sessionEndMs = sessionEndMs,
            createdAtMs = clock.nowMs(),
        )
    }

    private fun splitTitleAndBody(raw: String): Pair<String, String> {
        val lines = raw.lines()
        val firstIdx = lines.indexOfFirst { it.isNotBlank() }
        if (firstIdx < 0) return DEFAULT_TITLE to raw.take(MAX_BODY_CHARS)
        val title = lines[firstIdx]
            .removePrefix("#").trim()
            .removePrefix("Title:").removePrefix("TITLE:").trim()
            .trim('"', '«', '»', '*')
            .take(MAX_TITLE_CHARS)
            .ifBlank { DEFAULT_TITLE }
        val body = lines.drop(firstIdx + 1).joinToString("\n").trim()
        return if (body.length >= MIN_ENTRY_CHARS) {
            title to body.take(MAX_BODY_CHARS)
        } else {
            // The model gave us only one block — keep it whole as the body.
            DEFAULT_TITLE to raw.take(MAX_BODY_CHARS)
        }
    }

    /**
     * Splits text into Minecraft written-book pages. A book page does not
     * scroll, so each page is word-wrapped to a conservative width and capped
     * to a line count that always fits the visible area.
     */
    private fun paginate(text: String): List<String> {
        val pages = ArrayList<String>()
        val pageLines = ArrayList<String>()

        fun flush() {
            if (pageLines.isNotEmpty()) {
                pages += pageLines.joinToString("\n")
                pageLines.clear()
            }
        }
        fun addLine(line: String) {
            if (pageLines.size >= PAGE_LINES) flush()
            pageLines += line
        }

        for (rawLine in text.split("\n")) {
            if (rawLine.isBlank()) {
                addLine("")
                continue
            }
            var current = StringBuilder()
            for (word in rawLine.split(" ").filter { it.isNotEmpty() }) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (candidate.length > PAGE_LINE_WIDTH && current.isNotEmpty()) {
                    addLine(current.toString())
                    current = StringBuilder(word)
                } else {
                    current = StringBuilder(candidate)
                }
            }
            if (current.isNotEmpty()) addLine(current.toString())
        }
        flush()
        return pages.ifEmpty { listOf(text) }
    }

    private fun extractPlayerName(session: List<EventRecord>): String? {
        for (e in session) {
            val obj = runCatching { DirectorJson.decodeFromString<JsonObject>(e.payloadJson) }.getOrNull() ?: continue
            val name = (obj["name"] ?: obj["from"])?.let {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }
            if (!name.isNullOrBlank()) return PromptSafety.sanitizePlayerName(name)
        }
        return null
    }

    sealed interface RunReport {
        data object Disabled : RunReport
        data class TooQuiet(val meaningfulEvents: Int) : RunReport
        data class Written(val title: String) : RunReport
        data class Failed(val reason: String) : RunReport
    }

    companion object {
        private const val SESSION_EVENT_SCAN = 160
        private const val MIN_MEANINGFUL_EVENTS = 3
        private const val MAX_EVENTS_IN_PROMPT = 60
        private const val MIN_ENTRY_CHARS = 40
        private const val MAX_TITLE_CHARS = 60
        private const val MAX_BODY_CHARS = 900
        /** Undelivered entries pulled into one book. */
        private const val MAX_BOOK_ENTRIES = 12
        /** Hard cap on total pages (a written book holds 100). */
        private const val MAX_BOOK_PAGES = 50
        /** Word-wrap width and line count for a single book page — kept well
         *  inside what the non-scrolling page area actually shows. */
        private const val PAGE_LINE_WIDTH = 19
        private const val PAGE_LINES = 13
        private const val DEFAULT_TITLE = "An Unmarked Day"

        /** Event kinds worth narrating — the rest (raw snapshots, block breaks) is noise. */
        private val MEANINGFUL_KINDS: Set<String> = setOf(
            EventKind.PLAYER_DEATH,
            EventKind.PLAYER_HURT,
            EventKind.PLAYER_ADVANCEMENT,
            EventKind.PLAYER_CHAT,
            EventKind.PLAYER_CHANGE_DIMENSION,
            EventKind.PLAYER_CRAFT,
            EventKind.TOOL_INVOKED,
            EventKind.DIRECTOR_TICK,
        )
    }
}
