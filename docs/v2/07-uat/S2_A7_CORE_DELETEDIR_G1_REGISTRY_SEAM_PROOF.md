# S2-A7 / G1 — `core.deleteDir` Registry Seam Proof

**Cycle:** `lfc2-e1-s2-a7-core-deletedir`
**Branch:** `cycle/lfc2-e1-delete-dir`
**Base:** `f5e4003d` (LFC-2E1 harness — LegacyResidualSnapshot: single residual authority)
**Date:** 2026-09-12T14:42Z
**Status:** REGISTRY SEAM PROVEN — STOP after G1; G2..G3 follow.

## 1. Scope

`core.deleteDir` step burn-down from LEGACY_PLUGIN_IDS to REGISTRY_PRIMARY.
This G1 registers the `CoreDeleteDirStep` candidate WITHOUT changing `LEGACY_PLUGIN_IDS`,
the legacy decoder, the metadata row, or the legacy dispatcher.

Production allowed (G1):
- `CoreDeleteDirStep.kt` — StepDefinition, codecs, capability-routed handler.
- `WorkspaceResolverPort` capability declared in `Capabilities.kt`.
- `CanonicalRuntimeCapabilityAccess` wiring provides `WorkspaceResolverPort` from `controlDirRoot`.
- `CoreStepRegistryFactory` registration of `CoreDeleteDirStep` (registry-membership does
  NOT change production authority at G1).

Production forbidden (per slice firewall):
- no `LEGACY_PLUGIN_IDS` change
- no authority flip
- no legacy removal

## 2. Implementation Summary

### Files created
1. `CoreDeleteDirStep.kt` — Registry candidate with:
   - `DeleteDirInput(path: String = ".")` — typed input
   - `DeleteDirOutput(path, deletedCount, sha256)` — typed output
   - Input/Output codecs preserving legacy envelope
   - Handler using `WORKSPACE_RESOLVER_CAPABILITY`, `STAGE_IDENTITY_CAPABILITY`, `EVENT_SINK_CAPABILITY`
   - `StepDescriptor` with `WRITES_WORKSPACE` + `MEMOIZED`

2. `CoreDeleteDirStepUnitTest.kt` — 20 tests proving:
   - Identity (KEY, stepId, name)
   - Contract completeness (effects, ReplayPolicy, capabilities)
   - Codecs (input/output round-trip, reject foreign kind)
   - Handler execution (DirDeleted event)
   - Structural family (LegacyCore while in LEGACY_PLUGIN_IDS)
   - Counter invariant (6/6/6 unchanged)
   - Real seam (capability admission fail-closed)
   - Duplicate registration rejection

### Files modified
1. `Capabilities.kt` — Added `WorkspaceResolverPort` interface + `WORKSPACE_RESOLVER_CAPABILITY`
2. `CanonicalRuntimeCapabilityAccess.kt` — Added binding for `WORKSPACE_RESOLVER_CAPABILITY`
3. `CoreStepRegistryFactory.kt` — Added `CoreDeleteDirStep.registerInto(this)`

## 3. G1 Evidence

| Suite | Tests | Failures | Errors | Timestamp | SHA-256 |
|-------|-------|----------|--------|-----------|---------|
| `CoreDeleteDirStepUnitTest` | 20 | 0 | 0 | 2026-09-12T14:42:35Z | `5d37eba46425bf4652abacc91ba90f846847752ac3010619f29466fa1530a1ec` |

```bash
timeout 600 ./v2/gradlew -p v2 :pipeline-application:test \
  --tests 'CoreDeleteDirStepUnitTest' --rerun-tasks
# BUILD SUCCESSFUL in 34s, 20 actionable tests executed
```

## 4. Counters (post-G1, pre-G3)

```
core.deleteDir:
  REGISTERED         = true     (G1: CoreDeleteDirStep.registerInto CoreStepRegistryFactory)
  REGISTRY_PRIMARY   = false    (LEGACY_PLUGIN_IDS still contains "core.deleteDir")
  LEGACY_UNREACHABLE = false
  LEGACY_REMOVED     = false
  CERTIFIED         = false

legacy counters    = 6 / 6 / 6   (unchanged)
```

## 5. Stop

G1 ends here. The next slice (`S2-A7 / G2` — differential contract freeze) requires
explicit GO after review of this receipt.

Re-entry path for `S2-A7 / G2`:
```text
G2 → differential contract freeze (confirm D1..Dn decisions)
G3 → readiness assessment + contract suite (CoreDeleteDirStepContractSuiteTest)
STOP (no G4 in this wave)
```

Until then:
- `core.deleteDir` is REGISTERED (G1 done) but NOT authority-flipped.
- `deleteDir` is MIGRATION_READY for the registry candidate (handler + codecs +
  capability admission proven by 20/0 G1 tests).
