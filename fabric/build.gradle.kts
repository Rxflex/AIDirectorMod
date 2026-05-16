plugins {
    id("com.gradleup.shadow") version "8.3.5"
}

architectury {
    platformSetupLoomIde()
    fabric()
}

loom {
    accessWidenerPath.set(project(":common").loom.accessWidenerPath)
}

val common: Configuration by configurations.creating
val shadowCommon: Configuration by configurations.creating
val developmentFabric: Configuration = configurations.getByName("developmentFabric")

configurations {
    compileClasspath.get().extendsFrom(common)
    runtimeClasspath.get().extendsFrom(common)
    developmentFabric.extendsFrom(common)
}

val minecraft_version: String by rootProject
val parchment_version = rootProject.findProperty("parchment_version") as String?
val fabric_loader_version: String by rootProject
val fabric_api_version: String by rootProject
val fabric_kotlin_version: String by rootProject
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

    modImplementation("net.fabricmc:fabric-loader:$fabric_loader_version")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabric_api_version")
    modImplementation("net.fabricmc:fabric-language-kotlin:$fabric_kotlin_version")
    modImplementation("dev.architectury:architectury-fabric:$architectury_version")

    common(project(":common", configuration = "namedElements")) { isTransitive = false }
    shadowCommon(project(":common", configuration = "transformProductionFabric")) { isTransitive = false }

    // Pure logic module — :common is pulled non-transitively, so :core must be
    // wired in explicitly for both compilation and shading into the final JAR
    // (mirrors the :neoforge setup).
    common(project(":core")) { isTransitive = false }
    shadowCommon(project(":core")) { isTransitive = false }

    // Bundle external libs into the final JAR. Each include() emits a JiJ entry
    // so the loader exposes the lib at runtime without classpath conflicts.
    include(implementation("com.squareup.okhttp3:okhttp:$okhttp_version")!!)
    include(implementation("com.squareup.okio:okio:3.17.0")!!)
    include(implementation("com.squareup.okio:okio-jvm:3.17.0")!!)
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
    inputs.property("fabric_loader_version", fabric_loader_version)
    inputs.property("fabric_kotlin_version", fabric_kotlin_version)
    inputs.property("minecraft_version", minecraft_version)

    filesMatching("fabric.mod.json") {
        expand(
            "mod_id" to mod_id,
            "mod_name" to mod_name,
            "mod_version" to mod_version,
            "mod_description" to mod_description,
            "fabric_loader_version" to fabric_loader_version,
            "fabric_kotlin_version" to fabric_kotlin_version,
            "minecraft_version" to minecraft_version,
        )
    }
}

tasks.shadowJar {
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
