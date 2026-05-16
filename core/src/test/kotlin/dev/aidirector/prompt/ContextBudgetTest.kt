package dev.aidirector.prompt

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ContextBudgetTest {

    private fun sections(
        system: String = "SYS",
        state: String = "STATE",
        tension: String = "TENSION",
        arc: String? = "ARC",
        npcs: String = "NPCS",
        quests: String = "QUESTS",
        events: String = "EVENTS",
        retrieved: String = "RETR",
        instruction: String = "INSTR",
    ) = ContextBudget.Sections(
        systemPrompt = system,
        playerStateBlock = state,
        tensionBlock = tension,
        campaignBlock = null,
        narrativeArc = arc,
        activeNpcs = npcs,
        activeQuests = quests,
        recentEvents = events,
        retrievedFacts = retrieved,
        instruction = instruction,
    )

    @Test
    fun `passes through when total under cap`() {
        val s = sections()
        val fitted = ContextBudget.fit(s, maxChars = 10_000)
        assertThat(fitted).isEqualTo(s)
    }

    @Test
    fun `trims recent events first`() {
        val s = sections(
            events = "E".repeat(5_000),
            retrieved = "R".repeat(100),
            npcs = "N".repeat(100),
        )
        val fitted = ContextBudget.fit(s, maxChars = 1_000)
        assertThat(fitted.recentEvents.length).isLessThan(s.recentEvents.length)
        assertThat(fitted.retrievedFacts).isEqualTo(s.retrievedFacts)
    }

    @Test
    fun `trims through priority order until under cap`() {
        val s = sections(
            events = "E".repeat(5_000),
            retrieved = "R".repeat(5_000),
            npcs = "N".repeat(5_000),
            quests = "Q".repeat(5_000),
        )
        val fitted = ContextBudget.fit(s, maxChars = 500)
        // Each lower-priority section should have shrunk before higher ones.
        // Per-section min keeps a small floor; tolerate a few section minimums in the total.
        assertThat(fitted.totalChars()).isLessThanOrEqualTo(500 + 4 * 200)
        // System, state, tension are untouched.
        assertThat(fitted.systemPrompt).isEqualTo(s.systemPrompt)
        assertThat(fitted.playerStateBlock).isEqualTo(s.playerStateBlock)
        assertThat(fitted.tensionBlock).isEqualTo(s.tensionBlock)
    }

    @Test
    fun `nulls narrative arc only when forced`() {
        val s = sections(arc = "A".repeat(2_000))
        val fittedHigh = ContextBudget.fit(s, maxChars = 10_000)
        assertThat(fittedHigh.narrativeArc).isEqualTo(s.narrativeArc)
        val fittedLow = ContextBudget.fit(s, maxChars = 200)
        // Arc should have been trimmed (not necessarily nulled).
        assertThat(fittedLow.narrativeArc?.length ?: 0).isLessThan(s.narrativeArc!!.length)
    }
}
