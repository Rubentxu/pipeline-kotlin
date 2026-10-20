plugins {
    kotlin("jvm")
    `maven-publish`
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
    // Module split precondition.
    // `api(:pipeline-runtime-api)`, not `implementation`: the three production
    // adapters (`RuntimeRecoverPortStoreAdapter`, `RuntimeControlPortStoreAdapter`,
    // `RuntimeIntrospectionPortStoreAdapter`) implement the port interfaces
    // declared in the new module and reference their sealed ADTs in their public
    // type signatures. `api` makes the contract transitive: a downstream module
    // that depends on `:pipeline-runtime` keeps seeing the ports without an extra
    // `implementation(:pipeline-runtime-api)` declaration. `implementation` would
    // break compile against the four ports for any importer that did not also
    // depend on the API module directly.
    api(project(":pipeline-runtime-api"))
    // `api`, not `implementation`: the adapter composition reaches `OutputStreamId`,
    // `OutputTailState`, `EventRecordReadPort`, and the event-plane read cursors
    // (the introspect adapter's `EventQuery.All` argument and the recover adapter's
    // `ReplayCursor.load`). Hiding any of these behind `implementation` would put
    // them at `runtime` scope in the POM and hand a consumer a contract whose own
    // value types it cannot resolve at compile time — exactly the failure mode the
    // Read-side wires guard against
    // After the split, these two `api` deps are technically
    // reachable transitively from `:pipeline-runtime-api`. They are kept as direct
    // `api` declarations for the (legitimate) `:pipeline-events` / `:pipeline-output`
    // consumption that the adapters do, NOT because the new module forces it: a
    // future tightening that drops them here in favour of a single
    // `api(:pipeline-runtime-api)` would still leave the contracts transitive.
    api(project(":pipeline-events"))
    api(project(":pipeline-output"))
    // `implementation`: the adapters compose internal stores (the SQLite
    // event store, the file-backed lease, the operation journal, the segment
    // output follower). The public ABI does not name any of these types, so
    // they belong at `implementation` scope: a consumer that pulls in
    // `:pipeline-runtime` does not transitively pull in JDBC drivers or the
    // file-backed lease lock. This module is NOT in `publishedContractModules`,
    // so the `implementation` scope is the strong barrier; the `:pipeline-runtime-api`
    // module is the same but with zero store deps in its POM.
    implementation(project(":pipeline-events-store"))
    implementation(project(":pipeline-output-store"))
    implementation(libs.kotlin.stdlib)

}

// Module split precondition. This `publishing { }` block is
// Intentionally ORPHANED in this commit: the four ports live in the new
// `:pipeline-runtime-api` module, NOT in this one. This block keeps a
// `sdk` publication declared so the publication machinery is wired up if a
// future cycle decides to publish `:pipeline-runtime` for an internal-only
// contract test, but `publishedContractModules` in `pipelinek/build.gradle.kts` does
// NOT name this module and the root `publishSdkForExternalPlugin` task does
// NOT depend on `:pipeline-runtime:publishSdkPublicationToSdkRepository`.
// The block is harmless dead code today; the publication itself, were it ever
// to fire, would publish an adapter-only artifact with a POM that names
// `:pipeline-events-store` and `:pipeline-output-store` at `implementation`
// scope — the same architecture-as-policy the new `:pipeline-runtime-api`
// module exists to make safe.
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

// Unit-test wiring REMOVED .
// This module carries no unit tests: it is the RUNTIME that executes steps; a JUnit class
// driving it is a harness, and the plan bans harness code. Its verification is the executable
// E2E layer -- `.pipeline.kts` pipelines under `examples/`, driven by
// `examples/run.sh` -- and not a JUnit class.
// The JUnit dependency, the launcher and the `useJUnitPlatform()` block were removed together.
// Left in place they were a SILENT green: a `Test` task whose source set is empty reports
// success while executing zero classes. Gradle 8.14 exposes no `failOnNoDiscoveredTests`
// property -- the setting does not exist in this version, so nothing inside the build can
// turn that green into a failure, and a build file asserting otherwise is describing a flag
// it cannot compile.
// What turns it into a failure lives outside Gradle: `scripts/verify-all.sh` enumerates the
// modules it asks from the filesystem and fails when a module that has a test source produces
// no JUnit XML. The wiring that could have produced the misleading green is gone from the
// modules that have no tests, so no module reaches that check in silence.
