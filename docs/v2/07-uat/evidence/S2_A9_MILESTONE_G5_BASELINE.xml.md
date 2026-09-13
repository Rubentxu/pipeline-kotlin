# S2-A9 / G5 BASELINE — milestone pre-destructive (canary)

Base: main @ 0a370f43 (= origin/main, clean)
Worktree: ../pipeline-milestone-g5 @ 0a370f43
Date: 2026-09-13T07:26Z
Budget: targeted ~30s

## Counters (G4 window)

```text
LEGACY_PLUGIN_IDS       = 4  (cleanWs, load, waitUntil, archiveArtifacts)
metadata rows            = 5  (echo, sh, error, sleep, writeFile, emit.event, isUnix, deleteDir, pwd)
dispatcher files         = 5
registry-primary (live)  = 5  (echo, sh, error, sleep, writeFile, emit.event, isUnix, deleteDir, pwd, isUnix)
```

## Milestone baseline XML (fresh, canary via rm -f + run)

| Suite | tests | failures | errors |
|---|---|---|---|
| CoreMilestoneStepContractSuiteTest | 23 | 0 | 0 |
| CoreMilestoneStepUnitTest | 19 | 0 | 0 |
| UatLocal013MilestoneTimingTest | 4 | 0 | 0 |

## S3 fitness baseline XML

| Suite | tests | failures | errors |
|---|---|---|---|
| S3EchoLegacyRemovedFitnessTest | 7 | 0 | 0 |
| S3EmitEventLegacyRemovedFitnessTest | 8 | 0 | 0 |
| S3ErrorLegacyRemovedFitnessTest | 12 | 0 | 0 |
| S3IsUnixLegacyRemovedFitnessTest | 9 | 0 | 0 |
| S3PwdLegacyRemovedFitnessTest | 8 | 0 | 0 |
| S3SleepLegacyRemovedFitnessTest | 4 | 0 | 0 |
| S3WriteFileLegacyRemovedFitnessTest | 4 | 0 | 0 |
| **Total** | **52** | **0** | **0** |
