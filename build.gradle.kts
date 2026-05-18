plugins {
    id("architectury-plugin") version "3.4-SNAPSHOT"
    id("dev.architectury.loom") version "1.10-SNAPSHOT" apply false
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
}

architectury {
    // Resolved from the multi-version matrix in settings.gradle.kts and
    // injected onto the root project's extra properties before evaluation.
    minecraft = project.property("minecraft_version") as String
}

// Modules that do NOT use the Architectury Loom toolchain (no MC mojmap on
// classpath). They configure themselves entirely from their own build script.
val pureProjects = setOf("core", "paper")

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

    val mod_id: String by rootProject
    val mod_version: String by rootProject
    val mod_group: String by rootProject

    project.group = mod_group
    project.version = mod_version

    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.architectury.dev/")
        maven("https://maven.neoforged.net/releases/")
        maven("https://maven.parchmentmc.org/")
        maven("https://thedarkcolour.github.io/KotlinForForge/")
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
        }
    }

    // MC-bound modules pick up Loom + Architectury. Pure modules (core, paper)
    // skip them — their build scripts handle their own java/toolchain setup.
    if (project.name !in pureProjects) {
        apply(plugin = "dev.architectury.loom")
        apply(plugin = "architectury-plugin")

        // Stamp the Minecraft version into the jar name so a release artifact
        // is unambiguous, e.g. aidirector-neoforge-mc1.21.4-0.4.4.jar.
        val mcVersion = project.property("minecraft_version") as String
        the<org.gradle.api.plugins.BasePluginExtension>().archivesName.set("$mod_id-${project.name}-mc$mcVersion")
        the<org.gradle.api.plugins.JavaPluginExtension>().apply {
            toolchain.languageVersion.set(JavaLanguageVersion.of(21))
            withSourcesJar()
        }
    }
}
