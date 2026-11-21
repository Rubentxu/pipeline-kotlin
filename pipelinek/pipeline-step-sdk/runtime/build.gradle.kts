plugins {
    kotlin("jvm")
}

// The mutation seam of this module is GONE, and its absence is the fact.
//
// The seam was the durable decision kernel — the `EffectReplayPolicy` contract and the
// `DefaultEffectReplayPolicy` that implements it. Both moved to `:pipeline-domain`,
// which is where the vocabulary they decide over lives (`ReplayPolicy`, `Effect`,
// `OperationStatus`) and which is PUBLISHED: a plugin compiled against the SDK has to
// be able to read the rule that says whether a journalled result may be served again.
//
// The seam did not move with them, because `:pipeline-domain` already declares one:
// its `pitest` block targets `dev.rubentxu.pipeline.v2.domain.durable.*` and its rows
// are `dev.rubentxu.pipeline.v2.domain.durable.*Test`. The moved rows are therefore
// measured there, by the seam that was already watching that package.
//
// A lane with no target class is a lane that measures nothing while looking like a
// gate, so this module declares no mutation seam at all.

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
