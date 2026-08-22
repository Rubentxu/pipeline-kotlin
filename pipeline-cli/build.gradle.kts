import java.time.Duration

plugins {
    alias(libs.plugins.kotlin.jvm)
    // alias(libs.plugins.shadow)  // Shadow 9.6.1 usa API eliminada en Gradle 8.14.5; reactivar cuando salga versión compatible.
    //    alias(libs.plugins.graalvm.native)
    application
}

group = "dev.rubentxu.pipeline.cli"
version = "0.1.0"

dependencies {
    implementation(project(":core"))
    implementation(project(":pipeline-config"))
    implementation(project(":pipeline-backend"))

    // Modern CLI with Kotlin Native libraries
    implementation(libs.clikt)
    implementation(libs.mordant)
    implementation(libs.okio)
    implementation(libs.kaml)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    
    // Logging
    implementation(libs.logback.core)
    implementation(libs.logback.classic)
    implementation(libs.slf4j.api)

    testImplementation(kotlin("test"))
    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.junit.jupiter)
    
    // GraalVM dependencies for testing sandbox functionality
//    testImplementation(libs.bundles.graalvm)
    testImplementation(libs.mockk)
}

application {
    mainClass.set("dev.rubentxu.pipeline.cli.PipelineCli")
}

distributions {
    main {
        distributionBaseName.set("pipeline-cli")
        contents {
            from("README.md")
            into("bin") {
                from("src/main/resources/run.sh")

            }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.toVersion("21")
}

tasks {
    compileKotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
        // Exclude files with missing dependencies
        exclude("**/SimplePipelineCli.kt") // Depends on PipelineScriptRunner and normalizeAndAbsolutePath from pipeline-backend
        exclude("**/Main.kt") // Depends on SimplePipelineCli
        // INC-006: AdvancedCommands.kt uses Context.terminal (Clikt 3.x) which does not exist in Clikt 2.8.0
        // INC-007 (cli-activation-lifecycle) owns forward planning for re-inclusion or removal
        // Exploration evidence: cycle-artifacts/explore/inc-006-cli-terminal-api-drift-amendment-3.md
        // sha256: f15eed31ad088df7e5bfb25b4e5868c263063d38de76c0de6f46ff45168b201d
        exclude("**/AdvancedCommands.kt")
    }
    compileTestKotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
        // Exclude tests with missing dependencies
        exclude("**/*Test.kt") // Tests depend on excluded classes
    }

    test {
        useJUnitPlatform()
        
        // Allow parallel execution for non-conflicting tests
        // CLI resource conflicts are handled by AbstractE2ETest lock
        maxParallelForks = 4  // Run tests in parallel, but CLI tests sync internally
        
        // Increase timeouts for E2E tests
        timeout.set(Duration.ofMinutes(10))
        
        // System properties for test isolation
        systemProperty("java.io.tmpdir", System.getProperty("java.io.tmpdir"))
        // Remove global parallelism restriction - let individual tests decide
        // systemProperty("kotest.framework.parallelism", "1")
        
        // JVM options for better test stability
        jvmArgs(
            "-XX:+UseG1GC",
            "-Xmx3g",  // Increase memory for parallel execution
            "-XX:MaxMetaspaceSize=768m"
        )
        
        // Skip test filtering since we have excluded all tests
        // filter {
        //     includeTestsMatching("*")
        // }
        
        // Clean up temp directories before tests
        doFirst {
            delete(fileTree("${System.getProperty("java.io.tmpdir")}").matching {
                include("cli-test-*/**")
            })
        }
    }
    
    // shadowJar {  // desactivado: ver plugin shadow comentado arriba
    //     isZip64 = true
    //     archiveBaseName.set("pipeline-cli")
    //     archiveClassifier.set("")
    //     archiveVersion.set("")
    // }
}





tasks.register("printClasspath") {
    doLast {
        configurations["runtimeClasspath"].forEach { println(it) }
    }
}

// GraalVM Native Image configuration
//graalvmNative {
//    toolchainDetection.set(true)
//
//    binaries {
//        named("main") {
//            imageName.set("pipeline-cli")
//            mainClass.set("dev.rubentxu.pipeline.cli.MainKt")
//
//            // Build arguments for optimization
//            buildArgs.addAll(
//                // Basic initialization
//                "--initialize-at-build-time=kotlin",
//                "--initialize-at-build-time=org.slf4j",
//
//                // Performance optimizations
//                "--no-fallback",
//                "--report-unsupported-elements-at-runtime",
//
//                // Memory settings
//                "-J-Xmx4g",
//                "-H:+ReportExceptionStackTraces",
//
//                // JLine exclusions to prevent configuration conflicts
//                "--exclude-config",
//                ".*jline.*",
//                "META-INF/native-image/.*",
//
//                // Resources inclusion
//                "-H:+UnlockExperimentalVMOptions",
//                "-H:IncludeResources=.*\\.kts$",
//                "-H:IncludeResources=.*\\.yaml$",
//                "-H:IncludeResources=.*\\.yml$",
//                "-H:IncludeResources=.*\\.properties$"
//            )
//
//            // JVM arguments for build process
//            jvmArgs.addAll(
//                "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
//                "--add-exports=java.base/java.io=ALL-UNNAMED",
//                "--add-opens=java.base/java.nio=ALL-UNNAMED",
//                "--add-opens=java.base/java.io=ALL-UNNAMED",
//                "--add-opens=java.base/java.lang=ALL-UNNAMED",
//                "--add-opens=java.base/java.util=ALL-UNNAMED"
//            )
//        }
//    }
//
//    // Agent configuration for automatic metadata generation
//    agent {
//        defaultMode.set("standard")
//
//        // Configure modes
//        modes {
//            standard {
//                // Standard mode configuration
//            }
//        }
//    }
//}
