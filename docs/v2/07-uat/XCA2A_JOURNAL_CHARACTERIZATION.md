# XCA-2A — Journal characterization (resolves 2A.2 / 2A.3 / 2A.4)

**Base:** `4e368a94`
**Method:** inspection of the existing canonical durable port and domain types. No code written.

## 2A.2 / 2A.3 — the port ALREADY provides what is needed

`dev.rubentxu.pipeline.v2.events.durable.OperationJournal`:

```kotlin
fun listForRun(runId: String): List<DurableOperation>
/**
 * Lists all journaled operations for a given [runId], ordered by [created_at] ascending.
 * @return A [List] of [DurableOperation] in execution order.
 */
```

This is exactly the 2A.3 "acceptable extension" — *read persisted invocation records for
RunId* — and it already exists, already ordered by the canonical durable execution order
that 2A.2 requires.

**Consequence: NO port extension is required, and no RED for a port gap is needed.**
2A.3 reduces to a thin adapter delegating to `listForRun`. No `XcaJournal`,
no second table, no second schema, no parallel SQL reader.

## 2A.4 — what constitutes "executed"

`DurableOperation` carries:

```kotlin
abstract val id: String          // invocation identity
abstract val fingerprint: Fingerprint
abstract val input: OperationInput
abstract val output: OperationOutput?
abstract val status: OperationStatus
abstract val attempt: Int        // monotonic attempt number within the run
abstract val replayPolicy: ReplayPolicy
```

`OperationStatus` is the execution-boundary vocabulary:

```text
PENDING     created, execution has NOT started
RUNNING     currently executing            <- crossed the boundary
SUCCEEDED   completed successfully
FAILED      executed but failed (non-zero exit)
ABORTED     deliberately aborted by policy
DIVERGENT   result diverged from journaled fingerprint
LOST        worker/process lost mid-execution
```

### The criterion

```text
observed  <=>  status != PENDING
```

That is: an invocation counts as executed once it has left the pre-execution state.

This is deliberately **not** success-based. `RUNNING` and `LOST` are states that prove
execution STARTED without any terminal success, so a Step interrupted mid-execution still
counts as observed. A terminal-success criterion would silently drop exactly those cases,
which is the under-reporting hole the directive warned about.

`FAILED` counts as observed, as required.

### Rejected heuristics (explicitly)

```text
"has output"        -> false negative for RUNNING / LOST / FAILED
"terminal success"  -> false negative for FAILED / ABORTED
"has event"         -> events are observability, not authority
```

### Declared-but-unexecuted

A structurally declared Step with no journal row, or a row still `PENDING`, is NOT
observed. `PENDING` is the precise representation of "declared/scheduled but never
entered execution".

### Replay

`listForRun` returns operations for the run; `attempt` distinguishes retries of the same
operation and `id` is the logical invocation identity. The reader must therefore return
INVOCATIONS keyed by logical identity, so a resume/replay does not multiply observations,
while two genuinely distinct invocation ids of the same StepKey both appear.

`attempt` is available for future semantics but XCA-2A does not need to interpret it.

## Effect on the slices

```text
2A.1  ExecutedInvocationEvidence { invocationId = DurableOperation.id, stepKey, status }
      Reuse existing RunId/StepKey/InvocationId types where present; no parallel Strings.
2A.2  port as specified; ordering = listForRun's created_at ascending (canonical)
2A.3  thin adapter delegating to OperationJournal.listForRun -- NO port extension
2A.4  observed <=> status != PENDING  (characterized above, with rationale)
```

Nothing here is implemented yet; this is the characterization the directive requires
BEFORE codifying `observed`.
