plugins {
    id("com.gradleup.shadow") version "8.3.5"
}

architectury {
    platformSetupLoomIde()
    forge()
}

loom {
    accessWidenerPath.set(project(":common").loom.accessWidenerPath)

    forge {
        convertAccessWideners.set(true)
        extraAccessWideners.add(loom.accessWidenerPath.get().asFile.name)
    }
}

val common: Configuration by configurations.creating
val shadowCommon: Configuration by configurations.creating
val developmentForge: Configuration = configurations.getByName("developmentForge")

configurations {
    compileClasspath.get().extendsFrom(common)
    runtimeClasspath.get().extendsFrom(common)
    developmentForge.extendsFrom(common)
}

val minecraft_version: String by rootProject
val parchment_version: String by rootProject
val forge_version: String by rootProject
val kotlin_for_forge_version: String by rootProject
val architectury_version: String by rootProject
val okhttp_version: String by rootProject
val sqlite_jdbc_version: String by rootProject
val tomlj_version: String by rootProject

dependencies {
    minecraft("com.mojang:minecraft:$minecraft_version")
    mappings(loom.layered {
        officialMojangMappings()
        parchment("org.parchmentmc.data:parchment-$minecraft_version:$parchment_version@zip")
    })

    "forge"("net.minecraftforge:forge:$minecraft_version-$forge_version")
    implementation("thedarkcolour:kotlinforforge:$kotlin_for_forge_version")
    modImplementation("dev.architectury:architectury-forge:$architectury_version")

    common(project(":common", configuration = "namedElements")) { isTransitive = false }
    shadowCommon(project(":common", configuration = "transformProductionForge")) { isTransitive = false }

    include(implementation("com.squareup.okhttp3:okhttp:$okhttp_version")!!)
    include(implementation("com.squareup.okio:okio:3.9.1")!!)
    include(implementation("com.squareup.okio:okio-jvm:3.9.1")!!)
    include(implementation("org.xerial:sqlite-jdbc:$sqlite_jdbc_version")!!)
    include(implementation("org.tomlj:tomlj:$tomlj_version")!!)
    include(implementation("org.antlr:antlr4-runtime:4.11.1")!!)
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
    inputs.property("forge_version", forge_version)
    inputs.property("minecraft_version", minecraft_version)

    filesMatching("META-INF/mods.toml") {
        expand(
            "mod_id" to mod_id,
            "mod_name" to mod_name,
            "mod_version" to mod_version,
            "mod_description" to mod_description,
            "forge_version" to forge_version,
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
