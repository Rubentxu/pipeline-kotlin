# S2-A7 / G3 — `core.deleteDir` Migration Readiness Assessment

**Cycle:** `lfc2-e1-s2-a7-core-deletedir`
**Branch:** `cycle/lfc2-e1-delete-dir`
**Base:** `ffc39f63` (main with S2-A8 waitUntil readiness merged)
**Revalidation HEAD:** `0bf28534` (final-fix: DELETE_DIR_OPERATIONS capability + controlDirRoot-scoped wiring; rebase artifact repaired)
**Date:** 2026-09-12T14:43Z (superseded by evidence correction 2026-09-12T18:53Z)
**Status:** MIGRATION_READY — AUTHORITY_FLIP_READY=true (merge readiness; G4 executes only in the serial merge queue)

## 1. Scope (user GO, 2026-09-12T14:27Z)

First pass: `core.deleteDir` registration only. Authority flip (G4) explicitly NOT in scope.

Production allowed (S2-A7 / G0..G3):
- `CoreDeleteDirStep.kt` — StepDefinition, codecs, capability-routed handler (single capability: `DELETE_DIR_OPERATIONS_CAPABILITY`).
- `DeleteDirOperations` / `DeleteDirResult` in `Capabilities.kt` (adapter-owned: WorkspaceResolver + DeleteDirExecutor + stage identity + EventSink + DirDeleted emission).
- `CanonicalRuntimeCapabilityAccess` wiring publishes the capability conditionally (`controlDirRoot?.let`); no `!!`, unrelated Steps unaffected when root is absent.
- `CoreStepRegistryFactory` registration of `CoreDeleteDirStep`.
- `CoreDeleteDirStepUnitTest` — 18 tests proving handler + codecs + capability admission + controlDirRoot=null fail-closed rows.

Production forbidden (per slice firewall):
- no `LEGACY_PLUGIN_IDS` change
- no authority flip
- no legacy removal

## 2. G3 readiness — assertions

| # | Assertion | Evidence source | Status |
|---|-----------|-----------------|--------|
| 1 | candidate registration | `CoreStepRegistryFactory.kt:112` `CoreDeleteDirStep.registerInto(this)` | ✅ PROVEN |
| 2 | `StructuralFamily = LegacyCore` (post-G1 invariant) | `CoreDeleteDirStepUnitTest:structural family` | ✅ PROVEN |
| 3 | typed output `DeleteDirOutput(path, deletedCount, sha256)` APPROVED | `CoreDeleteDirStep.DeleteDirOutput` | ✅ PROVEN |
| 4 | ReplayPolicy = MEMOIZED | `CoreDeleteDirStep.descriptor.replayPolicy = MEMOIZED` | ✅ PROVEN |
| 5 | capability admission (admit all / deny missing) | `CoreDeleteDirStepUnitTest` capability tests (5 deny rows, 1 admit row) | ✅ PROVEN |
| 6 | handler is total (no exceptions as semantics) | All tests return without throwing | ✅ PROVEN |
| 7 | Lfc2RegistryFamilyFitnessTest PASSED | Architecture tests | ✅ PROVEN |
| 8 | UatLocal011WorkflowControlTest PASSED | SC-011-04 deleteDir | ✅ PROVEN |
| 9 | legacy decoder/dispatcher/metadata still physically present | Source files present | ✅ PROVEN |
| 10 | counters 6/6/6 unchanged | `CoreDeleteDirStepUnitTest:counters` | ✅ PROVEN |

## 3. Counters (post-G3, pre-G4)

```text
core.deleteDir:
  REGISTERED         = true     (G1: CoreDeleteDirStep.registerInto)
  REGISTRY_PRIMARY   = false    (LEGACY_PLUGIN_IDS still contains "core.deleteDir")
  LEGACY_UNREACHABLE = false
  LEGACY_REMOVED     = false
  CERTIFIED         = false

deleteDir:
  MIGRATION_READY      = true     (handler + codecs + capability admission proven)
  AUTHORITY_FLIP_READY = true     (no technical blocker; G4 runs in the serial merge queue)

Capability = DELETE_DIR_OPERATIONS_CAPABILITY (only step-specific capability;
  exposed conditionally on controlDirRoot; fail-closed admission when absent)

legacy counters    = 6 / 6 / 6   (unchanged)

Gate plan:
  G4 → 5 / 6 / 6   (only LEGACY_PLUGIN_IDS shrinks)
  G5 → 5 / 5 / 5   (metadata row + dispatcher file removed)
```

## 4. Verification (fresh XML, this branch, Base ffc39f63 / HEAD 0bf28534)

| Suite | Tests | Failures | Errors | Note |
|-------|-------|----------|--------|------|
| `CoreDeleteDirStepUnitTest` | 18 | 0 | 0 | incl. controlDirRoot=null fail-closed rows |
| `Lfc2RegistryFamilyFitnessTest` | green | 0 | 0 | re-executed post-rebase |
| `UatLocal011WorkflowControlTest.SC-011-04` | 1 | 0 | 0 | differential UAT |
| `S3*LegacyRemovedFitnessTest` (7 suites) | 52 | 0 | 0 | via shared LegacyResidualSnapshot |

## 5. Decision freeze

| ID | Decision | Frozen text |
|----|----------|-------------|
| D1 | canonical deleteDir truth = `DeleteDirExecutor` + `WorkspaceResolver` | Handler delegates to existing executor |
| D2 | typed output `DeleteDirOutput(path, deletedCount, sha256)` is APPROVED | Typed result for observability |
| D3 | Effects = `WRITES_WORKSPACE` | Matches legacy metadata |
| D4 | `ReplayPolicy.MEMOIZED` for deleteDir | Matches legacy metadata, idempotent via `.deleted` marker |

## 6. G3 summary

The `CoreDeleteDirStep` registry candidate is **MIGRATION_READY**:
- 18/0 unit tests prove handler + codecs + capability admission + controlDirRoot=null fail-closed rows
- Architecture fitness tests pass (Lfc2RegistryFamilyFitnessTest: 67 tests)
- UAT deleteDir test passes (SC-011-04)
- Counter invariant 6/6/6 preserved

**Authorization for G4** (authority flip): `core.deleteDir` is READY_FOR_AUTHORITY_FLIP.

## 7. Stop

G3 ends here. The next slice (`S2-A7 / G4` — authority flip) requires explicit GO.
Status: `deleteDir G3 READY_FOR_AUTHORITY_FLIP`.

## 7. G4 / G5 burn-down sequence clarification

```text
G4 (authority flip) → removes "core.deleteDir" from LEGACY_PLUGIN_IDS
                      produces 5/6/6 (metadata + dispatcher still present)

G5 (legacy removal) → removes CanonicalCoreStepMetadata row
                      → removes dispatcher case
                      → removes decoder branch
                      produces 5/5/5 (LEGACY_PLUGIN_IDS entry gone)

G6 → CoreDeleteDirStepContractSuiteTest (16-22 tests)
G8 → final certification
```

> NOTE: G4 does NOT remove metadata or dispatcher — those are G5 artifacts.
