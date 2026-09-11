# S2-A2 / G2 — `core.sleep` Differential Contract Freeze

> Gate: **G2 — differential contract freeze, no cutover**  
> Base: `16f20040`  
> Date: 2026-09-11

## Decision

G2 freezes the candidate contract before any registry-primary change. No edit was made
to `LEGACY_PLUGIN_IDS`, legacy metadata, decoder, command algebra, or dispatcher.
`StructuralFamilyResolver.classify(core.sleep, registry) == LegacyCore` remains true.

## Resolved differentials

| Dimension | Legacy | Candidate | Verdict |
|---|---|---|---|
| positive seconds | blocking success | suspendable success | PARITY |
| zero | immediate success | immediate suspendable success | PARITY |
| negative | late untyped exception | deterministic input/decode rejection | APPROVED CONTRACT DELTA |
| `Long.MAX_VALUE` | millisecond overflow defect | `Duration.seconds`, cancellation-tested | APPROVED FIX |
| normal outcome | Success | typed Success carrier | PARITY |
| effects / replay | READ_ONLY / MEMOIZED | READ_ONLY / MEMOIZED | PARITY |
| capabilities | none | `emptySet()` | PARITY |
| input envelope | `dsl-v1` sleep payload | byte-identical | PARITY |
| ordinary cancellation | not coroutine-cooperative | propagates structurally | APPROVED FIX |
| outer timeout ownership | legacy ineffective | parent retains cancellation | REQUIRED FIX |
| running recovery | no temporal recovery | generic `RecoveryPolicy.None` reruns full operation | CURRENT-SCOPE DECISION |

## G2 corrections

1. `CoreSleepInput` now permits `seconds == 0` and rejects only negative values.
   This restores legacy/Jenkins-compatible zero-duration success while retaining the
   deliberate early-rejection delta for invalid negative values.
2. `RegistryExecutionBoundary` no longer turns `TimeoutCancellationException` into a
   typed durable result. `TimeoutCancellationException` is a `CancellationException`,
   so the generic boundary rethrows both unchanged. The cancelling parent owns timeout
   semantics. A Step that needs a durable TIMEOUT outcome must use an unambiguous typed
   domain result, not intercept coroutine cancellation.
3. `RecoveryPolicy.None` is explicit on the candidate descriptor. A seeded RUNNING
   `core.sleep` row follows the generic READ_ONLY + MEMOIZED non-success path and reruns
   from the full duration. It does not persist an absolute deadline or resume a remaining
   duration. This is the accepted **CURRENT-SCOPE** behavior, not Jenkins durable-timer
   behavior.

No `TemporalCapability`, scheduler, clock, persistent timer, `RecoveryPolicy.Sleep`,
or `waitUntil` refactor was introduced.

## Evidence

### Red evidence

Before the correction, focused G2 tests failed exactly for zero-duration candidate
rejection:

```text
CoreSleepStepUnitTest: 9 tests, 2 failures
- CoreSleepInput(0) rejected `seconds > 0`
- codec decode of {"kind":"sleep","seconds":0} rejected
```

`/tmp/s2a2-g2-red.log`

### Green evidence

```text
CoreSleepStepUnitTest                         10 / 0 / 0
CoreSleepCoordinatorCharacterizationTest       4 / 0 / 0
CoreSleepLegacyCharacterizationTest            7 / 0 / 0
GenericRegistryExecutionCarrierTest            6 / 0 / 0
A4_3TypedShellOutputIntegrationTest           20 / 0 / 0
EchoStepContractSuiteTest                     17 / 0 / 0
CoreErrorStepUnitTest                         20 / 0 / 0
CoreErrorRegistryPrimaryFitnessTest           14 / 0 / 0
ErrorStepContractSuiteTest                    17 / 0 / 0
------------------------------------------------------
Scoped total                                   115 / 0 / 0
```

Commands used `timeout 600`:

```text
: pipeline-application:test --tests CoreSleepStepUnitTest
: pipeline-application:test --tests CoreSleepStepUnitTest --tests CoreSleepCoordinatorCharacterizationTest
: pipeline-application:test --fail-fast [the nine classes listed above]
```

The G2 recovery test seeds a real compatible RUNNING journal row and observes one
fresh `StepStarted`, terminal `SUCCEEDED`, and no special recovery path. It proves the
contract for `RecoveryPolicy.None` without inventing a temporal recovery feature.

## State after G2

```text
core.sleep REGISTERED         = true
core.sleep REGISTRY_PRIMARY   = false
core.sleep LEGACY_UNREACHABLE = false
core.sleep LEGACY_REMOVED     = false
core.sleep CERTIFIED          = false

StructuralFamily(core.sleep) = LegacyCore
counters                     = 11 / 11 / 11
```

**STOP.** G3 may begin only with a separate GO, as Approved Differential Parity and
Migration Readiness. `core.sleep` remains IMPLEMENTED_UNCERTIFIED.
