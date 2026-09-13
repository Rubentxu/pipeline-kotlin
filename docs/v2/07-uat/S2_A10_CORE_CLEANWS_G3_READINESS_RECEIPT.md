# S2-A10 / G3 — `core.cleanWs` Migration Readiness Assessment

**Cycle:** `lfc2-e1-s2-a10-core-cleanws`
**Branch:** `cycle/lfc2-e1-cleanws-clean` (fresh branch from `main @ 2c4885f1`, cherry-pick of `0c3103eb`, `6fd6c043`, `bdd4f81e` from the pre-S2-A9/G5 branch)
**Base:** `main @ 2c4885f1` (post-S2-A9/G8 milestone CERTIFIED + counter reconciliation)
**HEAD at verification:** `4803f696` (this slice's counter-reconciliation commit)
**Date:** 2026-09-13 (local +02:00)
**Status:** MIGRATION_READY — AUTHORITY_FLIP_READY=true (G4 executes only in the serial merge queue with explicit user GO)

## 1. Scope (slice S2-A10 — cherry-pick from pre-S2-A9/G5 branch)

The pre-existing `cycle/lfc2-e1-cleanws` branch (off `main @ 53b8fca0`,
pre-S2-A9/G5 milestone merge) carried G1+G2+G3 commits as a divergent
history. The branch was **deleted** and the three commits were
cherry-picked onto a fresh branch off `main @ 2c4885f1` to (a) drop the
divergent-history deletions of milestone receipts/canaries and (b) absorb
the post-S2-A9/G8 counter-reconciliation state cleanly.

**Cherry-picked SHAs (from deleted branch `cycle/lfc2-e1-cleanws`):**

```text
0c3103eb → e0d3680d   G1: CoreCleanWsStep registry candidate (224+93 lines new)
6fd6c043 → edf95c35   G2: core.cleanWs differential contract freeze test (360 lines new)
bdd4f81e → ab5d222a   G3: core.cleanWs StepContractSuite 23/23 (721 lines new)
```

**Reconciliation commit (this slice, 4803f696):**

```text
2 files / 23 ins / 3 del:
- CoreCleanWsStepContractSuiteTest.kt  G1 candidate invariant baseline 5/5/5 → 4/4/4
- CoreSleepRegistryPrimaryFitnessTest.kt  post-S2-A9-G5 snapshot @Disabled; new post-S2-A10-G1 truth
```

Production allowed (S2-A10 / G1..G3):
- `CoreCleanWsStep.kt` — StepDefinition, codecs, capability-routed handler (single capability: `CLEAN_WS_OPERATIONS_CAPABILITY`).
- `Capabilities.kt` — `CLEAN_WS_OPERATIONS_CAPABILITY` constant addition (single capability; handler is a thin typed seam with zero infrastructure).
- `CoreStepRegistryFactory.kt` — `CoreCleanWsStep.registerInto(this)` (candidate-only registration; `core.cleanWs` remains in LEGACY_PLUGIN_IDS).
- `CanonicalRuntimeCapabilityAccess.kt` — capability bridge exposes the capability only when `controlDirRoot != null` (fail-closed admission).
- `CleanWsOperationsAdapter.kt` — single `WsCleaned` emission authority over the existing `CleanWsExecutor` SDK substrate (same construction as the legacy dispatcher).

Production forbidden (per slice firewall):
- no `LEGACY_PLUGIN_IDS` change (`core.cleanWs` still in)
- no authority flip (legacy dispatcher remains the production authority)
- no legacy removal (decoder branch / metadata row / dispatcher file still present)

## 2. G3 readiness — assertions

| # | Assertion | Evidence source | Status |
|---|-----------|-----------------|--------|
| 1 | candidate registration | `CoreStepRegistryFactory.kt` (this slice) `CoreCleanWsStep.registerInto(this)` | ✅ PROVEN |
| 2 | `StructuralFamily = LegacyCore` (post-G1 invariant) | `CoreCleanWsStepContractSuiteTest > registry resolution — production factory contains core dot cleanWs` | ✅ PROVEN |
| 3 | typed output `CleanWsOutput` round-trip byte-identical | `CoreCleanWsStepContractSuiteTest > codec output — CleanWsOutput round-trips byte-identically` | ✅ PROVEN |
| 4 | ReplayPolicy = MEMOIZED + Effects = WRITES_WORKSPACE + RecoveryPolicy = None | `CoreCleanWsStepContractSuiteTest > contract completeness — key, descriptor, codecs, single capability, WRITES_WORKSPACE, MEMOIZED, None` | ✅ PROVEN |
| 5 | capability admission (admit all / deny missing) | `CoreCleanWsStepContractSuiteTest > capability admission — the capability available prepares Ready` + `missing capability — admission rejects when CLEAN_WS_OPERATIONS is absent` | ✅ PROVEN |
| 6 | handler is total (no exceptions as semantics) | `CoreCleanWsStepContractSuiteTest > fresh durable — first execution of core dot cleanWs writes one terminal SUCCEEDED operation` + `WsCleaned event payload — exactly one event with non-negative counts, echoed patterns, 64-hex sha256` | ✅ PROVEN |
| 7 | Lfc2RegistryFamilyFitnessTest PASSED | `pipeline-architecture-tests:test --tests 'Lfc2*'` 7/0/0 (3+4) | ✅ PROVEN |
| 8 | S3 sibling LegacyRemoved fitness green | `pipeline-architecture-tests:test --tests 'S3*LegacyRemovedFitnessTest'` 52/0/0 across 7 suites | ✅ PROVEN |
| 9 | legacy decoder/dispatcher/metadata still physically present | Source files present at expected paths; not touched by G1 | ✅ PROVEN |
| 10 | counters 4/4/4 (post-S2-A9/G5 baseline) — NOT changed by this slice | `CoreCleanWsStepContractSuiteTest > G1 candidate invariant — ... counters remain 4 4 4 (post-S2-A9-G5 baseline)` | ✅ PROVEN |
| 11 | Differential contract freeze (legacy vs registry emit equivalent WsCleaned) | `CoreCleanWsDifferentialContractTest` 5/0/0 | ✅ PROVEN |
| 12 | StepContractSuite 23/23 (full per-Step coverage at G3) | `CoreCleanWsStepContractSuiteTest` 23/0/0 | ✅ PROVEN |

## 3. Counters (post-G3, pre-G4)

```text
core.cleanWs:
  REGISTERED         = true     (G1: CoreCleanWsStep.registerInto)
  REGISTRY_PRIMARY   = false    (LEGACY_PLUGIN_IDS still contains "core.cleanWs"; legacy dispatcher remains the production authority)
  LEGACY_UNREACHABLE = false
  LEGACY_REMOVED     = false
  CERTIFIED         = false

cleanWs:
  MIGRATION_READY      = true     (handler + codecs + capability admission + ContractSuite + diff freeze proven)
  AUTHORITY_FLIP_READY = true     (no technical blocker; G4 runs in the serial merge queue with explicit GO)

Capability = CLEAN_WS_OPERATIONS_CAPABILITY (only step-specific capability;
  exposed conditionally on controlDirRoot; fail-closed admission when absent)

legacy counters    = 4 / 4 / 4   (post-S2-A9/G5 milestone baseline; UNCHANGED by this slice)

Gate plan (per AGENTS.md §LB-02 burn-down template and the S2-A9/G5 law
that we applied to milestone: G4 = N/N/N → (N-1)/N/N (ids only);
G5 = (N-1)/N/N → (N-1)/(N-1)/(N-1) (metadata row + dispatcher file
physically deleted)):
  G4 REGISTRY_PRIMARY
    4 / 4 / 4   (current baseline)
        ↓ LEGACY_PLUGIN_IDS -= "core.cleanWs"  (ids only; one source file)
    3 / 4 / 4
    STOP

  G5 LEGACY_REMOVED
    3 / 4 / 4   (post-G4)
        ↓ CanonicalCoreStepMetadata -= "core.cleanWs" row  (metadata)
        ↓ CanonicalCleanWsNodeDispatcher.kt physical deletion  (dispatcher)
    3 / 3 / 3
    STOP
```

## 4. Verification (fresh XML, this branch, Base `2c4885f1` / HEAD `4803f696`)

```text
:pipeline-application
  CoreCleanWsStepContractSuiteTest          tests=23 skipped=0 failures=0 errors=0
    sha256 = 7909634a9711179bacafc5cdaddbf498f772db5b18a1577e48ef9f6b1866c720
  CoreCleanWsDifferentialContractTest       tests=5  skipped=0 failures=0 errors=0
    sha256 = a87a31e24e08271bf4ff6be6e29dd0fe102a9c83b5ee3b65f6dd83b2cae8602c
  CoreMilestoneStepContractSuiteTest        tests=24 skipped=0 failures=0 errors=0
    sha256 = cc0153313748a52f1efd0df8de2c9b192ce28dc2830c52149a6c34ab006660f3
  CoreMilestoneStepUnitTest                 tests=19 skipped=0 failures=0 errors=0
  UatLocal013MilestoneTimingTest            tests=4  skipped=0 failures=0 errors=0
  *RegistryPrimaryFitnessTest (6 suites)    tests=67 skipped=18 failures=0 errors=0
  CoreDeleteDirStepContractSuiteTest        tests=22 skipped=0 failures=0 errors=0
  CoreDeleteDirStepUnitTest                 tests=18 skipped=0 failures=0 errors=0
  CanonicalCoreStepCommandRegistryTest      tests=6  skipped=0 failures=0 errors=0
  UatLocal011WorkflowControlTest            tests=13 skipped=1 failures=0 errors=0
  UppercaseStepContractSuiteTest            tests=14 skipped=0 failures=0 errors=0

:pipeline-architecture-tests
  S3*LegacyRemovedFitnessTest (7 suites)    tests=52 skipped=0 failures=0 errors=0
  Lfc2 family (2 suites)                    tests=7  skipped=0 failures=0 errors=0
                                            ────────
                                    275 tests / 22 skipped / 0 failures / 0 errors
```

## 5. Decision freeze

| ID | Decision | Frozen text |
|----|----------|-------------|
| D1 | canonical cleanWs truth = `CleanWsExecutor` SDK substrate | Handler delegates to existing executor (same substrate as the legacy dispatcher; parity proven by `CoreCleanWsDifferentialContractTest`) |
| D2 | typed output `CleanWsOutput(deletedDirectories, deletedFiles, patterns)` is APPROVED | Typed result for observability |
| D3 | Effects = `WRITES_WORKSPACE` + ReplayPolicy = `MEMOIZED` + RecoveryPolicy = `None` | Matches legacy metadata byte-equivalent |
| D4 | Capability = `CLEAN_WS_OPERATIONS_CAPABILITY` (single declared capability) | Handler is a thin typed seam with zero infrastructure; capability exposed only when `controlDirRoot != null` |
| D5 | LEGACY_PLUGIN_IDS unchanged at G1..G3 | Candidate-only registration; authority flip deferred to G4 |
| D6 | legacy dispatcher `CanonicalCleanWsNodeDispatcher.kt` stays physically present | LEGACY_REMOVED is a G5 property; G4 leaves the file intact but unreachable |
| D7 | G3 already includes the full StepContractSuite 23/23 (not a separate G7) | Slice is more advanced than the burn-down template minimum |

## 6. Files touched by this slice (atomic cherry-pick + reconciliation)

```text
Cherry-picked from cycle/lfc2-e1-cleanws (deleted):
  + v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreCleanWsStep.kt           224 lines
  + v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CleanWsOperationsAdapter.kt  93 lines
  + v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreCleanWsDifferentialContractTest.kt  360 lines
  + v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreCleanWsStepContractSuiteTest.kt   721 lines
  M v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Capabilities.kt                  +43 lines (CLEAN_WS_OPERATIONS_CAPABILITY)
  M v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt       +14 lines (CoreCleanWsStep.registerInto)
  M v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalRuntimeCapabilityAccess.kt  +25 lines (capability bridge)

Reconciliation (this slice, 4803f696):
  M v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreCleanWsStepContractSuiteTest.kt  +23/-3 (G1 invariant baseline 5/5/5 → 4/4/4)
  M v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreSleepRegistryPrimaryFitnessTest.kt  +15/-0 (@Disabled post-S2-A9-G5 snapshot + new post-S2-A10-G1 truth)

Total: 9 files changed (5 new, 3 production-modified, 1 test-modified,
1 receipt new). The CoreCleanWsStepContractSuiteTest.kt appears once
in this list — it was created by G3 cherry-pick (721 lines) and
extended by 4803f696 (counter reconciliation; +23/-3 surgical update
to a single test method's baseline value). Both changes land in the
same file because the reconciliation updates the G3 contract-suite
itself.
Production source under v2/**/main/**: 5 files (2 new, 3 modified) — all G1 production
Production forbidden NOT touched (verified): LEGACY_PLUGIN_IDS unchanged; metadata row unchanged; dispatcher file unchanged
```

## 7. Stop — awaiting GO for G4

This slice is **MIGRATION_READY + AUTHORITY_FLIP_READY**. It does NOT
execute G4 (the authority flip itself: `LEGACY_PLUGIN_IDS -= "core.cleanWs"`).
Per the firewall preference and the slice mandate (no automatic authority
flip), the orchestrator STOPS here and waits for explicit user GO before:

1. merging `cycle/lfc2-e1-cleanws-clean` into `origin/main` (PR for the
   G3 slice — registry candidate + ContractSuite + diff freeze; counters
   unchanged),
2. opening the next change cycle (`G4 REGISTRY_PRIMARY authority flip`,
   `G5 LEGACY_REMOVED`, `G6 architecture fitness`, `G8 CERTIFIED`).

The G4 slice will be a destructive change touching only
`CanonicalCoreStepDecoder.kt` (`LEGACY_PLUGIN_IDS -= "core.cleanWs"`; the
single source-of-truth for the LEGACY_PLUGIN_IDS set), plus stale G1-era
invariant updates in `CoreCleanWsStepContractSuiteTest` and the post-G4
snapshot in `CoreSleepRegistryPrimaryFitnessTest`. Per the precedent
deleteDir G4, the G4 slice must update `LegacyResidualSnapshot`'s
`registryPrimaryPendingRemoval` field and toggle the S3 sibling fitness
tests from `assertConverged` to `assertCurrentState` for the flip window.

NOT in G4 scope: metadata row deletion in `CanonicalCoreStepMetadata.kt`
and physical removal of `CanonicalCleanWsNodeDispatcher.kt` are G5
properties (LEGACY_REMOVED), per the S2-A9/G5 law we applied to milestone.
G5 will also update `CoreCleanWsStepContractSuiteTest` to assert
`LEGACY_PLUGIN_IDS.size == 3` (post-G4) and the corresponding
`sealedSubclasses` count, and update the post-G5 snapshot in
`CoreSleepRegistryPrimaryFitnessTest`.

End of G3.
