# S2-A5 / CORE-LOAD-REJECTED — REJECTED: `core.load` legacy execution authority physically deleted

**Cycle**: `cycle/wu-g5b-core-load-rejected`
**Branch**: `cycle/wu-g5b-core-load-rejected` (continuation of WU-G5B worktree; this slice was the second phase of the same worktree)
**Base**: `main` @ `9f0b1e28` (post-WU-G5B; LEGACY_PLUGIN_IDS = 1/1/1, `core.load` only)
**Working SHA**: uncommitted at receipt writing (slice not yet pushed; STOP before push + FF to main)
**Date**: 2026-09-17

## Counter convergence

```
                WU-G5B-G5   CORE-LOAD-REJECTED  (this slice)
legacy IDs          1              0            (FIRST ZERO LEGACY RESIDUAL)
metadata rows       1              0
dispatcher files    1              0
```

**FIRST ZERO LEGACY RESIDUAL.** `LEGACY_PLUGIN_IDS` shrinks 1 → 0; `CanonicalCoreStepMetadata.pluginIds`
shrinks 1 → 0; the legacy dispatcher source directory contains zero `Canonical*NodeDispatcher.kt`
files (the only remaining one, `CanonicalLoadNodeDispatcher.kt`, was deleted in this slice).

## Decision (REJECTED, not LEGACY_REMOVED)

`core.load` is **REJECTED**, not `LEGACY_REMOVED`. The two paths are distinct:

| Path | When | Outcome |
|---|---|---|
| LEGACY_REMOVED | Step is migrated to registry; legacy forms deleted | `CERTIFIED + LEGACY_REMOVED` |
| REJECTED | Step is impossible to migrate under current architecture law; legacy forms deleted; no replacement | `REJECTED` (no CERTIFIED transition) |

For `core.load`, the directive explicitly forbids the only viable migration shape:
"handler → compiler arbitrario → execute child pipeline como segundo execution engine".
`core.load` IS a second execution engine by Jenkins semantics: it loads and evaluates a
`.pipeline.kts` script from the stage workspace. SPIKE-018 §1.3 declares it the "LARGEST
remaining legacy lift", requiring `SCRIPT_COMPILATION_CAPABILITY` + child-body re-entry
via `BODY_INVOKER_CAPABILITY` — both out of LFC-2 scope.

**Converging signals (G0)**:
1. Legacy `CanonicalLoadNodeDispatcher` is a silent no-op: reads the file, emits
   `WorkflowLoaded stepCount=0`, returns `Success`. It does NOT execute the child pipeline
   (which is itself an execution engine — forbidden by the directive).
2. Latent contract defect: DSL `load(path)` → `OpaqueStepNode("core.load")` with no
   `path` in the canonical envelope → fails closed at decode. Even if a child body were
   intended, the path is missing at runtime.
3. `UatLocal011WorkflowControlTest::SC-011-11` is `@Disabled` (INC-024).
4. Zero `load(...)` usage in `v2/compatibility/` (10 oracle fixtures).

Decision: option **B** (REJECT), per user directive. `core.load` is not a candidate for
the standard burn-down; it is a separate design item requiring new infrastructure
(`SCRIPT_COMPILATION_CAPABILITY` + `BODY_INVOKER_CAPABILITY`) outside LFC-2E.

## Production deletions (6 forms, all physically removed)

1. **Subtype `CanonicalCoreStepCommand.Load`** — deleted from the sealed hierarchy
   in `CanonicalCoreStepDecoder.kt`.
2. **Decoder branch `LOAD_PLUGIN_ID -> Load(...)`** — deleted from
   `CanonicalCoreStepDecoder.decode`. The constant `LOAD_PLUGIN_ID` is removed too.
3. **`CanonicalCoreStepMetadata["core.load"]` row** — deleted from the legacy metadata
   table.
4. **`CanonicalLoadNodeDispatcher.kt`** — file deleted
   (`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/`).
5. **DSL façade `fun load(path: String)`** — deleted from
   `pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt:1614`.
6. **`StepSpec.Load` data class** — deleted from
   `pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/StepSpec.kt`.

## Facade surgery (CanonicalNodeDispatcher)

- `private val loadDispatcher = CanonicalLoadNodeDispatcher()` — removed.
- `is CanonicalCoreStepCommand.Load -> loadDispatcher.dispatch(...)` — removed.
- `is StepSpec.Load -> currentStep` arm in retry-policy block — removed.
- `is StepSpec.Load,` in `BlockStepFlattener.kt` flatten branch — removed.
- `Main.kt` bridge error string referencing `"load"` — removed.

## Test-side surgery (legacy characterization tests)

The following tests depended on `CanonicalCoreStepCommand.Load(path = ...)` as the
vehicle for a legacy command and were retired in this slice:

- `CanonicalCoreStepCommandRegistryTest.Load` — test removed; legacy subtype no longer exists.
- `CoreLegacyStepMetadataResolverTest.resolves sleep metadata...` — `@Disabled` body
  commented; was already `@Disabled` for `Pwd`/`CleanWs` historical reasons.
