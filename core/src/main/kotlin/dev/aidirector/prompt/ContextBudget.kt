package dev.aidirector.prompt

/**
 * Crude char-based prompt budget. We trim *non-critical* sections in order of
 * least-to-most-important until the total fits under [maxChars]. The state /
 * tension / system prompt are never trimmed by this — they're load-bearing.
 *
 * Order (least important first):
 *   recent events → RAG retrievals → active NPCs → active quests → narrative arc
 *
 * Each trimmed section is replaced with a truncated subset that still gives
 * the LLM situational awareness without flooding context.
 */
object ContextBudget {

    /** ~4 chars per token; 40k chars ≈ 10k tokens. Conservative for 128k-context models. */
    const val DEFAULT_MAX_CHARS = 40_000

    /** Hard cap on any single section after trimming. */
    private const val ABSOLUTE_MIN_SECTION_CHARS = 200

    /**
     * Trims each section of [Sections] until the total fits under [maxChars].
     * Sections fixed (system, state, tension) are passed through unchanged.
     */
    fun fit(sections: Sections, maxChars: Int = DEFAULT_MAX_CHARS): Sections {
        if (sections.totalChars() <= maxChars) return sections

        // Trim order: events first, then RAG, then NPCs, then quests, then arc.
        val trimSteps: List<Pair<String, (Sections, Int) -> Sections>> = listOf(
            "recentEvents" to { s, target -> s.copy(recentEvents = truncate(s.recentEvents, target)) },
            "retrievedFacts" to { s, target -> s.copy(retrievedFacts = truncate(s.retrievedFacts, target)) },
            "activeNpcs" to { s, target -> s.copy(activeNpcs = truncate(s.activeNpcs, target)) },
            "activeQuests" to { s, target -> s.copy(activeQuests = truncate(s.activeQuests, target)) },
            "narrativeArc" to { s, target -> s.copy(narrativeArc = truncate(s.narrativeArc.orEmpty(), target).takeIf { it.isNotBlank() }) },
        )

        var current = sections
        for ((_, fn) in trimSteps) {
            if (current.totalChars() <= maxChars) return current
            current = fn(current, ABSOLUTE_MIN_SECTION_CHARS)
        }
        // If still over, hard-truncate the user-facing aggregate to maxChars from the end of recent events.
        return if (current.totalChars() > maxChars) {
            val over = current.totalChars() - maxChars
            current.copy(
                recentEvents = truncate(current.recentEvents, (current.recentEvents.length - over).coerceAtLeast(80)),
            )
        } else {
            current
        }
    }

    private fun truncate(s: String, max: Int): String {
        if (s.length <= max) return s
        val keep = max.coerceAtLeast(80)
        return s.take(keep - 1) + "…"
    }

    data class Sections(
        val systemPrompt: String,
        val playerStateBlock: String,
        val tensionBlock: String,
        /** Campaign plan — load-bearing direction, never trimmed by the budget. */
        val campaignBlock: String?,
        val narrativeArc: String?,
        val activeNpcs: String,
        val activeQuests: String,
        val recentEvents: String,
        val retrievedFacts: String,
        val instruction: String,
    ) {
        fun totalChars(): Int =
            systemPrompt.length +
                playerStateBlock.length +
                tensionBlock.length +
                (campaignBlock?.length ?: 0) +
                (narrativeArc?.length ?: 0) +
                activeNpcs.length +
                activeQuests.length +
                recentEvents.length +
                retrievedFacts.length +
                instruction.length
    }
}
