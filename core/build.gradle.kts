plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.kotest")
    id("com.google.devtools.ksp")
    id("dev.rubentxu.pipeline.steps")
}

group = "dev.rubentxu.pipeline.core"
version = "1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)

    jvm {

        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
            jvmArgs("--add-opens", "java.base/java.util=ALL-UNNAMED")
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":pipeline-steps-system:annotations"))
                implementation(libs.kotlin.serialization)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.bundles.kotest)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.snakeyaml)
                implementation(libs.gradle.tooling.api)
                implementation(libs.jgit)
                implementation(libs.logback.classic)
                implementation(libs.bundles.kotlin.scripting)
                implementation(libs.bundles.docker)
                compileOnly(libs.bundles.graalvm) // Only needed for compilation, not runtime
                implementation(libs.bundles.maven.resolver)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.mockito.kotlin)
                implementation(libs.mockk)
                implementation(libs.junit.jupiter)
                implementation(libs.bundles.graalvm)
            }
        }
    }
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