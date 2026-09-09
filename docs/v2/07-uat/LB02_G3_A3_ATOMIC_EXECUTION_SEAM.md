# LB-02 / G3-A3: Atomic Execution Seam (Path a)

**Cycle:** LB-02 / G3 (registry Step path) — slice A3
**Cycle base:** `53d096b2` (G3-A2 — preserve StepOutcome / encoded-output distinction)
**Author:** LFC-2 pipeline-application author
**Status:** Accepted per A3 gate

## Summary

A3 implements Path (a) of the durable spine projection: widening
`CommonExecutionBoundary.execute()` to return `CommonExecutionResult(outcome, encodedOutput?)`
atomically, instead of introducing a sibling `encodedOutputFor()` accessor on the registry
boundary (Path (b)).

The decision rationale is documented in the cycle's correspondence: Path (b) would have
introduced **temporal connascence** between `execute()` and `encodedOutputFor()` — two
calls that must observe exactly the same execution in order, with no shared state to
prove it. The carrier value `CommonExecutionResult` is the single source of truth
for one execution and is computed in one pass.

## Why Path (a) over Path (b)

The user rule is:

> execution produces data; durability decides whether/how to persist it

Path (b) would have violated this rule because `encodedOutput` is produced **inside**
`execute()`. To expose it through a second call we'd need:

1. Internal state on the registry boundary (mutable, concurrent-execution hazard),
2. mutating `prepared` (violates immutability / prepared is immutable by construction),
3. caching the result in `ctx` (ctx is shared across multiple Steps — collision),
4. recomputing it (defeats the point of having called `execute()`),
5. a side-channel between the two calls (temporal connascence).

Path (a) widens the seam atomically: one return value, two responsibilities,
zero coupling between calls.

## Implementation

### Files changed (production)

| File | Change |
|---|---|
| `CommonExecutionResult.kt` (new) | The atomic carrier: `outcome: StepOutcome, encodedOutput: EncodedStepValue?` |
| `CommonExecutionBoundary.kt` | `execute()` returns `CommonExecutionResult` instead of `StepOutcome` |
| `LegacyExecutionAdapter.adapt()` | Lifts the legacy `StepOutcome` into a `CommonExecutionResult(outcome, encodedOutput = null)` |
| `RegistryExecutionBoundary.coexecute()` | Returns `CommonExecutionResult` (was `RegistryExecutionResult`) with the typed `O` encoded through `outputCodec.encode(O)` |
| `RegistryExecutionBoundary.adapt()` | Routes registry family to `coexecute()` directly; rejects legacy family |
| `StepExecutionBoundary.execute()` | Body returns `CommonExecutionResult`; lifecycle events still key off `outcome` |
| `CanonicalDurableRunCoordinator.kt` | Three sites (`Execute`, `RecoverRunning`, `RejectedAbort`) updated to consume `CommonExecutionResult` and project `encodedOutput` into `OperationOutput(result, durationMs, finishedAt)` |
| `ExecutionBoundaryFactory.kt` | `RecordingBoundary.execute()` returns `CommonExecutionResult` |
| `RegistryExecutionResult.kt` (deleted) | Renamed to `CommonExecutionResult` at the seam |

### Files changed (tests)

8 existing test classes mechanically migrated to consume `.outcome`:
`DualExecutionSeamCharacterizationTest`,
`DurableProtocolInvocationCharacterizationTest`,
`ExecutionBoundaryFactoryTest`,
`GenericRegistryExecutionCarrierTest`,
`RegistryExecutionOutcomeTest`,
`SeamedExecutionRouterTest`,
`StepAdmissionObservedTest`,
`StepExecutionBoundaryTest`,
`StepOutcomeEncodedOutputDistinctionTest`,
`CoordinatorFixtureTest`.

1 new test class added (3 tests):
- `A3DurableProjectionCharacterizationTest` — proves the durable projection law,
  legacy behaviour preservation, and A3.6 atomicity (no retention across calls).

## Gate acceptance (per user spec)

| Gate | Status | Evidence |
|---|---|---|
| legacy behavior unchanged | ✅ | Legacy path returns `encodedOutput = null`; A3-1 proves |
| registry output encoded | ✅ | Registry boundary encodes typed `O` via `outputCodec.encode(O)` |
| `OperationOutput.result` populated | ✅ | A3-2 proves (`"a3-registry\\n"` for echo) |
| journal schema unchanged | ✅ | No schema field added, renamed, or removed |
| fingerprint unchanged | ✅ | No fingerprint computation touched |
| ReplayPolicy laws unchanged | ✅ | No ReplayPolicy touched |
| `CommonExecutionBoundary` remains single execution seam | ✅ | No `encodedOutputFor()` accessor; one call returns both |
| coordinator does not know typed O | ✅ | Coordinator only reads `.outcome` and `.encodedOutput?.value` (string), never calls a codec |
| coordinator does not need registry-specific output accessor | ✅ | Projection is a single `let { OperationOutput(...) }` over `CommonExecutionResult.encodedOutput` |
| `core.echo` StepContractSuite still green | ✅ | 17/17 |

