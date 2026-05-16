package dev.aidirector.prompt

/**
 * Appends a "respond in <language>" instruction to a system prompt. Used by
 * the per-tick director loop, the reflection cycle (narrative arcs), and the
 * NPC dialogue handler so player-facing text consistently matches the
 * configured language without affecting tool argument formats (item ids,
 * sound ids, etc. stay English).
 */
object LanguageDirective {

    fun applyTo(systemPrompt: String, language: String): String {
        val normalized = language.trim()
        if (normalized.isEmpty() || normalized.equals("English", ignoreCase = true)) {
            return systemPrompt
        }
        return systemPrompt + "\n\n" +
            "OUTPUT LANGUAGE: All player-facing text — narration messages, NPC " +
            "dialogue, lore notes, quest titles and descriptions, advancement " +
            "titles and descriptions, boss-bar labels, narrative arc summaries " +
            "— MUST be written in $normalized. Tool argument values that are " +
            "namespaced ids (e.g. `minecraft:bread`, `minecraft:entity.wolf.howl`) " +
            "stay in their original English form."
    }
}
