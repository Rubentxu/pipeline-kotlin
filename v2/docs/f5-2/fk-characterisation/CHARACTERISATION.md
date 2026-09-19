# WU-LPR-FK — Characterisation

## What is in scope

The transformation `PluginStepException(kind=USER) → envelope.failureKind=ENGINE`
observed in F5.2's negative scenarios. The audit must distinguish:

- A failure declared by a plugin handler as `USER`.
- An unexpected exception from a plugin handler (NPE, ISE, IOOBE, etc.).
- A failure from `core.sh`'s exit-code path (separate code, separate contract).
- A failure from `RegistryExecutionBoundary`'s catch block (separate code,
  separate contract).
- A failure from `RegistryExecutionPreparation.prepare` (codec / decode /
  missing capability) — fails BEFORE the handler runs.

## Where the kind actually lives

The boundary has exactly one transformation site:

`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/RegistryExecutionBoundary.kt`

```kotlin
return try {
    val produced: Any = definition.handler.execute(prepared.decodedInput, handlerContext)
    ...
    val outcome: StepOutcome =
        (produced as? TypedStepOutput)?.outcome
            ?: StepOutcome.Success
    CommonExecutionResult(outcome = outcome, encodedOutput = encoded)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    CommonExecutionResult(
        outcome = StepOutcome.Failure(
            PipelineFailure(
                kind = FailureKind.ENGINE,
                message = "registry step '${prepared.key.value}' handler failed: ${e.message ?: "unknown"}",
                cause = e,
            ),
        ),
        encodedOutput = null,
    )
}
```

Two paths:

1. **Happy path / typed-failure path** — `produced` is a `TypedStepOutput`
   → boundary uses `produced.outcome` verbatim.
2. **Exception path** — any `Exception` thrown by the handler → wrapped as
   `StepOutcome.Failure(kind=ENGINE, message, cause)`.

The boundary does NOT inspect the exception type. There is no
`PluginStepException` recognition, no `kind` extraction. Whatever kind
the plugin handler declared is lost the moment it throws.

## The contract that already exists (and what it implies)

`v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/TypedStepOutput.kt`:

```kotlin
/**
 * LB-02 / G3-A4.3 — marker interface for Step-produced outputs that carry a canonical
 * [StepOutcome] alongside the durable payload.
 *
 * Contract:
 *  - `outcome` MUST be derived from the same domain ADT that produced [OperationOutput.result]
 *    via the single, reusable classifier for that Step.
 *  - `outcome` MUST be stable for the lifetime of this output.
 *  - Implementations MUST NOT duplicate or override the classifier inside the Step handler.
 *
 * [CommonExecutionBoundary] uses `produced as? TypedStepOutput` to project `outcome` without
 * needing to know the concrete Step type.
 */
interface TypedStepOutput {
    val outcome: StepOutcome
}
```

The contract explicitly states: a Step whose handler wishes to declare a
typed failure must return a `TypedStepOutput`. The boundary is Step-agnostic
and reads the outcome by structural subtyping. Throwing exceptions is the
unhappy path reserved for adapter/engine faults.

## Survey of every plugin handler that fails today

### `core.sh` — `CoreShellStep` (`v2/pipeline-application/.../CoreShellStep.kt`)

`core.sh` returns `CoreShellOutput(result: ShellInvocationResult, outcome: StepOutcome)`.
`CoreShellOutput` implements `TypedStepOutput`. `outcome` is computed via
`ShellStepOutcomeClassifier.toStepOutcome()` from the typed `ShellInvocationResult`.

`ShellInvocationResult.Failed.failure.kind` for `sh` exit non-zero is
`FailureKind.SCRIPT` (line 70 of `ShellInvocationResult.kt`):

```kotlin
private fun DurableTaskTerminal.Exited.scriptFailure(): ShellInvocationResult.Failed =
    ShellInvocationResult.Failed(
        PipelineFailure(FailureKind.SCRIPT, "shell exited with code $exitCode"),
        exitCode = exitCode,
    )
```

`FailureKind.SCRIPT` IS what we observe in the event envelope
(`StepFailed(failureKind=SCRIPT)`) for F5.2's E2E (e.g. `neg3_failing_strict`).

