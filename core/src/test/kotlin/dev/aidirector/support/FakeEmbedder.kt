package dev.aidirector.support

import dev.aidirector.llm.Embedder
import kotlin.math.absoluteValue

/**
 * Deterministic embedder for tests. Maps each input string to a fixed-size
 * vector derived from a content-addressed hash, with one boosted dimension
 * per token so cosine distance preserves token overlap meaningfully.
 */
class FakeEmbedder(private val dim: Int = 32) : Embedder {

    override suspend fun embed(model: String, inputs: List<String>, inputType: String): List<FloatArray> =
        inputs.map { vectorFor(it) }

    private fun vectorFor(text: String): FloatArray {
        val v = FloatArray(dim)
        for (token in text.lowercase().split(Regex("\\s+|[^\\p{L}\\p{N}]")).filter { it.isNotEmpty() }) {
            val slot = (token.hashCode().absoluteValue) % dim
            v[slot] = v[slot] + 1.0f
        }
        // Avoid all-zero vector (otherwise norm = 0).
        if (v.all { it == 0f }) v[0] = 1f
        return v
    }
}
