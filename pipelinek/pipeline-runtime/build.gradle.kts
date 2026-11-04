plugins {
    kotlin("jvm")
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
    // `testImplementation`, not `implementation`: this module's PRODUCTION code no longer
    // names any store. Its adapters hold the PORTS — the journal, the cursor, the lease —
    // and those ports live in `:pipeline-runtime-api`, which is declared above as `api`.
    // What is left of `:pipeline-events-store` here is the in-memory implementation of the
    // journal and the cursor, and it is there for the unit tests, which need a store that
    // answers without a database. The edge is a TEST edge: the production classpath of this
    // module reaches the ports and the adapters, and nothing else.
    testImplementation(project(":pipeline-events-store"))
    implementation(project(":pipeline-output-store"))
    implementation(libs.kotlin.stdlib)

    // Unit-test wiring, because `src/test/kotlin` is not empty.
    //
    // The module carries THREE unit tests, and each one is there for ONE
    // subject:
    //
    //  - `RunCancellationDecisionTest` — the cancellation rule. It is a pure
    //    function over four observed facts (who owns the run, whether the run
    //    is known, whether it already finished, whether a cancel was already
    //    recorded), and it decides whether a cancel may proceed. That rule
    //    used to live buried inside a store-backed adapter, where nothing
    //    could reach it: the adapter's own "is this run terminal?" check
    //    turned out to read the wrong fact (the first TERMINAL OPERATION row,
    //    which a half-finished run has as soon as its first step succeeds),
    //    and no test could have said so.
    //  - `RuntimeIntrospectionPortStoreAdapterTest` — what the introspection
    //    plane may say about a RUN. The run's terminal fact is the event
    //    lane's `RunFinished` bookend, not a journal row about one operation.
    //  - `RuntimeRecoverPortStoreAdapterTest` — what the recover plane may
    //    close, and how. The run's verdict is the verdict of ALL of its work,
    //    computed by the domain's single reducer, and it is `null` while the
    //    run still has work in flight.
    //
    // The store-backed half of this module is NOT unit-tested, and does not
    // pretend to be: an adapter that reads a lease, writes a journal and
    // appends an event needs a real lease, a real journal and a real event
    // store, which is what the integration lane composes. A JUnit class that
    // faked the modules under test would be a harness, and harnesses are not
    // what this repository buys.
    //
    // What turns a missing observer into a failure lives outside Gradle:
    // `scripts/verify-all.sh` enumerates the modules it asks from the
    // filesystem and fails when a module that has a test source produces no
    // JUnit XML. The wiring that could have produced the misleading green is
    // gone from the modules that have no tests, so no module reaches that
    // check in silence.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// NOT published, and the absence of `maven-publish` is the mechanism.
//
// The four ports live in `:pipeline-runtime-api`, which is the published
// contract: it is in `publishedContractModules`, in the BOM and in the
// release candidate manifest. This module holds the three adapters that
// implement those ports over the event store, the journal and the lease,
// and it is consumed only by `:pipeline-application`, at `implementation`
// scope. An external consumer compiles against the ports and never against
// the adapters, so an artifact for them would resolve nothing and would
// carry a POM naming `:pipeline-events-store` and `:pipeline-output-store`.
//
// A `publishing { }` block used to sit here, declaring an `sdk` publication
// that no task depended on: `publishedContractModules` does not name this
// module and the root `publishSdkForExternalPlugin` task never reached it.
// It was the fossil of the alternative this module was not built for —
// published as a contract — and it left two ways to read the module's fate
// when there is only one. The sibling modules that are not published
// (`:pipeline-events-store`, `:pipeline-output-store`) carry no
// `maven-publish` either, and this one now follows the same rule.

// Unit-test wiring, because `src/test/kotlin` is not empty.
// The subjects of those tests, and why the store-backed half of this module is
// left to the integration lane, are stated with the dependencies it belongs to.
tasks.test {
    useJUnitPlatform()
}