**`core.sh` is consistent with the contract.**

### `junit.results` — `JUnitResultsStepDefinition` (`v2/pipeline-step-sdk/junit/.../JUnitResultsStepDefinition.kt`)

The handler `throws PluginStepException(failure = PipelineFailure(kind = USER, message))`
on every error path. The boundary catches this as a generic `Exception`
and produces `StepOutcome.Failure(kind=ENGINE, message="registry step
'junit.results' handler failed: ...")`.

**`junit.results` violates the contract. The handler SHOULD return a
`TypedStepOutput` with `outcome = StepOutcome.Failure(USER, ...)` rather
than throw.**

### Other handlers

Surveyed:

- `core.echo` (LB-02 / G3-A4.1 certified, atomic): returns the typed output
  directly; no `TypedStepOutput` wrapper needed because echo never fails.
- `core.writeFile`, `core.readFile` (file-step-sdk): not surveyed in depth
  for this cycle; if they fail, they currently throw or wrap as
  `failureKind = USER`. Out of scope for this WU.
- `core.deleteDir`, `core.cleanWs` (temporary-workspace operations): not
  surveyed for this cycle.
- `core.archiveArtifacts`: out of scope.

## Behaviour matrix (observed in F5.2 receipts)

| Origin | Failure path | `PipelineFailure.kind` declared | envelope `failureKind` |
| --- | --- | --- | --- |
| `core.sh` exit 0 | success | — | — (StepFinished, not StepFailed) |
| `core.sh` exit 1 | `ShellInvocationResult.Failed(failure=SCRIPT)` → `TypedStepOutput.outcome = Failure(SCRIPT)` | SCRIPT | SCRIPT ✅ preserved |
| `junit.results` missing file | throws `PluginStepException(USER, "report file not found at ...")` | USER | ENGINE ❌ lost |
| `junit.results` malformed XML | throws `PluginStepException(USER, "malformed XML ...")` | USER | ENGINE ❌ lost |
| `junit.results` failOnFailure + failing XML | throws `PluginStepException(USER, "1 failed test(s)")` | USER | ENGINE ❌ lost |
| `junit.results` failOnFailure=false + failing XML | returns `JUnitReportSummary` (success) | — | — (StepFinished) |
| Unknown handler exception (NPE etc.) | boundary catch | (no kind — handler didn't declare) | ENGINE ✅ correct |

The transformation `USER → ENGINE` is real and observable for the only
plugin whose handler fails today with a typed USER failure (`junit.results`).

## Where the boundary's catch is correct

The boundary MUST classify unexpected exceptions as `ENGINE`. A handler
that throws `NullPointerException`, `IllegalStateException`,
`ClassCastException`, etc. is a real adapter/engine defect, and the
envelope must surface that — the audit story requires ENGINE for these.
Changing the boundary to inspect every exception class and look for
`PluginStepException` would (a) couple the boundary to a specific
exception type and (b) still not solve the contract violation; plugins
must declare their typed failure via the public `TypedStepOutput` seam,
not via a magic exception class.

## Decision

**Decision: NO change to `RegistryExecutionBoundary`.**

The boundary is consistent with the contract it documents. The contract
says: declare typed failures via `TypedStepOutput`. The contract is also
the LB-02 / G3-A4.3 reference. Changing the boundary to inspect
exception classes would violate the same contract from the other side
and create the very coupling the boundary was designed to avoid.

**Decision: MIGRATE `JUnitResultsStepDefinition` to return `TypedStepOutput`.**

The handler's current "throw PluginStepException(USER)" is the violation.
The minimum, generic change is:

- Define a `JUnitResultsOutput` data class implementing `TypedStepOutput`,
  carrying `summary: JUnitReportSummary` AND `outcome: StepOutcome`.
- Replace each `throw PluginStepException(...)` with
  `return JUnitResultsOutput(summary, outcome)` where `outcome` is
  `StepOutcome.Failure(PipelineFailure(USER, message))`.
- The handler's `O` type becomes `JUnitResultsOutput`; the contract's
  `outputCodec` stays `JUnitReportSummaryCodec` (no change there — the
  envelope only sees `JUnitReportSummary`, the outcome is separately
  projected).
- Success path: handler still returns a `JUnitResultsOutput` with
  `outcome = Success`.

This is the SAME pattern `CoreShellStep` already uses (`CoreShellOutput`).

## Cascade check

After the migration:

- `RegistryExecutionBoundary.coexecute` sees `produced as TypedStepOutput`
  → reads `outcome = Failure(USER, ...)` → returns
  `CommonExecutionResult(outcome = Failure(USER, ...))`.
- `CanonicalDurableRunCoordinator` calls `stepFailed(outcome)` →
  emits `StepFailed(failureKind=USER, message=...)`.
- The typed `JUnitReportSummary` is encoded by `outputCodec.encode(produced)`
  (where `produced` is `JUnitResultsOutput`). The codec is
  `JUnitReportSummaryCodec` which expects `JUnitReportSummary` — we must
  make the boundary extract the `summary` field from the `TypedStepOutput`
  before encoding.

The last point requires care. The boundary's existing code does:

```kotlin
val encoded: EncodedStepValue? = if (produced is Unit) {
    null
} else {
    val codec = definition.contract.outputCodec as StepCodec<Any>
    codec.encode(produced)
}
```

If `produced` is now `JUnitResultsOutput`, calling
`JUnitReportSummaryCodec.encode(JUnitResultsOutput)` would fail because
the codec expects `JUnitReportSummary`. Two options:

1. Change the contract's `outputCodec` to encode `JUnitResultsOutput`,
   carrying both fields. The durable journal would then roundtrip the
   failure kind too (an additive gain, but a journal-shape change).
2. Keep the contract's `outputCodec` encoding `JUnitReportSummary`. Make
   the boundary extract `produced.summary` for a `TypedStepOutput`
   carrier (generic, no per-step branching) — i.e. add a
   `TypedStepOutput.unwrapSummary` contract or a generic extractor.

Option 2 needs the boundary to know `produced` is a `TypedStepOutput`
that ALSO carries a "summary" view. That is overloading. The cleanest
generic approach: a `TypedStepOutput` carrier optionally exposes the
value to encode via a separate interface, OR the codec encodes the
carrier directly and the carrier is the durable value.

The simplest minimum change is **option 1**: the durable output is the
`JUnitResultsOutput` itself. The journal records both `summary` and
`outcome`. The current `JUnitReportSummaryCodec` becomes
`JUnitResultsOutputCodec`. This is an additive journal change (the
field set grows); no existing journal entry is invalidated because
`junit.results` is the FIRST plugin in the certified OFFICIAL_PLUGIN
family to declare a typed failure, so no historical journal exists
that would diverge.

After deliberation: option 1 is correct for this WU. The journal
change is additive and documented in the receipt.

## Tests (lock-in suite)

`RegistryExecutionBoundaryFailureKindTest` (new) under
`v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/`:

1. `handler throws unexpected Exception → boundary outcome.kind == ENGINE`
   (regression: this is the only place ENGINE is correct today).
2. `handler returns TypedStepOutput(outcome = Failure(USER)) → boundary outcome.kind == USER`
   (the lock-in assertion the migration must satisfy).
3. `handler returns TypedStepOutput(outcome = Success) → boundary outcome == Success`.
4. `handler returns TypedStepOutput(outcome = Failure(SCRIPT)) → boundary outcome.kind == SCRIPT`
   (parity with `core.sh`'s reference).
5. `handler returns TypedStepOutput(outcome = Failure(TIMEOUT)) → boundary outcome.kind == TIMEOUT`.

Tests 2..5 assert the public contract: a `TypedStepOutput` carrier is
the SINGLE authority for outcome projection, and the boundary never
rewrites the kind.

`F5_2_JUnitStepContractTest` (existing, expanded):

6. `handler fails USER (missing report) → outcome.kind == USER` (was:
   boundary catch → ENGINE; after migration: USER).
7. `handler fails USER (malformed XML) → outcome.kind == USER`.
8. `handler fails USER (failOnFailure=true + failing XML) → outcome.kind == USER`.

Tests 6..8 are the regression surface for the F5.2 plugin migration.
