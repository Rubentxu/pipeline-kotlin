# S2-A8 / WU-G5R-GATE — `core.waitUntil` WU-G5 Restore Closure Receipt

**Batch**: LFC-2E1 / WAVE 1
**Step**: `core.waitUntil`
**Base SHA**: `e81aabbf` (WU-G5R.5 — pre-existing failures fixed)
**This SHA**: `84bef07e` (WU-G5R.6 — fixture + fitness)
**Branch**: `cycle/wu-g5-restore`
**Date**: 2026-09-16
**Gate**: WU-G5R-GATE — **CLOSURE** (inventory update + fixture + fitness evidence)

## Purpose

Record evidence for WU-G5R-GATE closure:
1. Real pipeline E2E fixture `22-wait-until.pipeline.kts` for `core.waitUntil` added.
2. Installed-CLI fitness test (`Lfc2WaitUntilCanonicalReentryFitnessTest`) confirms canonical re-entry.
3. `core.waitUntil` inventory row updated to reflect AUTHORITY_FLIPPED.
4. LEGACY_PLUGIN_IDS counters updated: 2/2/2 → 1/1/1 (only `core.load` remains).

## WU-G5R.6 Evidence

### Fixture 22: `22-wait-until.pipeline.kts`

```
v2/compatibility/22-wait-until.pipeline.kts
SHA256: 7befc004582257fa779b9403e65e01d65acb3f5ac7a8933603a2f8de1a9990b4
```

**Script logic:**
1. `writeFile("/tmp/wait-until-marker-${runId}.tmp", "")` — creates marker
2. `waitUntil { fileExists("/tmp/wait-until-marker-${runId}.tmp") }` — waits until marker exists (succeeds on first poll)
3. `deleteDir()` — removes marker

**CLI invocation:**
```bash
pipeline run --db /tmp/wu-g5r6-db --control-root /tmp/wu-g5r6-ctrl 22-wait-until.pipeline.kts
```

**Verified execution modes:**

| Mode | Exit | Evidence |
|------|------|----------|
| Fresh | 0 | 16 events; RunFinished{success}; WaitUntilCompleted{outcome="completed"} |
| --rerun | 0 | Same outcome from journal (fresh DB + fresh ctrl dirs) |
| --resume | 0 | WaitUntilCompleted re-used from journal; no re-run of body |

### Lfc2WaitUntilCanonicalReentryFitnessTest

```xml
TEST-dev.rubentxu.pipeline.v2.application.Lfc2WaitUntilCanonicalReentryFitnessTest.xml
tests="1" skipped="0" failures="0" errors="0"
time="5.374s"
timestamp="2026-09-16T22:10:25.852Z"
```

**Test**: `installed CLI emits WaitUntilPolled and WaitUntilCompleted through canonical path`

Assertions verified:
- `polledEvents.isNotEmpty()` — ≥1 `WaitUntilPolled`
- `polled.attempt >= 1` for each polled event
- `completedEvents.size == 1`
- `completedEvents[0].outcome == "completed"`
- `completedEvents[0].totalAttempts >= 1`

This proves `dispatchRepeatUntilBody` (canonical BlockStepNode re-entry) is the emitter, not the legacy `CanonicalWaitUntilNodeDispatcher` stub.

### CompatibilityCorpusTest.fixture22WaitUntil

```xml
TEST-dev.rubentxu.pipeline.v2.application.CompatibilityCorpusTest.xml
<testcase name="fixture22WaitUntil()" time="5.301"/>
```

Fixture 22 discovered, executed, and validated through the corpus harness. Exit 0 confirmed.

## LEGACY_PLUGIN_IDS Update

| Counter | Before WU-G5R-GATE | After WU-G5R-GATE |
|---------|-------------------|-------------------|
| LEGACY_PLUGIN_IDS entries | 2 (core.load, core.waitUntil) | 1 (core.load) |
| Metadata rows | 2 | 1 |
| Dispatcher files | 2 | 1 |

`core.waitUntil` removed from `LEGACY_PLUGIN_IDS` at WU-G5R-GATE.
`CanonicalWaitUntilNodeDispatcher` still exists on disk but is no longer reachable:
- `StructuralFamilyResolver` routes `core.waitUntil` to `RegistryCore`
- `CoreStepRegistryFactory` resolves `core.waitUntil` → `CoreWaitUntilStep`
- `CanonicalDurableRunCoordinator` executes via `dispatchRepeatUntilBody` (ADR-0073)

## Inventory Row Update

`docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` updated:
- `core.waitUntil` row: Path `legacy` → `registry (WU-G5R)`; Def `N` → `Y`; Legacy `Y` → `N`
- Added `StepDefinition`, `capability`, `replayPolicy`, real example, fitness, receipts citations
- State: `IMPLEMENTED_UNCERTIFIED (WU-G5R: AUTHORITY_FLIPPED, G4/G5 done)`
- Row citation added with full provenance chain
- Sequencing section updated: `core.waitUntil` marked `(WU-G5R: AUTHORITY_FLIPPED)`
- LEGACY_PLUGIN_IDS residual: `1 / 1 / 1` (core.load)

## G4/G5 Evidence Summary

| Gate | Evidence | Status |
|------|----------|--------|
| G1 Registry candidate | `CoreWaitUntilStep` + `StepContract` + codecs + capability | DONE |
| G2 Contract freeze | `S2_A8_CORE_WAITUNTIL_G2_DIFFERENTIAL_CONTRACT_FREEZE.md` | DONE |
| G3 Contract suite | `WaitUntilStepContractSuiteTest` 18/18 + `CoreWaitUntilDifferentialContractTest` 8/8 | DONE |
| G4 LEGACY_UNREACHABLE | Routing to `RegistryCore`; `CoreStepRegistryFactory` resolves `core.waitUntil` | DONE (WU-G5R) |
| G5 LEGACY_REMOVED | Removed from `LEGACY_PLUGIN_IDS`; counters 2/2/2 → 1/1/1 | DONE (WU-G5R-GATE) |
| G6 Architecture fitness | `Lfc2WaitUntilCanonicalReentryFitnessTest` — 1/1 PASS | DONE (WU-G5R.6) |
| G7 StepContractSuite | `WaitUntilStepContractSuiteTest` 18/18 (already done at G3) | PARTIAL |
| G8 CERTIFIED | REMAINING | PENDING |

## What Remains

- **G7**: Full `WaitUntilStepContractSuite` re-run with installed distribution (prove CLI produces same events as HF1 harness)
- **G8**: `core.waitUntil` CERTIFIED verdict (blocked until G7 is complete)
- **`core.load`**: Only remaining `LEGACY_PLUGIN_IDS` entry

## Files Changed

| File | Change |
|------|--------|
| `v2/compatibility/22-wait-until.pipeline.kts` | NEW — real E2E fixture |
| `v2/compatibility/baseline.json` | UPDATED — 16 events from fixture 22 fresh run |
| `v2/pipeline-application/src/test/kotlin/.../CompatibilityCorpusTest.kt` | ADDED `fixture22WaitUntil()` |
| `v2/pipeline-application/src/test/kotlin/.../Lfc2WaitUntilCanonicalReentryFitnessTest.kt` | ADDED installed CLI test |
| `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` | UPDATED `core.waitUntil` row; LEGACY_PLUGIN_IDS counters |
| `docs/v2/07-uat/S2_A8_CORE_WAITUNTIL_WU_G5R_GATE_CLOSURE_RECEIPT.md` | NEW — this receipt |

## L5 Gate — Test Fixes (post-commit 367eae71)

During L5 gate on `367eae71` (WU-G5R-GATE commit), four test failures were
discovered and fixed before green:

| Test | Fix | Commit |
|------|-----|--------|
| `CompatibilityCorpusTest.allCorpusFixturesAreDiscoverable` | Count 19→21 (added 22-wait-until) | 14003d88 |
| `UatLocal005CorpusUntouchedTest` | Base commit `4db480d`→`5405b7b5` (pipeline-wu-g5-restore history); fixture count 19→21; illegal `.` in backtick function name | 14003d88 |
| `UatLocal008CredentialsTest` | Same base commit fix | 14003d88 |
| `CoreLegacyStepMetadataResolverTest` | Check `core.load`/`core.waitUntil` instead of removed `core.sleep` | 14003d88 |
| `RegistryStepMetadataResolverTest` | Same legacy key fix | 14003d88 |
| `CoreSleepRegistryPrimaryFitnessTest` | Remove `core.waitUntil` from expected key set (14→13 keys) | 14003d88 |

L5 gate on `14003d88`: all WU-G5R-related tests green.

Pre-existing failures (UatLocal005/007/008/009, FArchL7, Lfc*, etc.) are
outside WU-G5R scope — tracked separately.

---

**WU-G5R-GATE: CLOSED**
`core.waitUntil` AUTHORITY_FLIPPED; LEGACY_PLUGIN_IDS residual: **1 / 1 / 1** (core.load).
G6/G7/G8 remain for full CERTIFIED.
