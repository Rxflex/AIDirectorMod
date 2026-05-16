package dev.aidirector.campaign

import kotlinx.serialization.Serializable

/**
 * The long-horizon plan the director works toward. Where the per-tick loop is
 * reactive ("something happened, respond"), the campaign is intentional ("we
 * are three acts into a story about the player's hubris; the climax is the
 * mine collapse"). The [Showrunner] owns this object — it bootstraps it and
 * revises it on a slow cadence; the per-tick director only READS it and picks
 * actions that nudge the current act toward its goal.
 */
@Serializable
data class CampaignState(
    /** One paragraph: what this whole campaign is fundamentally about. */
    val premise: String,
    /** The throughline — the idea every act keeps circling back to. */
    val theme: String,
    /** Emotional register: "slow dread", "wry and warm", "epic and tragic", etc. */
    val tone: String,
    /** Ordered acts. The story moves through them one at a time. */
    val acts: List<CampaignAct>,
    /** Index into [acts] of the act currently in play. */
    val currentActIndex: Int,
    /** Bumped on every Showrunner revision — useful for debugging drift. */
    val revision: Int,
    val updatedAtMs: Long,
) {
    val currentAct: CampaignAct?
        get() = acts.getOrNull(currentActIndex)

    fun isComplete(): Boolean =
        acts.isNotEmpty() && acts.all { it.status == ActStatus.DONE.wire }

    companion object {
        const val MAX_ACTS = 7
        const val MAX_BEATS_PER_ACT = 6
    }
}

@Serializable
data class CampaignAct(
    /** Short evocative title, e.g. "The Sealed Mine". */
    val title: String,
    /** What the director must accomplish in this act, in director-facing terms. */
    val goal: String,
    /**
     * Concrete moments the director should try to stage during this act,
     * roughly in order. Free-form text — the director interprets them.
     */
    val beats: List<String>,
    /** "pending" | "active" | "done" — see [ActStatus]. */
    val status: String,
)

enum class ActStatus(val wire: String) {
    PENDING("pending"),
    ACTIVE("active"),
    DONE("done");

    companion object {
        fun fromWire(s: String): ActStatus =
            entries.firstOrNull { it.wire.equals(s, ignoreCase = true) } ?: PENDING
    }
}
