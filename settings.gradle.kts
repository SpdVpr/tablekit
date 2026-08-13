import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "tablekit"

pluginManagement {
    plugins {
        // Pinned: 2.4+ removed apiVersion 1.9, which we need while sinceBuild is 242
        // (IntelliJ 2024.2 bundles Kotlin stdlib 1.9.24). 2.3.21 is also the newest
        // Kotlin release validated against Gradle 9.3.
        id("org.jetbrains.kotlin.jvm") version "2.3.21"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.18.1"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}
