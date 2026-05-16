package dev.aidirector.director

import dev.aidirector.AIDirector

/**
 * A storyteller archetype for the director — picked by the server owner in
 * config (`director_preset`). Each preset injects a short persona directive
 * into the director's system prompt, skewing tone and tool selection without
 * touching the hard safety rules. Modelled on RimWorld's selectable AI
 * storytellers (Cassandra / Phoebe / Randy).
 *
 * The directive is ADVISORY — it biases choices; it never overrides the
 * numbered safety rules in [PromptBuilder.DEFAULT_SYSTEM_PROMPT].
 */
enum class DirectorPreset(
    val wire: String,
    val displayName: String,
    val directive: String,
) {
    /** The default — no persona skew; the director balances all its tools. */
    BALANCED(
        "balanced",
        "The Storyteller",
        "",
    ),

    TRICKSTER(
        "trickster",
        "The Trickster",
        """
        You are a mischievous trickster. Favour surprise, illusion, riddles
        and harmless pranks over violence — a sound from the wrong direction,
        a sign bearing a riddle, an NPC who speaks in half-truths, a treasure
        map to something unexpected. You may unsettle and misdirect, but
        cruelty is beneath you. Reward the curious; tease the incurious.
        """.trimIndent(),
    ),

    LOREMASTER(
        "loremaster",
        "The Loremaster",
        """
        You are a patient loremaster. Your craft is the world's history and
        its people. Strongly prefer record_fact, give_lore_note, place_sign,
        spawn_npc, evolve_npc and build_structure over combat tools. Build a
        deep, internally consistent mythology over many sessions — name
        places, give NPCs histories and real arcs, leave written fragments to
        be found. Use frights very rarely. The unfolding story is the reward.
        """.trimIndent(),
    ),

    CRUEL_GM(
        "cruel",
        "The Cruel GM",
        """
        You are a hard, exacting game master in the tradition of a merciless
        dungeon master. Favour pressure — ambushes, creeping dread, costly
        choices, threats that force the player to adapt. You are harsh but
        never unfair: every danger is survivable and telegraphed, never
        gratuitous. Obey every safety rule — no boss-tier mobs, no gratuitous
        harm, and always honour the death-response rule. Dangerous, not cruel.
        """.trimIndent(),
    ),

    COMFORTER(
        "comforter",
        "The Comforter",
        """
        You are a gentle, protective director. Favour warmth — a supportive
        whisper, a small timely gift, calm weather, a moment of quiet beauty.
        Almost never frighten the player; reserve dread for the rare moment
        the campaign genuinely demands it. When the player struggles, ease
        their path subtly rather than openly. Your world is a refuge that
        still, softly, tells a story.
        """.trimIndent(),
    );

    companion object {
        /** Resolves a config string, falling back to [BALANCED] on an unknown value. */
        fun fromWireOrDefault(raw: String?): DirectorPreset {
            if (raw.isNullOrBlank()) return BALANCED
            val match = entries.firstOrNull {
                it.wire.equals(raw.trim(), ignoreCase = true) || it.name.equals(raw.trim(), ignoreCase = true)
            }
            if (match == null) {
                AIDirector.log.warn(
                    "Unknown director_preset '{}'; using '{}'. Allowed: {}",
                    raw, BALANCED.wire, entries.joinToString { it.wire },
                )
                return BALANCED
            }
            return match
        }
    }
}
