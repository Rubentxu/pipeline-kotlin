# LB-02 Decision: Typed-Output Channel for Registry-Routed Steps

**Status**: decided 2026-09-09 at HEAD `1eb06d0a`. Authoritative until an ADR overrules it.
**Scope**: LB-02 `core.sh` (and every future registry-routed Step whose typed handler returns
a non-`Unit` value the durable protocol must observe).
**Authority**: per AGENTS.md, ADRs/SPECs win; this note is a working decision that must either
graduate into an ADR (preferred) or be re-opened at the earliest gate (G0 baseline canary).

## TL;DR

The registry execution boundary today discards the typed `O` returned by a `StepHandler`
(`/v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/RegistryExecutionBoundary.kt`,
lines 76-87). Echo doesn't notice: its observable meaning flows through the `EventSink`
capability, not through the typed result. `core.sh` cannot work that way: a shell invocation
returns a `ShellInvocationResult` (typed ADT) that the durable protocol must read to classify
exit code, stdout, returnMode, cancellation, and failure kind. The decision is to lift the
typed-output channel into the durable protocol via `EncodedStepValue` rather than coupling it
to log files on disk.

## The fork

`core.sh` must surface:

1. exit code → `Status` of the typed result.
2. stdout when `returnMode == STDOUT` → `Stdout(value)`.
3. status when `returnMode == STATUS` → the channel above carries the typed value.
4. cancellation distinct from failure → `Interrupted(interruption)` must NOT collapse into
   `StepOutcome.Failure`.
5. failure kind (`SCRIPT` vs `INFRASTRUCTURE` vs timeout) → must be classified before the
   durable journal sees a terminal.

Path B (observability-only via EventSink + log files on disk) leaks stdout across Steps only
through files, which breaks Jenkins equivalence for the cross-Step stdout case and creates a
side channel that the durable protocol cannot reason about.

## Path A (chosen) — Typed-output channel via `EncodedStepValue`

### Seam shape

```text
StepHandler.execute(input: I, ctx: StepHandlerContext): O
   ↓
outputCodec.encode(O) -> EncodedStepValue   (value-class over String, no Any in durable protocol)
   ↓
journal.setTerminalWithOutput(opId, status, EncodedStepValue)
   ↓
durable protocol stores EncodedStepValue alongside RerunOperation.status
   ↓
Replay: re-read EncodedStepValue, decode via outputCodec.decode(...) -> O, classify to StepOutcome.
```

The single new seam is `RegistryExecutionBoundary.execute` writing the encoded output into the
journal at terminal classification time. Echo pattern generalises: observability may flow
through capabilities (events) AND/OR through the typed-output channel; the latter is the
canonical answer for any value a later Step in the same run might consume.

### No-`Any` invariant

`EncodedStepValue` is `value class EncodedStepValue(val value: String)` (already declared at
`/v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/StepRegistry.kt:15`).
The handler's typed `O` is never stuffed into a `StepOutcome` and never crosses to the
coordinator as `Any`; it crosses as a String under an `outputCodec`. The "no `Any` as durable
contract" rule from AGENTS.md §MUST NOT still holds at the journal layer. The output codec is
the validator: it must accept only well-formed payloads (rejection is a typed failure).

### Replay correctness

When the journal already records `RerunOperation` with status `SUCCEEDED` and a non-empty
`EncodedStepValue`, the durable spine reuses the encoded output and the handler does NOT
re-run. This is exactly the echo replay shape, generalised. For `core.sh`, replay refusing to
re-run the process is the Jenkins-equivalent semantics (a step that already SUCCEEDED
remembers its output).

### Recovery interaction

`RecoveryPolicy.ExternalSubprocess` stays on the `StepMetadata` (read by the coordinator, not
the Step key, per ADR-0074 territory). Recovery for `core.sh` continues to inspect the control
directory + process state via `StepReconcilerL1`. The new typed-output slot does NOT bypass
recovery; it records the recovered terminal after the reconciler has decided
Complete/TimedOut/Reattach/Lost. On Reattach, the recovered output is re-classified into the
typed shell result before the journal absorbs it.

### Cancellation

`ShellInvocationResult.Interrupted` becomes `OperationStatus.ABORTED` (or the equivalent
durable terminal for cancellation) — distinct from `FAILED`. The coordinator's `RunOutcome`
already collapses aborted to `Aborted` (closed set `Success | Unstable | Failure | Aborted`).
This requires no new public surface: the typed output codec classifies for the journal.

### Risks

- **Journal schema**. Adding a slot to `RerunOperation` touches every test that asserts the
  schema shape. Read-compatibility MUST be preserved: existing legacy entries (no slot)
  remain `Optional` on read.
- **Codec proliferation**. Every registry Step that wants typed output needs both codecs.
  Acceptable: this is what AGENTS.md §Step implementation golden path requires already.
- **Capability-overlap temptation**. The new typed-output channel is NOT a hidden capability;
  it is a property of the durable protocol and the engine reads it via `outputCodec.decode`.
- **Side-channel entry**. If anyone adds a second typed-output channel, divergence reappears.
  Mitigation: ONE seam (the journal slot), enforced by an architecture fitness (similar to
  the existing "no central concrete-Step switch" rule).

## Path B (rejected) — observability-only via EventSink

Doesn't surface typed stdout across Steps. Couples durable output to control-dir log files.
Cannot express `returnStdout` cleanly. The shell invocation result is encoded into a thin
String status projection (`runShellCommand`), which is already deprecated for canonical
callers. Path B is structural debt.

## Reopen conditions

This decision is reopened (treated as not-decided) at any of the following gates:

1. G0 baseline canary surfaces a typed-output requirement that Path A cannot express.
2. An ADR (`ADR-0076` or later) is published that explicitly overrides this note.
3. The Post-LB-02 "external plugin proof" requires a different output channel.
4. `PipelineRule` / TestKit replay assertions diverge from the typed-output semantics in a
   way Path A cannot repair without breaking the no-`Any` invariant.

Until one of those gates triggers, the G1..G8 sequence in `AGENTS.md §Burn-down sequence
template G0..G8` may proceed under Path A.

## Open questions deferred to G1 / G7

- Exact `operation.output: EncodedStepValue?` placement on `RerunOperation` (nullable for
  legacy entries and for handlers that don't return typed output).
- How `ReplayPolicy.Once` interacts with the typed-output slot — must end up
  equivalent to echo (replay = re-read encoded output, no re-run).
- Whether the output slot is stored as `String` (current) or a further-encoded form for
  secret-aware steps (e.g. shell stdout with embedded credentials) — defer until `core.sh`
  StepContractSuite surfaces a real need.

## References

- AGENTS.md §STEP IMPLEMENTATION — OPERATIVE GUIDE (DERIVED FROM ADRs/SPEC).
- AGENTS.md §STEP CONSTITUTION & EXTENSIBILITY, item 7 (forbidden) and item 9 (CERTIFIED).
- `/v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/RegistryExecutionBoundary.kt:76-87`
  — the current seam that drops typed `O`.
- `/v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/StepRegistry.kt:15`
  — `EncodedStepValue` definition (the canonical channel).
- `/v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/ShellInvocationResult.kt`
  — the closed shell result ADT that motivates Path A.
- `/v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/ShExecution.kt:135`
  — `invokeShell` (the certified engine entry-point `CoreShellStep.handler` adapts to).
- `/v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt:795-818`
  — `recoverRunningShell`, which reads `metadata.recoveryPolicy` from the contract (not the
  Step key).
