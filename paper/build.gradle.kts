// Paper/Spigot plugin module. NO Minecraft mojmap, NO Architectury, NO Loom.
// Just pure Kotlin + Bukkit/Paper API + the shared :core logic.

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.gradleup.shadow") version "8.3.5"
}

val mod_id: String by rootProject
val mod_group: String by rootProject
val mod_version: String by rootProject

group = mod_group
version = mod_version
base.archivesName.set("$mod_id-paper")

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.63-stable")
    implementation(project(":core"))
}

tasks.processResources {
    val props = mapOf(
        "mod_id" to mod_id,
        "mod_version" to mod_version,
    )
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.shadowJar {
    archiveClassifier.set("")
    // Shade only what's needed; do NOT touch kotlin-stdlib (Paper bundles its own).
    // Relocate to avoid clashes with other plugins on the same server.
    relocate("okhttp3", "dev.aidirector.shaded.okhttp3")
    relocate("okio", "dev.aidirector.shaded.okio")
    relocate("org.tomlj", "dev.aidirector.shaded.tomlj")
    relocate("org.antlr.v4.runtime", "dev.aidirector.shaded.antlr.v4.runtime")
    relocate("org.sqlite", "dev.aidirector.shaded.sqlite")
    relocate("kotlinx.serialization", "dev.aidirector.shaded.kotlinx.serialization")
    relocate("kotlinx.coroutines", "dev.aidirector.shaded.kotlinx.coroutines")

    mergeServiceFiles()

    minimize {
        // Don't minimize SQLite native loader — it inspects classpath at runtime.
        exclude(dependency("org.xerial:sqlite-jdbc:.*"))
        // Same for SLF4J binding discovery.
        exclude(dependency("org.slf4j:.*"))
    }
}

tasks.build { dependsOn(tasks.shadowJar) }

tasks.jar { archiveClassifier.set("dev") }
