plugins {
    kotlin("jvm")
    // Matched to the Kotlin version (2.4.10), NOT the kotlinx-serialization
    // runtime version. The catalog alias `libs.plugins.kotlin.serialization`
    // binds to the runtime version and does not resolve as a Gradle plugin id.
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
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
    // Honour a system property override so CI can point the real-artifact
    // checks at a different build tree. Default: this module lives at
    // v2/pipeline-release/, so the parent is v2/.
    val v2RootDefault = projectDir.parentFile.absolutePath
    systemProperty("release.v2.root", System.getProperty("release.v2.root", v2RootDefault))
}
