package dev.aidirector.platform

import dev.aidirector.AIDirector
import dev.aidirector.memory.FactKinds
import dev.aidirector.memory.Memory
import dev.aidirector.memory.WorldStateKeys
import dev.aidirector.rag.Rag
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation

/**
 * Scans the live registries for non-vanilla content (items, mobs, blocks,
 * effects, particles) and ingests a single summarised fact into RAG so the
 * director "knows" what mods are installed and can use their content.
 *
 * Idempotent: a marker is stored in [WorldStateStore] so the dump runs at
 * most once per world. Re-run when mods are added by clearing the key.
 */
object RegistryDumper {

    private const val MARKER_KEY = "registry.dumped_namespace_set"

    /** Per-namespace sample size when listing modded content. */
    private const val SAMPLE_PER_NS = 12

    /** Items / mobs / etc. counted but not listed when there are more than this. */
    private const val MAX_NAMESPACES_LISTED = 24

    suspend fun dumpAndIngestIfNeeded(memory: Memory, rag: Rag) {
        val signature = buildSignature()
        val previous = memory.worldState.get(MARKER_KEY)
        if (previous == signature) {
            AIDirector.log.info("Registry signature unchanged — skipping mod registry RAG dump")
            return
        }

        val summary = summarize()
        if (summary.isBlank()) {
            AIDirector.log.info("No modded content detected — only vanilla registries present")
            memory.worldState.put(MARKER_KEY, signature)
            return
        }

        rag.ingestAndEmbed(FactKinds.WORLD_EVENT, summary, importance = 5)
        memory.worldState.put(MARKER_KEY, signature)
        AIDirector.log.info("Ingested mod registry summary into RAG ({} chars)", summary.length)
    }

    /**
     * Cheap fingerprint of the modded namespaces present in each registry.
     * Changing the modpack changes the signature → re-dump.
     */
    private fun buildSignature(): String {
        val parts = listOf(
            "items" to BuiltInRegistries.ITEM,
            "entities" to BuiltInRegistries.ENTITY_TYPE,
            "blocks" to BuiltInRegistries.BLOCK,
            "effects" to BuiltInRegistries.MOB_EFFECT,
            "particles" to BuiltInRegistries.PARTICLE_TYPE,
            "sounds" to BuiltInRegistries.SOUND_EVENT,
        ).joinToString("|") { (kind, reg) ->
            val nss = nonVanillaNamespaces(reg)
            "$kind=${nss.size}:${nss.sorted().joinToString(",")}"
        }
        return parts
    }

    private fun nonVanillaNamespaces(registry: Registry<*>): Set<String> {
        val ns = mutableSetOf<String>()
        for (rl in registry.keySet()) {
            if (rl.namespace != "minecraft") ns += rl.namespace
        }
        return ns
    }

    private fun summarize(): String = buildString {
        appendCategory("items", BuiltInRegistries.ITEM)
        appendCategory("entity_types", BuiltInRegistries.ENTITY_TYPE)
        appendCategory("blocks", BuiltInRegistries.BLOCK)
        appendCategory("effects", BuiltInRegistries.MOB_EFFECT)
        appendCategory("particles", BuiltInRegistries.PARTICLE_TYPE)
        appendCategory("sounds", BuiltInRegistries.SOUND_EVENT)
    }.trim()

    private fun StringBuilder.appendCategory(label: String, registry: Registry<*>) {
        val modded: Map<String, List<ResourceLocation>> = registry.keySet()
            .filter { it.namespace != "minecraft" }
            .groupBy { it.namespace }
        if (modded.isEmpty()) return

        appendLine("MODDED $label (${modded.values.sumOf { it.size }} total across ${modded.size} mod(s)):")
        for ((ns, ids) in modded.entries.sortedByDescending { it.value.size }.take(MAX_NAMESPACES_LISTED)) {
            val sample = ids.take(SAMPLE_PER_NS).joinToString(", ") { it.toString() }
            val more = if (ids.size > SAMPLE_PER_NS) " … (+${ids.size - SAMPLE_PER_NS} more)" else ""
            appendLine("  - $ns (${ids.size}): $sample$more")
        }
        appendLine()
    }
}
