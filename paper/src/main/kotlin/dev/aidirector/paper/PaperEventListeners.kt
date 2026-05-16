package dev.aidirector.paper

import dev.aidirector.bootstrap.DirectorLifecycle
import dev.aidirector.bootstrap.EventThrottle
import dev.aidirector.memory.EventKind
import dev.aidirector.prompt.PromptSafety
import dev.aidirector.util.DirectorJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Bridges Bukkit events to the director's memory log. Same throttling and
 * sanitization as the mod version — and like the mod, chat is captured as
 * ambient context but does NOT trigger an immediate tick. Players cannot
 * directly command the director through chat.
 */
class PaperEventListeners : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onJoin(event: PlayerJoinEvent) {
        record(
            event.player.uniqueId,
            EventKind.PLAYER_JOIN,
            simple("name", event.player.name),
        )
        runCatching { DirectorLifecycle.serviceOrNull()?.onPlayerLogin(event.player.uniqueId) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onQuit(event: PlayerQuitEvent) {
        record(
            event.player.uniqueId,
            EventKind.PLAYER_LEAVE,
            simple("name", event.player.name),
        )
        runCatching { DirectorLifecycle.serviceOrNull()?.onPlayerLogout(event.player.uniqueId) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath(event: PlayerDeathEvent) {
        val p = event.entity
        val killer = p.killer?.name ?: ""
        val payload = buildJsonObject {
            put("name", PromptSafety.sanitizePlayerName(p.name))
            put("source", event.damageSource.damageType.key.toString())
            put("attacker", PromptSafety.sanitize(killer, 64))
        }
        record(p.uniqueId, EventKind.PLAYER_DEATH, DirectorJson.encodeToString<JsonObject>(payload))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHurt(event: EntityDamageEvent) {
        val p = event.entity as? Player ?: return
        val now = System.currentTimeMillis()
        if (!EventThrottle.shouldRecord(p.uniqueId, EventKind.PLAYER_HURT, now)) return
        val payload = buildJsonObject {
            put("source", event.cause.name.lowercase())
            put("amount", event.finalDamage)
            put("hp_after", (p.health - event.finalDamage).coerceAtLeast(0.0))
        }
        record(p.uniqueId, EventKind.PLAYER_HURT, DirectorJson.encodeToString<JsonObject>(payload))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        val p = event.player
        val now = System.currentTimeMillis()
        if (!EventThrottle.shouldRecord(p.uniqueId, EventKind.PLAYER_BREAK_BLOCK, now)) return
        val payload = buildJsonObject {
            put("block", event.block.type.key.toString())
            put("x", event.block.x)
            put("y", event.block.y)
            put("z", event.block.z)
        }
        record(p.uniqueId, EventKind.PLAYER_BREAK_BLOCK, DirectorJson.encodeToString<JsonObject>(payload))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChat(event: AsyncPlayerChatEvent) {
        val p = event.player
        val now = System.currentTimeMillis()
        if (!EventThrottle.shouldRecord(p.uniqueId, EventKind.PLAYER_CHAT, now)) return
        val payload = buildJsonObject {
            put("text", PromptSafety.sanitize(event.message, 240))
            put("from", PromptSafety.sanitizePlayerName(p.name))
        }
        record(p.uniqueId, EventKind.PLAYER_CHAT, DirectorJson.encodeToString<JsonObject>(payload))
    }

    private fun simple(key: String, value: String): String =
        DirectorJson.encodeToString<JsonObject>(buildJsonObject { put(key, value) })

    private fun record(uuid: UUID, kind: String, payload: String) {
        val service = DirectorLifecycle.serviceOrNull() ?: return
        try {
            service.recordEvent(uuid, kind, payload)
        } catch (_: Exception) { /* swallow — never break a Bukkit event */ }
    }
}
