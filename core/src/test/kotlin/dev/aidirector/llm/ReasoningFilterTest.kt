package dev.aidirector.llm

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReasoningFilterTest {

    @Test
    fun `strips a closed think block and keeps the answer`() {
        val out = ReasoningFilter.strip("<think>let me consider the options</think>The well runs dry.")
        assertThat(out).isEqualTo("The well runs dry.")
    }

    @Test
    fun `strips a thought block regardless of tag casing`() {
        val out = ReasoningFilter.strip("<Thought>plan the entry</Thought>\n\nThe night was long.")
        assertThat(out).isEqualTo("The night was long.")
    }

    @Test
    fun `strips an unclosed reasoning tail left by a token cutoff`() {
        // The model ran out of budget mid-thought — no closing tag.
        val out = ReasoningFilter.strip(
            "<thought>Role: Unseen chronicler. Format: one entry. Restrictions: no",
        )
        assertThat(out).isEmpty()
    }

    @Test
    fun `leaves ordinary text untouched`() {
        val text = "Bjorn descended into the dark and did not climb out the same."
        assertThat(ReasoningFilter.strip(text)).isEqualTo(text)
    }

    @Test
    fun `strips reasoning that precedes a JSON payload`() {
        val out = ReasoningFilter.strip("<think>decide the acts</think>{\"premise\":\"x\"}")
        assertThat(out).isEqualTo("{\"premise\":\"x\"}")
    }

    @Test
    fun `null in null out`() {
        assertThat(ReasoningFilter.strip(null)).isNull()
    }
}
