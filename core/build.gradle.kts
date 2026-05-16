// Pure Kotlin/JVM module — no Minecraft, no Loom. Contains the LLM client,
// RAG, memory, agent loop, tools, guardrails, config, prompt safety, and all
// other logic that doesn't touch net.minecraft.*. Consumed by the MC-bound
// `common` module (and through it by fabric / neoforge / forge) AND by the
// `paper` plugin module that has no MC mojmap on classpath.

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-library`
}

val mod_id: String by rootProject
val mod_group: String by rootProject
val mod_version: String by rootProject

group = mod_group
version = mod_version
base.archivesName.set("$mod_id-core")

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
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

val kotlinx_coroutines_version: String by rootProject
val kotlinx_serialization_version: String by rootProject
val okhttp_version: String by rootProject
val sqlite_jdbc_version: String by rootProject
val tomlj_version: String by rootProject
val junit_version: String by rootProject
val mockk_version: String by rootProject
val assertj_version: String by rootProject

dependencies {
    api(kotlin("stdlib-jdk8"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinx_coroutines_version")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:$kotlinx_coroutines_version")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinx_serialization_version")
    api("com.squareup.okhttp3:okhttp:$okhttp_version")
    api("org.xerial:sqlite-jdbc:$sqlite_jdbc_version")
    api("org.tomlj:tomlj:$tomlj_version")
    api("org.slf4j:slf4j-api:2.0.9")

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$kotlinx_coroutines_version")
    testImplementation("com.squareup.okhttp3:mockwebserver:$okhttp_version")
    testImplementation(platform("org.junit:junit-bom:$junit_version"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:$assertj_version")
    testImplementation("io.mockk:mockk:$mockk_version")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}
