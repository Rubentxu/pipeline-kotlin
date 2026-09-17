# XCA-2B — first real runtime vertical (installed CLI -> RunId -> journal)

**Base:** `21941bec`
**Status:** the authority chain is proven at the DATA level. The Kotlin reader has not yet
been exercised against this run; that is the immediate next step.

## The run

```text
CLI   v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application
         run --db <tmp>/run.db --control-root <tmp>/ctl <tmp>/min.pipeline.kts
exit  0
fixture:
  pipeline { stages {
    stage("one") { echo("hello from xca2b") }
    stage("two") { sh("echo second-step") } } }
```

Isolated workspace: unique temp dir, unique `--db`, unique `--control-root`.

```text
real RunId   006df865-a1d4-4bcf-ac8c-895c0864ea60
```

## What the durable authority actually recorded

`operation_journal` — 2 rows, exactly the two Steps:

```text
op_id                                      status      input.stepId   attempt
006df865-...-s0-0                          SUCCEEDED   core.echo      1
006df865-...-s1-0                          SUCCEEDED   core.sh        1
```

Columns present: `op_id, fingerprint, status, kind, attempt, input, output,
started_at, ended_at, created_at, updated_at, deadline_ms, run_id`.

## Contract confirmations (empirical, not assumed)

1. **A3 confirmed end-to-end.** The persisted `input` JSON carries `stepId` at the top
   level (`{"stepId":"core.echo","params":{...},"runId":"...","attempt":1}`), so
   `DurableOperation.input.stepId` really is the StepKey on real data — no decoding, no
   fingerprint parsing, no join.
2. **The reader mapping is exact**: `op_id -> OperationId`, `input.stepId -> PluginStepId`,
   `status -> OperationStatus`. Real statuses observed: `SUCCEEDED`.
3. **Ordering**: rows are addressable by `created_at` ascending. Observed `created_at`
   1789667553773 then 1789667553801, i.e. canonical execution order.
4. **Events are a SEPARATE store.** The `events` table holds 14 rows for the same run
   (`CompilationStarted`, `RunStarted`, `StageStarted`, `StepStarted`, `StepFinished`,
   `RunFinished`, ...). This empirically confirms the law that events are observability and
   NOT execution authority: the evidence for "what ran" comes from `operation_journal`,
   which contains no event rows and `events` contains no operation rows.
5. **`replay_cursor` is a third, separate store** (`run_id, last_op_id, stage_index`) —
   orchestration bookkeeping, not evidence.

## What this does NOT yet prove

```text
- the Kotlin reader has not been run against this journal (no test calls read(runId))
- only SUCCEEDED observed so far; RUNNING / FAILED / PENDING / LOST not yet produced
- RunNotFound: a non-existent RunId was not attempted here
- execute-once-per-fixture and the shared-fixture canary are not yet exercised
```

## Next (in order)

```text
B.2  exercise JournalRunExecutionEvidenceReader against a real journal
     -> assert observed stepKeys == {core.echo, core.sh}
B.3  negative statuses: a FAILED step must still be observed
B.4  execute-once-per-fixture + shared-fixture canary (12-error-handling)
B.5  STOPPED_G7 canary (20-pwd-tmp)
B.6  CLI success with a missing expected StepKey -> must NOT pass
C    E/O reconciliation        D  evidence schema migration
E    full corpus -> XCA-2 gate
```

## Note on method

The inspection above used raw SQLite reads via Python's stdlib **out of band**, purely to
verify that the reader's INPUT exists and has the expected shape. That is analyst
verification, not the production path: LAW-001 still forbids any XCA/certification layer
from reaching the journal backend directly. Production evidence must flow only through
`RunExecutionEvidenceReader` -> `OperationJournal`.
