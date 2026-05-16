package dev.aidirector.guardrails

import dev.aidirector.support.TestClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class RateLimiterTest {

    private val player = UUID.randomUUID()

    @Test
    fun `allows up to the cap then denies`() {
        val clock = TestClock()
        val limiter = RateLimiter(BucketLimits(mapOf("a" to Limit(3, 60_000))), clock)

        assertThat(limiter.tryAcquire(player, "a")).isTrue
        assertThat(limiter.tryAcquire(player, "a")).isTrue
        assertThat(limiter.tryAcquire(player, "a")).isTrue
        assertThat(limiter.tryAcquire(player, "a")).isFalse
        assertThat(limiter.currentCount(player, "a")).isEqualTo(3)
    }

    @Test
    fun `does not record on denial`() {
        val clock = TestClock()
        val limiter = RateLimiter(BucketLimits(mapOf("a" to Limit(1, 60_000))), clock)

        assertThat(limiter.tryAcquire(player, "a")).isTrue
        assertThat(limiter.tryAcquire(player, "a")).isFalse
        assertThat(limiter.tryAcquire(player, "a")).isFalse
        assertThat(limiter.currentCount(player, "a")).isEqualTo(1)
    }

    @Test
    fun `slides — old entries drop out after the window`() {
        val clock = TestClock()
        val limiter = RateLimiter(BucketLimits(mapOf("a" to Limit(2, 60_000))), clock)
        assertThat(limiter.tryAcquire(player, "a")).isTrue
        clock.advance(30_000)
        assertThat(limiter.tryAcquire(player, "a")).isTrue
        assertThat(limiter.tryAcquire(player, "a")).isFalse
        clock.advance(31_000) // first entry now outside the 60s window
        assertThat(limiter.tryAcquire(player, "a")).isTrue
    }

    @Test
    fun `tracks players independently`() {
        val clock = TestClock()
        val limiter = RateLimiter(BucketLimits(mapOf("a" to Limit(1, 60_000))), clock)
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        assertThat(limiter.tryAcquire(a, "a")).isTrue
        assertThat(limiter.tryAcquire(b, "a")).isTrue
        assertThat(limiter.tryAcquire(a, "a")).isFalse
    }

    @Test
    fun `unknown bucket throws`() {
        val limiter = RateLimiter(BucketLimits(emptyMap()), TestClock())
        org.assertj.core.api.Assertions.assertThatThrownBy {
            limiter.tryAcquire(player, "nope")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
