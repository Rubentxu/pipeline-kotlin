# Explore — WU-G5B (core.waitUntil LEGACY_REMOVED + CERTIFIED)

## Goal

Mechanical removal of every legacy executable artifact tied to `core.waitUntil`,
flipping its step-certification state from `IMPLEMENTED_UNCERTIFIED` to
`CERTIFIED` once no legacy path is reachable and no legacy metadata row
remains.

## Inputs (verified)

- `cycle/wu-g5-restore` → `main` → `9f0b1e28`: `core.waitUntil` canonical
  RepeatUntil machinery is wired and reachable from the public DSL via
  `BlockStepNode(BodyExecutionPolicy.RepeatUntil)` (factory entry removed at
  G5R.3; execution goes through `dispatchRepeatUntilBody`).
- LEGACY_PLUGIN_IDS residual before this slice: **2 / 2 / 2**
  (`core.load`, `core.waitUntil`).
- Targeted suite: `LegacyExecutableStepCountersTest` asserts the
  per-Phase counters as a single fitness authority. Slice MUST close with
  counter = 1 / 1 / 1 (only `core.load` remaining in the set).

## Surface to delete (provenance per file)

| File | Lines (legacy fragment) | Status after WU-G5B |
| --- | --- | --- |
| `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalWaitUntilNodeDispatcher.kt` | entire file | DELETED |
| `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalNodeDispatcher.kt` | `private val waitUntilDispatcher = CanonicalWaitUntilNodeDispatcher()`; `is WaitUntil -> waitUntilDispatcher.dispatchStub(...)`; `waitUntilContext()` helper | FIELDS/BRANCH/HELPER removed; `when` stays EXHAUSTIVE |
| `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt` | `data class WaitUntil` (line 219); `WAIT_UNTIL_PLUGIN_ID` constant (line 249); `WAIT_UNTIL_PLUGIN_ID ->` decoder branch (line 288); `"core.waitUntil",` in `LEGACY_PLUGIN_IDS` (line 153) | ALL removed; provenance comment block summarising the slice added |
| `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepMetadata.kt` | `"core.waitUntil" to StepMetadata(setOf(Effect.READ_ONLY), ReplayPolicy.MEMOIZED),` row | row removed |
| `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Main.kt` | "core.deleteDir/core.cleanWs/core.load/core.pwd/core.waitUntil." (informational help text) | string updated to remove `core.waitUntil` |
| `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalWaitUntilNodeDispatcherTest.kt` | entire file (legacy-only test) | DELETED |
| `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepCommandRegistryTest.kt` | `sealedSubclasses has exactly 2 entries (Load, WaitUntil)` (line 12); `WaitUntil has correct pluginId and defaultMetadata` test (lines 117-119) | comment + test updated to expect only `Load`; the WaitUntil test removed |

## Files NOT to touch (scope firewall)

- `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreWaitUntilStep.kt` (the registry candidate — keeps the canonical path alive).
- `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt` line 396 (`if (pluginStepId.value == "core.waitUntil")` inside `BodyExecutionPolicy.Retrying`). This is `wu-g5r.3` RepeatUntil machinery (`510e4c89`), not legacy. The directive explicitly forbids touching the new RepeatUntil machinery unless a test demonstrates a defect. None does.
- `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/WaitUntilControlJournal.kt`, `WaitUntilControlState.kt`, `FileBasedWaitUntilControlJournal.kt`, `WaitUntilReconciler*` — durable RepeatUntil surface from G5R.5.
- `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/Lfc2WaitUntilCanonicalReentryFitnessTest.kt` — installed-CLI proof of canonical reachability (kept; only re-run for evidence).
- `v2/compatibility/22-wait-until.pipeline.kts` — real DSL fixture (kept; re-executed by installed CLI).
- `v2/pipeline-domain/.../WaitUntilReconciler*`, `WaitUntilPredicateOutcome*` — pure repeat-until machinery (G5R.5; kept).

## Fitness / gate expectations

After the slice:
- `LegacyExecutableStepCountersTest` counters: `1 / 1 / 1` (only `core.load`).
- `Lfc2WaitUntilCanonicalReentryFitnessTest`: GREEN (already green in G5R; re-run as evidence).
- `22-wait-until.pipeline.kts` executed by installed CLI: GREEN.
- `CompatibilityCorpusTest`: GREEN (corpus count already 21 after G5R.6; this slice adds nothing).
- `WaitUntilReconcilerTest`, `FileBasedWaitUntilControlJournalTest`: GREEN (RepeatUntil machine untouched).
- Event Harness (existing UAT harness): GREEN.
- certification matrix fitness: GREEN.
- rollup YAML sync: GREEN.

## Risk

- The `when` block in `CanonicalNodeDispatcher.dispatch` becomes potentially
  non-exhaustive after removing the `WaitUntil` case. We verify by leaving
  only `is Load ->` and turning the `when` into a single-arm expression that
  falls through to the `else -> throw` rejection — OR keep the `when` and
  add an explicit `else -> throw IllegalArgumentException(...)` mirroring the
  pattern used at the bottom of the legacy list (the same pattern
  `S2-B10 / G5` already established for the closed dispatch).
  **Decision**: extend the `when` with `else -> throw IllegalArgumentException("Unsupported core plugin step")`
  so the structural classifier routes unknown keys; the exhaustiveness of
  the dispatch is preserved.

- The `CanonicalCoreStepCommand` sealed hierarchy currently has 2 subtypes
  (`Load`, `WaitUntil`). After this slice it has 1 subtype (`Load`). The
  `sealedSubclasses.size` assertions across the suite are updated.

- Tests that mention `core.waitUntil` only in KDoc strings (not as part of
  the legacy path) are left intact.

## Pre-existing reds (do NOT widen)

Per `wu-g5-restore/tasks.md §0.3` (still authoritative for this slice):

```text
CanonicalDurableRunCoordinatorTest          26 / 11
CompatibilityCorpusTest                     20 / 2
Lfc0GlobalStateFitnessTest                  1 (KDoc false positive)
UAT 005 / 007 / 008 / 009                   baseline
fixture14 (credentials)                     baseline
```

Slice gate enforces none of these counts goes UP.

## Acceptance

When the slice is done, the only remaining legacy executable step is
`core.load`. Its classification in `STEP_INVENTORY_LFC2E0.md` is updated
from `IMPLEMENTED_UNCERTIFIED (legacy)` → `CERTIFIED + LEGACY_REMOVED` for
`core.waitUntil`. The STEP_CERTIFICATION_MATRIX counter is updated to
reflect `core.waitUntil: CERTIFIED`.

Receipt: `docs/v2/07-uat/S2_A8_CORE_WAITUNTIL_WU_G5B_LEGACY_REMOVED_RECEIPT.md`.
