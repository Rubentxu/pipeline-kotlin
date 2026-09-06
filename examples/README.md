# pipeline-kotlin examples

Real, runnable pipelines executed by the V2 CLI binary
(`v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application`).

## Run them

```bash
./gradlew -p v2 :pipeline-application:installDist   # once, or let run.sh do it
examples/run.sh                                     # run all examples
examples/run.sh 03-shell.pipeline.kts               # run one
```

## Examples

| Example | What it shows |
|---|---|
| `01-hello.pipeline.kts` | Minimal pipeline: one stage, one `echo` |
| `02-multi-stage.pipeline.kts` | Multiple stages in declaration order |
| `03-shell.pipeline.kts` | Real OS processes via `sh` (incl. a shell `for` loop) |
| `04-kotlin-control-flow.pipeline.kts` | Real Kotlin control flow (`script {}` blocks) |
| `05-failing-step.pipeline.kts` | Typed failure: `sh` exits 3 → `StepFailed(kind=SCRIPT)`, CLI exits non-zero |
| `06-durable.pipeline.kts` | Durable execution with `--db`: journal, fingerprints, replay gating |

## Durable execution demo

```bash
BIN=v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application
$BIN run --db /tmp/demo-journal.db examples/06-durable.pipeline.kts   # first run executes
$BIN run --db /tmp/demo-journal.db examples/06-durable.pipeline.kts   # re-run replays journaled effects
```

With `--db`, every operation is journaled in SQLite with an input fingerprint;
a re-run skips already-completed effects instead of launching them twice.

## CLI contract

```
pipeline validate <script>                       # validate, emit events to stdout
pipeline run [--db <path>] [--resume] <script>   # durable run with SQLite journal
```

## Status (honest)

What these examples exercise **works today**: linear pipelines, stages, real
`sh` processes with structured failures, Kotlin `script {}` control flow, and
journaling with `--db`.

Known CLI limitations discovered while authoring these examples
(see `docs/debt/INC-021-cli-compile-error-success.md`):

- A script with a Kotlin **compilation error** reports `SUCCESS` with exit 0
  and executes zero steps (INC-021, high). Escape shell `$` as `\$` in
  regular strings.
- `validate` compiles raw Kotlin without the pre-compiler rewrite, so it can
  reject scripts that `run` fine (INC-021a).
- `--db` does not skip on re-run by itself and `--resume` output is a merged
  stream (INC-021b). Memoized same-run replay is proven at coordinator level
  and in SPIKE-016, not yet as one-command CLI resume.

Not yet implemented (tracked in `docs/v2/05-roadmap/`): timeout deadlines
(EM-5), `retry`/`catchError`/`warnError` semantics (EM-6), `withCredentials`
completion.
