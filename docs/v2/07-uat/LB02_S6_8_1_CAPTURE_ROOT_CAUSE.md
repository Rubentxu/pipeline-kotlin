# LB-02 / S6.8.1 — root cause and durable-protocol sub-gate

## Root cause (grounded)

`DurableShellExecutor.launch` (lines 310-311) launches the wrapper with:

```kotlin
pb.redirectOutput(ProcessBuilder.Redirect.to(logFile))  // jenkins-log.txt
pb.redirectError(ProcessBuilder.Redirect.to(logFile))   // jenkins-log.txt (same file)
```

Each `Redirect.to(file)` opens the file **O_TRUNC** with an independent file
descriptor. The second open truncates whatever the first descriptor wrote, so
when a process writes BOTH stdout and stderr:

```text
stdout fd → opens+truncates log → writes OUT
stderr fd → opens+truncates log (ERASES OUT) → writes ERR
→ log contains only ERR
```

This is the double-truncation bug that silently drops stdout when both streams
are present. Single-stream cases are unaffected (only one fd opens). This fully
explains the observed C3 failure (`both → only stderr`) and why `only-out` /
`only-err` are correct.

## Architectural finding — detached durable file model

The durable shell executor is **detached and file-based by design** for
kill/resume/recovery:

```text
launch (setsid bash wrapper) → detach → poll result.txt → read control-dir files
```

The JVM does NOT hold the subprocess pipes. A restarted JVM reconciles a RUNNING
process purely from the control directory (`.cookie`, `result.txt`,
`jenkins-log.txt`). Therefore live `TaskStream.STDOUT/STDERR` channel pipes
**cannot** span a detach/recovery. The `TaskStream` chunk model exists on a
*different*, in-JVM streaming path (`ProcessDurableTaskRuntime`), not the
detached durable shell path.

Consequence: in this model, per-channel durable identity means **separate
durable files** written by the detached wrapper, or a **single merged durable
transcript** that preserves both channels without loss.

## Decision — the S6.8.1 sub-gate

`jenkins-log.txt` is the merged-stream durable on-disk protocol read by ~14 main
files including recovery (`StepReconcilerL1`, `DurableScriptedOperationReconciler`,
`PipelineRun`, `ShExecution`, `DurableWalkContext`). Separating channels requires
choosing one of:

### Option A — protocol-preserving single-FD merge (no durable-protocol change)

Use one file descriptor for the merged durable transcript:
`pb.redirectErrorStream(true)` + a single `redirectOutput(log)`, or a shell
`> log 2>&1` so only ONE fd truncates/opens the file. `jenkins-log.txt` then
contains both stdout and stderr (no loss, no double-truncation). Recovery,
reattachment, and the file layout are unchanged.

- fixes C1-C3 (both observable, neither lost, neither duplicated) for plain sh
- preserves the durable on-disk protocol exactly
- **but** the durable file is merged (matches the existing persistent transcript);
  channel identity is not preserved as separate durable files.

### Option B — separate durable channel files (durable-protocol sub-gate)

Redirect stdout and stderr to **separate** durable files (e.g. keep
`jenkins-log.txt` / `output.txt` semantics but split stdout and stderr), then
project per channel. This is the literal "separate channels canonical" model but
**changes the durable on-disk protocol** consumed by the recovery/reattachment
files, requiring a compatibility matrix and reconciliation support before any
version bump. This is the larger, riskier change.

## Recommendation

The double-truncation data-loss bug is independent of the channel-identity
design and MUST be fixed regardless (both options fix it). Option A is the
minimal, protocol-preserving fix that restores the plain-sh public contract
(C1-C3: both streams observable, no loss) without touching recovery/persistence.
Option B is the full separate-durable-channel realization and is a durable
on-disk protocol change.

The user directive forbids `redirectErrorStream`/`2>&1` as the *canonical
capture authority*, but the detached durable shell transcript is file-persisted
by necessity. Which durable strategy is canonical for the plain-sh transcript —
**A (single merged durable file, both channels preserved) or B (separate durable
channel files, sub-gate)** — is the decision this sub-gate surfaces.

## Gate

Per S6.8.1, changing the durable on-disk protocol used by running/recovery is a
sub-gate. This document characterizes the dependency and presents the two
options; implementation waits on the durable-strategy decision. No legacy code is
reopened; no schema/version bump has been made.
