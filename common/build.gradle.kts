// MC-bound shared module. Only contains code that imports net.minecraft.* or
// Architectury events. Everything else lives in :core, which this module
// depends on. fabric / neoforge / forge bring this in to ship a working mod.

architectury {
    common("fabric", "neoforge")
}

loom {
    accessWidenerPath = file("src/main/resources/aidirector.accesswidener")
}

val minecraft_version: String by rootProject
val parchment_version = rootProject.findProperty("parchment_version") as String?
val architectury_version: String by rootProject
val kotlinx_coroutines_version: String by rootProject
val kotlinx_serialization_version: String by rootProject
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

    modImplementation("dev.architectury:architectury:$architectury_version")

    // Pull in the pure logic.
    api(project(":core"))

    // Kotlin stdlib + coroutines + serialization come from the platform modules
    // (FLK / KFF) at runtime. core declares them as api() so this module
    // compiles cleanly; the platform JARs handle runtime delivery.
    compileOnly(kotlin("stdlib-jdk8"))
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinx_coroutines_version")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinx_serialization_version")
    compileOnly("com.squareup.okhttp3:okhttp:$okhttp_version")
    compileOnly("org.xerial:sqlite-jdbc:$sqlite_jdbc_version")
    compileOnly("org.tomlj:tomlj:$tomlj_version")
}
