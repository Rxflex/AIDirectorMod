pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.architectury.dev/")
        maven("https://maven.neoforged.net/releases/")
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "AIDirector"

include("core", "common", "fabric", "neoforge", "paper")
// `forge` subproject exists on disk but is excluded — Forge 1.21.1 plus
// Architectury Loom currently has DSL friction that needs separate debugging.

// ---------------------------------------------------------------------------
// Multi-version build matrix.
//
// AI Director's logic lives in :core (pure Kotlin, version-agnostic) and the
// MC-bound code in :common targets a small, stable API surface that is shared
// across adjacent 1.21.x releases. Building for another version is therefore
// only a matter of swapping dependency coordinates — no source fork.
//
// Pick the target with `-Pmc=<version>`, e.g. `gradle build -Pmc=1.21.4`.
// The default (no flag) is 1.21.1, so existing builds are byte-for-byte
// unaffected. Each row pins the coordinates that change between releases;
// everything else (loom, KFF/FLK, libraries) is version-independent.
// `parchment` is a cosmetic mappings layer and may be left blank — the build
// falls back to plain Mojang mappings when it is empty.
// ---------------------------------------------------------------------------
val mcMatrix: Map<String, Map<String, String>> = mapOf(
    "1.21.1" to mapOf(
        "minecraft_version" to "1.21.1",
        "parchment_version" to "2024.11.17",
        "fabric_api_version" to "0.115.0+1.21.1",
        "neoforge_version" to "21.1.92",
        "architectury_version" to "13.0.8",
    ),
    "1.21.3" to mapOf(
        "minecraft_version" to "1.21.3",
        // No stable Parchment release for 1.21.3 (only nightlies, which Loom
        // rejects as "changing versions") — fall back to plain Mojang mappings.
        "parchment_version" to "",
        "fabric_api_version" to "0.112.1+1.21.3",
        "neoforge_version" to "21.3.96",
        "architectury_version" to "14.0.4",
    ),
    "1.21.4" to mapOf(
        "minecraft_version" to "1.21.4",
        "parchment_version" to "",
        "fabric_api_version" to "0.119.3+1.21.4",
        "neoforge_version" to "21.4.157",
        "architectury_version" to "15.0.3",
    ),
)

val selectedMc: String = (startParameter.projectProperties["mc"]
    ?: providers.gradleProperty("mc").orNull
    ?: "1.21.1").trim()

val coords: Map<String, String> = mcMatrix[selectedMc]
    ?: error("Unsupported -Pmc=$selectedMc. Supported targets: ${mcMatrix.keys.joinToString()}")

println("[AI Director] build target: Minecraft $selectedMc")

// Inject the resolved coordinates onto the root project before it is
// evaluated, so every subproject's `by rootProject` delegate resolves them.
gradle.rootProject {
    extra.set("aidirector_target_mc", selectedMc)
    coords.forEach { (key, value) -> extra.set(key, value) }
}
