plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "dev.rubentxu.pipeline.v2"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(project(":pipeline-domain"))
    implementation(project(":pipeline-events"))
    implementation(project(":pipeline-scripting-api"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    // WU-RP-5 / FASE 2 item S: wall-clock performance probes are not valid
    // under full-build contention, so they are excluded from the standard gate
    // and run deliberately through `performanceTest` (PR-015 methodology).
    // Nothing is weakened: the probe keeps its floor; it measures where the
    // measurement means something.
    useJUnitPlatform {
        excludeTags("performance")
    }
}

tasks.register<Test>("performanceTest") {
    group = "verification"
    description = "Runs the @Tag(\"performance\") probes in isolation (PR-015 methodology)."
    useJUnitPlatform {
        includeTags("performance")
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    // Performance probes must measure an otherwise-idle machine: forbid Gradle
    // from scheduling other test executions in parallel with this one.
    setMaxParallelForks(1)
}