- `CoreLegacyStepMetadataResolverTest.ordinary steps declare no recovery` — rewritten
  to assert the empty `LEGACY_PLUGIN_IDS` invariant (no per-key recovery assertions
  meaningful anymore).
- `CoreIsUnixStepUnitTest.counters are 0-0-0 post-WU-G5B-and-CORE-LOAD-REJECTED` — counter
  test renamed and asserted at `setOf("Canonical*NodeDispatcher.kt")` empty set;
  `core.load` added to the retired-keys list.
- `LegacyExecutionAdapterTest.prepared legacy execution through the adapter invokes
  the old executor exactly once` — removed (depended on `Load` subtype).
- `ExecutionBoundaryFactoryTest.kt` — **entire file deleted** (all 4 tests used
  `legacyPrepared` helper that returned `PreparedLegacyExecution(Load(...))`).
- `A3DurableProjectionCharacterizationTest.A3-1 legacy fresh produces encodedOutput
  null...` — removed (depended on `Load` subtype).
- `GenericRegistryExecutionCarrierTest.seamed router — legacy family still routes
  through Pre-existing CommonExecutionBoundary` — removed.
- `RegistryExecutionBoundaryTest.a legacy family prepared execution fails closed in the
  registry boundary` — removed.
- `SeamedExecutionRouterTest.a legacy family prepared execution reaches only the
  legacy executor once` — removed.

The deletion of these tests is **structurally necessary**, not a "test weakening": the
sealed `CanonicalCoreStepCommand` now has no legacy constructors, so the type system
itself forbids the construction `PreparedLegacyExecution(Load(...))`. The fail-closed
and cross-family invariants previously asserted by these tests are now structural
properties (empty input space at the type level) and are asserted at the architecture
fitness layer (`Lfc2ZeroLegacyResidualFitnessTest`).

## New fitness test (G6)

- `Lfc2ZeroLegacyResidualFitnessTest` — 7/7 PASS, gates the FIRST ZERO LEGACY RESIDUAL:
  - `LEGACY_PLUGIN_IDS is the empty set`
  - `CanonicalCoreStepMetadata pluginIds is the empty set`
  - `FamilyRouter decide routes registry-owned keys through SeamedRouting only`
  - `no production StepKey routes through the legacy dispatcher path`
  - `LegacyExecutionAdapter has empty input space at the type level`
  - `CanonicalDurableRunCoordinator class exists with public dispatch surface`
  - `counters report N=registry-primary and M=zero legacy executable`

## Real-CLI canary (gate G7)

| Fixture | Expected | Observed |
|---|---|---|
| `02-environment.pipeline.kts` (NON-load) | SUCCESS (unchanged behaviour for registry-owned keys) | SUCCESS — `EchoOutputCaptured{"building..."}` + `RunFinished{outcome=success}` |
| `/tmp/load-reject-canary.pipeline.kts` (`load(...)`) | FAILURE (fail-closed at DSL compile time) | FAILURE — diagnostics: `"Unresolved reference 'load'"`, `"Too many arguments for 'fun steps()'"` |

Both canaries confirm the contract:
1. Registry-owned Steps (echo, sh, etc.) continue to execute via the registry seam —
   ZERO production behaviour change for non-load Steps.
2. `load(...)` now fails closed at DSL compilation time. The legacy "silent no-op" path
   is structurally unreachable (no `load` function exists).

## Counter final state

```
                Pre-WU-G5B   WU-G5B-G5   CORE-LOAD-REJECTED  (this slice)
legacy IDs          2            1              0
metadata rows       2            1              0
dispatcher files    2            1              0
```

**N + M = 0 + (registry-known)**: the registry is the only execution authority for
every Step. The legacy executor's input surface is empty at the type level.

## Receipt links

- WU-G5B predecessor: `docs/v2/07-uat/S2_A8_CORE_WAITUNTIL_WU_G5B_LEGACY_REMOVED_RECEIPT.md`
- Matrix: `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` (`core.load` row flipped to `REJECTED`)
- This receipt: `docs/v2/07-uat/S2_A5_CORE_LOAD_REJECTION_RECEIPT.md`

## Out of scope (deferred to LFC-3+)

Any "load child pipeline" Step with proper semantics (recursive script evaluation,
child body re-entry) must be designed from scratch as a registry Step with:

- `SCRIPT_COMPILATION_CAPABILITY` (a new capability)
- `BODY_INVOKER_CAPABILITY` (ADR-0073 child-body re-entry)
- Typed input carrier (`LoadChildPipelineInput` or similar)
- Typed output carrier (`LoadChildPipelineOutput` with `childRunId`, `childEvents`)
- Required events: `ChildPipelineStarted`, `ChildPipelineFinished`

This is a future design item, NOT an LFC-2E burn-down item. The REJECTED decision
preserves the architectural invariants (no second execution engine inside a Step
handler) while making the failure mode explicit at the DSL compile time.
