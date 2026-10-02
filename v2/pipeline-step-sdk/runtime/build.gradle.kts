plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp")
    // WU-RP-040 R4: selective mutation (durable decision kernels). Run explicitly: :pipeline-step-sdk:runtime:pitest
    id("info.solidsoft.pitest")
}

pitest {
    junit5PluginVersion = "1.2.1"
    targetClasses = listOf(
        "dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy*",
        "dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy",
    )
    targetTests = listOf(
        "dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy*Test",
    )
    threads = System.getenv("PIT_THREADS")?.toInt() ?: 4
    timeoutConstInMillis = 10000
    outputFormats = listOf("XML", "HTML")
    mutators = listOf("DEFAULTS")
}

group = "dev.rubentxu.pipeline.v2"
version = "0.36.0"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
    }
}

dependencies {
    implementation(project(":pipeline-domain"))
    implementation(project(":pipeline-step-sdk:api"))
    implementation(project(":pipeline-events"))
    implementation(project(":pipeline-scripting-api"))
    implementation(libs.kotlinx.coroutines.core)
    ksp(project(":pipeline-step-sdk:processor"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
