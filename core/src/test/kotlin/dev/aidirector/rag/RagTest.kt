package dev.aidirector.rag

import dev.aidirector.memory.FactKinds
import dev.aidirector.memory.Memory
import dev.aidirector.support.FakeEmbedder
import dev.aidirector.support.TestClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class RagTest {

    @Test
    fun `ingestAndEmbed makes the fact retrievable`(@TempDir dir: Path) = runTest {
        Memory.open(dir.resolve("rag.db"), TestClock()).use { mem ->
            val rag = Rag(mem.facts, FakeEmbedder(), model = "fake", scope = scope())
            rag.ingestAndEmbed(FactKinds.LORE, "the haunted well east of spawn", importance = 4)
            val results = rag.retrieve(query = "haunted well", k = 3, minSimilarity = 0.0)
            assertThat(results).isNotEmpty
            assertThat(results.first().fact.content).contains("haunted well")
        }
    }

    @Test
    fun `retrieve returns empty when no facts have embeddings`(@TempDir dir: Path) = runTest {
        Memory.open(dir.resolve("rag2.db"), TestClock()).use { mem ->
            val rag = Rag(mem.facts, FakeEmbedder(), model = "fake", scope = scope())
            val results = rag.retrieve("anything", k = 3)
            assertThat(results).isEmpty()
        }
    }

    @Test
    fun `backfillEmbeddings fills in pending facts`(@TempDir dir: Path) = runBlocking {
        Memory.open(dir.resolve("rag3.db"), TestClock()).use { mem ->
            val rag = Rag(mem.facts, FakeEmbedder(), model = "fake", scope = scope())
            mem.facts.add(FactKinds.LORE, "fact A", embedding = null)
            mem.facts.add(FactKinds.LORE, "fact B", embedding = null)
            assertThat(mem.facts.lackingEmbedding(10)).hasSize(2)
            val count = rag.backfillEmbeddings()
            assertThat(count).isEqualTo(2)
            assertThat(mem.facts.lackingEmbedding(10)).isEmpty()
        }
    }

    private fun scope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
}
