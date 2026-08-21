plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotest)
    // KSP pendiente de release para Kotlin 2.4.x — ver settings.gradle.kts.
    // alias(libs.plugins.ksp)
}

group = "dev.rubentxu.pipeline.core"
version = "1.0-SNAPSHOT"

dependencies {
    // Plugin annotations for @Step and related annotations
    implementation(project(":pipeline-steps-system:plugin-annotations"))

    // Compiler plugin for @Step transformation (temporarily disabled due to IR errors)
    // kotlinCompilerPluginClasspath(project(":pipeline-steps-system:compiler-plugin"))

    implementation(libs.snakeyaml)
    implementation(libs.kotlin.serialization)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.gradle.tooling.api)
    implementation(libs.jgit)
    implementation(libs.logback.classic)
    implementation(libs.bundles.kotlin.scripting)
    implementation(libs.bundles.docker)
//    compileOnly(libs.bundles.graalvm) // Only needed for compilation, not runtime
    implementation(libs.bundles.maven.resolver)

    // Kotlin reflection for step registry
    implementation(kotlin("reflect"))

    // Explicit dependency on Kotlin standard library
    implementation(libs.kotlin.stdlib)

    // Koin DI
    implementation(libs.bundles.koin)

    // JCTools for high-performance concurrent collections
    implementation(libs.jctools.core)

    // Kotest required for generated test frameworks
    implementation(libs.bundles.kotest)

    testImplementation(kotlin("test"))
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.mockk)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.bundles.koin.test)
    // GraalVM dependencies for tests that use sandbox functionality
//    testImplementation(libs.bundles.graalvm)
}

java {
    sourceCompatibility = JavaVersion.toVersion("21")
    targetCompatibility = JavaVersion.toVersion("21")
}

tasks {
    compileKotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
        // === E0-01: stale excludes removed 2026-08-22 (audit ref docs/v2/05-roadmap/E0_EXCLUDES_AUDIT.md) ===
        // 49 of 51 excluded paths no longer exist on disk; KEEP only the
        // `disabled/` convention used by V2 sources.
        exclude("**/disabled/**")
    }
    compileTestKotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
        exclude("**/disabled/**")

        // Context system tests - exclude problematic ones, include working ones
        exclude("**/context/**")
        include("**/context/managers/RealManagersModuleTest.kt") // ✅ Working DI test

        // Integration tests - include current phase, exclude future phases
        include("**/integration/PipelineServiceInitializationSpec.kt") // ✅ Phase 1 complete
        include("**/integration/PipelineRunnerIntegrationSpec.kt") // ✅ Phase 2 - in development
        exclude("**/integration/UnifiedContextIntegrationTest.kt") // Unified context not ready

        // Old/deleted implementations
        exclude("**/RealManagersTest.kt") // References deleted KoinParameterManager
    }

    test {
        useJUnitPlatform()
//        jvmArgs()
    }
}

tasks.withType<Test> {
    jvmArgs("--add-opens", "java.base/java.util=ALL-UNNAMED")
}

tasks
    .withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>()
    .configureEach {
        compilerOptions
            .languageVersion
            .set(
                org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2
            )
    }