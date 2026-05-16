package dev.aidirector.support

import dev.aidirector.util.Clock

/** Deterministic clock for tests. Advance time via [advance]. */
class TestClock(initialMs: Long = 1_700_000_000_000L) : Clock {
    @Volatile
    private var now: Long = initialMs

    override fun nowMs(): Long = now

    fun advance(ms: Long) {
        require(ms >= 0) { "Cannot advance backward" }
        now += ms
    }

    fun setTo(ms: Long) {
        now = ms
    }
}
