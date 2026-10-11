# CRIC-M2 Runtime Ports — SDK Publication Plan

## Document metadata

| Field | Value |
| --- | --- |
| Status | DRAFT — design-only, no implementation performed |
| Branch | `consolidate/cric-m3-plus-main` |
| Worktree HEAD | `cc81653c` (2026-10-11 15:21:23 +0200, clean tree) |
| Author | PK-R1 follow-up orchestrator agent |
| Source gates | `docs/v2/07-uat/evidence/v0.51.0-rc2/release-receipt.md` (git blob `69986a1f`, only present in HEAD, not in the working tree), `docs/pipelinek-coordinated-evolution/handoffs/pk-contract-handoff-cric-m2.md` (peel `bc5861b7`) |
| Out of scope (this document) | Any source mutation, any modification to `publishedContractModules`, `sdkBomModules`, `bcvModules`, or the v0.51.0-rc2 candidate. The candidate is `CANDIDATE_PUBLISHED`; do not reopen it. |

---

## Executive summary

The runtime publish/subscribe ports `RuntimeRecoverPort`, `RuntimeControlPort`, `RuntimeIntrospectionPort` (plus the public pure decider `RuntimeRecoverDecision`) currently ship **inside** the host binary `pipeline-application-0.51.0.jar` but are **not** published as separate Maven coordinates. The release-receipt of `v0.51.0-rc2` documents this gap as CRIC-M2 debt and explicitly defers a fix to a candidate later than rc2.

This document is the design of record for that fix. The recommendation is:

1. **Publish all three ports** plus `RuntimeRecoverDecision` as a new published module `pipeline-runtime`, surfaced through the existing `maven-publish` machinery that is already wired into `v2/pipeline-runtime/build.gradle.kts` but currently orphaned from the root `publishSdkForExternalPlugin` task.
2. **Pre-condition: split `:pipeline-runtime` into `:pipeline-runtime-api` (interfaces + ADTs, no internal-store deps) and keep `:pipeline-runtime` (adapters + store composition) inside the host.** Publishing the adapters and their store dependencies is neither required nor safe — the stores are intentionally internal.
3. **Carry this on `v0.51.0-rc3`** (still train sequence 3 of 0.51.0) and gate the capability promotion from `EXPERIMENTAL` to `PUBLICADA` on the existing `pipelinek-release-harness` `CERTIFIED` verdict, exactly as the M2 contract handoff already prescribes (lines 245–249).
4. **Bump project version to `0.52.0` at the same time**, because the addition of a 5th published module and its initial `apiCheck` baseline is a MINOR additive change under the project's semver convention (`0.51.0 → 0.52.0`). The `0.51.0-rc3` line carries the rc3 → 0.52.0-rc1 transition for this scope.
5. **Re-certify Fabric pair-certification** in PK-R2 once the new surface is published, because the M2 handoff is in `PREPARED` state pending `PAIR_CERTIFIED` (§15.2 of the M2 handoff).

The detailed reasoning follows in seven sections.

---

## 1. Current state — what is NOT published

### 1.1 The three ports

All three live under `v2/pipeline-runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/runtime/` in three sibling packages, all stamped M2 in their KDocs and all classed as `**EXPERIMENTAL**` in the `v0.51.0-rc1` capability table:

| Port | File | Package | Fun-interface? | KDoc summary |
| --- | --- | --- | --- | --- |
| `RuntimeRecoverPort` | `recover/RuntimeRecoverPort.kt` (1–79) | `dev.rubentxu.pipeline.v2.runtime.recover` | no (regular interface, has companion constants) | Idempotent recover verb that brings a run back to a known state **without** re-executing external effects. References `RuntimeRecoverDecision.decideRecovery` and `RecoveredExecutionMaterializer.materialize`. |
| `RuntimeControlPort` | `control/RuntimeControlPort.kt` (1–61) | `dev.rubentxu.pipeline.v2.runtime.control` | yes (`fun interface RuntimeControlPort { ... }`) | Terminal control verb that flips a run's durable state to `CANCELLED` exactly once. Cross-references `DurableShellLaunching.kill` and `OutputSealPort.seal` as the gaps it supersedes. |
| `RuntimeIntrospectionPort` | `inspect/RuntimeIntrospectionPort.kt` (1–65) | `dev.rubentxu.pipeline.v2.runtime.inspect` | yes (`fun interface RuntimeIntrospectionPort { ... }`) | Read-only port that answers "what does this run look like right now?" for a given `runId`. Reconciles existing primitives (`EventRecordReadPort`, `OutputReadPort`, `OutputFrameIndex`, `OutputTailPort`, `OperationJournal`, `ReplayCursorStore`, `RunExecutionLease`) into one typed `RuntimeObservation`. |

A fourth M2-public type — **`RuntimeRecoverDecision`** at `recover/RuntimeRecoverDecision.kt` — is part of the same surface. It is the **pure decider** that the recover adapter delegates to, and the `pk-contract-handoff-cric-m2.md` (line 85) lists it among the "classes públicas del nuevo módulo `:pipeline-runtime`." This document treats it as in-scope for the same publication decision.

### 1.2 Where they live

All three ports, all their input/option types, all their outcome ADTs, and the public pure decider live inside `:pipeline-runtime`. The `:pipeline-runtime` module exists; it is real, it has production adapters, and it has 32 test cases (24 adapter + 8 fitness, see §1.4). It is *not* speculative — it is operational inside the host today. What is missing is its `maven-publish` payload into the SDK repository consumed by external consumers.

### 1.3 Why they are not published today

