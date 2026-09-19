# PipelineK — Events & troubleshooting

**Release verified against**: `pipelinek 0.39.0`.

## What events look like

Every CLI run emits a stream of typed JSON events to **stdout**, one
per line, NDJSON-compatible. Each event has:

```json
{
  "eventId":   "uuid-v4",
  "runId":     "uuid-v4",
  "sequence":  <int>,
  "kind":      "CompilationStarted" | "CompilationFinished" |
               "RunStarted"           | "RunFinished" |
               "StageStarted"         | "StageFinished" |
               "StepStarted"          | "StepFinished" | "StepFailed" |
               "EchoOutputCaptured",
  "occurredAt":"2026-09-19T09:26:45.066Z",
  ...kind-specific fields...
}
```

`RunFinished.outcome` is the headline:

- `"success"` — the run succeeded; the CLI exits `0`.
- `"failure"` — at least one step failed; the CLI exits `1`. The failing
  step's `failureKind` and `message` are in the `StepFailed` event.

## Where to find what happened

| Where | What | Sensitive? |
|---|---|---|
| stdout (during the run) | live NDJSON event stream | No (events are redacted) |
| `--db <file>.sqlite` | durable journal of every event | No (redacted) |
| `--control-root/<run-id>/transcript.txt` | raw transcript of the `sh` step | **Yes — may contain secrets** (redacted) |
| `--control-root/<run-id>/…` | step-level captured output | Possibly |

## Common failure modes

### `StepFailed` with `failureKind=SCRIPT`

The step's shell command exited non-zero. Look at the transcript for
the command's own error output.

```bash
less "$PIPELINEK_CONTROL_ROOT/<run-id>/transcript.txt"
```

If the transcript shows a credentials-related error (HTTP 401,
"authentication failed"), check that the environment variable your step
expects is set.

### Run ends immediately with no `StepStarted`

Usually means the script failed to compile (`validate` would also have
returned exit `2`). Inspect the `CompilationFinished` event for
`diagnostics[]`.

### The CLI exits `0` but the run failed

This is **not** a thing in `0.39.0`: `outcome=success` implies CLI exit
`0`, and `outcome=failure` implies CLI exit `1`. If you observe
otherwise, it is a bug; report it.

## Re-running a previous run

If your run failed and you fixed the script:

```bash
# Same --db and --control-root as the failed run; --rerun re-executes all steps.
pipelinek run --workspace . --db myapp.sqlite --control-root myapp-ctl \
              --rerun pipeline.kts
```

If your run failed and you want to continue from where it left off
(only the not-yet-finished steps run):

```bash
# No --rerun; resume by default.
pipelinek run --workspace . --db myapp.sqlite --control-root myapp-ctl \
              pipeline.kts
```

In both cases, the `--db` and `--control-root` paths must match the
previous run exactly; otherwise PipelineK starts fresh.

## How to read the SQLite journal

The journal schema is **not** part of the stable contract. If you need
to inspect it from outside, prefer the NDJSON event stream captured
from stdout (with `tee` or a logger) over writing to the database
directly.
