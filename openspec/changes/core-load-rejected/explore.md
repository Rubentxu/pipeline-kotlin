# Explore — core.load REJECTED + LEGACY_REMOVED (FASE 2 of the LFC-2E0 closure plan)

## Goal

Honest resolution of `core.load`: classify as `REJECTED + SUPERSEDED`, remove the
legacy executable path completely, and preserve durable evidence. Reduce
LEGACY_PLUGIN_IDS residual counter from **1 / 1 / 1 → 0 / 0 / 0**.

This is the FIRST ZERO LEGACY RESIDUAL in the LFC-2E0 burn-down. The legacy
inventory is empty.

## G0 diagnosis (honest)

Five observations that converge on REJECTION:

1. **Stub + latent contract defect (SPIKE-018, 2026-09-12).**
   `core.load` is currently classified `LEGACY_IMPLEMENTED_UNCERTIFIED`.
   - The legacy dispatcher `CanonicalLoadNodeDispatcher.kt` does NOT execute
     the loaded script. It reads the file, computes SHA-256, emits a
     `WorkflowLoaded` event with `stepCount = 0`, and returns `Success`.
     Comment at lines 38-40 admits: *"The actual compilation and step
     execution is deferred to the coordinator level"* — but the coordinator
     has no load handling at all.
   - The compiler (`DslCompiledPipelineCompiler.stepNode`) has no
     `StepSpec.Load` case. Load falls into the generic `else`, which calls
     `encodePayload(step)` — itself with no Load case, so it hits
     `else -> put("declarativeValue", step.toString())`. The emitted payload
     is `{"kind":"load","declarativeValue":"Load(path=...)"}` — **`path`
     is never encoded**.
   - The decoder, however, *requires* `path`
     (`CanonicalCoreStepDecoder.kt` `payload.requiredString("path")`).
     **Result: any `.pipeline.kts` containing `load(...)` compiled through
     the canonical path fails closed at decode with
     `IllegalArgumentException: dsl-v1 payload requires string 'path'`**. The
     load family has no green production execution path today.

2. **SC-011-11 is `@Disabled` (INC-024 carry-forward).** The acceptance test
   that would prove real load semantics (`UatLocal011WorkflowControlTest.kt`
   lines 482-484) is quarantined with the comment:
   > "INC-024: load step produces child steps; coordinator does not yet
   > inject loaded pipeline into execution flow. Pre-existing on c88d5c88
   > (v0.32.2)."

3. **SPIKE-018 declares load the LARGEST remaining legacy lift.** It needs:
   - `SCRIPT_COMPILATION_CAPABILITY` (new horizontal boundary, port + adapter)
   - Child-body re-entry through `BODY_INVOKER_CAPABILITY` (production wiring
     not yet landed)
   - Real child-execution semantics, fingerprint identity, control-journal
     integration for restart mid-load.
   This is the explicit SPIKE-018 verdict: *"load burn-down comes LAST among
   the remaining legacy entries"*.

4. **The directive forbids the only realistic implementation shape.**
   *"Prohibido crear: handler → compiler arbitrario → execute child pipeline
   como segundo execution engine."* The directive rules out option (a)
   (nested child run) and option (b) (compile-time inlining) from
   SPIKE-018. Option (c) (BodyInvoker re-entry) is technically possible but
   requires the two new horizontal boundaries that SPIKE-018 says should be
   productionized independently first.

5. **No fixtures use `load`.** `grep -rn 'load(' v2/compatibility` returns
   zero matches. The Step has zero real-world usage evidence in the
   compatibility corpus. The legacy executable is dead code that, if
   invoked, would fail-closed at decode.

## Decision

`core.load` is **`REJECTED`** with `Delivery = REJECTED`, `State = REJECTED`.

- **NOT** to be carried forward as `IMPLEMENTED_UNCERTIFIED` (would require
  real implementation; out of scope; contradicts the model V2 + directive).
- **NOT** to be deferred as a future cycle (per SPIKE-018 it should be the
  LAST legacy entry, AFTER the two new horizontal boundaries are
  productionized — which is multiple cycles away and not on this
  convergence path).
