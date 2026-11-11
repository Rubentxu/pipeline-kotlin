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
