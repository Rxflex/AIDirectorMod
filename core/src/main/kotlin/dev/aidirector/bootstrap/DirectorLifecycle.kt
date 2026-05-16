package dev.aidirector.bootstrap

import dev.aidirector.AIDirector
import dev.aidirector.config.ConfigNotInitializedException
import dev.aidirector.config.ConfigService
import dev.aidirector.director.DirectorService
import dev.aidirector.sensors.SensorsHolder
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Wires up the moving parts when the server starts and tears them down when it
 * stops. Platform modules call into here from their server start/stop hooks.
 *
 * Lifecycle:
 *   onServerStarting(saveDir)   → load config, build service, start tick loop
 *   onServerStopping()          → close service, release DB connections
 *   reloadConfig()              → invoked by /aidirector reload, atomic swap
 */
object DirectorLifecycle {

    private val configServiceRef = AtomicReference<ConfigService?>(null)
    private val serviceRef = AtomicReference<DirectorService?>(null)
    private val saveDirRef = AtomicReference<Path?>(null)

    /**
     * Initializes the director for a freshly-started server. Must be called
     * after the platform module has installed [dev.aidirector.actions.ServerActionsHolder]
     * and [SensorsHolder]. Safe to call only once per server lifetime.
     *
     * Returns false if config is not initialized yet (default config written —
     * operator must edit and reload). Returns true on full success.
     */
    fun onServerStarting(configDir: Path, saveDir: Path): Boolean {
        require(serviceRef.get() == null) { "Director already started — call onServerStopping first" }

        val configService = ConfigService(configDir)
        configServiceRef.set(configService)
        saveDirRef.set(saveDir)

        try {
            configService.load()
        } catch (e: ConfigNotInitializedException) {
            AIDirector.log.warn(
                "AI Director config not initialized: ${e.message} — director will stay disabled until /aidirector reload.",
            )
            return false
        }

        val service = DirectorService.create(
            configService = configService,
            sensors = SensorsHolder.require(),
            saveDir = saveDir,
        )
        serviceRef.set(service)
        service.start()
        return true
    }

    fun onServerStopping() {
        val service = serviceRef.getAndSet(null) ?: return
        AIDirector.log.info("Stopping AI Director")
        try {
            service.close()
        } catch (e: Exception) {
            AIDirector.log.warn("Error stopping AI Director: ${e.message}")
        }
        configServiceRef.set(null)
        saveDirRef.set(null)
    }

    /**
     * Reloads config and rebuilds the running service. Used by
     * /aidirector reload and by first-run flow after the operator edits the
     * default file.
     */
    fun reloadConfig() {
        val saveDir = saveDirRef.get()
            ?: throw IllegalStateException("Director was never started — no save dir known")
        val configService = configServiceRef.get()
            ?: throw IllegalStateException("Director was never started — no config service")

        configService.load() // may throw ConfigNotInitializedException / ConfigParseException

        val existing = serviceRef.getAndSet(null)
        existing?.close()

        val rebuilt = DirectorService.create(
            configService = configService,
            sensors = SensorsHolder.require(),
            saveDir = saveDir,
        )
        serviceRef.set(rebuilt)
        rebuilt.start()
    }

    fun serviceOrNull(): DirectorService? = serviceRef.get()
}
