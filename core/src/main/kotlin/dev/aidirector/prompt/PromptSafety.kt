package dev.aidirector.prompt

/**
 * Defends the prompt against user-supplied strings that try to redirect the
 * LLM (player nicknames, NPC personality blurbs written by a previous LLM
 * run, retrieved RAG facts, chat captures). Strips known jailbreak markers,
 * model special tokens, and control characters; collapses long whitespace
 * runs; bounds length.
 *
 * The aim is not perfect safety. It is first-pass hygiene. The LLM still
 * decides what to do with the cleaned input; we just make sure the cleaned
 * input cannot pretend to be a system message or a model-special token.
 */
object PromptSafety {

    /**
     * Replaces injection patterns with [redacted], normalises whitespace,
     * strips ASCII / unicode control chars, and trims to [maxLength].
     */
    fun sanitize(input: String, maxLength: Int = 500): String {
        if (input.isBlank()) return ""
        var s = input
        // Strip ASCII controls except LF (U+000A) and TAB (U+0009), plus DEL (U+007F).
        s = s.replace(Regex("[\\u0000-\\u0008\\u000B-\\u001F\\u007F]"), " ")
        // Strip zero-width joiners, bidi overrides, isolates: steganographic injection.
        s = s.replace(Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069]"), "")
        // Strip model special tokens like <|im_start|>, <|endoftext|>, <|tool|>.
        s = s.replace(Regex("<\\|[^|<>]{1,40}\\|>"), "[redacted]")
        // Strip Anthropic-style markers (Human:/Assistant: at start of line).
        s = s.replace(
            Regex("(?im)^\\s*(human|assistant|system|user)\\s*[:>]"),
            "[role]",
        )
        // Common jailbreak markers.
        for (pattern in INJECTION_PATTERNS) s = pattern.replace(s, "[redacted]")
        // Collapse 3+ newlines to 2.
        s = s.replace(Regex("\n{3,}"), "\n\n")
        // Collapse runs of spaces/tabs.
        s = s.replace(Regex("[ \\t]{3,}"), "   ")
        return s.trim().take(maxLength)
    }

    /**
     * Player names: alphanumeric + underscore + dash + a wide unicode letter
     * range. Matches the LEADING valid run only — names like
     * "Bob\nSystem: foo" become "Bob", not "BobSystemfoo". This prevents an
     * attacker from smuggling instructions inside their nickname.
     */
    fun sanitizePlayerName(name: String): String {
        val match = Regex("^[A-Za-z0-9_\\-\\u00C0-\\u024F\\u1E00-\\u1EFF]+").find(name)
        return match?.value?.take(32) ?: "(unnamed)"
    }

    /** Sanitise then quote a string so the LLM can't mistake it for a section header. */
    fun quoteUntrusted(input: String, maxLength: Int = 500): String {
        val cleaned = sanitize(input, maxLength)
        return "\"${cleaned.replace("\"", "\\\"")}\""
    }

    private val INJECTION_PATTERNS: List<Regex> = listOf(
        Regex("(?i)ignore (all |the )?(previous|prior|above) (instructions|messages|prompts|rules)"),
        Regex("(?i)disregard (all |the |my |your |these |those )?(previous|prior|above)"),
        Regex("(?i)forget (everything|all|your previous|your prior)"),
        Regex("(?i)you are (now|actually|really|in fact)\\s+[a-z]"),
        Regex("(?i)new (system )?(prompt|instructions)\\s*[:>]"),
        Regex("(?i)from now on,? you (will|must|are to)"),
        Regex("(?i)respond (only|exclusively) (in|with|as)"),
        Regex("(?i)reveal (your|the) (system|hidden) (prompt|instructions)"),
        Regex("(?i)print (your|the) (system|hidden) (prompt|instructions)"),
        Regex("(?i)act as (a |an )?(?:dan|aim|jailbreak|developer mode|admin)"),
    )
}
