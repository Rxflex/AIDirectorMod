package dev.aidirector

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Mod-wide constants + the shared logger. Pure JVM — no Minecraft, no Bukkit.
 * Platform entry points (fabric / neoforge / paper) call their own bootstrap
 * code which sets up event wiring; this object just owns the identifiers.
 */
object AIDirector {
    const val MOD_ID = "aidirector"
    const val VERSION = "0.3.0"
    val log: Logger = LoggerFactory.getLogger("AIDirector")
}
