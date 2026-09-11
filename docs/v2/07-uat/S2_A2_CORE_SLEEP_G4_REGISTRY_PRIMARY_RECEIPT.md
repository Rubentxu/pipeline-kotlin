# S2-A2 / G4 — `core.sleep` REGISTRY_PRIMARY receipt

## Scope

G4 performs exactly the authority flip. The production change in `5e897715` removes
`"core.sleep"` from `CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS`. It does **not**
remove the legacy decoder branch, command subtype, metadata row, or dispatcher.

```text
BEFORE
  core.sleep ∈ LEGACY_PLUGIN_IDS
  StructuralFamily(core.sleep) = LegacyCore
  metadata authority = CanonicalCoreStepMetadata
  IDs / metadata / dispatchers = 11 / 11 / 11

AFTER
  core.sleep ∉ LEGACY_PLUGIN_IDS
  StructuralFamily(core.sleep) = Registry
  metadata authority = CoreSleepStep.definition.contract.descriptor
  IDs / metadata / dispatchers = 10 / 11 / 11
```

The physical legacy forms intentionally remain for the separate `LEGACY_REMOVED` gate:
`CanonicalCoreStepCommand.Sleep`, `SLEEP_PLUGIN_ID`, the legacy decoder branch,
`CanonicalSleepNodeDispatcher`, and the `core.sleep` metadata row.

## Permanent transitional fitness

`CoreSleepRegistryPrimaryFitnessTest` is the permanent G4 gate. Fresh JUnit XML:

```text
argv: timeout 600 ./gradlew -p v2 :pipeline-application:test \
  --tests dev.rubentxu.pipeline.v2.application.CoreSleepRegistryPrimaryFitnessTest
exit: 0
XML: tests=5, failures=0, errors=0, skipped=0
log SHA-256: aca75204e0cb4bbfc3c7e1b9b824423f478bc01a8c25db7e5378840c309d5f74
XML SHA-256: d9e77b6190a7bee79d39b84e0359ff16ae9fc4cd1bac4b5f147587c05036137b
```

It proves all of the following:

- `core.sleep` is present in `CoreStepRegistryFactory.registry()`.
- `core.sleep` is absent from `LEGACY_PLUGIN_IDS` and resolves as `Registry`.
- The exact registry-primary set is `core.echo`, `core.sh`, `core.error`, and
  `core.sleep`.
- The exact residual legacy set is:

  ```text
  core.file.writeFile
  core.emit.event
  core.milestone
  core.deleteDir
  core.cleanWs
  core.load
  core.pwd
  core.isUnix
  core.waitUntil
  core.archiveArtifacts
  ```

- Effective metadata is derived from the registry descriptor and equals
  `READ_ONLY + MEMOIZED + RecoveryPolicy.None`.
- `RegistryExecutionPreparation → Ready → RegistryExecutionBoundary` completes
  `sleep(0)` with `StepOutcome.Success`.
- Parent cancellation crosses that same real preparation/boundary seam for
  `sleep(Long.MAX_VALUE)`. It remains structured cancellation rather than a
  fabricated `CommonExecutionResult.Failure`.

The historical pre-flip readiness assertion remains in source but is explicitly
`@Disabled` as G3 evidence. It is not inverted into a post-flip test.

## Installed-distribution behavior

The real distribution was exercised with the exact positional-script CLI contract and
one durable state directory:

```text
binary: v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application
script: v2/compatibility/16-sleep.pipeline.kts
fresh argv:  pipeline-application run --db <evidence>/sleep.db --control-root <evidence>/control 16-sleep.pipeline.kts
replay argv: pipeline-application run --db <same>/sleep.db --control-root <same>/control 16-sleep.pipeline.kts
```

| Run | Exit | Observed result | Evidence |
| --- | ---: | --- | --- |
| Fresh | 0 | `sleep` emitted `StepStarted` and `StepFinished`, then `echo("woke")`; `RunFinished.outcome = success` | 12,598 ms wall time |
| Replay | 0 | New run emitted no `StepStarted` or `StepFinished` for `sleep` or `echo`; `RunFinished.outcome = success` | 9,571 ms wall time |

The replay therefore reused the memoized durable result and did not execute `sleep` a
second time. Compilation startup dominates both wall-clock measurements, so event
absence is the execution oracle.

```text
Evidence directory: /tmp/s2a2-g4-sleep.ZcvCaP
fresh log SHA-256:  3a8e64a42c4a9dc4d74eaf74568e7b3f28c400e382b50bd49d4ed66568d57a6a
replay log SHA-256: babad9f46e63d2043903e08384bda980a898d20fa2b6afebb2cd120acbce1ca2
```

## State at gate exit

```text
core.sleep:
  REGISTERED         = true
  REGISTRY_PRIMARY   = true
  LEGACY_UNREACHABLE = true
  LEGACY_REMOVED     = false
  CONTRACT_SUITE     = false
  CERTIFIED          = false

Counters: 10 / 11 / 11
```

`timeout { sleep(...) }` remains outside this G4 evidence because the existing timeout
block does not yet establish a parent coroutine cancellation boundary end to end. No
Temporal capability, timer framework, timeout-block rebuild, or legacy-source removal
is included in this gate.
