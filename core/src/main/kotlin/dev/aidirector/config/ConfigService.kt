package dev.aidirector.config

import dev.aidirector.AIDirector
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe holder for the active [DirectorConfig]. Read by the director loop
 * and tool executors via [current]; written by the loader on startup and by the
 * /aidirector reload command.
 */
class ConfigService(private val configDir: Path) {
    private val ref = AtomicReference<DirectorConfig?>(null)

    val current: DirectorConfig
        get() = ref.get() ?: throw IllegalStateException("ConfigService not initialized — call load() first")

    val isLoaded: Boolean get() = ref.get() != null

    /**
     * Loads or reloads the config. Returns the new config on success.
     * On [ConfigNotInitializedException], rethrows so the caller can show the
     * operator the path. On any other failure, leaves the previous config in
     * place and rethrows.
     */
    @Throws(ConfigParseException::class, ConfigNotInitializedException::class)
    fun load(): DirectorConfig {
        val loaded = ConfigLoader.load(configDir)
        ref.set(loaded)
        AIDirector.log.info(
            "Loaded AI Director config: model=${loaded.llm.model} " +
                "tick=${loaded.director.tickIntervalMs}ms enabled=${loaded.director.enabled}",
        )
        return loaded
    }
}
