package dev.aidirector.util

/** Wall-clock abstraction so tests can drive time deterministically. */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}
