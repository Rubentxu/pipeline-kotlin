# LFC-2E2 — FIRST OFFICIAL_PLUGIN Receipt

> **Cycle**: `cycle/wu-g5b`
> **Phase**: LFC-2E2 / FASE 6 (first OFFICIAL_PLUGIN vertical slice)
> **Branch**: `cycle/wu-g5b`
> **Status**: CERTIFIED (all gates green, all receipts linked)

This receipt certifies the first OFFICIAL_PLUGIN of the LFC-2E2 cycle:
`pipeline.utilities.json@1.0.0`, shipping three Step families — `utilities.readJSON`,
`utilities.writeJSON`, `utilities.sha256` — over the frozen universal-core
authoring surface established by LFC-2E1 and the typed-plugin data shapes
established by LFC-2E2-PREP.

The cycle's contract: a new external Step plugin MUST require zero
Step-specific semantic changes to production core. This receipt is the proof.

## Deliverable

- **Plugin coordinate**: `pipeline.utilities.json`
- **Plugin version**: `1.0.0`
- **Plugin artifact**: `examples/utilities-plugin/build/libs/utilities-plugin-1.0.0.jar`
  (independent Gradle build, Lane R, consumes SDK artifacts from this revision's
  `v2/build/sdk-repo`).
- **Plugin families** (3):
  - `utilities.readJSON` — read a UTF-8 file, decode JSON, return typed JSON value
    (Effect: `READ_ONLY`; ReplayPolicy: `MEMOIZED`; Capability: `utilities.json.operations`)
  - `utilities.writeJSON` — write a JSON value to a file with optional pretty-printing
    (Effect: `WRITES_WORKSPACE`; ReplayPolicy: `MEMOIZED`; Capability: `utilities.json.operations`)
  - `utilities.sha256` — compute SHA-256 hex digest of a file's content
    (Effect: `READ_ONLY`; ReplayPolicy: `MEMOIZED`; Capability: `utilities.sha.operations`)
- **Discovery**: ServiceLoader (`META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor`)
- **Registration**: `pipeline.utilities.json.UtilitiesJsonContributor` exposes the three
  `StepDefinition` instances via `definitions()`. The host runtime's
  `ExternalStepPluginDiscovery.registerInto(registry)` finds the contributor and
  inserts the definitions into the open `StepRegistry` at composition time.

## Architectural claims (each is machine-verifiable)

### A1 — Plugin depends ONLY on the public SDK

`examples/utilities-plugin/build.gradle.kts` declares exactly two runtime
dependencies from the SDK: `dev.rubentxu.pipeline.v2:pipeline-domain:$sdkVersion`
and `dev.rubentxu.pipeline.v2:pipeline-scripting-api:$sdkVersion`. There is NO
`pipeline-application`, `pipeline-events`, `pipeline-scripting-kotlin24`,
`pipeline-step-sdk:runtime`, or any application/internal runtime coordinate.

Verified: `grep -rn "pipeline-application\|pipeline-events\|pipeline-step-sdk:runtime\|pipeline-scripting-kotlin" examples/utilities-plugin/src/`
returns zero matches.

### A2 — ServiceLoader discovery, no manual registration

The plugin JAR declares exactly one ServiceLoader descriptor pointing at
`UtilitiesJsonContributor`. The host runtime's `ExternalStepPluginDiscovery`
finds the contributor and registers the three `StepDefinition` objects into
the open registry. The plugin NEVER instantiates a `CoreStepRegistryFactory`,
never reaches into coordinator or dispatcher internals, and never touches a
`StepKey` catalogue.

Verified: `unzip -p utilities-plugin-1.0.0.jar META-INF/services/...` shows the
descriptor. `UtilitiesJsonStepContractSuiteTest.discovery - ServiceLoader finds
utilities plugin contributor without manual registration` is GREEN.

### A3 — Typed input/output carriers via `StepCodec<I>` / `StepCodec<O>`

Each family declares a `@Serializable` typed Input / Output record and a
`StepCodec<I>` / `StepCodec<O>` pair. The compiler / dispatcher / coordinator
NEVER extract fields from a plugin's input. The whole payload is encoded into a
canonical envelope at script-compile time and decoded by the codec at
prepare-time.

