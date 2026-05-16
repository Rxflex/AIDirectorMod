package dev.aidirector.actions

/**
 * Single global holder for the platform-bound [ServerActions]. Fabric and
 * NeoForge entry points call [install] in their init phase. Common code reads
 * the current implementation via [require].
 */
object ServerActionsHolder {
    @Volatile
    private var instance: ServerActions? = null

    fun install(actions: ServerActions) {
        instance = actions
    }

    fun require(): ServerActions = instance
        ?: throw IllegalStateException(
            "ServerActions not installed. The platform module's onInitialize/Mod constructor must call ServerActionsHolder.install().",
        )

    /** Test-only — clears the holder between tests. */
    internal fun reset() {
        instance = null
    }
}
