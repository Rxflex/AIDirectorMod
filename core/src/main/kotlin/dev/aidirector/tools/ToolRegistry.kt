package dev.aidirector.tools

import dev.aidirector.llm.ToolSpec

/** Holds all enabled tools and exposes them in the format the LLM expects. */
class ToolRegistry(tools: List<Tool<*>>) {

    private val byName: Map<String, Tool<*>> = tools.associateBy { it.name }

    init {
        val pattern = Regex("^[a-z][a-z0-9_]{0,63}$")
        tools.forEach {
            require(pattern.matches(it.name)) {
                "Tool name '${it.name}' does not match required pattern $pattern"
            }
        }
        require(byName.size == tools.size) {
            val dups = tools.groupBy { it.name }.filter { it.value.size > 1 }.keys
            "Duplicate tool names: $dups"
        }
    }

    val all: Collection<Tool<*>> get() = byName.values

    operator fun get(name: String): Tool<*>? = byName[name]

    fun specs(): List<ToolSpec> = byName.values.map { it.spec() }
}