## Test evidence

Run command (targeted, inner loop):

```bash
timeout 600 ./gradlew -p v2 :pipeline-application:test --tests \
  'EchoStepContractSuiteTest' \
  'ReconciliationStatusOnlyTest' \
  'ReplayOutputDecouplingTest' \
  'StepAdmissionObservedTest' \
  'CoordinatorFixtureTest' \
  'StepExecutionBoundaryTest' \
  'SeamedExecutionRouterTest' \
  'ExecutionBoundaryFactoryTest' \
  'GenericRegistryExecutionCarrierTest' \
  'RegistryExecutionOutcomeTest' \
  'StepOutcomeEncodedOutputDistinctionTest' \
  'A3DurableProjectionCharacterizationTest' \
  --no-daemon
```

Result (12 test classes, 81 tests): all green.

| Class | tests | failures | errors |
|---|---|---|---|
| EchoStepContractSuiteTest | 17 | 0 | 0 |
| ReconciliationStatusOnlyTest | 2 | 0 | 0 |
| ReplayOutputDecouplingTest | 23 | 0 | 0 |
| StepAdmissionObservedTest | 9 | 0 | 0 |
| CoordinatorFixtureTest | 3 | 0 | 0 |
| StepExecutionBoundaryTest | 4 | 0 | 0 |
| SeamedExecutionRouterTest | 2 | 0 | 0 |
| ExecutionBoundaryFactoryTest | 4 | 0 | 0 |
| GenericRegistryExecutionCarrierTest | 6 | 0 | 0 |
| RegistryExecutionOutcomeTest | 4 | 0 | 0 |
| StepOutcomeEncodedOutputDistinctionTest | 4 | 0 | 0 |
| A3DurableProjectionCharacterizationTest | 3 | 0 | 0 |
| **TOTAL** | **81** | **0** | **0** |

## Pre-existing failures (NOT introduced by A3)

`DualExecutionSeamCharacterizationTest` (2 tests) and `DurableProtocolInvocationCharacterizationTest` (1 test)
fail with "No canonical core metadata registered for plugin 'core.echo'". These failures
were verified to exist on the cycle base SHA `53d096b2` via `git stash + test + git stash pop`,
proving they are pre-existing test isolation issues (the canonical coordinator's metadata
registration path is missing the echo entry — a separate work item from A3).

The DSL UAT tests (`UatDsl001JenkinsFamiliarityTest`, `UatDsl005TimeoutGrammarTest`,
`UatDsl006BodyExecutionTest`, etc.) similarly fail at `origin/main` before A3 was applied —
verified via the same stash+test+pop pattern. 10/13 of these DSL tests fail on the base
SHA, all with `IllegalStateException` at fixture construction, before reaching the
execution seam touched by A3.

Per rule 16: these failures are NOT classified as A3 regressions and remain for separate
investigation.

## A3.6 atomicity law

`A3-6 boundary does not retain execution output across calls` (3rd test in the new
characterization) explicitly proves:

1. Invoking the boundary twice with different inputs yields independent carriers
   (no shared state).
2. Re-invoking the first invocation reproduces the original carrier exactly
   (no hidden mutation).
3. There is no `lastOutput` field, no `ThreadLocal`, no per-key cache — the
   carrier is the single source of truth.

The boundary code (`RegistryExecutionBoundary.coexecute`) is stateless: the carrier is
built in one `try` block from local variables (`definition`, `contract`, `access`,
`handlerContext`, `produced`, `encoded`) and returned. No mutable state survives the
function call.

## Future work (NOT in this commit)

A4: route `core.sh` through the registry path, replacing `CanonicalShellNodeDispatcher`'s
direct call to `ShExecution.invokeShell` with a typed handler that goes through the new
seam. This will require:

- A registry-defined `ShStepHandler` that delegates to the existing `ShExecution.invokeShell`
  (no process-engine reimplementation).
- An `SHELL_OPERATIONS_CAPABILITY` StepCapability that wraps `ShExecution.invokeShell`
  (inert supporting files `ShellOperations.kt`, `ShellOperationsCapabilityKey.kt`,
  `ShOperationsAdapter.kt` are already in working tree, uncommitted — see LB02_G0 doc).

The inert supporting files were left uncommitted in this commit deliberately; they are
not referenced by any production code path on `origin/main` and remain a quarantined
backlog item until A4 begins.