Two reinforcing reasons, both recorded in the project:

1. **Architectural:** `v2/build.gradle.kts` (lines 517–523) defines `publishedContractModules = listOf("pipeline-domain", "pipeline-scripting-api", "pipeline-events", "pipeline-output")` and `pipeline-runtime` is **deliberately absent**. This is not an oversight. The receipt says explicitly:

   > "`pipeline-runtime` (where `RuntimeRecoverPort`, `RuntimeControlPort`, `RuntimeIntrospectionPort` live) NO está en `publishedContractModules` y por tanto NO se publica. Esto es una decisión arquitectónica explícita y rastreable, no una omisión."

2. **Capability:** the same three ports appear in the `v0.51.0-rc1` capability table as `runtime.inspect.v1`, `runtime.cancel.v1`, `runtime.recover.v1` with status `**EXPERIMENTAL**`. The contract handoff states the transition rule: "Transición de `EXPERIMENTAL` a `PUBLICADA` para las tres capacidades M2: ocurre en el contrato tras la certificación `CERTIFIED` por `pipelinek-release-harness`." Until then, the capabilities are "negociables pero todavía no estables."

The two reasons collapse into one operational rule: the module is gated until the capability is `CERTIFIED`, and the capability is gated until the harness approves it. The user's brief captures this: "Mantén los puertos de recovery no publicados como deuda explícita CRIC-M2. Si rc2 afirma que están disponibles para consumidores externos, corrige la declaración antes de solicitar certificación."

### 1.4 Classification history

| Cycle | Status | Source |
|---|---|---|
| `v0.50.0-rc1` (peel `bc5861b7`) | M2 public types introduced; capability `EXPERIMENTAL`; module stays inside host | M2 handoff lines 11–50, 245–249 |
| `v0.51.0-rc1` | Capability carries forward `EXPERIMENTAL`; `v0.51.0-rc1` gate fails (283 pre-existing failures) | `docs/v2/07-uat/evidence/v0.51.0-rc1/release-receipt.md` |
| `v0.51.0-rc2` (HEAD `cc81653c`) | Gap documented as deuda CRIC-M2; "El certifier decidirá si requiere `v0.51.0-rc3` que extienda el SDK para incluir los puertos publish/subscribe" | `docs/v2/07-uat/evidence/v0.51.0-rc2/release-receipt.md` (git blob `69986a1f`) |
| `v0.51.0-rc3` (proposed) | Publish `:pipeline-runtime-api`; capability flips on `CERTIFIED` | This document |

