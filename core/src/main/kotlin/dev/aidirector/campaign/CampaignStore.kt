package dev.aidirector.campaign

import dev.aidirector.AIDirector
import dev.aidirector.memory.WorldStateStore
import dev.aidirector.util.DirectorJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString

/**
 * Persists the single per-world [CampaignState] as JSON inside the existing
 * world_state key/value table. One campaign per world; it survives restarts
 * and is the spine the [Showrunner] revises over many sessions.
 */
class CampaignStore(private val worldState: WorldStateStore) {

    fun load(): CampaignState? {
        val raw = worldState.get(KEY) ?: return null
        return try {
            DirectorJson.decodeFromString<CampaignState>(raw)
        } catch (e: SerializationException) {
            AIDirector.log.warn("Campaign state corrupt, ignoring: ${e.message}")
            null
        }
    }

    fun save(state: CampaignState) {
        worldState.put(KEY, DirectorJson.encodeToString(state))
    }

    fun exists(): Boolean = worldState.get(KEY) != null

    companion object {
        const val KEY = "campaign.state"
    }
}
