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
    // Spike stage-scoped: depend ONLY on the public scripting seam and the domain
    // types. NO reference to :pipeline-application, :pipeline-scripting-kotlin24,
    // or the canonical coordinator wiring. The spike must be isolated so it
    // cannot leak into the production durable spine or any consumer that has
    // not opted in.
    implementation(project(":pipeline-domain"))
    implementation(project(":pipeline-scripting-api"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
    // Spike tests are HF1 (in-process); no real processes, no killing, no
    // remote distribution. The harness stays inside the JVM.
}
