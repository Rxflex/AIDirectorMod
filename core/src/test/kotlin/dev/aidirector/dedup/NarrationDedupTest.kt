package dev.aidirector.dedup

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class NarrationDedupTest {

    private val player = UUID.randomUUID()

    @Test
    fun `first narration is fresh`() {
        val d = NarrationDedup()
        val r = d.checkAndStore(player, "A cold wind blows through the trees.", 0L)
        assertThat(r).isEqualTo(NarrationDedup.Result.Fresh)
    }

    @Test
    fun `identical re-send is refused`() {
        val d = NarrationDedup()
        d.checkAndStore(player, "A cold wind blows through the trees.", 0L)
        val r = d.checkAndStore(player, "A cold wind blows through the trees.", 1_000L)
        assertThat(r).isInstanceOf(NarrationDedup.Result.TooSimilar::class.java)
    }

    @Test
    fun `paraphrase is refused`() {
        val d = NarrationDedup()
        d.checkAndStore(player, "A cold wind blows through the trees.", 0L)
        val r = d.checkAndStore(player, "Through the trees a cold wind blows.", 1_000L)
        assertThat(r).isInstanceOf(NarrationDedup.Result.TooSimilar::class.java)
    }

    @Test
    fun `wholly different narration is fresh`() {
        val d = NarrationDedup()
        d.checkAndStore(player, "A cold wind blows through the trees.", 0L)
        val r = d.checkAndStore(player, "Footsteps echo from the old mine shaft.", 1_000L)
        assertThat(r).isEqualTo(NarrationDedup.Result.Fresh)
    }

    @Test
    fun `Russian paraphrase is refused`() {
        val d = NarrationDedup()
        d.checkAndStore(player, "Тихий шёпот ветра скользит сквозь кроны.", 0L)
        val r = d.checkAndStore(player, "Шёпот ветра тихо скользит сквозь кроны деревьев.", 1_000L)
        assertThat(r).isInstanceOf(NarrationDedup.Result.TooSimilar::class.java)
    }

    @Test
    fun `entry expires after maxAgeMs`() {
        val d = NarrationDedup(maxAgeMs = 1_000L)
        d.checkAndStore(player, "A cold wind blows through the trees.", 0L)
        val r = d.checkAndStore(player, "A cold wind blows through the trees.", 5_000L)
        assertThat(r).isEqualTo(NarrationDedup.Result.Fresh)
    }

    @Test
    fun `different players don't collide`() {
        val d = NarrationDedup()
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        d.checkAndStore(a, "Echoing footsteps in the stone hall.", 0L)
        val r = d.checkAndStore(b, "Echoing footsteps in the stone hall.", 0L)
        assertThat(r).isEqualTo(NarrationDedup.Result.Fresh)
    }
}
