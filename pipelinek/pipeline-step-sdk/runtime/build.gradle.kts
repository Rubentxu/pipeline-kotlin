plugins {
    kotlin("jvm")
    // Selective mutation (durable decision kernels). Run explicitly::pipeline-step-sdk:runtime:pitest
    id("info.solidsoft.pitest")
}

// The mutation seam of the SDK runtime, and the mutants it cannot kill.
//
// WHAT IT MEASURES: the durable decision kernel of this module — the
// `EffectReplayPolicy` contract and the `DefaultEffectReplayPolicy` that
// implements it. That is the one place where a durable engine decides
// whether to serve a journalled result instead of running the work again.
//
// WHAT IT DOES NOT MEASURE: the rest of the module. Its rows live in
// `DefaultEffectReplayPolicyTest`, and pitest cannot use the tests of
// another module from here, so the mutants of the code outside this seam
// are NO_COVERAGE and not survivors. That is a hole of the HARNESS, not of
// the product, and it is measured as such instead of being hidden.
//
// THE MUTANTS THAT SURVIVE HERE, AND WHY THEY CANNOT BE KILLED — two of them:
//
// They are NOT excluded from the seam: a number that hides its survivors is a
// number that cannot be read. They are REGISTERED here, with the reason.
//
// Both are in `DefaultEffectReplayPolicy.memoisable`, and both are
// `RemoveConditionalMutator_EQUAL_ELSE` ("removed conditional - replaced
// equality check with false"). NEITHER is the `==` of the source: that
// comparison is the `if_acmpne` at offset 92 of the method, and the mutation
// does not touch it. The two jumps it DOES touch are the FAST PATH of the
// INLINED `Collection.all` — offset 31 ("this is not a Collection") and
// offset 43 ("this is not empty") — and the `LineNumberTable` of the method
// attributes them to line 164 of the LIBRARY, not to a line of this source
// (the file has 162 lines). MEASURED with `javap -p -c -l` over the
// compiled class.
//
// - The one at offset 31 is the `instanceof Collection` check: the parameter
//   of `memoisable` is a `Set<Effect>`, and a Set is ALWAYS a Collection, so
//   the branch is dead code.
// - The one at offset 43 is the `isEmpty` check: an empty collection
//   answers true on the fast path AND on the iterator path, so the shortcut
//   and the loop answer the same thing for every input and the shortcut has
//   no observable.
//
// The module's own rows confirm it: the row that says a purely read-only
// reusable completion is memoised, and the rows that say an effectful, a
// mixed and an empty effect set are not, all stay green, because the two
// paths agree on every input.
pitest {
    junit5PluginVersion = "1.2.1"
    targetClasses = listOf(
        "dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy*",
        "dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy",
    )
    targetTests = listOf(
        "dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicyTest",
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
