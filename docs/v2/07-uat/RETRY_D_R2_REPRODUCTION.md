# RETRY-D R2 — installed-distribution reproduction at HEAD

**Date:** 2026-09-10
**HEAD:** `e4cca233` (post B13/E-EM-11 closure)
**Binary:** `v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application`
**Driver:** `/home/rubentxu/.jcode/scratch/retry-d-r2/r2_run.sh`

## Method

The driver writes a `pipeline-retry-r2.kts` with `retry(2) { sh("""…""") }` whose body:

- increments `$WORKDIR/counter.txt` on every `sh` invocation (independent of exit code);
- if `$WORKDIR/marker.txt` is absent: writes `attempt-1` and exits 1;
- if the marker is present: writes `retry-ok` and exits 0.

Both runs use the **same `--db` and `--control-root`**. The default durable reuse path is in effect. The counter proves whether `sh` was actually executed; the marker proves whether the body semantics changed.

## R2 reproduction (RED baseline, HEAD)

```
== BEGIN MODE=failFirst ==
COUNTER_PRE=absent
MARKER_PRE=absent
EXIT=0
COUNTER_POST=2
MARKER_POST=attempt-1\nretry-ok
LOG_TAIL=…RetryAttemptStarted(1)…StepStarted(sh-0)…StepFailed…StepFinished…
…RetryAttemptStarted(2)…StepStarted(sh-0)…StepFinished…RunFinished(success)…
== END MODE=failFirst ==

== BEGIN MODE=reuse ==
COUNTER_PRE=2
MARKER_PRE=attempt-1\nretry-ok
EXIT=0
COUNTER_POST=3
MARKER_POST=attempt-1\nretry-ok\nretry-ok
LOG_TAIL=…RetryAttemptStarted(1)…StepStarted(sh-0)…StepFinished…StageFinished(success)…
== END MODE=reuse ==
```

## Observation

- Run #1 used 2 `sh` invocations (one failed, one succeeded). The retry block reached terminal success at attempt 2.
- Run #2 (no `--rerun`) used 1 additional `sh` invocation. The retry block ran again from attempt 1. The marker appended a second `retry-ok`.
- The retry block does NOT honor durable completion on re-entry: `dispatchBody` enters the loop at attempt 1 on every fresh coordinator invocation.
- No new `RetryAttemptFinished` events are emitted by the canonical coordinator; `RetryAttemptStarted(2)` only appears on run #1 because run #2 took the success branch on attempt 1 (no failure → no attempt 2).

## Acceptance criteria for GREEN

After RETRY-D closes, run #2 must produce:

```
COUNTER_POST=2            # no new sh invocations
MARKER_POST=attempt-1\nretry-ok  # no new marker line
```

with zero `StepStarted(sh-0)` events in run #2.

## Saved artifacts

- `r2_run.sh` (driver)
- `$SCRATCH/retry-d-r2/state/counter.txt`
- `$SCRATCH/retry-d-r2/state/marker.txt`
- `$SCRATCH/retry-d-r2/state/journal.db`
- `$SCRATCH/retry-d-r2/state/control/`
- `$SCRATCH/retry-d-r2/state/run-failFirst.log`
- `$SCRATCH/retry-d-r2/state/run-reuse.log`
