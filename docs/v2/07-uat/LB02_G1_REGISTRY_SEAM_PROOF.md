# LB-02 G1 Registry Seam Proof (2026-09-09)

**Status**: G1 PASS for LB-02. `core.sh` is wired behind the open
`StepRegistry` surface as a `StepDefinition<CoreShellInput, CoreShellOutput>`,
with the legacy canonical-core decode/dispatch/metadata path **intact**.
HEAD: `d7d9606f + 576c2a91 + 5bb0a41b` → this slice commits on top.

## What this slice proves

- The typed `I/O` shape of `core.sh` matches the contract draft
  (`docs/v2/00-context/LB02_CORE_SH_CONTRACT_DRAFT.md`).
- The Input codec emits the canonical dsl-v1 envelope `{kind, script,
  encoding?, label?, returnMode}` as a well-formed JSON object
  (durable-spine eligibility CDE.3-e1/e2).
- The Output codec emits a well-formed JSON object carrying the variant
  discriminant (`UNIT | STDOUT | STATUS | FAILED | INTERRUPTED`) plus
  `capturedStdout` and `durationMs`. The discriminant names match the
  existing scripted-runtime wire shape so G2 corpus migration is
  mechanically equivalent.
- Key uniqueness (duplicate registration fails).
- Required-capability == used-capability: G1 declares `emptySet()`
  because the stub handler does not reach any capability.
- Legacy decode/dispatch is untouched. `CanonicalCoreStepCommand.Shell`,
  `CanonicalShellNodeDispatcher`, and the `core.sh` metadata row remain
  in place. No coordinator / dispatcher / compiler / application seam
  edit.

## What this slice does NOT prove (deferred)

- Real sh execution — stub handler returns `ShellInvocationResult.UnitValue`.
  Real launch lands at G3 via the `SHELL_OPERATIONS` capability adapter.
- `RecoveryPolicy.ExternalSubprocess` — currently lives on
  `StepMetadata`, not on `StepContract.descriptor`. G3 lifts this into
  the registry contract. Flagged in `CoreShellStep.kt` docstring.
- Typed-output channel at the boundary — the typed-output seam from Path A
  (`LB02_TYPED_OUTPUT_DECISION.md`) lands at G3 alongside the capability
  wiring. `outputCodec.decode` is `TODO()` until G7 (replay path).
- G3 also flips `CoreStepRegistryFactory.registry()` to register
  `CoreShellStep`. Until that flip, G1 explicitly asserts the factory
  does NOT yet contain `core.sh`.

## Diff summary

Three new files in `:pipeline-application/main`:

- `CoreShellInput.kt` — typed input value (`ShellCommand + stepIndex`).
- `CoreShellOutput.kt` — typed output (`ShellInvocationResult + capturedStdout + durationMs`).
- `CoreShellStep.kt` — `StepDefinition<CoreShellInput, CoreShellOutput>` with
  typed codecs and a deterministic G1 stub handler.

One new test file in `:pipeline-application/test`:

- `CoreShellStepTest.kt` — 10 tests, mirrors `EchoStepContractSuite` row-for-row
  for sh at the registry-seam level.

No edits to:
- `CoreStepRegistryFactory.kt` (G3 flip).
- `CanonicalCoreStepCommand.kt`, `CanonicalCoreStepDecoder.kt`,
  `CanonicalCoreStepMetadata.kt`, `CanonicalShellNodeDispatcher.kt`,
  `CanonicalDurableRunCoordinator.kt`, `CanonicalNodeDispatcher.kt`,
  `Capabilities.kt`, `CanonicalRuntimeCapabilityAccess.kt`,
  `ShExecution.kt`.

## Evidence

### L0 compile

```text
timeout 240 ./gradlew :pipeline-application:compileKotlin
   exit=0 (1.8s)

timeout 240 ./gradlew :pipeline-application:compileTestKotlin
   exit=0 (2.1s)
```

### L1 G1 test class

```bash
timeout 600 ./gradlew :pipeline-application:test \
    --tests 'dev.rubentxu.pipeline.v2.application.CoreShellStepTest' --rerun-tasks
```

| Artifact | SHA-256 | counts |
|----------|---------|--------|
| `TEST-dev.rubentxu.pipeline.v2.application.CoreShellStepTest.xml` | `28bd117c9ca3c73d4376dc9fc5b91c14d31d009fa9ae4c3c613ecfdfa158724e` | 10 / 0 / 0 |
| log `/tmp/lb02-g1-test.log` | `d0076ccedc0b16a09c68b2069df4635b6c16a9ec9134cc0880f967bbbb8263a7` | BUILD SUCCESSFUL in 22s |

### L2 regression sweep (G0 positive set)

```bash
timeout 600 ./gradlew :pipeline-application:test \
    --tests 'dev.rubentxu.pipeline.v2.application.EchoStepContractSuiteTest' \
    --tests 'dev.rubentxu.pipeline.v2.application.UatDurable002DivergenceFailsClosedTest' \
    --tests 'dev.rubentxu.pipeline.v2.application.UatDurable003ScriptBlockReplayTest'
```

| Artifact | SHA-256 | counts |
|----------|---------|--------|
| `TEST-dev.rubentxu.pipeline.v2.application.EchoStepContractSuiteTest.xml` | `1cded4925bacc4e446f7c569ddbb6650a742234c5518b7769ec66d737a1323d0` | 17 / 0 / 0 |
| `TEST-dev.rubentxu.pipeline.v2.application.UatDurable002DivergenceFailsClosedTest.xml` | `39a6e79dd12861571507e675ab18ce81435136cd720410802973771943656696` | 2 / 0 / 0 |
| `TEST-dev.rubentxu.pipeline.v2.application.UatDurable003ScriptBlockReplayTest.xml` | `21eb93b3a33f746bd32077df80c453f7268546799d5fd2dcfa83eec1a112522d` | 2 / 0 / 0 |

**Verdict**: 21 / 21 / 0 / 0 across the regression suite. No regressions
introduced by G1.

## Open questions surfaced at G1

1. `StepContract.descriptor.replayPolicy = ReplayPolicy.RERUN` matches
   what the canonical command path declared. G3 / G7 must confirm this
   is correct under real sh replay semantics.
2. `StepContract` does not carry `recoveryPolicy`. For sh, the canonical
   metadata records `RecoveryPolicy.ExternalSubprocess`. Either G3 adds
   a `recoveryPolicy` field to `StepContract` (preferred, since the
   engine already reads it that way for legacy) or G3 keeps the
   canonical metadata as the source of truth and reads through a
   separate seam. Decision deferred to G3 with the `SHELL_OPERATIONS`
   capability work.
3. The typed-output seam from Path A is the only missing spine piece
   for real sh execution. Concrete: `RegistryExecutionBoundary.execute`
   currently returns `StepOutcome.Success` and discards typed `O`. G3
   lifts typed `O` into the journal via `outputCodec.encode(O)` →
   `OperationOutput.result`.

## Verdict

```text
LB-02 G1 = DONE ✅
0 regressions introduced
legacy decode/dispatch intact
real sh execution: deferred to G3
```

LB-02 cleared to start G2 (corpus migration) and G3 (REGISTRY_PRIMARY +
typed-output boundary seam).
