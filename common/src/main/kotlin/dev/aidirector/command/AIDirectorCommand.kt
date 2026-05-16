package dev.aidirector.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder.literal
import dev.aidirector.AIDirector
import dev.aidirector.bootstrap.DirectorLifecycle
import dev.aidirector.config.ConfigNotInitializedException
import dev.aidirector.config.ConfigParseException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component

object AIDirectorCommand {

    private val cmdScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            literal<CommandSourceStack>("aidirector")
                .requires { it.hasPermission(REQUIRED_PERMISSION_LEVEL) }
                .then(literal<CommandSourceStack>("status").executes { ctx ->
                    val service = DirectorLifecycle.serviceOrNull()
                    val status = when {
                        service == null -> "not started"
                        service.isPaused -> "paused"
                        service.isRunning -> "running"
                        else -> "stopped"
                    }
                    ctx.source.sendSuccess(
                        { Component.literal("AI Director: $status (mod v${AIDirector.VERSION})") },
                        false,
                    )
                    1
                })
                .then(literal<CommandSourceStack>("pause").executes { ctx ->
                    val service = DirectorLifecycle.serviceOrNull()
                    if (service == null) {
                        ctx.source.sendFailure(Component.literal("AI Director is not running"))
                        return@executes 0
                    }
                    service.pause()
                    ctx.source.sendSuccess({ Component.literal("AI Director paused") }, true)
                    1
                })
                .then(literal<CommandSourceStack>("resume").executes { ctx ->
                    val service = DirectorLifecycle.serviceOrNull()
                    if (service == null) {
                        ctx.source.sendFailure(Component.literal("AI Director is not running"))
                        return@executes 0
                    }
                    service.resume()
                    ctx.source.sendSuccess({ Component.literal("AI Director resumed") }, true)
                    1
                })
                .then(literal<CommandSourceStack>("reload").executes { ctx ->
                    try {
                        DirectorLifecycle.reloadConfig()
                        ctx.source.sendSuccess({ Component.literal("AI Director config reloaded") }, true)
                        1
                    } catch (e: ConfigNotInitializedException) {
                        ctx.source.sendFailure(Component.literal("Default config written to ${e.configFile}. Edit it and run /aidirector reload again."))
                        0
                    } catch (e: ConfigParseException) {
                        ctx.source.sendFailure(Component.literal("Config invalid: ${e.message}"))
                        0
                    } catch (e: Exception) {
                        ctx.source.sendFailure(Component.literal("Reload failed: ${e.message}"))
                        0
                    }
                })
                .then(literal<CommandSourceStack>("trigger").executes { ctx ->
                    val service = DirectorLifecycle.serviceOrNull()
                    if (service == null) {
                        ctx.source.sendFailure(Component.literal("AI Director is not running"))
                        return@executes 0
                    }
                    ctx.source.sendSuccess({ Component.literal("AI Director: triggering immediate tick") }, false)
                    cmdScope.launch {
                        try {
                            val reports = service.triggerNow()
                            val summary = reports.joinToString(", ") { r ->
                                "${r.playerUuid.toString().take(8)}=${r.toolsExecuted}/${r.toolsAttempted}"
                            }.ifEmpty { "no online players" }
                            AIDirector.log.info("Manual trigger: $summary")
                        } catch (e: Exception) {
                            AIDirector.log.error("Manual trigger failed: ${e.message}", e)
                        }
                    }
                    1
                })
                .then(literal<CommandSourceStack>("chronicle").executes { ctx ->
                    val service = DirectorLifecycle.serviceOrNull()
                    if (service == null) {
                        ctx.source.sendFailure(Component.literal("AI Director is not running"))
                        return@executes 0
                    }
                    val player = ctx.source.player
                    if (player == null) {
                        ctx.source.sendFailure(Component.literal("Run /aidirector chronicle as a player"))
                        return@executes 0
                    }
                    ctx.source.sendSuccess({ Component.literal("AI Director: writing the session chronicle...") }, false)
                    val uuid = player.uuid
                    cmdScope.launch {
                        try {
                            val report = service.triggerChronicle(uuid)
                            service.deliverChronicleNow(uuid)
                            AIDirector.log.info("Manual chronicle: $report")
                        } catch (e: Exception) {
                            AIDirector.log.error("Manual chronicle failed: ${e.message}", e)
                        }
                    }
                    1
                }),
        )
    }

    private const val REQUIRED_PERMISSION_LEVEL = 2
}
