import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // IntelliJ 2024.2 bundles Kotlin stdlib 1.9.24 and we deliberately do not
        // ship our own copy (kotlin.stdlib.default.dependency=false), so calling a
        // newer stdlib API would blow up at runtime on the oldest supported IDE.
        // Kotlin 2.4 dropped apiVersion 1.9 entirely, which is why the Kotlin
        // Gradle plugin is pinned to 2.3.x in settings.gradle.kts until we raise
        // sinceBuild past 242.
        apiVersion = KotlinVersion.KOTLIN_1_9
        languageVersion = KotlinVersion.KOTLIN_2_1
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))

        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
        zipSigner()
    }

    // Embedded query engine: reads parquet/csv/tsv/jsonl in place, does the
    // sorting, filtering and statistics we would otherwise do on the heap.
    implementation("org.duckdb:duckdb_jdbc:1.5.5.1")

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    // Bytecode instrumentation only adds @NotNull assertions to Java classes and
    // compiles GUI Designer .form files - we have neither, and the instrumenter
    // ships a legacy JDK layout probe that fails on Windows.
    instrumentCode = false

    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // No untilBuild: the plugin must not expire with every IDE release.
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }

    // Credentials come from the environment only - see MARKETPLACE-SETUP.md.
    signing {
        certificateChainFile = providers.environmentVariable("CERTIFICATE_CHAIN_FILE").map { file(it) }
        privateKeyFile = providers.environmentVariable("PRIVATE_KEY_FILE").map { file(it) }
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}
