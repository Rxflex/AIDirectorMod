package dev.aidirector.phantom

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A fake "player" the director has placed in the server's player list. */
data class Phantom(
    val name: String,
    val uuid: UUID,
    val joinedAtMs: Long,
)

/**
 * In-memory registry of the phantom players currently shown in the tab list.
 *
 * Session-scoped on purpose: a tab-list entry is client state that does not
 * survive a server restart, so persisting it would only ever describe ghosts
 * that no longer exist. The director reads [all] to know which phantoms are
 * live, and the phantom tools keep it in sync with the packets they send.
 */
class PhantomRegistry {

    private val active = ConcurrentHashMap<String, Phantom>()

    private fun key(name: String): String = name.trim().lowercase()

    /** Registers a phantom. Returns false if one with that name is already live. */
    fun add(phantom: Phantom): Boolean =
        active.putIfAbsent(key(phantom.name), phantom) == null

    fun remove(name: String): Phantom? = active.remove(key(name))

    fun get(name: String): Phantom? = active[key(name)]

    fun isActive(name: String): Boolean = active.containsKey(key(name))

    fun isFull(): Boolean = active.size >= MAX_ACTIVE

    /** Live phantoms, oldest first. */
    fun all(): List<Phantom> = active.values.sortedBy { it.joinedAtMs }

    fun clear() = active.clear()

    companion object {
        /** A tab list crowded with phantoms stops being eerie — keep it small. */
        const val MAX_ACTIVE = 4
    }
}
