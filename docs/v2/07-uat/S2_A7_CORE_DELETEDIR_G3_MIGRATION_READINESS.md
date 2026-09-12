# S2-A7 / G3 — `core.deleteDir` Migration Readiness Assessment

**Cycle:** `lfc2-e1-s2-a7-core-deletedir`
**Branch:** `cycle/lfc2-e1-delete-dir`
**Base:** `2a247b18` (LFC-2E1 harness — LEGACY_PLUGIN_IDS baseline)
**Post-rebase revalidation:** SHA `c7d9fca1` (rebase applied 2026-09-12); suites re-executed post-rebase with counts in §4.
**Date:** 2026-09-12T14:43Z
**Status:** MIGRATION_READY — STOP after G3; READY_FOR_AUTHORITY_FLIP.

## 1. Scope (user GO, 2026-09-12T14:27Z)

First pass: `core.deleteDir` registration only. Authority flip (G4) explicitly NOT in scope.

Production allowed (S2-A7 / G0..G3):
- `CoreDeleteDirStep.kt` — StepDefinition, codecs, capability-routed handler.
- `WorkspaceResolverPort` capability declared in `Capabilities.kt`.
- `CanonicalRuntimeCapabilityAccess` wiring provides `WorkspaceResolverPort`.
- `CoreStepRegistryFactory` registration of `CoreDeleteDirStep`.
- `CoreDeleteDirStepUnitTest` — 20 tests proving handler + codecs + capability admission.

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
  MIGRATION_READY   = true     (handler + codecs + capability admission proven)

legacy counters    = 6 / 6 / 6   (unchanged)
```

## 4. Verification (fresh XML, this session, this branch)

| Suite | Tests | Failures | Errors | Timestamp |
|-------|-------|----------|--------|-----------|
| `CoreDeleteDirStepUnitTest` | 20 | 0 | 0 | 2026-09-12T14:42:35Z |
| `Lfc2RegistryFamilyFitnessTest` | 67 | 0 | 0 | 2026-09-12T14:43:39Z |
| `UatLocal011WorkflowControlTest.SC-011-04` | 1 | 0 | 0 | 2026-09-12T14:41:49Z |

Command (L1 evidence):
```bash
timeout 600 ./v2/gradlew -p v2 :pipeline-application:test \
  --tests 'CoreDeleteDirStepUnitTest' --rerun-tasks
# BUILD SUCCESSFUL in 34s, 20 actionable tests executed
```

## 5. Decision freeze

| ID | Decision | Frozen text |
|----|----------|-------------|
| D1 | canonical deleteDir truth = `DeleteDirExecutor` + `WorkspaceResolver` | Handler delegates to existing executor |
| D2 | typed output `DeleteDirOutput(path, deletedCount, sha256)` is APPROVED | Typed result for observability |
| D3 | Effects = `WRITES_WORKSPACE` | Matches legacy metadata |
| D4 | `ReplayPolicy.MEMOIZED` for deleteDir | Matches legacy metadata, idempotent via `.deleted` marker |

## 6. G3 summary

The `CoreDeleteDirStep` registry candidate is **MIGRATION_READY**:
- 20/0 unit tests prove handler + codecs + capability admission
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
