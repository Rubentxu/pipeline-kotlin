plugins {
    kotlin("jvm")
}

group = "dev.rubentxu.pipeline.v2"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        // KotlinScriptedSourceMapper deliberately reaches into compiler PSI/CLI
        // internals; Kotlin 2.4 gates both behind opt-in markers.
        freeCompilerArgs.addAll(
            "-opt-in=org.jetbrains.kotlin.K1Deprecation",
            "-opt-in=org.jetbrains.kotlin.config.CompilerConfiguration.Internals",
        )
    }
}

dependencies {
    implementation(libs.kotlin.scripting.jvm.host)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.compiler.embeddable)
    implementation(project(":pipeline-scripting-api"))
    implementation(project(":pipeline-events"))
    implementation(project(":pipeline-domain"))
}

// The module is a compiler front end, so the end-to-end layer is its main
// observer: every scenario in `examples/` compiles a `.pipeline.kts` through
// it. That observer, however, only reaches the compiler — not the protection
// it applies to the text it compiles. A credential referenced as `$NAME` is
// rewritten before the compiler ever sees it, and no scenario writes one that
// way: the ones that need a value write it already escaped. So the guarantee
// that a bound credential reaches the shell instead of the compiler had no
// observer at all, which is what `ScriptTextEscaperTest` is for.
//
// These lines exist because a test source does, and `scripts/verify-all.sh`
// fails the build when a module with a test source produces no JUnit XML.
// Gradle 8.14 exposes no `failOnNoDiscoveredTests` property — the setting does
// not exist in this version — so the guarantee that makes this wiring safe
// lives in that script, not here.
dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
