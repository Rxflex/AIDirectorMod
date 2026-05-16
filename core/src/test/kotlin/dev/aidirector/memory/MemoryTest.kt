package dev.aidirector.memory

import dev.aidirector.support.TestClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class MemoryTest {

    @Test
    fun `records and reads events newest-first`(@TempDir dir: Path) {
        val clock = TestClock()
        Memory.open(dir.resolve("test.db"), clock).use { memory ->
            val player = UUID.randomUUID()
            memory.recordEvent(player, EventKind.PLAYER_JOIN, """{"name":"a"}""")
            clock.advance(1_000)
            memory.recordEvent(player, EventKind.PLAYER_DEATH, """{"source":"fall"}""")
            clock.advance(1_000)
            memory.recordEvent(player, EventKind.TOOL_INVOKED, """{"tool":"x"}""")

            val rows = memory.recentEvents(player, 10)
            assertThat(rows).hasSize(3)
            assertThat(rows.map { it.kind }).containsExactly(
                EventKind.TOOL_INVOKED,
                EventKind.PLAYER_DEATH,
                EventKind.PLAYER_JOIN,
            )
        }
    }

    @Test
    fun `filters by kind`(@TempDir dir: Path) {
        val clock = TestClock()
        Memory.open(dir.resolve("k.db"), clock).use { memory ->
            val player = UUID.randomUUID()
            memory.recordEvent(player, EventKind.PLAYER_JOIN, "{}")
            memory.recordEvent(player, EventKind.PLAYER_DEATH, "{}")
            memory.recordEvent(player, EventKind.TOOL_INVOKED, "{}")
            val deaths = memory.recentEventsByKind(player, listOf(EventKind.PLAYER_DEATH), 10)
            assertThat(deaths).hasSize(1)
            assertThat(deaths[0].kind).isEqualTo(EventKind.PLAYER_DEATH)
        }
    }

    @Test
    fun `isolates per-player`(@TempDir dir: Path) {
        Memory.open(dir.resolve("iso.db")).use { memory ->
            val a = UUID.randomUUID()
            val b = UUID.randomUUID()
            memory.recordEvent(a, "x", "{}")
            memory.recordEvent(b, "x", "{}")
            memory.recordEvent(b, "x", "{}")
            assertThat(memory.recentEvents(a, 10)).hasSize(1)
            assertThat(memory.recentEvents(b, 10)).hasSize(2)
        }
    }

    @Test
    fun `prune drops old rows and trims excess`(@TempDir dir: Path) {
        val clock = TestClock(0)
        Memory.open(dir.resolve("p.db"), clock).use { memory ->
            val player = UUID.randomUUID()
            // 10 ancient rows + 5 fresh rows
            repeat(10) { memory.recordEvent(player, "old", "{}") }
            clock.setTo(100L * 86_400_000) // jump 100 days ahead
            repeat(5) { memory.recordEvent(player, "new", "{}") }
            val deleted = memory.prune(retentionDays = 30, maxRows = 1_000)
            assertThat(deleted).isEqualTo(10)
            assertThat(memory.recentEvents(player, 100)).hasSize(5)
        }
    }

    @Test
    fun `rejects oversized payloads`(@TempDir dir: Path) {
        Memory.open(dir.resolve("big.db")).use { memory ->
            val huge = "x".repeat(17 * 1024)
            assertThatThrownBy { memory.recordEvent(UUID.randomUUID(), "k", huge) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
