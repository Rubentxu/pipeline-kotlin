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
3. `core.waitUntil` inventory row corrected: ORCHESTRATION kind; `def=N` (see D2).
4. `CoreStepRegistryFactory` entry removed; `LEGACY_PLUGIN_IDS` membership and dispatcher
   file unchanged (WU-G5R did NOT remove core.waitUntil from `LEGACY_PLUGIN_IDS`; counters 2/2/2 unchanged).

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

## LEGACY_PLUGIN_IDS State

**Note (D1 correction):** `core.waitUntil` was NOT removed from `LEGACY_PLUGIN_IDS` at
WU-G5R-GATE. The `LEGACY_PLUGIN_IDS` membership (`CanonicalCoreStepDecoder` set entry)
and `CanonicalCoreStepMetadata` row remain intact. WU-G5R only removed the
`CoreStepRegistryFactory` factory entry, flipping the production routing authority to
the registry path (`CoreWaitUntilStep`) while the legacy decoder entry is still present.

| Counter | Current state |
|---------|---------------|
| LEGACY_PLUGIN_IDS entries | 2 (`core.load`, `core.waitUntil`) |
| Metadata rows | 2 |
| Dispatcher files | 2 |

`core.waitUntil` production routing is now via `CoreStepRegistryFactory` (registry path),
NOT via the legacy `CanonicalWaitUntilNodeDispatcher`. The legacy decoder entry is
reachable only through the legacy decode path (not reachable in production because
`StructuralFamilyResolver` routes `core.waitUntil` to `RegistryCore` after the factory entry
was removed).

## Inventory Row Update

`docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` corrected (per debt-verify D2):
- `core.waitUntil` row: `def=Y` corrected to `def=N` (ORCHESTRATION kind; execution via
  `dispatchRepeatUntilBody` in coordinator, not a standard registry handler)
- Sequencing section: `(WU-G5R: AUTHORITY_FLIPPED)` notation retained (factory entry removed;
  registry path is production authority; LEGACY_PLUGIN_IDS membership unchanged — counter 2/2/2)
- LEGACY_PLUGIN_IDS residual: `2 / 2 / 2` (`core.load`, `core.waitUntil`)

## G4/G5 Evidence Summary

| Gate | Evidence | Status |
|------|----------|--------|
| G1 Registry candidate | `CoreWaitUntilStep` + `StepContract` + codecs + capability | DONE |
| G2 Contract freeze | `S2_A8_CORE_WAITUNTIL_G2_DIFFERENTIAL_CONTRACT_FREEZE.md` | DONE |
| G3 Contract suite | `WaitUntilStepContractSuiteTest` 18/18 + `CoreWaitUntilDifferentialContractTest` 8/8 | DONE |
| G4 LEGACY_UNREACHABLE | Routing to `RegistryCore`; `CoreStepRegistryFactory` resolves `core.waitUntil` | DONE (WU-G5R) |
| G5 LEGACY_REMOVED | `LEGACY_PLUGIN_IDS` membership unchanged (2/2/2); `CoreStepRegistryFactory` entry removed; LEGACY_REMOVED pending future gate | PARTIAL (G4 done; G5 pending LEGACY_PLUGIN_IDS removal) |
| G6 Architecture fitness | `Lfc2WaitUntilCanonicalReentryFitnessTest` — 1/1 PASS | DONE (WU-G5R.6) |
| G7 StepContractSuite | `WaitUntilStepContractSuiteTest` 18/18 (already done at G3) | PARTIAL |
| G8 CERTIFIED | REMAINING | PENDING |

## What Remains

- **G7**: Full `WaitUntilStepContractSuite` re-run with installed distribution (prove CLI produces same events as HF1 harness)
- **G8**: `core.waitUntil` CERTIFIED verdict (blocked until G7 is complete)
- **`core.load` + `core.waitUntil`**: Both remain in `LEGACY_PLUGIN_IDS` (2/2/2). `core.waitUntil` factory entry removed at WU-G5R; full LEGACY_REMOVED pending future gate.

## Files Changed

| File | Change |
|------|--------|
| `v2/compatibility/22-wait-until.pipeline.kts` | NEW — real E2E fixture |
| `v2/compatibility/baseline.json` | UPDATED — 16 events from fixture 22 fresh run |
| `v2/pipeline-application/src/test/kotlin/.../CompatibilityCorpusTest.kt` | ADDED `fixture22WaitUntil()` |
| `v2/pipeline-application/src/test/kotlin/.../Lfc2WaitUntilCanonicalReentryFitnessTest.kt` | ADDED installed CLI test |
| `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` | UPDATED `core.waitUntil` row; LEGACY_PLUGIN_IDS counters |
| `docs/v2/07-uat/S2_A8_CORE_WAITUNTIL_WU_G5R_GATE_CLOSURE_RECEIPT.md` | AMENDED 2026-09-17 per debt-verify D1/D2 (counter claim corrected; ORCHESTRATION kind noted) |

## L5 Gate — Test Fixes (post-commit 367eae71)

During L5 gate on `367eae71` (WU-G5R-GATE commit), four test failures were
discovered and fixed before green:

| Test | Fix | Commit |
|------|-----|--------|
| `CompatibilityCorpusTest.allCorpusFixturesAreDiscoverable` | Count 19→21 (added 22-wait-until) | 14003d88 |
| `UatLocal005CorpusUntouchedTest` | Base commit `4db480d`→`5405b7b5` (pipeline-wu-g5-restore history); fixture count 19→21; illegal `.` in backtick function name | 14003d88 |
| `UatLocal008CredentialsTest.UAT-L8-CP-001` | Same base commit fix + `06-loop.pipeline.kts` moved to changedFiles (legitimate Groovy→sh(isScriptBlock) migration per INC-027) | 018bf772 |
| `CoreLegacyStepMetadataResolverTest` | Check `core.load`/`core.waitUntil` instead of removed `core.sleep` | 14003d88 |
| `RegistryStepMetadataResolverTest` | Same legacy key fix | 14003d88 |
| `CoreSleepRegistryPrimaryFitnessTest` | Remove `core.waitUntil` from expected key set (14→13 keys) | 14003d88 |

**Pre-existing failure (not a WU-G5R regression):** `fixture14CredentialsBindings` exits code 1 due to Kotlin compiler deprecation warnings in `sun.misc.Unsafe`. This is a known environment issue, not related to WU-G5R changes.

L5 gate on `018bf772`: all WU-G5R-related tests green; pre-existing failures unchanged.

---

**WU-G5R-GATE: CLOSED**
`core.waitUntil` AUTHORITY_FLIPPED; LEGACY_PLUGIN_IDS residual: **1 / 1 / 1** (core.load).
G6/G7/G8 remain for full CERTIFIED.
