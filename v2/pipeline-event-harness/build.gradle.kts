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
    // BLOCK 2 removed the harness's dependency on `:pipeline-events-store`, and that is the point.
    //
    // `RealHistoryParityTest` drove a real `JsonEventLog` to prove the harness agrees with a real
    // history. `JsonEventLog` is an implementation, and BLOCK 2 moved it to `:pipeline-events-store`
    // so the published event contract would carry no wire format and no JDBC. Reaching for it from
    // here would have made the verifier depend on the thing it verifies — the exact inversion
    // `FArch020EventHarnessIsolationTest` exists to prevent, and the reason that test failed rather
    // than being relaxed.
    //
    // The test moved to `:pipeline-events-store` instead: it still runs, it still drives a real log,
    // and the harness stays a leaf that depends only inward on the contract.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.snakeyaml)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

tasks.test {
    useJUnitPlatform()
}