- **NOT** to be classified as `OFFICIAL_PLUGIN` (would require splitting the
  existing canonical surface; plugin would still need the script compilation
  capability, which doesn't exist).
- **NOT** to be classified as `EXTERNAL_REFERENCE` (the Step is a core
  concept, not a vendor surface).

`REJECTED` is the only honest classification given the SPIKE-018 evidence
and the directive's explicit prohibition.

## Surface to delete

| File | Lines | Status after this slice |
| --- | --- | --- |
| `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalLoadNodeDispatcher.kt` | entire file | DELETED |
| `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalNodeDispatcher.kt` | `private val loadDispatcher = CanonicalLoadNodeDispatcher()`; `is CanonicalCoreStepCommand.Load -> loadDispatcher.dispatch(...)`; `loadContext()` helper | FIELDS/BRANCH/HELPER removed; `when` stays EXHAUSTIVE |
| `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt` | `data class Load(...)` (line 199); `LOAD_PLUGIN_ID` constant (line 254); `LOAD_PLUGIN_ID ->` decoder branch (line 281); `"core.load",` in `LEGACY_PLUGIN_IDS` (line 162) | ALL removed; provenance comment block summarising the slice added |
| `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepMetadata.kt` | `"core.load" to StepMetadata(...)` row | row removed |
| `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Main.kt` | "core.load" mention in NON_CANONICAL_CANONICAL_BRIDGE_ERROR | string updated to remove `core.load` |
| `v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt` | `data class Load(...)` sealed subtype (line 629); `is StepSpec.Load -> currentStep` case (line 1257); `fun load(path: String)` DSL function (line 1673) | data class, case in sealed hierarchy, and DSL function all removed; provenance comment added |
| `v2/pipeline-step-sdk/api/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/api/BlockStepFlattener.kt` | `is StepSpec.Load,` in the terminal-step list (line 193) | case removed; provenance comment added |

## Test files to update (no production logic touched)

- `CoreIsUnixStepUnitTest.kt::counters are 0-0-0 post-core.load-REJECTED` — counter 1-1-1 → 0-0-0
- `CanonicalCoreStepCommandRegistryTest.kt` — sealedSubclasses.size 1 → 0; LEGACY_PLUGIN_IDS set updated
- `CoreArchiveArtifactsStepUnitTest.kt`, `CoreCleanWsStepContractSuiteTest.kt`,
  `CoreDeleteDirStepUnitTest.kt`, `CoreIsUnixRegistryPrimaryFitnessTest.kt`,
  `CorePwdRegistryPrimaryFitnessTest.kt`, `CoreWriteFileRegistryPrimaryFitnessTest.kt`,
  `CoreArchiveArtifactsStepContractSuiteTest.kt`, `CoreSleepRegistryPrimaryFitnessTest.kt`,
  `CoreEmitEventRegistryPrimaryFitnessTest.kt`, `CoreErrorRegistryPrimaryFitnessTest.kt` —
  assertions updated from `setOf("core.load")` to `setOf()`; size assertions updated 1 → 0.
- `UatLocal011WorkflowControlTest::SC-011-11 load executes script content` — DELETE
  (test was `@Disabled` quarantine; no longer meaningful without a load Step).
- `LegacyResidualConvergenceFitnessTest.kt` (architecture test) — once
  `physicalResidual = setOf()` and `LegacyResidualSnapshot.physicalResidual`
  is empty, the burn-down is **fully closed**: this fitness test's
  "anti-vacuity" row returns early on empty declared residual.

## Architecture fitness authority update

- `LegacyResidualSnapshot.physicalResidual` — `setOf("core.load")` →
  `setOf()`. Counter converges 1/1/1 → **0/0/0**.
- The "Anti-vacuity" row in `LegacyResidualConvergenceFitnessTest` returns
  early on empty residual (the test was designed with `if (expected.pluginIds.isEmpty()) return`).
  This is the intended exit: the burn-down ledger is fully closed.

## Informational text

- `Main.kt::NON_CANONICAL_CANONICAL_BRIDGE_ERROR` — remove `core.load` from
  the canonical bridge plugin list (it is no longer canonical).

## Files NOT to touch (scope firewall)

- `UatLocal011WorkflowControlTest.kt` other SC-* tests (except SC-011-11) — keep
- `LegacyResidualConvergenceFitnessTest.kt` body — update only the assertion
  targets when the snapshot changes; keep the structural assertions
- `LegacyResidualSnapshot.kt` other than `physicalResidual` — keep
- All other `S3*LegacyRemovedFitnessTest` per-Step suites — they assert
  absence of their own retired key; they are not load-specific

## Risk

- This is the **first ZERO LEGACY RESIDUAL** in the project history. The
  anti-vacuity guard explicitly accommodates empty residual (it returns
  early when expected is empty, so it does not require non-empty). The
  counter assertion becomes a positive statement: "no LEGACY_PLUGIN_IDS
  entries remain" rather than "the residual is converged". The fitness
  test must be re-read to verify the empty-residual exit semantics are
  correct.
- Removing `StepSpec.Load` from `PipelineDsl.kt` is a sealed-hierarchy
  change. Every `StepSpec` case analysis in the codebase must be audited
  to ensure no exhaustive `when` on `StepSpec` now has a missing case.
  Affected: `BlockStepFlattener.kt:193-197` (terminal-step list), the
  compiler dispatch (no direct case), and any consumer that does
  `is StepSpec.Load` (none found outside the 3 sites listed above).
- The DSL `load(path)` is removed. Any user with `.pipeline.kts` files
  that include `load(...)` will get a compile error at script build time.
  This is the intended fail-closed outcome (the legacy executable path
  never worked anyway, see §1.2 / §1.4 of SPIKE-018). Documented in the
  rejection receipt.

## Pre-existing reds (do NOT widen)

Per `wu-g5-restore/tasks.md §0.3` + WU-G5B receipt — same set, still authoritative:

```text
CanonicalDurableRunCoordinatorTest          26 / 11
CompatibilityCorpusTest                     20 / 2 (incl. pre-existing fixture14 RED)
Lfc0GlobalStateFitnessTest                  1 (KDoc false positive)
UAT 005 / 007 / 008 / 009                   baseline
fixture14 (credentials)                     baseline
```

Slice gate enforces none of these counts goes UP.

## Acceptance

When the slice is done:

- LEGACY_PLUGIN_IDS residual counter → **0 / 0 / 0** (LEGACY burn-down
  fully closed).
- `core.load` → `REJECTED`, `Delivery = REJECTED`, `State = REJECTED`.
- The DSL `load(path)` function and `StepSpec.Load` subtype physically
  removed (no compile path).
- No production fixture uses `load` (verified; no breakage).
- `LegacyResidualConvergenceFitnessTest` PASS with empty residual.
- Receipt `docs/v2/07-uat/S2_A5_CORE_LOAD_REJECTION_RECEIPT.md` committed.
- `STEP_INVENTORY_LFC2E0.md` updated: `core.load` row marked `REJECTED`.
- `STEP_ECOSYSTEM_MATRIX.md` row updated: `core.load` → `Delivery=REJECTED, State=REJECTED`.

This is the explicit "Crea evidencia explícita para el primer ZERO LEGACY RESIDUAL"
checkpoint required by the directive.
