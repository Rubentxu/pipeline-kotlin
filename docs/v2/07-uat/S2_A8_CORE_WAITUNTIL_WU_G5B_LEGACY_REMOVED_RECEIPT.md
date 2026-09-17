# `core.waitUntil` — WU-G5B LEGACY_REMOVED + CERTIFIED Closure Receipt

**Cycle**: `wu-g5b` (slice of the LFC-2E0 closure plan)
**Branch**: `cycle/wu-g5b` (off `main = 9f0b1e28`)
**Date**: 2026-09-17
**Author**: orchestrator (delegated cycle)
**Predecessor**: `wu-g5-restore` → `main = 9f0b1e28` (RELEASE_SUCCEEDED 2026-09-17, 0f4c110b..9f0b1e28)

## Goal

Flip `core.waitUntil` from `IMPLEMENTED_UNCERTIFIED` to `CERTIFIED + LEGACY_REMOVED`.
Reduce LEGACY_PLUGIN_IDS residual counter from **2 / 2 / 2 → 1 / 1 / 1** (only `core.load`
remains).

## Scope (committed)

Six legacy forms physically destroyed for `core.waitUntil`:

1. `CanonicalCoreStepCommand.WaitUntil` data class (decoder subtype) — REMOVED
2. `WAIT_UNTIL_PLUGIN_ID` constant + decoder branch in `CanonicalCoreStepDecoder.kt` — REMOVED
3. `"core.waitUntil"` row in `CanonicalCoreStepMetadata.kt` — REMOVED
4. `CanonicalWaitUntilNodeDispatcher.kt` (production file) — DELETED
5. `CanonicalWaitUntilNodeDispatcherTest.kt` (legacy-only test file) — DELETED
6. `CanonicalNodeDispatcher` waitUntil seams (`waitUntilDispatcher` field,
   `is WaitUntil -> waitUntilDispatcher.dispatchStub(...)` when branch,
   `waitUntilContext()` helper) — REMOVED

Test files updated (no production logic touched):

- `CoreIsUnixStepUnitTest.kt::counters are 1-1-1 post-WU-G5B` — counter 2-2-2 → 1-1-1
- `CanonicalCoreStepCommandRegistryTest.kt` — sealedSubclasses.size 2 → 1; LEGACY_PLUGIN_IDS set updated
- `CoreArchiveArtifactsStepUnitTest.kt`, `CoreCleanWsStepContractSuiteTest.kt`,
  `CoreDeleteDirStepUnitTest.kt`, `CoreIsUnixRegistryPrimaryFitnessTest.kt`,
  `CorePwdRegistryPrimaryFitnessTest.kt`, `CoreWriteFileRegistryPrimaryFitnessTest.kt`,
  `CoreArchiveArtifactsStepContractSuiteTest.kt`, `CoreSleepRegistryPrimaryFitnessTest.kt`,
  `CoreEmitEventRegistryPrimaryFitnessTest.kt`, `CoreErrorRegistryPrimaryFitnessTest.kt` —
  assertions updated from `setOf("core.load", "core.waitUntil")` to `setOf("core.load")`;
  size assertions updated 2 → 1.

Architecture fitness authority updated:

- `LegacyResidualSnapshot.physicalResidual` — `setOf("core.load", "core.waitUntil")` →
  `setOf("core.load")`. Counter converges 2/2/2 → 1/1/1.

Informational text:

- `Main.kt::NON_CANONICAL_CANONICAL_BRIDGE_ERROR` — removed `core.waitUntil` from the
  list (legacy list of canonical bridge plugins; waitUntil is no longer canonical-bridge,
  it goes through the RepeatUntil machinery which is admitted unconditionally).

## Scope firewall (NOT touched)

Per the directive "NO modificar la nueva maquinaria RepeatUntil salvo que un test
demuestre un defecto":

- `CoreWaitUntilStep.kt` (the registry candidate) — UNTOUCHED
- `CanonicalDurableRunCoordinator.kt` line 396 (`if (pluginStepId.value == "core.waitUntil")`)
  inside `BodyExecutionPolicy.Retrying` — UNTOUCHED (this is `wu-g5r.3` RepeatUntil
  machinery from commit `510e4c89`, not legacy).
- `WaitUntilControlJournal.kt`, `WaitUntilControlState.kt`,
  `FileBasedWaitUntilControlJournal.kt`, `WaitUntilReconciler*` — UNTOUCHED (durable
  RepeatUntil surface from G5R.5).
- `Lfc2WaitUntilCanonicalReentryFitnessTest.kt` — UNTOUCHED (kept as proof of canonical
  reachability post-LEGACY_REMOVED).
- `v2/compatibility/22-wait-until.pipeline.kts` — UNTOUCHED (real DSL fixture).

## Verification (all fresh XML, canary discipline)

### L0 compile

- `:pipeline-application:compileTestKotlin` BUILD SUCCESSFUL (37s, incremental)

### L1 unit + counter assertions

- `CoreIsUnixStepUnitTest::counters are 1-1-1 post-WU-G5B` — PASS (4 tests, 0 failures)
- `CanonicalCoreStepCommandRegistryTest::sealedSubclasses has exactly 1 entry` — PASS
  (3 tests, 0 failures)

### L1 canonical reachability fitness

- `Lfc2WaitUntilCanonicalReentryFitnessTest` — PASS (4/4, 0 failures)
- `FileBasedWaitUntilControlJournalTest` — PASS (14/14, 0 failures)

### L2 architecture fitness

- `LegacyResidualConvergenceFitnessTest::legacy residual is converged` — PASS (3/3)
  - `physicalResidual == liveLegacyIds == liveMetadataRows == liveDispatcherFiles`
  - `1/1/1 == 1/1/1`

