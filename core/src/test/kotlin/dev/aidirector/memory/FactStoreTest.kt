package dev.aidirector.memory

import dev.aidirector.support.TestClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class FactStoreTest {

    @Test
    fun `add and retrieve by kind`(@TempDir dir: Path) {
        Memory.open(dir.resolve("f.db"), TestClock()).use { mem ->
            mem.facts.add(FactKinds.LORE, "the well is haunted", importance = 4)
            mem.facts.add(FactKinds.LORE, "the merchant lies about prices", importance = 2)
            mem.facts.add(FactKinds.NPC_PERSONALITY, "elder speaks in riddles", importance = 3)
            val lore = mem.facts.byKind(FactKinds.LORE, 10)
            assertThat(lore).hasSize(2)
            assertThat(lore.map { it.content }).contains("the well is haunted", "the merchant lies about prices")
            assertThat(lore.first().importance).isEqualTo(4) // sorted by importance DESC
        }
    }

    @Test
    fun `topKByCosine returns most similar facts first`(@TempDir dir: Path) {
        Memory.open(dir.resolve("c.db"), TestClock()).use { mem ->
            val a = floatArrayOf(1f, 0f, 0f)
            val b = floatArrayOf(0f, 1f, 0f)
            val c = floatArrayOf(0.9f, 0.1f, 0f)
            mem.facts.add(FactKinds.LORE, "a", embedding = a)
            mem.facts.add(FactKinds.LORE, "b", embedding = b)
            mem.facts.add(FactKinds.LORE, "c", embedding = c)
            val results = mem.facts.topKByCosine(floatArrayOf(1f, 0f, 0f), k = 3)
            assertThat(results).hasSize(3)
            assertThat(results[0].fact.content).isEqualTo("a")
            assertThat(results[1].fact.content).isEqualTo("c")
            assertThat(results[2].fact.content).isEqualTo("b")
            assertThat(results[0].similarity).isGreaterThan(results[1].similarity)
        }
    }

    @Test
    fun `topKByCosine skips facts with mismatched dim`(@TempDir dir: Path) {
        Memory.open(dir.resolve("d.db"), TestClock()).use { mem ->
            mem.facts.add(FactKinds.LORE, "a", embedding = floatArrayOf(1f, 0f, 0f))
            mem.facts.add(FactKinds.LORE, "b", embedding = floatArrayOf(1f, 0f))
            val results = mem.facts.topKByCosine(floatArrayOf(1f, 0f, 0f), k = 5)
            assertThat(results).hasSize(1)
            assertThat(results[0].fact.content).isEqualTo("a")
        }
    }

    @Test
    fun `setEmbedding fills in lazily`(@TempDir dir: Path) {
        Memory.open(dir.resolve("l.db"), TestClock()).use { mem ->
            val id = mem.facts.add(FactKinds.LORE, "later", embedding = null)
            assertThat(mem.facts.lackingEmbedding(10)).hasSize(1)
            mem.facts.setEmbedding(id, floatArrayOf(1f, 0f))
            assertThat(mem.facts.lackingEmbedding(10)).isEmpty()
        }
    }

    @Test
    fun `prune removes low-importance old rows but keeps high-importance`(@TempDir dir: Path) {
        val clock = TestClock(0)
        Memory.open(dir.resolve("p.db"), clock).use { mem ->
            mem.facts.add(FactKinds.LORE, "old trivia", importance = 1)
            mem.facts.add(FactKinds.LORE, "old canon", importance = 5)
            clock.setTo(60L * 86_400_000)
            val deleted = mem.facts.prune(retentionDays = 30)
            assertThat(deleted).isEqualTo(1)
            assertThat(mem.facts.all(10).map { it.content }).containsExactly("old canon")
        }
    }
}
