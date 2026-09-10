# pipeline-kotlin examples

Real, runnable pipelines executed by the V2 CLI binary
(`v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application`).

## Run them

```bash
./gradlew -p v2 :pipeline-application:installDist   # once, or let run.sh do it
examples/run.sh                                     # run all examples (asserts expected outcomes)
examples/run.sh 03-shell.pipeline.kts               # run one
```

`run.sh` checks each example's exit code AND terminal outcome
(`success` / `failure` / `unstable`), plus event-level contracts for the
semantic examples (07–10). A green run is executable acceptance evidence.

## Examples

| Example | What it shows |
|---|---|
| `01-hello.pipeline.kts` | Minimal pipeline: one stage, one `echo` |
| `02-multi-stage.pipeline.kts` | Multiple stages in declaration order |
| `03-shell.pipeline.kts` | Real OS processes via `sh` (incl. a shell `for` loop) |
| `04-kotlin-control-flow.pipeline.kts` | Real Kotlin control flow (`script {}` blocks) |
| `05-failing-step.pipeline.kts` | Typed failure: `sh` exits 3 → `StepFailed(kind=SCRIPT)`, run outcome `failure` |
| `06-durable.pipeline.kts` | Durable execution with `--db`: journal, fingerprints, crash resume |
| `07-catch-error.pipeline.kts` | Nested `catchError`: two `CatchErrorTriggered` events (inner FAILURE → outer UNSTABLE), pipeline continues |
| `08-parallel.pipeline.kts` | Two concurrent branches; second run with the same `--db` reuses the terminal aggregate (zero branch/step events) |
| `09-retry.pipeline.kts` | `retry`: first attempt fails, second succeeds (marker-file deterministic) |
| `10-timeout.pipeline.kts` | `timeout` deadline aborts an over-running `sh`; run outcome `failure` |

## Durable execution demo

```bash
BIN=v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application
$BIN run --db /tmp/demo-journal.db examples/06-durable.pipeline.kts   # first run executes
# kill it mid-run, then:
$BIN run --db /tmp/demo-journal.db examples/06-durable.pipeline.kts   # resume
```

With `--db`, every operation is journaled in SQLite with an input fingerprint.
On resume of an interrupted run, completed non-effectful steps replay from the
journal and effectful `sh` steps re-execute (recoverable policy). A completed
run is not memoized across independent runs by design; the one cross-run reuse
guarantee today is the parallel terminal aggregate (see `08`).

## CLI contract

```
pipeline validate <script>                       # validate, emit events to stdout
pipeline run [--db <path>] [--resume] <script>   # durable run with SQLite journal
```

## Status (honest)

What these examples exercise **works today**, each proven by executing the
real CLI: linear pipelines, stages, real `sh` processes with structured
failures, Kotlin `script {}` control flow, durable journaling with `--db`
including crash resume, nested `catchError`, `parallel` with durable rerun
reuse, `retry`, and `timeout` deadlines.

Known limitations (tracked in `docs/debt/`):

- Corpus fixtures `06-loop.pipeline.kts`, `08-withEnv-pipeline.pipeline.kts`,
  `09-archive-artefacts.pipeline.kts` fail to compile due to a DSL surface
  issue (missing `isScriptBlock` parameter on `StageScope.sh()`). Deferred to
  INC-021c.
- `--resume` output is a merged stream (prior journal replay + new events with
  original timestamps). Deferred to INC-021d.
- `warnError` and `withCredentials` are not demonstrated here yet.

## Event-contract acceptance (EVT)

`examples/` is executable product documentation: a supported example is accepted only when its
expected execution outcome and observable event contract both pass through the installed CLI
distribution. `run.sh` already asserts this for 07–10 (see above).

Companion normalized contracts live in `examples/contracts/*.events.yaml` (candidate schemas, EVT-07/EVT-08):
they normalize the already-proven P4-EX laws so the Event Harness can differential-test against the
`run.sh` oracle (POST_RUN by default; live verification not required). Until the harness lands,
`run.sh` is the executable contract authority.