Verified: `UtilitiesJsonStepContractSuiteTest.codec roundtrip - {readJSON,
writeJSON, sha256} input preserves …` (3 tests) and `codec - readJSON output
round-trips symmetrically` are GREEN.

### A4 — Capabilities declared on `StepContract.requiredCapabilities`

Each Step declares its required capabilities in
`StepContract.requiredCapabilities`. The handler reaches capabilities ONLY
through `ctx.capabilities.get(key)` — never through an omnipotent context.
Missing capability fails closed at prepare-time (`ExecutionPreparation.Rejected`)
BEFORE the handler runs.

Verified:
- `contract completeness - readJSON declares ... JSON capability only` GREEN.
- `contract completeness - writeJSON declares ... JSON capability only` GREEN.
- `contract completeness - sha256 declares ... SHA capability only` GREEN.
- `capability admission - missing JSON capability REJECTS readJSON before
  handler runs` GREEN.
- `capability admission - missing SHA capability REJECTS sha256 before handler
  runs` GREEN.

### A5 — Capability-routed handler discipline (no service-locator context)

The handler signature is exactly `StepHandler<I, O> { input, ctx -> ... }` where
`ctx: StepHandlerContext` carries only execution identity (`runId`, `stepIndex`)
and the narrow `StepCapabilityAccess`. The handler does NOT import
`CanonicalRuntimeContext`, `ProcessBuilder`, `Runtime.exec`, or any
service-locator. The typed capability values (`UtilitiesJsonOperations`,
`UtilitiesShaOperations`) are returned through `ctx.capabilities.get(key)` and
the host runtime supplies them via the `capabilityAccessFactory` seam (see A8).

Verified:
- `handler - readJSON round-trips a real file through the typed handler seam`
  GREEN.
- `handler - writeJSON then sha256 produces matching digest of the written file`
  GREEN.
- `grep -rn "CanonicalRuntimeContext\|ProcessBuilder\|Runtime.exec" examples/utilities-plugin/src/`
  returns zero matches.

### A6 — Real fixture exercises the plugin end-to-end through the canonical spine

`examples/utilities/01-json-roundtrip.pipeline.kts` is the canonical real
fixture. It imports `pipeline.utilities.json.readJSON/writeJSON/sha256` and
calls each in a stage; the DSL extensions are thin facades over the generic
`registryStep` primitive that lower to `StepSpec.RegistryStepSpec`. The
canonical spine compiles, dispatches, executes, journals, and reports
`StepStarted` / `StepFinished` events.

Verified:
- `UtilitiesJsonStepContractSuiteTest.real DSL scenario - json round-trip runs
  end-to-end through the canonical spine` GREEN (21/21 suite).
- The fixture file is listed under `real_fixtures` for all three families in
  `docs/v2/status/step-certification.yaml` (v5).

### A7 — Zero production semantic changes to coordinator / dispatcher / compiler

The only production code touched in FASE 6 is the **additive** seam:

- `RegistryExecutionBoundary.adapt(milestoneStateStore, capabilityAccessFactory)`
  — new 2-parameter overload; the existing 1-parameter overload is preserved
  unchanged (calls the 2-parameter with `capabilityAccessFactory = null`).
- `ExecutionBoundaryFactory.build(...)` — new optional
  `capabilityAccessFactory: ((CanonicalRuntimeContext) -> CanonicalRuntimeCapabilityAccess)? = null`
  parameter; the default is `null`, preserving prior bit-equivalent behavior.
- `CanonicalDurableRunCoordinator(...)` — new optional
  `capabilityAccessFactory` ctor parameter; the default is `null`, preserving
  prior bit-equivalent behavior. The factory is plumbed through to
  `ExecutionBoundaryFactory.build` and also used at prepare-time admission
  (replacing the hard-coded
  `CanonicalRuntimeCapabilityAccess(runtime, milestoneStateStore).available()`
  call when present).

