package dev.aidirector.llm

/** Abstraction over the embedding endpoint so tests can pass a deterministic stub. */
interface Embedder {
    suspend fun embed(model: String, inputs: List<String>, inputType: String = "passage"): List<FloatArray>
}
