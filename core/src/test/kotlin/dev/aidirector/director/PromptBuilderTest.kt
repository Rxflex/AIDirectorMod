package dev.aidirector.director

import dev.aidirector.memory.EventKind
import dev.aidirector.memory.EventRecord
import dev.aidirector.sensors.TimeOfDay
import dev.aidirector.support.Fixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class PromptBuilderTest {

    private val nowMs = 1_700_000_000_000L

    private fun input(
        recentEvents: List<EventRecord> = emptyList(),
        activeNpcs: List<dev.aidirector.memory.NpcRecord> = emptyList(),
        activeQuests: List<dev.aidirector.memory.QuestRecord> = emptyList(),
        arc: String? = null,
        retrieved: List<dev.aidirector.memory.ScoredFact> = emptyList(),
        tension: Double = 0.4,
        timeOfDay: TimeOfDay = TimeOfDay.NIGHT,
        playerName: String = "alice",
        campaign: dev.aidirector.campaign.CampaignState? = null,
    ) = PromptInput(
        snapshot = Fixtures.snapshot(name = playerName, timeOfDay = timeOfDay),
        tensionScore = tension,
        recentEvents = recentEvents,
        activeNpcs = activeNpcs,
        activeQuests = activeQuests,
        narrativeArc = arc,
        retrievedFacts = retrieved,
        campaign = campaign,
        nowMs = nowMs,
    )

    @Test
    fun `system message includes rules and user message includes state`() {
        val messages = PromptBuilder().build(input())
        assertThat(messages).hasSize(2)
        assertThat(messages[0].role).isEqualTo("system")
        assertThat(messages[0].content).contains("AI Director", "WHEN TO ACT")
        assertThat(messages[1].role).isEqualTo("user")
        assertThat(messages[1].content).contains("alice", "night", "minecraft:overworld", "Tension")
    }

    @Test
    fun `override replaces system prompt`() {
        val msgs = PromptBuilder("CUSTOM").build(input())
        assertThat(msgs[0].content).isEqualTo("CUSTOM")
    }

    @Test
    fun `recent events ordered newest-first with ages`() {
        val uuid = UUID.randomUUID()
        val events = listOf(
            EventRecord(1, uuid, EventKind.PLAYER_DEATH, """{"source":"fall"}""", nowMs - 5_000),
            EventRecord(2, uuid, EventKind.TOOL_INVOKED, """{"tool":"play_sound"}""", nowMs - 120_000),
        )
        val rendered = PromptBuilder().build(input(recentEvents = events))[1].content!!
        assertThat(rendered).contains(EventKind.PLAYER_DEATH, "5s ago", "2m ago")
        val idxDeath = rendered.indexOf(EventKind.PLAYER_DEATH)
        val idxTool = rendered.indexOf(EventKind.TOOL_INVOKED)
        assertThat(idxDeath).isLessThan(idxTool)
    }

    @Test
    fun `narrative arc surfaces in user message`() {
        val msg = PromptBuilder().build(input(arc = "Shadows gather near the old keep."))[1].content!!
        assertThat(msg).contains("Narrative arc", "Shadows gather")
    }

    @Test
    fun `retrieved facts appear under World memory with similarity scores`() {
        val sf = dev.aidirector.memory.ScoredFact(
            fact = dev.aidirector.memory.Fact(
                id = 1, kind = "lore", content = "the well is haunted",
                importance = 4, embedding = null, embeddingDim = 3,
                timestampMs = nowMs,
            ),
            similarity = 0.84,
        )
        val msg = PromptBuilder().build(input(retrieved = listOf(sf)))[1].content!!
        assertThat(msg).contains("World memory", "Retrieved details", "lore", "haunted", "0.84")
    }

    @Test
    fun `balanced preset injects no persona block`() {
        val sys = PromptBuilder(preset = DirectorPreset.BALANCED).build(input())[0].content!!
        assertThat(sys).doesNotContain("Director persona")
    }

    @Test
    fun `a non-default preset injects its persona directive into the system prompt`() {
        val sys = PromptBuilder(preset = DirectorPreset.CRUEL_GM).build(input())[0].content!!
        assertThat(sys).contains("Director persona", "The Cruel GM", "Favour pressure")
        // The safety rules are still present — the persona never replaces them.
        assertThat(sys).contains("ANTI-HALLUCINATION", "DEATH RESPONSE")
    }

    @Test
    fun `pinned facts (similarity 1) render under Canon, not Retrieved`() {
        val pinned = dev.aidirector.memory.ScoredFact(
            fact = dev.aidirector.memory.Fact(
                id = 2, kind = "narrative.arc", content = "the kingdom is at war",
                importance = 5, embedding = null, embeddingDim = null,
                timestampMs = nowMs,
            ),
            similarity = 1.0,
        )
        val msg = PromptBuilder().build(input(retrieved = listOf(pinned)))[1].content!!
        assertThat(msg).contains("Canon", "the kingdom is at war")
        assertThat(msg).doesNotContain("Retrieved details")
    }
}