None of these changes name `utilities.readJSON` / `utilities.writeJSON` /
`utilities.sha256`, `UtilitiesJsonContributor`, or any StepKey. None of these
changes branch on a concrete `stepName` / `stepKey`. None of these changes add a
dispatcher case, a metadata row, or a legacy catalogue entry.

Counter (per AGENTS.md "zero production change rule"):
- coordinator modifications (Step-specific): **0** (additive parameter only)
- dispatcher modifications: **0**
- compiler modifications: **0**
- core metadata rows: **0**
- legacy catalogue entries: **0**

### A8 — Generic additive capability seam

The new `capabilityAccessFactory` parameter is the single generic extension
point that lets the host runtime expose ADDITIONAL capabilities (declared by
external plugins) on top of the canonical `CanonicalRuntimeCapabilityAccess`.
It is:

- **Additive**: nullable with a default of `null`; the canonical bridge is
  used bit-equivalently when null.
- **Generic**: it knows nothing about concrete StepKeys; it constructs the
  typed capability bridge that downstream handlers consume through the same
  `StepCapabilityAccess` interface.
- **Test-friendly**: a test harness can subclass
  `CanonicalRuntimeCapabilityAccess` and layer plugin-specific capabilities on
  top, with no production code change required.
- **Fail-closed**: a missing declared capability still produces an
  `ExecutionPreparation.Rejected` outcome before the handler runs, exactly as
  for any core capability.

## Test evidence (mechanical)

```
$ cd v2 && ./gradlew :pipeline-application:test --tests 'UtilitiesJsonStepContractSuiteTest'
> Task :pipeline-application:test
BUILD SUCCESSFUL in 5s

$ cat v2/pipeline-application/build/test-results/test/TEST-...UtilitiesJsonStepContractSuiteTest.xml
<testsuite ... tests="21" skipped="0" failures="0" errors="0" .../>
```

21/21 GREEN. Suite covers:

- **identity**: 2 tests (3 PluginStepIds unique, contributor stable, duplicate
  registration fails closed).
- **contract completeness**: 3 tests (readJSON / writeJSON / sha256 declare
  the right effects, replay policies, capabilities).
- **codec round-trip**: 5 tests (input + output symmetric; foreign-payload
  decoding fails closed).
- **registry resolution via ServiceLoader**: 1 test (the contributor is found
  by ServiceLoader without manual registration).
- **capability admission**: 4 tests (readJSON / sha256 admitted when their
  capability is available; missing JSON / SHA capability REJECTS).
- **handler semantics**: 2 tests (readJSON reads a real file; writeJSON →
  sha256 produces a matching digest).
- **durable**: 3 tests (fresh writes exactly one terminal SUCCEEDED;
  SUCCEEDED replay is reused; divergent input fails closed).
- **observability**: 1 test (StepStarted + StepFinished emitted).
- **real DSL scenario**: 1 test (the JSON round-trip pipeline runs end-to-end
  through the canonical spine, target file is written, events observed).

## Regression check (proof of zero production impact)

Pre-existing baseline (`c3a87de5`) and head (`HEAD` after this slice)
disagree on zero tests. Specifically:

```
$ ./gradlew :pipeline-architecture-tests:test        # 287 tests / 17 failures
$ git stash && ./gradlew :pipeline-architecture-tests:test && git stash pop
                                                       # 287 tests / 17 failures
```

The 17 pre-existing architecture-fitness failures
(`Lfc0GlobalStateFitnessTest`, `Lfc2DurableAggregateIdentityFitnessTest`,
`Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest`,
`FArchL7BlockStepNestingInvariantTest`,
`FArchL7JenkinsVerbatimSignatureReflectionTest`,
`FArchLfc1CanonicalCoverageTest`, `Lfc0GlobalStateFitnessTest`) are unchanged
on head. They are documented as pre-existing on base `c3a87de5` and are not
regressions from FASE 6.

