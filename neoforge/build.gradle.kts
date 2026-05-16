plugins {
    id("com.gradleup.shadow") version "8.3.5"
}

architectury {
    platformSetupLoomIde()
    neoForge {
        platformPackage = "forge"
    }
}

loom {
    accessWidenerPath.set(project(":common").loom.accessWidenerPath)
}

val common: Configuration by configurations.creating
val shadowCommon: Configuration by configurations.creating
val developmentNeoForge: Configuration = configurations.getByName("developmentNeoForge")

configurations {
    compileClasspath.get().extendsFrom(common)
    runtimeClasspath.get().extendsFrom(common)
    developmentNeoForge.extendsFrom(common)
}

val minecraft_version: String by rootProject
val parchment_version = rootProject.findProperty("parchment_version") as String?
val neoforge_version: String by rootProject
val kotlin_for_forge_neoforge_version: String by rootProject
val architectury_version: String by rootProject
val okhttp_version: String by rootProject
val sqlite_jdbc_version: String by rootProject
val tomlj_version: String by rootProject

dependencies {
    minecraft("com.mojang:minecraft:$minecraft_version")
    mappings(loom.layered {
        officialMojangMappings()
        parchment_version?.takeIf { it.isNotBlank() }?.let { pv ->
            parchment("org.parchmentmc.data:parchment-$minecraft_version:$pv@zip")
        }
    })

    "neoForge"("net.neoforged:neoforge:$neoforge_version")
    implementation("thedarkcolour:kotlinforforge-neoforge:$kotlin_for_forge_neoforge_version")
    modImplementation("dev.architectury:architectury-neoforge:$architectury_version")

    common(project(":common", configuration = "namedElements")) { isTransitive = false }
    shadowCommon(project(":common", configuration = "transformProductionNeoForge")) { isTransitive = false }

    // Pure logic module — neoforge needs it directly for compile + at runtime.
    // We shade it into the final JAR so the loader picks it up.
    common(project(":core")) { isTransitive = false }
    shadowCommon(project(":core")) { isTransitive = false }

    include(implementation("com.squareup.okhttp3:okhttp:$okhttp_version")!!)
    include(implementation("com.squareup.okio:okio:3.9.1")!!)
    include(implementation("com.squareup.okio:okio-jvm:3.9.1")!!)
    include(implementation("org.xerial:sqlite-jdbc:$sqlite_jdbc_version")!!)
    include(implementation("org.tomlj:tomlj:$tomlj_version")!!)
    // antlr4-runtime is NOT bundled. NeoForge's night-config already brings
    // antlr in as an Automatic-Module-Name'd JPMS module; shipping our own
    // copy would cause "more than one module named org.antlr.antlr4.runtime".
    // tomlj 1.1.1 was built against antlr 4.11 but runs cleanly against 4.13
    // (the org.antlr.v4.runtime.* API surface tomlj touches is stable).
    implementation("org.antlr:antlr4-runtime:4.11.1")
}

tasks.processResources {
    val mod_id: String by rootProject
    val mod_name: String by rootProject
    val mod_version: String by rootProject
    val mod_description: String by rootProject

    inputs.property("mod_id", mod_id)
    inputs.property("mod_name", mod_name)
    inputs.property("mod_version", mod_version)
    inputs.property("mod_description", mod_description)
    inputs.property("neoforge_version", neoforge_version)
    inputs.property("minecraft_version", minecraft_version)

    filesMatching("META-INF/neoforge.mods.toml") {
        expand(
            "mod_id" to mod_id,
            "mod_name" to mod_name,
            "mod_version" to mod_version,
            "mod_description" to mod_description,
            "neoforge_version" to neoforge_version,
            "minecraft_version" to minecraft_version,
        )
    }
}

tasks.shadowJar {
    exclude("fabric.mod.json")
    exclude("architectury.common.json")
    configurations = listOf(shadowCommon)
    archiveClassifier.set("dev-shadow")
}

tasks.remapJar {
    inputFile.set(tasks.shadowJar.get().archiveFile)
    dependsOn(tasks.shadowJar)
    archiveClassifier.set(null as String?)
}

tasks.jar {
    archiveClassifier.set("dev")
}

tasks.sourcesJar {
    val commonSources = project(":common").tasks.named<Jar>("sourcesJar")
    dependsOn(commonSources)
    from(commonSources.get().archiveFile.map { zipTree(it) })
}

components.named<AdhocComponentWithVariants>("java") {
    withVariantsFromConfiguration(configurations["shadowRuntimeElements"]) { skip() }
}
