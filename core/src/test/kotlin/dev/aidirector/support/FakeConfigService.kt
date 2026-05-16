package dev.aidirector.support

import dev.aidirector.config.ConfigService
import dev.aidirector.config.DirectorConfig
import java.lang.reflect.Field

/**
 * Bypasses [ConfigService.load] (which reads from disk) by setting the
 * internal AtomicReference reflectively. Keeps production code free of
 * test-only hooks.
 */
object FakeConfigService {
    fun of(cfg: DirectorConfig): ConfigService {
        val service = ConfigService(java.nio.file.Path.of("ignored-in-test"))
        val field: Field = ConfigService::class.java.getDeclaredField("ref")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val ref = field.get(service) as java.util.concurrent.atomic.AtomicReference<DirectorConfig?>
        ref.set(cfg)
        return service
    }
}
