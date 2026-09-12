# S2-A7 / G4 — core.deleteDir REGISTRY_PRIMARY Receipt

> Gate: **G4 — REGISTRY_PRIMARY (authority flip, legacy unreachable, physically present)**
> Base: `main @ cac9b587` — counters 6/6/6
> Date: 2026-09-12T20:03–20:09Z
> Result: **counters 6/6/6 → 5/6/6** (generic gate law: G4 N→N-1 in ids only)

## Scope firewall (exactly what changed)

```text
1. CanonicalCoreStepDecoder.kt     LEGACY_PLUGIN_IDS -= "core.deleteDir"
2. LegacyResidualSnapshot.kt       registryPrimaryPendingRemoval = "core.deleteDir"
                                   header burn-down: deleteDir G4 = current
3. Transitional-window test policy (restored at G5):
   S3{EmitEvent,Error,IsUnix,Pwd,Sleep,WriteFile}LegacyRemovedFitnessTest
   assertConverged -> assertCurrentState (converged closure is a G5 property;
   the snapshot itself rejects assertConverged while a flip is in flight)
4. Stale G1-era invariants updated to their G4 counterparts:
   - CoreDeleteDirStepUnitTest: family=Registry, counters 5/6/6
   - CanonicalCoreStepCommandRegistryTest: expected ids set -= core.deleteDir
5. New receipt (this file) + pwd G7-STOP receipt
```

PROHIBIDO and NOT touched: metadata row, decoder command branch, dispatcher
file (`CanonicalDeleteDirNodeDispatcher.kt` — physically present but
UNREACHABLE), `DeleteDirOperations`, handler, capability wiring.

## State after G4

```text
LEGACY_PLUGIN_IDS = 5
metadata rows     = 6
dispatcher files  = 6

core.deleteDir:
  REGISTERED         = true
  REGISTRY_PRIMARY   = true
  LEGACY_UNREACHABLE = true
  LEGACY_REMOVED     = false   (G5)
  CERTIFIED          = false   (G8)
```

## Validation (fresh XML, timestamps 20:04–20:09Z)

| Suite | Result |
|---|---|
| `CoreDeleteDirStepUnitTest` | 18/0/0 |
| `UatLocal011WorkflowControlTest` | 13/0/0 |
| `CanonicalCoreStepCommandRegistryTest` | 8/0/0 |
| `CorePwdStepContractSuiteTest` (regression canary) | 23/0/0 |
| S3 LegacyRemovedFitness (7 suites, transitional-aware) | 52/0/0 |
| `Lfc2RegistryFamilyFitnessTest` | 3/0/0 |

## Installed-CLI authority proof (legacy unreachable)

```text
command : pipeline-application run --db /tmp/dd-g4-ws/dd.db DD-G4.pipeline.kts
exit    : 0
events  : DirDeleted {path=.../dd-g4-0, deletedCount=0, sha256=df6c1af1...}
          stepName = dd-g4/deletedir-0  (registry-routed execution)
log sha256 = 22f7add2ab8d3240c14f81e22ecc4a98c66cb29eddfc0667bc9016d61b582744
```

The typed `DirDeleted` event with adapter sha256 marker is emitted only through
the capability-routed registry handler; production classification routes
`core.deleteDir` to `StructuralStepFamily.Registry` (asserted in
`CoreDeleteDirStepUnitTest`). The legacy dispatcher file remains on disk for
G5 physical removal — presence without reachability is exactly the G4 contract.

## STOP

No G5 in this slice. Next authorized gate: **core.deleteDir G5 — LEGACY_REMOVED
(5/6/6 → 5/5/5)**: delete command subtype, decoder branch/provenance comment,
metadata row, dispatcher file; restore `assertConverged` in the 6 sibling suites.
