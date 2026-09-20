# WU-LPR-073 — Fitness boundary reconciliation

**Date**: 2026-09-20  
**Status**: CLOSED — scoped fitness correction, with module gate follow-up recorded  
**Authority**: LPR-GATE-1, E1.ecosystem-local-first, Step Constitution public-SDK boundary

## Trigger

After WU-LPR-072 repaired the installed-binary UAT launchers, the next full
`pipeline-application` run exposed two independently stale fitness assertions:

1. `A4_8LegacyRegistrySemanticParityTest.A4_8_4` treated `TypedStepOutput` as
   an application-private marker, despite E1's domain `ArtifactHandle` and the
   certified `junit.results` public SDK output implementing the declared public
   contract.
2. `CoreSleepRegistryPrimaryFitnessTest` asserted a 16-key core registry while
   `CoreStepRegistryFactory` has registered `core.artifact.query` since E1,
   producing the correct 17-key composition.

## Investigation and decision

`ArtifactHandle` is a typed domain output for `core.archiveArtifacts` /
`core.artifact.query`, not durable coordinator, journal, recovery, or event
state. `junit.results` is a certified plugin that implements `TypedStepOutput`
through the public `pipeline-step-sdk` surface. Both are legal under the
hexagonal public-port rule.

The old raw-text scan additionally classified documentation references to
`CoreShellOutput` as a concrete dependency. That is false: the JUnit source had
no import or type dependency on the application-only `CoreShellOutput` type.
A dependency edge is a real import, not prose.

## Change

- `A4_8LegacyRegistrySemanticParityTest`
  - permits the marker declaration and the explicit domain `ArtifactHandle`
    output;
  - scans actual imports rather than documentation text for an illegal external
    `CoreShellOutput` dependency;
  - allows `TypedStepOutput` implementations in the public step SDK while
    still rejecting leakage into all other v2 modules, including events and
    durable substrate.
- `CoreSleepRegistryPrimaryFitnessTest`
  - makes the registered-key inventory reflect the 17-key factory composition,
    including E1's `core.artifact.query`.

No runtime behavior, registry registration, plugin, durability policy, or
public DSL changed.

## Verification

### L1 focused fitness tests

```bash
timeout 600 ./gradlew -p v2 :pipeline-application:test \
  --tests 'A4_8LegacyRegistrySemanticParityTest.A4_8_4*' \
  --tests 'CoreSleepRegistryPrimaryFitnessTest.production registry contains exactly the registered core steps*' \
  --rerun-tasks
```

Observed result:

```text
BUILD SUCCESSFUL in 34s
59 actionable tasks: 59 executed
```

Log SHA-256:

```text
babaafdec769db0a3d3ff94b984899bb212eac9d91ebd9af5ee2d33f2016f0ae
```

### L2 module regression attempt

A full `:pipeline-application:test --rerun-tasks` was started after focused
green. It exposed three unrelated compatibility-fixture failures
(`fixture05ScriptedIf`, `fixture25YamlRoundtrip`, `fixture27ZipUnzip`) and then
stalled in the unrelated `UatLocal005EnvSpecialCharsTest.WS-S-008`.

`jcmd` evidence captured the test worker blocked in:

```text
UatLocal005EnvSpecialCharsTest.runPipeline(UatLocal005EnvSpecialCharsTest.kt:294)
BufferedReader.readText() over ProcessPipeInputStream
```

The worker's child command was a real `MainKt run --db ... --control-root ...`
process. The run was cancelled rather than raising its timeout or falsely
claiming the module gate green. Follow-up investigation is required for the
process-output deadlock and the compatibility fixtures.

## End-of-work-unit closure

```text
Reference implementation consulted: none applicable, architecture-fitness correction
Behaviour adopted:                  public ports are checked by actual dependency edges
Intentional deviations:             raw documentation-text scan replaced by import scan
Security implications reviewed:     n/a, test-only architecture fitness
Tests demonstrating the contract:    A4_8LegacyRegistrySemanticParityTest.A4_8_4;
                                     CoreSleepRegistryPrimaryFitnessTest registry inventory
```

**Signed off**: WU-LPR-073, 2026-09-20. The stale fitness assertions are
corrected. Full module verification is explicitly blocked on separately
reproduced UAT process-hygiene and compatibility-fixture failures.
