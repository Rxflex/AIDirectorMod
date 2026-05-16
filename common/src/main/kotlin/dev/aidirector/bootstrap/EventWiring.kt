package dev.aidirector.bootstrap

import dev.aidirector.AIDirector
import dev.aidirector.command.AIDirectorCommand
import dev.aidirector.memory.EventKind
import dev.aidirector.prompt.PromptSafety
import dev.aidirector.util.DirectorJson
import dev.architectury.event.EventResult
import dev.architectury.event.events.common.BlockEvent
import dev.architectury.event.events.common.ChatEvent
import dev.architectury.event.events.common.CommandRegistrationEvent
import dev.architectury.event.events.common.EntityEvent
import dev.architectury.event.events.common.InteractionEvent
import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import java.util.concurrent.atomic.AtomicReference

/**
 * Subscribes the cross-platform event listeners that drive the director.
 * Platform-specific holders (ServerActions, Sensors) must already be installed
 * before this is called.
 *
 * NOTE: We currently subscribe to a conservative subset of Architectury events.
 * Some events that exist in older Architectury versions (CHAT.RECEIVED,
 * PLAYER_CRAFT_ITEM, CHANGE_DIMENSION) have signatures that differ between
 * arch 12.x and 13.x. Once the target Architectury version stabilises, those
 * subscriptions can be re-added in additional sensors.
 */
object EventWiring {

    val serverRef = AtomicReference<MinecraftServer?>(null)

    fun register() {
        LifecycleEvent.SERVER_STARTED.register { server ->
            serverRef.set(server)
            val configDir = dev.architectury.platform.Platform.getConfigFolder()
            val saveDir = server.getWorldPath(LevelResource.ROOT).resolve(AIDirector.MOD_ID)
            try {
                val ok = DirectorLifecycle.onServerStarting(configDir, saveDir)
                if (ok) {
                    // Wire the MC registry dump into the director's startup phase.
                    DirectorLifecycle.serviceOrNull()?.platformStartupTask = { memory, rag ->
                        if (configDir != null) { /* configDir always non-null on MC, this is a hint to the compiler */ }
                        dev.aidirector.platform.RegistryDumper.dumpAndIngestIfNeeded(memory, rag)
                    }
                } else {
                    AIDirector.log.warn(
                        "AI Director will stay disabled until you set api_key in " +
                            "${configDir.resolve("aidirector.toml")} and run /aidirector reload.",
                    )
                }
            } catch (e: Exception) {
                AIDirector.log.error("AI Director failed to start: ${e.message}", e)
            }
        }

        LifecycleEvent.SERVER_STOPPING.register { _ ->
            DirectorLifecycle.onServerStopping()
            serverRef.set(null)
        }

        PlayerEvent.PLAYER_JOIN.register { player ->
            recordIfRunning(player.uuid, EventKind.PLAYER_JOIN, simplePayload("name", player.gameProfile.name))
            DirectorLifecycle.serviceOrNull()?.let { service ->
                try {
                    service.onPlayerLogin(player.uuid)
                } catch (e: Exception) {
                    AIDirector.log.warn("onPlayerLogin failed: ${e.message}")
                }
            }
        }

        PlayerEvent.PLAYER_QUIT.register { player ->
            recordIfRunning(player.uuid, EventKind.PLAYER_LEAVE, simplePayload("name", player.gameProfile.name))
            DirectorLifecycle.serviceOrNull()?.let { service ->
                try {
                    service.onPlayerLogout(player.uuid)
                } catch (e: Exception) {
                    AIDirector.log.warn("onPlayerLogout failed: ${e.message}")
                }
            }
        }

        EntityEvent.LIVING_DEATH.register { entity, source ->
            if (entity is net.minecraft.server.level.ServerPlayer) {
                val payload = buildJsonObject {
                    put("name", PromptSafety.sanitizePlayerName(entity.gameProfile.name))
                    put("source", source.msgId)
                    put("attacker", PromptSafety.sanitize(source.entity?.name?.string ?: "", 64))
                }
                recordIfRunning(entity.uuid, EventKind.PLAYER_DEATH, DirectorJson.encodeToString<JsonObject>(payload))
            }
            EventResult.pass()
        }

        EntityEvent.LIVING_HURT.register { entity, source, amount ->
            if (entity is net.minecraft.server.level.ServerPlayer) {
                val now = System.currentTimeMillis()
                if (EventThrottle.shouldRecord(entity.uuid, EventKind.PLAYER_HURT, now)) {
                    val payload = buildJsonObject {
                        put("source", source.msgId)
                        put("amount", amount.toDouble())
                        put("hp_after", (entity.health - amount).coerceAtLeast(0f).toDouble())
                        put("attacker", PromptSafety.sanitize(source.entity?.name?.string ?: "", 64))
                    }
                    recordIfRunning(entity.uuid, EventKind.PLAYER_HURT, DirectorJson.encodeToString<JsonObject>(payload))
                }
            }
            EventResult.pass()
        }

        BlockEvent.BREAK.register { _, pos, state, player, _ ->
            val now = System.currentTimeMillis()
            if (EventThrottle.shouldRecord(player.uuid, EventKind.PLAYER_BREAK_BLOCK, now)) {
                val payload = buildJsonObject {
                    put("block", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.block).toString())
                    put("x", pos.x)
                    put("y", pos.y)
                    put("z", pos.z)
                }
                recordIfRunning(player.uuid, EventKind.PLAYER_BREAK_BLOCK, DirectorJson.encodeToString<JsonObject>(payload))
            }
            EventResult.pass()
        }

