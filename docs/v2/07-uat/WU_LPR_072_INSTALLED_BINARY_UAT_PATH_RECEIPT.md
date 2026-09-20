# WU-LPR-072 — Installed-binary UAT path repair

**Date**: 2026-09-20  
**Status**: CLOSED — improvement over the released LPR plan  
**Authority**: `LPR-GATE-1`, `WU_LPR_DIAG_UAT_BINARY_PATH_RECEIPT.md`, WU-LPR-070

## Goal

Restore the real installed-distribution UAT launchers after WU-LPR-070 renamed
Gradle's `applicationName` from `pipeline-application` to `pipelinek`.

## Deep investigation finding

`AppBinSupport.discover()` was already the canonical test harness adapter. It
resolves both the current `build/install/pipelinek/bin/pipelinek` location and
the legacy `pipeline-application` location, fail-closed if neither exists.

Eleven UAT classes still duplicated a stale literal lookup of the old binary
path rather than using this adapter. This made real UAT coverage fail before a
pipeline was launched, despite the installed `pipelinek` executable itself
working correctly. The complete diagnosis is pinned in
`WU_LPR_DIAG_UAT_BINARY_PATH_RECEIPT.md`.

## Change

Replaced the duplicated `appBin` / `binary` path discovery in these real
installed-distribution test classes with `AppBinSupport.discover()`:

- `UatDsl001JenkinsFamiliarityTest`
- `UatDsl003ParallelTest`
- `UatDsl005TimeoutGrammarTest`
- `UatDsl006BodyExecutionTest`
- `UatEvt001ReplayTest`
- `UatEvt002MultiStepReplayTest`
- `UatStep001ShExecutionTest`
- `UatStep002EchoCaptureTest`
- `UatStep003ErrorAbortTest`
- `UatStep004SleepTimingTest`
- `cli/WULpr011ResumeLifecycleUatTest`

No production code, DSL contract, durable policy, or application behavior
changed. The tests now consume the same shared discovery seam used by the
post-LPR CLI and corpus UATs.

## Verification

### L1/L2 targeted installed-distribution UAT batch

```bash
timeout 1800 ./gradlew -p v2 :pipeline-application:test \
  --tests 'UatDsl001*' --tests 'UatDsl003*' \
  --tests 'UatDsl005*' --tests 'UatDsl006*' \
  --tests 'UatEvt001*' --tests 'UatEvt002*' \
  --tests 'UatStep001*' --tests 'UatStep002*' \
  --tests 'UatStep003*' --tests 'UatStep004*' \
  --tests 'WULpr011Resume*' --rerun-tasks
```

Observed result:

```text
BUILD SUCCESSFUL in 4m 25s
59 actionable tasks: 59 executed
```

Fresh JUnit XML canary:

```text
v2/pipeline-application/build/test-results/test/TEST-*.xml
files=12 tests=39 failures=0 errors=0
```

HTML test report independently records `39` tests, `0` failures, `0` errors,
and `3m49.38s` execution time. Full Gradle log SHA-256:

```text
6f9dbc11a417aac6fc4fc7a02266e55d8b94800022bc2e185bb4af8d43d41048
```

## Scope and remaining work

This closes the stale installed-binary path failure only. The independent
application fitness failures diagnosed after the original broad run remain
outside this WU:

- `A4_8LegacyRegistrySemanticParityTest.A4_8_4` must be reconciled with E1's
  new `ArtifactHandle.kt` reference to the `TypedStepOutput` marker.
- `CoreSleepRegistryPrimaryFitnessTest` needs its separately-scoped registry
  inventory investigation.

Neither condition is caused by the WU-LPR-070 binary rename, and neither was
altered here. They remain future, explicit investigation items.

## End-of-work-unit closure

```text
Reference implementation consulted: none applicable, test-harness path repair
Behaviour adopted:                  every installed-binary UAT resolves via AppBinSupport
Intentional deviations:             none
Security implications reviewed:     n/a, test-only binary path resolution
Tests demonstrating the contract:    UatDsl001/003/005/006, UatEvt001/002,
                                     UatStep001..004, WULpr011ResumeLifecycleUatTest
```

**Signed off**: WU-LPR-072, 2026-09-20. The stale rename defect is repaired
and real installed-distribution UAT evidence is GREEN.
