package dev.aidirector.paper

import dev.aidirector.AIDirector
import dev.aidirector.actions.ServerActionsHolder
import dev.aidirector.bootstrap.DirectorLifecycle
import dev.aidirector.config.ConfigNotInitializedException
import dev.aidirector.config.ConfigParseException
import dev.aidirector.sensors.SensorsHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin

class AIDirectorPaperPlugin : JavaPlugin() {

    private val cmdScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onEnable() {
        AIDirector.log.info("AI Director (Paper) {} starting", AIDirector.VERSION)

        ServerActionsHolder.install(PaperServerActions(this))
        SensorsHolder.install(PaperSensors(this))

        val configDir = dataFolder.toPath()
        val saveDir = server.worldContainer.toPath().resolve(AIDirector.MOD_ID)
        try {
            val ok = DirectorLifecycle.onServerStarting(configDir, saveDir)
            if (!ok) {
                logger.warning(
                    "AI Director is disabled. Edit ${configDir.resolve("aidirector.toml")} " +
                        "(set api_key, etc.) and run /aidirector reload.",
                )
            }
        } catch (e: Exception) {
            logger.severe("AI Director failed to start: ${e.message}")
        }

        server.pluginManager.registerEvents(PaperEventListeners(), this)
    }

    override fun onDisable() {
        cmdScope.cancel()
        DirectorLifecycle.onServerStopping()
        AIDirector.log.info("AI Director (Paper) stopped")
    }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        if (command.name != "aidirector") return false
        if (!sender.hasPermission("aidirector.admin")) {
            sender.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED))
            return true
        }
        val sub = args.firstOrNull() ?: "status"
        when (sub.lowercase()) {
            "status" -> {
                val service = DirectorLifecycle.serviceOrNull()
                val state = when {
                    service == null -> "not started"
                    service.isPaused -> "paused"
                    service.isRunning -> "running"
                    else -> "stopped"
                }
                sender.sendMessage(
                    Component.text("AI Director: $state (v${AIDirector.VERSION})", NamedTextColor.GOLD),
                )
            }
            "pause" -> {
                DirectorLifecycle.serviceOrNull()?.let {
                    it.pause()
                    sender.sendMessage(Component.text("AI Director paused.", NamedTextColor.GOLD))
                } ?: sender.sendMessage(Component.text("Not running.", NamedTextColor.RED))
            }
            "resume" -> {
                DirectorLifecycle.serviceOrNull()?.let {
                    it.resume()
                    sender.sendMessage(Component.text("AI Director resumed.", NamedTextColor.GOLD))
                } ?: sender.sendMessage(Component.text("Not running.", NamedTextColor.RED))
            }
            "reload" -> {
                try {
                    DirectorLifecycle.reloadConfig()
                    sender.sendMessage(Component.text("AI Director config reloaded.", NamedTextColor.GOLD))
                } catch (e: ConfigNotInitializedException) {
                    sender.sendMessage(
                        Component.text("Default config written to ${e.configFile}. Edit it then reload again.", NamedTextColor.YELLOW),
                    )
                } catch (e: ConfigParseException) {
                    sender.sendMessage(Component.text("Config invalid: ${e.message}", NamedTextColor.RED))
                } catch (e: Exception) {
                    sender.sendMessage(Component.text("Reload failed: ${e.message}", NamedTextColor.RED))
                }
            }
            "trigger" -> {
                val service = DirectorLifecycle.serviceOrNull()
                if (service == null) {
                    sender.sendMessage(Component.text("Not running.", NamedTextColor.RED))
                } else {
                    sender.sendMessage(Component.text("AI Director: triggering immediate tick.", NamedTextColor.GOLD))
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
                }
            }
            "chronicle" -> {
                val service = DirectorLifecycle.serviceOrNull()
                val player = sender as? org.bukkit.entity.Player
                when {
                    service == null ->
                        sender.sendMessage(Component.text("Not running.", NamedTextColor.RED))
                    player == null ->
                        sender.sendMessage(Component.text("Run /aidirector chronicle as a player.", NamedTextColor.RED))
                    else -> {
                        sender.sendMessage(
                            Component.text("AI Director: writing the session chronicle...", NamedTextColor.GOLD),
                        )
                        val uuid = player.uniqueId
                        cmdScope.launch {
                            try {
                                val report = service.triggerChronicle(uuid)
                                service.deliverChronicleNow(uuid)
                                AIDirector.log.info("Manual chronicle: {}", report)
                            } catch (e: Exception) {
                                AIDirector.log.error("Manual chronicle failed: ${e.message}", e)
                            }
                        }
                    }
                }
            }
            else -> {
                sender.sendMessage(
                    Component.text(
                        "Usage: /aidirector <status|pause|resume|reload|trigger|chronicle>",
                        NamedTextColor.YELLOW,
                    ),
                )
            }
        }
        return true
    }
}
