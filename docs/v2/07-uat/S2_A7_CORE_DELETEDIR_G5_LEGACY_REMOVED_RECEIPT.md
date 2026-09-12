# S2-A7 / G5 — core.deleteDir LEGACY_REMOVED Receipt

> Gate: **G5 — physical removal of all legacy forms (converged 5/5/5)**
> Base: `main @ 017906ca` — counters 5/6/6 (G4 window)
> Writer commit: `bd9f6ee7` (single atomic destructive commit; Lane A exclusive)
> Date: 2026-09-12T20:42–20:46Z
> Result: **counters 5/6/6 → 5/5/5** (generic gate law: G5 (N-1)/N/N → (N-1)/(N-1)/(N-1))

## Atomic removal set (exactly what was deleted)

```text
CanonicalCoreStepDecoder.kt
  - CanonicalCoreStepCommand.DeleteDir subtype
  - DELETE_DIR_PLUGIN_ID constant
  - DELETE_DIR_PLUGIN_ID decoder branch
  + provenance comments (LEGACY_REMOVED, S2-A7 / G5, 2026-09-12)

CanonicalCoreStepMetadata.kt
  - "core.deleteDir" metadata row (provenance comment)

durable/CanonicalDeleteDirNodeDispatcher.kt   DELETED (file)
durable/CanonicalNodeDispatcher.kt
  - deleteDirDispatcher field
  - DeleteDir when-branch
  - deleteDirContext()
  (all with provenance comments)
```

## Snapshot transition

```text
LegacyResidualSnapshot:
  physicalResidual             -= "core.deleteDir"
  registryPrimaryPendingRemoval = null
  header current                = deleteDir G5 -> 5 / 5 / 5 (converged)

6 sibling S3 suites: assertCurrentState -> assertConverged RESTORED
  (EmitEvent, Error, IsUnix, Pwd, Sleep, WriteFile)
```

## Tests updated to G5 truth (no aliases, no stubs, no weakened assertions)

```text
CoreDeleteDirStepUnitTest:
  `counters - G5 converged 5 5 5 legacy physically removed`
  (asserts id absent from LEGACY_PLUGIN_IDS AND from CanonicalCoreStepMetadata.pluginIds)
CanonicalCoreStepCommandRegistryTest:
  sealed subtypes 6 -> 5; legacy `DeleteDir family` test removed with provenance
```

## State after G5

```text
LEGACY_PLUGIN_IDS = 5
metadata rows     = 5
dispatcher files  = 5

core.deleteDir:
  REGISTERED         = true
  REGISTRY_PRIMARY   = true
  LEGACY_UNREACHABLE = n/a (physically removed)
  LEGACY_REMOVED     = true
  CERTIFIED          = false   (G6 contract suite + G7/G8 pending)
```

## Lane C verification (fresh, on `bd9f6ee7`, XML timestamps 20:44–20:46Z)

| Suite | Result |
|---|---|
| `CoreDeleteDirStepUnitTest` | 18/0/0 |
| `CanonicalCoreStepCommandRegistryTest` | 7/0/0 |
| `UatLocal011WorkflowControlTest` | 13/0/0 |
| `CorePwdStepContractSuiteTest` (regression canary) | 23/0/0 |
| S3 LegacyRemovedFitness ×7 (**assertConverged**) | 52/0/0 |
| `Lfc2RegistryFamilyFitnessTest` | 3/0/0 |

## Lane B static audit

Pre-audit inventory @ `017906ca` matched the removal set exactly
(subtype, constant, branch, row, dispatcher file, 3 CanonicalNodeDispatcher
seams, `CanonicalDeleteDirDispatchContext`). Post-audit on `bd9f6ee7`:
zero surviving code references — the only textual remains are provenance
comments. No aliases or stubs were created to keep historical tests green.

## Installed-CLI proof (registry path post-removal)

```text
command : pipeline-application run --db /tmp/dd-g5-ws/dd.db DD-G4.pipeline.kts
exit    : 0
events  : DirDeleted {path=.../dd-g4-0, deletedCount=0, sha256=df6c1af1...}
log sha256 (first 16) = 78f1efa7cd3ace44
decoder source sha256 (first 16) = 97f428f5834a6cb2
```

## STOP

No G6 in this slice. Lane D (`cycle/lfc2-e1-delete-dir-g6prep`, worktree
`pipeline-dd-g6prep`) prepares the G6 contract suite in parallel — READY FOR
REBASE only, merged after G5 closes. Next authorized gates:
**G6 contract suite → G7 installed acceptance → G8 CERTIFIED**.
