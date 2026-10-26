plugins {
    kotlin("jvm")
    // Selective mutation (durable decision kernels). Run explicitly::pipeline-step-sdk:runtime:pitest
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
    // `:pipeline-events-store` was here because `StepReconcilerL1` named `events.durable.OperationJournal`
    // to query RUNNING rows during resume. That query was a projection nobody called: the resume path
    // asks the control directory directly, through `RunningSubprocessRecovery`. With the projection
    // gone, the coupling is gone with it — and it goes rather than being wrapped in a port, because a
    // port here would be a second name for the reconciliation the SDK runtime already owns.
    implementation(project(":pipeline-scripting-api"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