Test coverage that backs the ports today (all inside `:pipeline-runtime`'s test tree, none exposed externally):

- `RuntimeRecoverPortAdapterTest` — 8 cases on real stores (terminal recovery, reattach, journal-incompatible refusal, lease boundary, unknown-run refusal, no-rerun invariant, idempotency, dry-run non-durability).
- `RuntimeRecoverDecisionTableFitnessTest` — 12 cases on the pure decider, side-stepping adapters.
- `RecoverRefusalPinExtensionTest` — 3 cases for the M3-added `PinnedBytesOutsideRecoveredRegion`.
- `RuntimeControlPortAdapterTest` — 8 cases (cancel running, idempotency, terminal refusal, lease boundary, unknown run, repeated cancel durability, journal-unavailable refusal, durable cancel observability).
- `RuntimeIntrospectionPortAdapterTest` — 8 cases (running run, lease-only signal, unknown run, lease boundary, read-only under concurrency, terminal observation, cursor stability, `LiveButEmpty` vs `UnknownRun` distinction).

Plus the architectural pin tests:

- `CapabilityRegistrationTest` (`:pipeline-application/src/test/.../coordination/`) — pins the contract SHA and the capability rows including the three M2 rows classified as `**EXPERIMENTAL**`.
- `PublishedContractBoundaryFitnessTest` (`:pv/pipeline-architecture-tests/src/test/.../architecture/`) — pins `publishedContractModules` to exactly the 4 entries (lines 37–42 of the test file).
- `P3EPublishedContractMaturityFitnessTest` (`:pv/pipeline-architecture-tests/src/test/.../architecture/`) — pins `published-contract-maturity.json` to the same 4 entries, all `EXPERIMENTAL`.

These three pin tests are the load-bearing evidence of safety for any publication change. §3.4 explains how to extend them.

---

## 2. The goal — what gets published

### 2.1 Decision per port

The M2 contract handoff is addressed `Destinatario: Fabric`. The whole purpose of CRIC-M2 was to segregate the publish/subscribe ports into a typed module that *could* be published, while leaving that publication gated on certification. The answer to "does Fabric need this port?" is therefore **yes, all three** — that is the entire point of M2.

| Port | Fabric use case (from M2 handoff + KDocs) | Decision |
|---|---|---|
| `RuntimeRecoverPort` | Recover a run idempotently without re-executing side effects; consult audit tables; pin-aware refusal (`PinnedBytesOutsideRecoveredRegion`). | **Publish.** Minimum surface: the interface, `RecoverOptions`, `RecoverOutcome` (with all four cases), `RecoverRefusal` (with all seven cases), `RecoverReport` (with `Empty`). |
| `RuntimeControlPort` | Cancel a run exactly once; propagate user-tagged reason. | **Publish.** Minimum surface: the interface, `CancelReason` (both variants), `CancelOutcome` (with all three cases), `CancelRefusal` (with all six cases). |
| `RuntimeIntrospectionPort` | Read-only snapshot of a run for status UIs; stable under concurrency. | **Publish.** Minimum surface: the interface, `RuntimeIntrospectionResult` (both cases), `RuntimeObservation` (all four cases including the refusal-carrying `Unobservable`), `IntrospectionRefusal`, `IntrospectionFailure`, `TerminalObservation`, `AttemptId`, `FencingToken`, `LeaseHolder`, `JournalPosition`, `OutputTailView`, `TailState`, `ProcessRef`. |
| `RuntimeRecoverDecision` (the pure function) | Lets fabric customise the recover decision matrix *before* the adapter invokes it. M2 handoff (line 85) lists it among public types. | **Publish.** Minimum surface: the interface (`decideRecovery(observation, proof): RecoveryChoice`), `RecoveryChoice` (all cases), `JournalProof`. The pure decider's body must stay dependency-free (it already is — it is referenced from `RuntimeRecoverDecisionTableFitnessTest`). |

None of the three ports has a `Razón para no publicar` in this assessment. The previous gate was capability-maturity and a published-module absent by choice; both gates have matured (see §3.4 and §5.4 for evidence).

### 2.2 Minimum surface (verifiable, line by line)

The exact types to publish are the ones reachable from the four port interfaces plus `RuntimeRecoverDecision`. The mapper enumerated them in §1 of the investigation report; this section enumerates the irreducible minimum and the rationale for excluding the rest.

**Published:** the `RuntimeRecoverPort` interface, the `RuntimeControlPort` interface, the `RuntimeIntrospectionPort` interface, the `RuntimeRecoverDecision` interface, and every type they transitively reference as a parameter, return type, or named companion (`RecoverOptions`, `RecoverOutcome.{RecoveredTerminal, ReattachPending, FailClosed, AlreadyRecovered}`, `RecoverRefusal.{UnknownRun, NotRecoverable, SubstrateUnavailable, JournalIncompatible, LeaseHeldByAnother, StorageError, PinnedBytesOutsideRecoveredRegion}`, `RecoverReport`, `CancelReason.{UserRequested, Annotated}`, `CancelOutcome.{Cancelled, AlreadyCancelled, Refused}`, `CancelRefusal.{UnknownRun, RunTerminal, LeaseHeldByAnother, IncompatibleRunState, JournalUnavailable, StorageError}`, `RuntimeIntrospectionResult.{Observation, Refused}`, `RuntimeObservation.{Running, Terminal, LiveButEmpty, Unobservable}`, `IntrospectionRefusal.{UnknownRun, LeaseHeldByAnother, StorageError}`, `IntrospectionFailure.{NoControlRoot, NoEventStore, NoOutputPlane, NoJournal, StorageError}`, `TerminalObservation.{Succeeded, Failed, Unstable, Aborted, Cancelled, Other}`, `AttemptId`, `FencingToken`, `LeaseHolder`, `JournalPosition`, `OutputTailView`, `TailState.{Open, Sealed}`, `ProcessRef`, `RecoveryChoice.{ReuseTerminal, FailClosed, AlreadyRecovered}`, `JournalProof`).

**NOT published:** the three adapters (`RuntimeRecoverPortAdapter`, `RuntimeControlPortAdapter`, `RuntimeIntrospectionPortAdapter`) — these compose the stores and are the host's internal wiring. Also NOT published: `RecoveredExecutionMaterializer`, `EffectReplayPolicy`, `RunningSubprocessRecovery`, `DurableShellLaunching`, `OutputSealPort`, `RunLifecycleEngine`. These are the host's internal primitives that the KDocs reference but the consumer never needs to bind to.

The rule that justifies this split is the **own-the-interface, not-the-implementation** policy that already guides `:pipeline-events` and `:pipeline-output`: the published modules expose the contracts, the host holds the wiring. The M2 handoff lines 80–88 articulate this as "Delta ABI compilada: ninguno. CRIC-M2 publica capacidades (`capacidades`), no cambia la forma binaria publicada."

### 2.3 What is explicitly NOT published

For traceability, this section lists the `:pipeline-runtime` package's internal symbols that **must remain** inside the host and never appear in the SDK POM:

- Adapter classes (the 3 production adapters).
- The store composition wiring (`RuntimeRecoverPortAdapter`'s store constructors, etc.).
- Internal types referenced from the KDocs but not from the public port signatures (`EffectReplayPolicy.decide`, `RunningSubprocessRecovery.observe`, `DurableShellLaunching.kill`, `OutputSealPort.seal`, `RunLifecycleEngine`).
- Anything from `:pipeline-output-store`, `:pipeline-events-store`, or `:pipeline-application` (these three modules are explicitly excluded from publication per the v0.51.0-rc2 receipt's "Lo que el consumer NO importa" list).

If a future implementer is tempted to publish any of these, the M2 handoff's architectural rule ("publica capacidades, no cambia la forma binaria publicada") is the binding constraint.

---

## 3. Build-side constraints

### 3.1 Current `pipeline-runtime/build.gradle.kts`

The current build script is 73 lines. Its `plugins` block (lines 1–4) applies `kotlin("jvm")` and `maven-publish`. Its `dependencies` block (lines 16–38) declares:

```kotlin
dependencies {
    api(project(":pipeline-events"))            // line 24 — fine, published
    api(project(":pipeline-output"))            // line 25 — fine, published
    implementation(project(":pipeline-events-store"))   // line 32 — internal, NOT published
    implementation(project(":pipeline-output-store"))   // line 33 — internal, NOT published
    implementation(libs.kotlin.stdlib)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}
```

And its `publishing` block (lines 61–73) declares a single publication named `sdk`:

```kotlin
publishing {
    publications {
        create<MavenPublication>("sdk") { from(components["java"]) }
    }
    repositories {
        maven { name = "sdk"; url = uri(rootProject.layout.buildDirectory.dir("sdk-repo")) }
    }
}
```

There is **no `signing` block** in `pipeline-runtime/build.gradle.kts`, and **no `signing` block anywhere in `v2/build.gradle.kts`**. The `mavenCentral()` declaration at the top of `v2/build.gradle.kts` (lines 18–20) is present but unwired. The `signing` config is therefore not a publication-side constraint today — it is a gap, but a gap the existing published modules have lived with for two cycles, so the publication of `:pipeline-runtime-api` does not impose new signing work.

### 3.2 Internal-store leakage

The risk is concrete: if `:pipeline-runtime` is added to `publishedContractModules` *as-is*, its POM (auto-generated from `dependencies`) will include `:pipeline-output-store` and `:pipeline-events-store` as `<scope>runtime</scope>` entries. An external consumer pulling `pipeline-runtime` would see them in the POM, but they cannot resolve them because neither is published. The consumer would fail at runtime with `NoClassDefFoundError` on any adapter call — which is exactly why the receipt states the gap is closed "POR DECISIÓN DEL OPERADOR" today.

The fix is **not** "remove the `implementation` declarations." The adapters legitimately need the stores at runtime. The fix is **to split the module**:

- **`v2/pipeline-runtime-api/build.gradle.kts`** (new module): holds the 4 port interfaces, the public pure decider, and every type in §2.2. Its `dependencies { }` block contains only `api(:pipeline-events)`, `api(:pipeline-output)`, `implementation(libs.kotlin.stdlib)`, plus `testImplementation` blocks. No store deps, no adapter deps. The `maven-publish` publication is named `sdk`.
- **`v2/pipeline-runtime/build.gradle.kts`** (existing module): keeps the adapters and the store composition. Its `dependencies { }` block declares `api(:pipeline-runtime-api)` plus the existing `implementation(:pipeline-events-store)` and `implementation(:pipeline-output-store)`. This module does **not** appear in `publishedContractModules`. It is the host's internal wiring module.

This split mirrors the existing pattern: the published surface is interfaces + ADTs; the host holds the wiring. `PublishedContractBoundaryFitnessTest`'s `the published contract set is the same one the build publishes` case (lines 207–230) will then continue to pass with a 5-entry `publishedContractModules` (the 4 existing + `pipeline-runtime-api`).

### 3.3 The publication wiring that is already in place

This is a load-bearing point that prevents over-engineering the proposal. Three pieces of infrastructure already exist and do not need to be built:

1. **The `sdk-repo` directory** (`v2/build/sdk-repo/`) is the SDK-repository root; the `publishSdkPublicationToSdkRepository` task for each published module lands its artifact here.
2. **The `sdk` publication pattern** is already exercised by `:pipeline-events` and `:pipeline-output` (the closest analogs — they also expose port interfaces + ADTs with no store deps). `:pipeline-runtime` already has the same `create<MavenPublication>("sdk") { from(components["java"]) }` block (lines 61–73), so the new `:pipeline-runtime-api` module inherits a known-good template.

The root `v2/build.gradle.kts` `publishSdkForExternalPlugin` task (lines 543–551) is the single switch:

```kotlin
val publishSdkForExternalPlugin by tasks.registering {
    group = "build"
    description = "Publishes the published-contract artifacts an external consumer compiles against."
    dependsOn(publishedContractModules.map { ":$it:publishSdkPublicationToSdkRepository" })
    dependsOn(sdkBomModules.map { ":$it:publishSdkPublicationToSdkRepository" })
}
```

Adding `pipeline-runtime-api` to `publishedContractModules` is therefore the only switch that wires the new module into the SDK publication. Adding it to `sdkBomModules` would publish it through the BOM convenience task; the BOM pattern says "the BOM is published by this same task, not by a second mechanism" (lines 547–551). The BOM is a "convenience, not contract" surface, so the design adds `pipeline-runtime-api` to `sdkBomModules` too — same set, same `dependsOn`.

### 3.4 Required additions (line-precise, by file)

The plan adds — but does not implement in this cycle — the following changes to `v2/build.gradle.kts`:

| File | Section | Change |
| --- | --- | --- |
| `v2/build.gradle.kts` lines 517–523 (`publishedContractModules`) | list | Append `"pipeline-runtime-api"` to the 4 existing entries. |
| `v2/build.gradle.kts` lines 539–541 (`sdkBomModules`) | list | Append `"pipeline-runtime-api"` so the BOM tracks it (consistency, since the BOM is the consumer's single entry point). |
| `v2/build.gradle.kts` lines 453–472 (`bcvModules`) | set | Append `"pipeline-runtime-api"`. This is required because the BCV pin is the only automated check that the published ABI matches the previous certified version. The BCV plugin is applied per-module via `subprojects { isBcvModule ... }` (lines 474–488), so adding the entry causes the plugin to apply and the `apiCheck` task to register. |
| `v2/build.gradle.kts` (anywhere after line 488) | new content | Generate the initial `v2/pipeline-runtime-api/api/pipeline-runtime-api.api` baseline on first run via `./gradlew :pipeline-runtime-api:apiDump`. The next `apiCheck` task verifies the baseline against the live code; any unannounced change fails the build. |
| `v2/contract/published-contract-maturity.json` | json | Add a fifth entry: `{"module": "pipeline-runtime-api", "status": "EXPERIMENTAL", "certifiedAt": null, "followsVersions": ["0.52.0"]}`. (The existing 4 entries are all `EXPERIMENTAL`, so the new entry matches the existing ledger's tone. The capability flips to `PUBLICADA` after `pipelinek-release-harness` returns `CERTIFIED`.) |
| `v2/pipeline-runtime-api/build.gradle.kts` (new module) | entire file | new module; mirrors `:pipeline-events`'s build script (the closest analog), with `dependencies { api(:pipeline-events); api(:pipeline-output); implementation(libs.kotlin.stdlib); ... }` and a `publishing { publications { create<MavenPublication>("sdk") { from(components["java"]) } } repositories { maven { name = "sdk"; url = uri(...) } } }` block. |
| `v2/pipeline-runtime/build.gradle.kts` lines 16–38 (existing module) | `dependencies` | change `api(:pipeline-events)` → `api(:pipeline-runtime-api)`, `api(:pipeline-output)` → `api(:pipeline-runtime-api)` (already covered), and add `api(:pipeline-runtime-api)` if not implicit. Keep `implementation(:pipeline-events-store)` and `implementation(:pipeline-output-store)` exactly as today. |

These are the only changes required to (a) publish the new module, (b) validate its ABI, and (c) wire it into the SDK publication. No new signing work; no new SDK-repo structure; no new publication framework.

### 3.5 The 50/0/0 gate (build side)

The v0.51.0-rc2 test gate is `2832 / 0 / 0 (123 skipped)` for `:pipeline-application:test` and `104 / 0 / 0` for `:pipeline-release:test`. The build-side additions above will exercise two new fitness tests on the first run:

- `PublishedContractBoundaryFitnessTest > the published contract set is the same one the build publishes` — passes (the new 5-entry list is parsed from `v2/build.gradle.kts` and matched against the test's expected list, which will need to be updated at lines 37–42 from 4 to 5 entries).
- `P3EPublishedContractMaturityFitnessTest > todo modulo publicado tiene clasificacion de madurez` — passes (the new 5th entry is in the maturity ledger).

Both tests are negative-passing — they fail only when the boundary disagrees. The changes must update both tests' expected values in lock-step with the boundary changes. Failing to update either test and the runner will fail with `expected 4 entries but got 5`, which is the desired signal — exactly the pin the test was written for.

---

## 4. Consumer-side constraints

### 4.1 The 50/0/0 gate (contract side)

The contract gate is the same `2832 / 0 / 0` figure. It must remain at `2832 / 0 / 0` (or higher, with the new test cases added). No test may be deleted from this gate.

### 4.2 External consumer test pattern

The current external consumer (`examples/example-external-consumer/`) declares four SDK coordinates in its build file (lines 67–78):

```kotlin
implementation("dev.rubentxu.pipeline.v2:pipeline-domain:$sdkVersion")
implementation("dev.rubentxu.pipeline.v2:pipeline-events:$sdkVersion")
implementation("dev.rubentxu.pipeline.v2:pipeline-output:$sdkVersion")
implementation("dev.rubentxu.pipeline.v2:pipeline-scripting-api:$sdkVersion")
```

and ships one contract test, `ReadPortsContractTest.kt`, which exercises the read ports that are in those coordinates (`OutputReadPort`, `OutputFrameIndex`, `EventRecordReadPort`). The test uses three patterns:

1. **Reflection on compiled class** — `OutputReadPort::class.java.declaredMethods.map { it.name.substringBefore("-") }.toSet()` (lines 50–58).
2. **Sealed-type permitted-subclasses verification** — `sealed.isSealed; permitted = sealed.permittedSubclasses.map { it.simpleName }.toSet(); assertEquals(...)` (lines 82–86).
3. **Closed ADT case verification** — assert each expected case name is in `permitted` (lines 96–107).
4. **Behavioural pattern-match** — `when (r) { is OutputReadResult.Page -> ...; is OutputReadResult.Refused -> ... }` (lines 121–148).

The test file's own header (lines 23–37) explicitly documents the gap: "The published SDK does not include `pipeline-runtime` (deliberate: the runtime read ports are the consumer side of the contract, not a published module). What IS published — and what every follow impl composes against — are the read ports in `pipeline-output` and `pipeline-events`."

The plan **must update that header** to remove the parenthetical once `:pipeline-runtime-api` is published, and add the new coordinate to the consumer's `dependencies { }` block. The pattern for testing the new ports is identical to the existing one, applied to `RuntimeRecoverPort`, `RuntimeControlPort`, `RuntimeIntrospectionPort`, and their refusal/outcome ADTs.

### 4.3 Required new tests

The plan adds (in a future implementation cycle, not this design cycle) a new contract test file in the external consumer: `RuntimePortsContractTest.kt`. It must, at minimum:

1. **Resolve `pipeline-runtime-api` as a 5th coordinate** — proves the SDK publication works.
2. **For each of the 3 ports + the decider**, assert the interface methods are present via reflection, with the same parameter types as the host's source-of-truth KDoc.
3. **For each outcome ADT**, assert it is `sealed` and that `permittedSubclasses` is exactly the set documented in §2.2.
4. **For each refusal ADT**, assert it is `sealed` and that `permittedSubclasses` matches the documented refusal cases (including `RecoverRefusal.PinnedBytesOutsideRecoveredRegion` for the M3 pin extension).
5. **Behavioural smoke test** — pattern-match over a sealed outcome to verify each branch type-checks and reports the documented invariants.

This pattern is exactly what `ReadPortsContractTest.kt` already does for the 4 read ports; the new test applies the same shape to the 3 publish/subscribe ports. The total new test count is bounded by §2.2's enumeration: ~30 cases is a reasonable budget.

### 4.4 The `CapabilityRegistrationTest` cross-link

`CapabilityRegistrationTest` in `:pipeline-application/src/test/.../coordination/` pins the contract SHA and the capability rows. The M2-relevant cases assert that the contract must reference `runtime.inspect.v1`, `runtime.cancel.v1`, `runtime.recover.v1` and must be marked `**EXPERIMENTAL**` — and that the audit tables must name the four M2 test classes. This test does not change with the publication: the capability classification is independent of module publication (both states today). What flips when the candidate is `CERTIFIED` is the `**EXPERIMENTAL**` → `**PUBLICADA**` status, exactly as the M2 handoff prescribes.

---

## 5. Migration path

### 5.1 Sequence

**Recommend: Introspection → Control → Recover.**

The reasoning is concrete and verifiable:

- **Introspection first** because it is the only read-only port. Read-only ports have no idempotency / no-rerun / lease-boundary / cancel-no-double-seal invariants to reason about concurrently with the consumer adoption. The blast radius is smallest. The existing 8-case test suite (`RuntimeIntrospectionPortAdapterTest`) is the lightest of the three, and the port is a `fun interface` (single SAM method), so the contract surface is one line.
- **Control second** because the cancel verb has idempotency + lease-boundary invariants, but those invariants are already independently battle-tested in the existing 8-case `RuntimeControlPortAdapterTest` (cases 2, 6, 7, 8 specifically exercise idempotency and journal-durability). The contract surface is one line (`fun cancel(runId, reason): CancelOutcome`).
- **Recover last** because recover is the heaviest invariant: no-rerun, idempotency, dry-run non-durability, lease boundary, journal compatibility, **and** the M3 pin extension (`PinnedBytesOutsideRecoveredRegion`). The existing 11-case suite (`RuntimeRecoverPortAdapterTest` + `RecoverRefusalPinExtensionTest` + `RuntimeRecoverDecisionTableFitnessTest`) is the most thorough. Recover also depends on `RuntimeRecoverDecision` (the pure decider), which means publishing recover pulls in the decider publicly — a larger ABI surface than control or introspection.

The alternative order **Introspection → Recover → Control** is also defensible (recover is more likely to be Fabric's primary use case after M2). The orchestrator does not strongly prefer one over the other; the section's recommendation is the smaller-blast-radius-first ordering.

### 5.2 Versioning

**Recommend: bump `0.51.0` → `0.52.0` as a MINOR version, and carry the work on `v0.52.0-rc1` (a fresh train).** The reasoning:

- The addition of a new published module is a MINOR additive change under semver. Pre-1.0 versions use MINOR for any additive change, so 0.51.0 → 0.52.0 is right.
- The current `0.51.0` train carries the EXPERIMENTAL → PUBLICADA capability promotion, which is a separate axis from module publication. Conflating the two creates ambiguity: was this version promoted (capability) or published (module)?
- The next train `0.52.0` cleanly separates: `0.51.0-rc3` will carry only the EXPERIMENTAL → PUBLICADA capability promotion (already planned per the rc2 receipt); `0.52.0-rc1` will carry the module publication. This separation lets each candidate stand alone for certification.

The user's brief cautions against modular deprecation for documentation's sake ("no quiero una nueva release de PipelineK únicamente por continuar elaborando documentación"), which is consistent with this: documentation-only work does not warrant a new release line, but module publication is non-doc work — splitting `:pipeline-runtime` into `:pipeline-runtime-api` + `:pipeline-runtime`, generating an `apiCheck` baseline, updating two fitness tests, updating one ledger, and adding a new SDK coordinate is substantial code change.

**Alternative: bump to `0.51.0-rc3` and stay on the 0.51.0 train.** This is the path the receipt contemplates ("El certifier decidirá si requiere `v0.51.0-rc3`"). The orchestrator's preference is the cleaner train separation (`0.52.0-rc1`), but the receipt's path is the historical consensus. The trade-off:

| Aspect | `v0.51.0-rc3` (stay on 0.51.0 train) | `v0.52.0-rc1` (new train) |
|---|---|---|
| ABI delta | Additive (new module) | Additive (new module) |
| Certifier workload | One CERTIFIED verdict on the rc3 candidate | One CERTIFIED verdict on the rc1 candidate, same scope |
| Consumer adoption | Consumer updates `sdkVersion` from `0.51.0` to `0.51.0-rc3` and adds a 5th coordinate | Consumer updates `sdkVersion` from `0.51.0` to `0.52.0-rc1` and adds a 5th coordinate |
| Capability promotion | Co-flipped on cert | Co-flipped on cert |
| Test debt | `PublishedContractBoundaryFitnessTest` updates 4→5 | `PublishedContractBoundaryFitnessTest` updates 4→5 |

The orchestrator recommends the new train for clarity; the receipt's path is the lower-friction option. **Both are valid; the choice is policy, not technical.** The future implementer should ask the operator which train they prefer.

### 5.3 Candidate

If `0.51.0-rc3`: carry on `consolidate/cric-m4-plus-main` (or whatever the next branch is named); produce a new ZIP; re-run the harness.

If `0.52.0-rc1`: branch `release/cric-m4-v0.52.0-rc1` off `cc81653c`; carry the work; produce a new ZIP; re-run the harness.

In either case the candidate must satisfy the same gate contract as rc2: `2832 / 0 / 0 (123 skipped)` for `:pipeline-application:test` and `104 / 0 / 0` for `:pipeline-release:test`, plus the new contract tests from §4.3.

### 5.4 Re-certifications

The Certifier-issued verifications that must be re-run for the new candidate:

1. **Capability registration** — `CapabilityRegistrationTest` re-runs unchanged; the contract SHA must include the new `pipeline-runtime-api` row in the published-contract-maturity ledger.
2. **Published contract boundary** — `PublishedContractBoundaryFitnessTest` re-runs with the updated 5-entry expectation.
3. **BCV (apiCheck)** — `:pipeline-runtime-api:apiCheck` re-runs against the initial baseline; failure on first run means the baseline is wrong and must be regenerated.
4. **External consumer contract test** — `example-external-consumer`'s test suite re-runs with the new `RuntimePortsContractTest` and the updated 5-coordinate `dependencies { }`.
5. **M2 capability flip** — `pipelinek-release-harness` returns `CERTIFIED` on the new candidate's SHA; the `runtime.inspect.v1`, `runtime.cancel.v1`, `runtime.recover.v1` capability rows flip from `**EXPERIMENTAL**` to `**PUBLICADA**` in the contract.

The five certifications together cover the same surface the rc2 candidate covered, plus the new module's contribution. The Certifier is unchanged from rc2.

### 5.5 What does NOT change

The migration path does not change:

- The 4 existing published modules (`pipeline-domain`, `pipeline-scripting-api`, `pipeline-events`, `pipeline-output`).
- The `sdk-repo` layout (a new artifact directory appears; nothing moves).
- The external consumer's test pattern (the existing pattern extends; nothing replaces).
- The M2 contract handoff's state transition rule (capabilities flip on `CERTIFIED`, regardless of module publication).
- The host binary `pipeline-application` (the host still bundles `:pipeline-runtime` internally; nothing in the host changes).

---

## 6. Risks and known unknowns

### 6.1 Refactor risk (HIGH)

The single highest risk is the `:pipeline-runtime` → `:pipeline-runtime-api` + `:pipeline-runtime` split. The split must preserve:

- The 32 existing test cases' coverage of the existing adapters (these move into `:pipeline-runtime`).
- The 3 production adapters' store wiring (these stay in `:pipeline-runtime`).
- The capability registration of the 3 ports (this is asserted by `CapabilityRegistrationTest`, which is a `:pipeline-application` test, so the assertion is unchanged regardless of where the ports physically live).
- The `:pipeline-application`'s composition root, which currently wires `:pipeline-runtime` (now `:pipeline-runtime-api`) and the adapters (now in `:pipeline-runtime`). This is one configuration edit.

The split is mechanical but touches four Gradle modules and the root build's three lists. A botched split will manifest as `PublishedContractBoundaryFitnessTest` failures and ABI binary mismatches. Mitigation: generate the `:pipeline-runtime-api:apiDump` baseline immediately after the split and verify the baseline matches the expected surface (every type in §2.2, no types from §2.3).

### 6.2 ABI stability

The initial baseline establishes the new module's binary surface at the moment of publication. Any subsequent change must be either:

- Additive (new method on a sealed interface → forbidden, sealed is closed).
- Additive (new sealed subclass → forbidden by `sealed`).
- Compatible (new `companion object` constant → allowed).

Because the module's interfaces and ADTs were designed M2-stable, the initial baseline should be defensible. The risk is low but the gate (`apiCheck`) is unforgiving: every release-candidate PR must pass `apiCheck`.

### 6.3 Test regressions

`Known unknowns that may fail.` The likely-failing tests after the publication change, with mitigations:

| Test | Risk | Mitigation |
|---|---|---|
| `PublishedContractBoundaryFitnessTest` | High (expected list changes 4 → 5) | Update lines 37–42 of the test in lock-step with the build.gradle.kts change. |
| `P3EPublishedContractMaturityFitnessTest` | High (ledger changes) | Update the JSON ledger in lock-step with the bcvModules change. |
| `:pipeline-runtime-api:apiCheck` | High (no baseline exists yet) | Generate baseline via `:pipeline-runtime-api:apiDump`; commit the baseline. |
| `CapabilityRegistrationTest` | Low (no change; capabilities stay `**EXPERIMENTAL**` until cert) | None. |
| `example-external-consumer:RuntimePortsContractTest` | Medium (new file) | Author the test using the existing `ReadPortsContractTest` pattern. |

No test should be deleted to make this work. The gate is `2832 / 0 / 0` minimum.

### 6.4 Consumer adoption risk

External consumers currently using the SDK at `0.51.0` will adopt the new coordinate in one of two ways:

1. **Add-only.** Add `pipeline-runtime-api` to their `dependencies { }` and a new `RuntimePortsContractTest` to their test tree. Their `sdkVersion` moves from `0.51.0` to `0.52.0-rc1` (or `0.51.0-rc3`).
2. **Module-skip.** Some consumers may not need the new ports; they keep their existing 4 coordinates and ignore the 5th. Their adoption is zero-cost.

The Fabric case (per the M2 handoff's `Destinatario: Fabric`) is case (1): Fabric has been waiting for these ports since M2 and will adopt immediately on publication.

### 6.5 Out-of-scope runtime ports (KNOWN UNKNOWN)

The mapper's report flagged that the rc2 receipt references "planes para una futura `RuntimeFollowPort` o similar, pero no en rc2." A `RuntimeFollowPort` is a hypothetical 4th port, distinct from the three M2 ports. It is NOT in scope for this design — its existence is documented only as a placeholder in the receipt's compatibility matrix. Any future follow-up that introduces `RuntimeFollowPort` would be a separate publication decision and should be the subject of a separate design document.

### 6.6 The `RuntimeRecoverDecision` surface

`RuntimeRecoverDecision.decideRecovery` is a pure function. Publishing it is safe (no store deps). But it is referenced from the `RuntimeRecoverPort.recover` adapter's implementation in a way that the consumer never sees — depends on whether the consumer is allowed to substitute their own decoder (which would be a plugin point). The current architecture treats the decoder as the host's internal; publishing it as a public interface is additive and does not require a plugin extension. The future implementer should confirm with the operator whether Fabric needs a custom decoder; if not, `RuntimeRecoverDecision` is publish-as-is.

### 6.7 The M3 handoff cross-link (KNOWN UNKNOWN)

The handoffs folder contains `pk-contract-handoff-cric-m1.md`, `-cric-m2.md`, `-cric-m3.md`. This design covers M2's publication debt. The M3 handoff may address M3 surface; the M3 surface may include additional ports or new contracts that interact with this publication plan. The future implementer should read `pk-contract-handoff-cric-m3.md` before producing the new candidate and reconcile the M3 commitments with this plan.

### 6.8 The release-receipt on-disk absence

`docs/v2/07-uat/evidence/v0.51.0-rc2/release-receipt.md` is in HEAD (git blob `69986a1f`, commit `cc81653c`) but NOT in the working tree. This is likely a sparse-checkout rule, but the orchestrator does not know for certain. Any reference to the receipt in this design is to the HEAD bytes via `git show 69986a1f:docs/v2/07-uat/evidence/v0.51.0-rc2/release-receipt.md` or the zip-extracted copy. The new candidate's release-receipt must be committed to the working tree at the same path.

---

## 7. Relationship to PK-R2 / Fabric integration

### 7.1 What Fabric needs to do

Once `:pipeline-runtime-api` is published (and certified as `v0.52.0-rc1` or `v0.51.0-rc3`), Fabric's integration work is:

1. **Add the new coordinate** to Fabric's SDK consumer build file (mirroring `examples/example-external-consumer/build.gradle.kts` lines 67–78).
2. **Wire the 3 ports into Fabric's adapter** so that Fabric's existing recovery / cancel / inspection flows delegate to the new ports. The M2 handoff (line 86) states Fabric's adapters must be wired against the published M2 ports; today they cannot be because the ports are not published.
3. **Pair-certify** the M2 handoff: from `PREPARED` to `PAIR_CERTIFIED`. The M2 handoff lines 286–296 list three conditions for `PAIR_CERTIFIED`: (a) `pipelinek-release-harness` returns `CERTIFIED`; (b) Fabric accepts the handoff; (c) the cross-matrix passes. Conditions (a) and (c) are gated on this plan's publication.
4. **Capability adoption.** Fabric's existing capability test (`CapabilityRegistrationTest` cross-link) already names the three M2 capabilities; Fabric's adoption does not change the capability contract, it only adds M2 to the SDK surface Fabric consumes.

### 7.2 Fabric-side changes (KNOWN UNKNOWN until Fabric team reads this plan)

Whether Fabric needs to make any source-side changes depends on Fabric's adapter architecture. The orchestrator cannot know without reading Fabric's code (out of scope for this design). Two likely scenarios:

- **Scenario A: Fabric's adapters are pre-wired to M2 but not yet activated.** Source changes are zero. The publication alone unblocks the activation.
- **Scenario B: Fabric's adapters need new wiring.** Source changes are bounded: add a `RuntimeRecoverPort` field to Fabric's coordinator, call it from the recovery hook, etc. The M2 handoff lines 80–88 hint at scenario B by stating that the 0.50.0 host binary "reemplaza el 0.49.0.jar con el mismo set de entry points públicos más las clases públicas del nuevo módulo `:pipeline-runtime`" — Fabric's existing 0.49.0 binary had implicit access to the M2 ports via classpath and would not need new wiring.

The PK-R2 follow-up agent should read Fabric's coordinator and confirm which scenario applies.

### 7.3 PK-R2 sequence

After `:pipeline-runtime-api` is published and Fabric's adapter is wired, PK-R2 is the natural follow-up cycle. PK-R2's task list (not authored here, but indicated by this plan) is approximately:

1. **PK-R2-T1**: read Fabric's coordinator; confirm scenario A or B.
2. **PK-R2-T2**: scenario B source changes (if applicable).
3. **PK-R2-T3**: scenario B contract test in Fabric's test tree.
4. **PK-R2-T4**: pair-certify the M2 handoff (`PREPARED → PAIR_CERTIFIED`).
5. **PK-R2-T5**: cross-matrix passes; release Fabric-side.

This is a sketch; PK-R2's actual planning is a separate exercise.

---

## Document metadata (footer)

| Field | Value |
| --- | --- |
| Reviewed-by | (none — first draft) |
| Approved-by | (none — pending operator) |
| Forwarded-to | next-cycle implementer |
| Companion artifacts | `docs/v2/07-uat/evidence/v0.51.0-rc2/release-receipt.md` (git blob `69986a1f`), `docs/pipelinek-coordinated-evolution/handoffs/pk-contract-handoff-cric-m2.md` (peel `bc5861b7`) |
| Companion commits referenced | `7eaea18b` (M2 public types), `6b8fe207` (M2 production adapters), `83d15127` (M2 tests), `d113721e` (M3 PinnedBytesOutsideRecoveredRegion), `cc81653c` (HEAD) |