        // Chat is captured as ambient context for the director's memory and RAG.
        // We DO NOT trigger a tick on chat — players cannot directly command the
        // director through chat. The next regular tick will see this message as
        // part of recent events and may or may not react, on its own terms.
        ChatEvent.RECEIVED.register { player, message ->
            if (player == null) return@register EventResult.pass()
            val text = message.string ?: ""
            if (text.isBlank()) return@register EventResult.pass()
            val now = System.currentTimeMillis()
            if (EventThrottle.shouldRecord(player.uuid, EventKind.PLAYER_CHAT, now)) {
                val payload = buildJsonObject {
                    put("text", PromptSafety.sanitize(text, 240))
                    put("from", PromptSafety.sanitizePlayerName(player.gameProfile.name))
                }
                recordIfRunning(
                    player.uuid,
                    EventKind.PLAYER_CHAT,
                    DirectorJson.encodeToString<JsonObject>(payload),
                )
            }
            EventResult.pass()
        }

        CommandRegistrationEvent.EVENT.register { dispatcher, _, _ ->
            AIDirectorCommand.register(dispatcher)
        }

        InteractionEvent.INTERACT_ENTITY.register { player, entity, _ ->
            if (player.level().isClientSide) return@register EventResult.pass()
            val service = DirectorLifecycle.serviceOrNull() ?: return@register EventResult.pass()
            try {
                if (service.handleNpcInteraction(player.uuid, entity.uuid)) {
                    EventResult.interruptTrue()
                } else {
                    EventResult.pass()
                }
            } catch (e: Exception) {
                AIDirector.log.warn("INTERACT_ENTITY handler error: ${e.message}")
                EventResult.pass()
            }
        }
    }

    private fun simplePayload(key: String, value: String): String =
        DirectorJson.encodeToString<JsonObject>(buildJsonObject { put(key, value) })

    private fun recordIfRunning(uuid: java.util.UUID, kind: String, payload: String) {
        val service = DirectorLifecycle.serviceOrNull() ?: return
        try {
            service.recordEvent(uuid, kind, payload)
        } catch (e: Exception) {
            AIDirector.log.warn("Failed to record $kind event: ${e.message}")
        }
    }
}
