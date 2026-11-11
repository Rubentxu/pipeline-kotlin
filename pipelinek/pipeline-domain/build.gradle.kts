plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `maven-publish`
    // Selective mutation (ReplayPolicy core). Run explicitly::pipeline-domain:pitest
    id("info.solidsoft.pitest")
}

// The mutation seam of the durable domain, and the mutants it cannot kill.
//
// WHAT IT MEASURES: every class of `dev.rubentxu.pipeline.v2.domain.durable.*`
// — the vocabulary the durable path is written in: the identity of an
// operation, the material it is digested from, the fingerprint and the policy
// that governs it.
//
// WHAT IT DOES NOT MEASURE: the durable ENGINE. Its rows live in
// `:pipeline-runtime` and `:pipeline-application`, and pitest cannot use the
// tests of another module from here, so its mutants are NO_COVERAGE and not
// survivors. That is a hole of the HARNESS, not of the product, and it is
// measured as such instead of being hidden.
//
// THE MUTANTS THAT SURVIVE IN THE TWO DOCUMENTS OF THE IDENTITY, AND WHY THEY
// CANNOT BE KILLED — nine of them:
//
// They are NOT excluded from the seam: a number that hides its survivors is a
// number that cannot be read. They are REGISTERED here, with the reason, so
// that the next reader does not measure them again as news. The other
// survivors of this seam live in classes that are not the identity of an
// operation, and they are tracked with their own measure.
//
// - 2 at `OperationInput.kt:84` and 4 at `IdentityMaterial.kt:148` and `:151`
//   are the FAST PATH of an inlined `Collection.any`, `Collection.all` and
//   `Collection.none` (`Iterable.kt` inlined into the class). The fast path
//   answers "an empty collection" without iterating, and in both places the
//   collection cannot be empty at that point: `SECRET_PATTERNS` is a constant
//   with nine patterns, and `excludedFields` was proved non-empty by the
//   guard one line above. The path is dead code, and a mutation of dead code
//   has no observable.
// - 1 at `OperationInput.kt:67` is the compiler's null check on the
//   platform-typed result of `String.toLowerCase(Locale.ROOT)`. That method
//   never returns null, so the check can never fire.
// - 1 at `IdentityMaterial.kt:25` is the `isFailure` test of
//   `Result.getOrNull()`: the mutation returns the RAW value even when the
//   result is a failure. The raw value of a failure is the `Throwable`, and a
//   `Throwable` is never a `JsonObject`, so the cast that follows answers
//   null exactly as the original does. MEASURED: the same behaviour written
//   in source — `try { ... } catch (e: Throwable) { e as? JsonObject }` —
//   leaves the 338 rows of the module green.
// - 1 at `IdentityMaterial.kt:68` is `Whole.getExcludedFields` returning
//   `Collections.emptySet()`. The field already holds `emptySet()`, and the
//   declared type promises the CONTENT of the set, not the instance of it:
//   `equals`, `size` and `isEmpty` all hold.
//
// THE MUTANTS THAT SURVIVE IN THE RETRY RECONCILER, AND WHY THEY CANNOT BE
// KILLED — ten of them:
//
// The reconciler is a PURE function and it had no row at all: the decision of
// whether a durable failure is retried was measured by nobody, in any module.
// Its rows now exist (`RetryReconcilerTest` and `RetryEvidenceTest`), and the
// ten that survive are the ones no input can distinguish.
//
// - 1 at `RetryReconciler.kt:90` is the boundary of the comparison inside the
//   INLINED `maxOfOrNull { it.attempt }`: changing `<` into `<=` only changes
//   WHICH element of a tie is kept. The reconciler reads the VALUE (the
//   ordinal) and never the element, and two rows with the same ordinal carry
//   the same value, so the tie-break is not observable.
// - 1 at `RetryReconciler.kt:112` is the `isPollFailure` half of
//   `control.status.isPollFailure || control.status.isTerminal`. Every poll
//   failure is terminal — `OperationStatus` decides it in the enum itself —
//   so the condition says the same as `isTerminal` alone.
// - 1 at `RetryReconciler.kt:143` is the `isTerminal` half of the same
//   condition. At that line the status cannot be SUCCEEDED nor UNSTABLE (both
//   returned earlier), so it IS a poll failure: the half is dead code.
// - 1 at `RetryReconciler.kt:145` is `if (realChildren.isEmpty())`. With no
//   child evidence there is no in-flight child, no success and no unstable
//   child, so the "no child evidence" path and the "every child is terminal
//   and not a success" path reach the same two answers (`ReuseFailure` and
//   `AdvanceAfterFailure` with the same arguments): the branch is a shortcut
//   with the same observable.
// - 2 at `RetryReconciler.kt:148` and 2 at `:163` are the
//   `if (attempt >= input.maxAttempts) return ReuseFailure(attempt)` of the
//   terminal branch, and they are UNREACHABLE. The control row of that
//   attempt is a poll failure, so its ordinal is counted by the filter at
//   `:85` and the exhaustion at `:91` has already answered before the loop
//   reaches those lines.
// - 2 at `RetryReconciler.kt:350` are the two checks of the FAST PATH of the
//   INLINED `Collection.any` of the legacy branch. The legacy bucket is
//   never empty there (the `legacy.isEmpty()` guard at `:242` answered
//   already) and the bucket is always a `Collection`, so the fast path and
//   the loop answer the same thing.
//
// They are NOT excluded from the seam: a number that hides its survivors is a
// number that cannot be read.
pitest {
    junit5PluginVersion = "1.2.1"
    targetClasses = listOf("dev.rubentxu.pipeline.v2.domain.durable.*")
    // Follow-up: kotlinx-generated $$serializer classes are compiler
    // output, not hand-written logic - mutating their default-value branches
    // only inflates the NO_COVERAGE denominator (they were 200+ of the 322).
    excludedClasses = listOf(
        "dev.rubentxu.pipeline.v2.domain.durable.*\$\$serializer",
    )
    targetTests = listOf(
        "dev.rubentxu.pipeline.v2.domain.durable.*Test",
    )
    threads = System.getenv("PIT_THREADS")?.toInt() ?: 4
    timeoutConstInMillis = 10000
    outputFormats = listOf("XML", "HTML")
    mutators = listOf("DEFAULTS")
}

dependencies {
    // `api`, not `implementation`. This module was already published, and
    // it declared the serialization API as `implementation`, which writes `runtime` scope into the
    // POM. The ABI dump settles whether that was wrong rather than taste: `api/pipeline-domain.api`
    // carries 535 references to `kotlinx/serialization`, so a consumer that names any of the ten
    // `@Serializable` domain types and touches its `Companion.serializer()` cannot compile against a
    // POM that keeps the serialization API off its compile classpath.
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

group = "dev.rubentxu.pipeline.v2"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

// Publish this SDK module into a build-local Maven repository so the
// independent external plugin build can compile against artifacts produced from
// THIS source revision. Replaces the committed libs/*.jar snapshots, which could
// silently drift from the live SDK.
publishing {
    publications {
        create<MavenPublication>("sdk") {
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "sdk"
            url = uri(rootProject.layout.buildDirectory.dir("sdk-repo"))
        }
    }
}