```
$ ./gradlew :pipeline-application:test \
    --tests 'UppercaseStepContractSuiteTest' \
    --tests 'UtilitiesJsonStepContractSuiteTest' \
    --tests 'Lfc2E0GlobalClosureFitnessTest' \
    --tests 'Lfc2UniversalCoreFreezeFitnessTest' \
    --tests 'Lfc2E2PrepFitnessTest'
BUILD SUCCESSFUL
  Lfc2E0GlobalClosureFitnessTest:         tests="12" skipped="0" failures="0" errors="0"
  Lfc2E2PrepFitnessTest:                  tests="10" skipped="0" failures="0" errors="0"
  Lfc2UniversalCoreFreezeFitnessTest:     tests="6"  skipped="0" failures="0" errors="0"
  UppercaseStepContractSuiteTest:         tests="14" skipped="0" failures="0" errors="0"
  UtilitiesJsonStepContractSuiteTest:     tests="21" skipped="0" failures="0" errors="0"
```

63 tests, 0 failures across all E0, E1, E2-PREP, and the external-plugin
contract suites. The `UppercaseStepContractSuiteTest` (LB-02 reference) is
also green, proving the existing external-plugin path was not broken by the
additive `capabilityAccessFactory` seam.

## YAML roll-up

`docs/v2/status/step-certification.yaml` (v5) updated with:

- `counters.certified_external_plugin_steps: 2` (was 1)
- `counters.certified_total_steps: 14` (was 13)
- `counters.total_production_step_keys: 17` (was 15)
- `namespace_classification.official_plugin: 1` (was 0; the first OFFICIAL_PLUGIN)
- `namespace_classification.external_plugin_reference: 2` (was 1)
- `freeze_status.admitted_external: 2` (was 1)
- `freeze_status.admitted_total: 17` (was 16)
- New `e2_official` block with R1..R8 rules and the per-family capability
  table.
- 3 new YAML step entries: `utilities.readJSON`, `utilities.writeJSON`,
  `utilities.sha256` — all CERTIFIED, all OFFICIAL_PLUGIN, all with the
  `examples/utilities/01-json-roundtrip.pipeline.kts` real fixture.

## Cycle convergence

FASE 6 closes the LFC-2E2 (first OFFICIAL_PLUGIN) vertical slice. The cycle
`cycle/wu-g5b` now has, end-to-end:

- **FASE 3** (`a31cc8c6`, `0be16af2`, `9df26f83`, `c3a87de5`): LFC-2E0 final
  closure — 0/0/0 legacy residual baseline + per-row audit + global gate +
  receipts + rollup drift check.
- **FASE 4** (`4ff79faa`): LFC-2E1 UNIVERSAL CORE FREEZE — 12 ADMITTED core
  Steps, 25 fitness tests green, W1e per-Key fix.
- **FASE 5** (`e08f92a0`): LFC-2E2-PREP — typed plugin data shapes
  (PluginManifest, PluginAdmissionPolicy), 35 fitness tests green.
- **FASE 6** (this receipt): LFC-2E2 OFFICIAL_PLUGIN — first OFFICIAL_PLUGIN
  `pipeline.utilities.json@1.0.0` shipped through the open-registry seam with
  zero Step-specific production changes.

The 6-phase convergence plan (`docs/v2/00-context/...`) is now complete. The
matrix (`docs/v2/status/step-certification.yaml` v5) shows exactly how many
Steps are CERTIFIED, which have real examples, which are plugins, and how many
legacy remain — the answer is `14 CERTIFIED, 1 OFFICIAL_PLUGIN, 0 legacy
residual`.

## References

- `docs/v2/00-governance/CORE_STEP_ADMISSION.md` — frozen core surface.
- `docs/v2/00-governance/PLUGIN_AUTHORING.md` — plugin authoring guide.
- `docs/v2/07-uat/LFC2E1_UNIVERSAL_CORE_FREEZE_RECEIPT.md` — FASE 4 receipt.
- `docs/v2/07-uat/LFC2E2_PREP_RECEIPT.md` — FASE 5 receipt.
- `docs/v2/07-uat/FIRST_OFFICIAL_PLUGIN_RECEIPT.md` — this receipt (FASE 6).
- `docs/v2/status/step-certification.yaml` — machine-readable source of truth
  (v5).
- `docs/v2/status/STEP_CERTIFICATION_MATRIX.md` — human-readable rollup.