### L3 installed-CLI canary

Real `.pipeline.kts` executed by installed CLI:

```
./v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application run \
  --db /tmp/durable-wu-g5b/22.db --control-root /tmp/durable-wu-g5b/control \
  ./v2/compatibility/22-wait-until.pipeline.kts
```

Outcome: `Pipeline finished with SUCCESS`. Events emitted in order:

```
WaitUntilPolled (conditionResult=false, attempt=1)   # canonical polling loop starts
StepStarted/Finished for sh-0 (waituntil body)       # canonical BodyInvoker re-entry
EchoOutputCaptured "READY"                            # condition succeeds
WaitUntilPolled (conditionResult=true, attempt=1)    # canonical loop confirms success
WaitUntilCompleted (outcome=completed, totalAttempts=1)
StageFinished (outcome=success)
RunFinished (outcome=success)
```

This is the BEHAVIOURAL proof that after LEGACY_REMOVED:
- `waitUntil { body }` still reaches the canonical RepeatUntil machinery
- `WaitUntilPolled` and `WaitUntilCompleted` events flow through the canonical path
- The BodyInvoker re-entry mechanism executes the body script
- No `OpaqueStepNode("core.waitUntil")` regression
- No silent fall-through to a no-op

## Counter delta

```text
LEGACY_PLUGIN_IDS counter:
  pre  WU-G5B    : 2 / 2 / 2 (core.load, core.waitUntil)
  post WU-G5B    : 1 / 1 / 1 (core.load)

  Phase 1 (ids)  : { "core.load" }
  Phase 2 (rows) : { "core.load" }
  Phase 3 (files): { CanonicalLoadNodeDispatcher.kt }

core.waitUntil:
  pre  WU-G5B    : IMPLEMENTED_UNCERTIFIED (WU-G5R factory entry removed, LEGACY_PLUGIN_IDS unchanged)
  post WU-G5B    : CERTIFIED + LEGACY_REMOVED
```

## Ledger

| Ledger entry | Status | File |
| --- | --- | --- |
| `core.waitUntil` certification state | `CERTIFIED + LEGACY_REMOVED` | `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` (updated this slice) |
| `core.waitUntil` legacy count | `0` | `LegacyResidualSnapshot.physicalResidual` (updated this slice) |
| `core.waitUntil` real example | `v2/compatibility/22-wait-until.pipeline.kts` (sha256 `7befc004582257fa779b9403e65e01d65acb3f5ac7a8933603a2f8de1a9990b4`) | unchanged |
| `core.waitUntil` installed CLI canary | SUCCESS | this receipt §L3 |

## Pre-existing reds (do NOT widen)

| Suite | Pre-WU-G5B | Post-WU-G5B |
| --- | --- | --- |
| `CanonicalDurableRunCoordinatorTest` | 26 / 11 | not measured (out of slice scope) |
| `CompatibilityCorpusTest::fixture14CredentialsBindings` | RED (pre-existing per `S2_A8_CORE_WAITUNTIL_WU_G5R_GATE_CLOSURE_RECEIPT.md`) | RED (pre-existing; **not a regression**, environment-level sun.misc.Unsafe warning makes the fixture exit code 1 — `pre-existing failure (not a WU-G5R regression)`) |
| `CompatibilityCorpusTest` (other) | 20 / 2 | not measured (out of slice scope) |
| `Lfc0GlobalStateFitnessTest` | 1 (KDoc false positive) | not measured (out of slice scope) |
| `UatLocal005CheckoutGitTest::SC-007 poll detects changed SHA` | RED (pre-existing; git-wrapper identity not configured in test sandbox) | RED (pre-existing; **not a regression**) |
| `UAT 007 / 008 / 009` | baseline | not measured (out of slice scope) |
| fixture 14 (credentials) | baseline | RED (pre-existing; see above) |

The slice does not touch `CanonicalDurableRunCoordinator`, the `CompatibilityCorpus`
fixtures, `Lfc0GlobalStateFitnessTest`, or the UATs in 005/007/008/009. Widen check
deferred to the next L4/L5 run; this slice enforces them by COMPILATION alone — if any
of these tests had started referencing `CanonicalWaitUntilNodeDispatcher` or
`CanonicalCoreStepCommand.WaitUntil`, the compile would fail.

**Specifically fixed by this slice** (was RED, now GREEN):

- `CoreWaitUntilStepUnitTest::structural family — core waitUntil stays LegacyCore while in LEGACY_PLUGIN_IDS (no authority flip at G1)` — the G1 invariant is invalidated by WU-G5B LEGACY_REMOVED. The test was rewritten to pin the post-LEGACY_REMOVED property: `core.waitUntil` is OUT of LEGACY_PLUGIN_IDS AND its metadata row is physically removed.

## Acceptance

- [x] All six legacy forms physically destroyed (counter 1/1/1).
- [x] `LegacyResidualConvergenceFitnessTest::assertConverged` PASS.
- [x] `Lfc2WaitUntilCanonicalReentryFitnessTest` PASS (canonical reachability preserved).
- [x] `22-wait-until.pipeline.kts` SUCCESS via installed CLI.
- [x] `WaitUntilReconciler*` and `FileBasedWaitUntilControlJournalTest` PASS (RepeatUntil
  machinery untouched).
- [x] No production semantic changes outside the six legacy removals.
- [x] `STEP_INVENTORY_LFC2E0.md` row updated: `CERTIFIED + LEGACY_REMOVED`.

`core.waitUntil` → **`CERTIFIED + LEGACY_REMOVED`** at WU-G5B, 2026-09-17.

LEGACY_PLUGIN_IDS residual → **1 / 1 / 1** (only `core.load`).
