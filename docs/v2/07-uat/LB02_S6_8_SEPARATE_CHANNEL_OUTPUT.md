# LB-02 / S6.8 — separate-channel Sh output substrate (design + staged plan)

## Accepted architecture decision

Represent `STDOUT` and `STDERR` as **distinct channels through capture**, then let
a per-Sh-mode projection policy decide what each channel becomes
(observable event vs typed return value). Do NOT use a merged-redirect model as
the canonical internal representation, and do NOT make the event stream the sole
source of truth for the shell result.

```text
subprocess
   ├── stdout ─┐
   └── stderr ─┤
               ↓
        channel-aware capture
               ↓
        projection policy
          /            \
   observable events    typed return/result
```

## Public contract chosen

- **plain `sh`**: stdout observable, stderr observable. Neither discarded.
- **`returnStdout=true`**: stdout captured/returned (not duplicated as a normal
  output event); stderr remains observable. `returnStdout` controls stdout, not
  stderr.
- **non-zero exit**: stdout/stderr produced before termination remain
  observable; exit != 0 → `Failure(SCRIPT)`. No output discarded on failure.
- **ordering**: per-stream order is contractual; cross-stream ordering is
  observed/best-effort unless the substrate explicitly sequences it. No fake
  timestamps.

## S6.8.1 — where stdout is currently lost (grounded)

The durable runtime already models channels as typed chunks
(`TaskStream.STDOUT` / `TaskStream.STDERR`, `ProcessDurableTaskRuntime`). The
loss happens upstream of that, in the **shell wrapper launch path** of
`DurableShellExecutor`:

- `buildWrapperContent` (≈ lines 493-558) builds the D3/D4 wrapper; for
  `captureStdout=false` the outer redirect is `> jenkins-log.txt 2>&1` (a merged
  single file); for `captureStdout=true` it is `> output.txt 2> jenkins-log.txt`.
- `executeTerminal` (≈ lines 851-1040) launches the wrapper and projects the
  terminal; `ShExecution.invokeShell` then reads either `capturedStdout`
  (capture mode) or the single `jenkins-log.txt` and emits one
  `EchoOutputCaptured`.

Observed inconsistency (installed distribution, registry path):

| fixture | EchoOutputCaptured |
| --- | --- |
| stdout only | stdout |
| stderr only | stderr |
| **stdout + stderr** | **stderr only — stdout lost** |
| returnStdout=true | none (stdout carried as value) |

The merged-file outer redirect plus the single-log read-back is where stdout is
dropped when both streams are present. This is the abstraction to correct: keep
**channel identity through capture** to the projection policy.

## S6.8.4 — projection policy (explicit, centralized)

```text
ShellOutputPolicy
  plain:        STDOUT -> emit ; STDERR -> emit
  returnStdout: STDOUT -> capture ; STDERR -> emit
  returnStatus: (preserve existing semantics; characterize before changing)
```

No modes that do not exist yet are designed.

## S6.8.3 — event-model sub-gate

Today the substrate emits `EchoOutputCaptured` (a single content string). Making
it channel-aware (`ExecutionOutputCaptured(channel, payload)` or a compatible
widening) touches the persisted event protocol, so it is an **explicit sub-gate**:
first check whether `EchoOutputCaptured` can carry channel metadata compatibly,
or whether a new typed event requires schema/versioning work. Do not silently
change a persisted event protocol. This decision is separate from the capture
fix and must be resolved before finalising the event representation.

## S6.8.2 — output vs transcript separation

`CoreShellOutput` / `EncodedStepValue` / `OperationOutput.result` carry only the
contractual returned value (e.g. stdout when `returnStdout=true`). The full
process transcript belongs to the output/event substrate, not the typed `O` or
the journal. Plain `sh` must not persist megabyte transcripts through
`encodedOutput`.

## Execution order (each step green, no legacy reopened)

1. Ground + add a controlled real-process **channel harness** asserting current
   behaviour (RED for the both-stream case).
2. S6.8.1 fix: separate-channel capture in the shell launch path, keeping channel
   identity; minimal production change.
3. S6.8.4 projection policy in `ShExecution`; S6.8.3 event-model sub-gate first.
4. S6.8.5 contract corpus C1..C6 via real fixtures.
5. S6.8.6 installed-distribution UAT (stdout+stderr, returnStdout+stderr,
   stderr+exit7).
6. S6.8.7 durability no-regression re-run (running recovery, cancellation,
   non-zero, durable output, core.echo certification).
7. S6.8.8 add the mandatory stderr/stdout rows to `ShStepContractSuiteTest`; if
   green, `core.sh = CERTIFIED`, `LB-02 = REMOVED`.

## Notes

- Reopens **no** legacy code: `CanonicalCoreStepCommand.Sh`,
  `CanonicalShellNodeDispatcher`, the legacy Sh decoder, and the legacy Sh
  metadata row stay removed. Any study of the old algorithm is historical only.
- The `24/14 → 24/12` coordinator narrowing from `8c4cbbae` is recorded as
  **pre-existing architecture debt fixed**, not a re-baseline.

## Checkpoint

The substrate fix is a deep, multi-file redesign of the shell executor's
wrapper/launch projection plus an event-model sub-gate with durability
implications. It is executed here as its own staged effort (step 1 RED harness
first) so each step stays green and the S6.8.7 durability gates are honoured,
rather than as an unverified broad rewrite.
