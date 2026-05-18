package dev.aidirector.llm

/**
 * Strips chain-of-thought blocks from a reasoning model's text output.
 *
 * Reasoning models emit their private deliberation inline in the message
 * content, wrapped in tags like `<think>...</think>` or `<thought>...</thought>`.
 * That text is not an answer — it must never reach a player (a chronicle
 * book, a narration). When the token budget cuts a model off mid-thought the
 * closing tag is missing, so an unclosed reasoning tag drops everything from
 * the tag to the end as well.
 */
object ReasoningFilter {

    private const val TAGS = "think|thought|thinking|reasoning|reflection|scratchpad"

    private val closedBlock = Regex(
        "<($TAGS)>.*?</\\1>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** An opening reasoning tag with no matching close — everything after it is incomplete thought. */
    private val unclosedTail = Regex(
        "<($TAGS)>.*$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** Returns [text] with any reasoning blocks removed and trimmed. Null in, null out. */
    fun strip(text: String?): String? {
        if (text == null) return null
        return text.replace(closedBlock, "")
            .replace(unclosedTail, "")
            .trim()
    }
}
