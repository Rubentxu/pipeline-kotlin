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

---

# A3 RESOLVED — the StepKey is already first-class

`DurableOperation.input: OperationInput`, and:

```kotlin
data class OperationInput(
    val stepId: String,          // <- the StepKey, required and validated
    val params: Map<String, JsonElement>,
    val runId: String,
    val attempt: Int,
) {
    init {
        require(stepId.isNotBlank()) { "stepId must not be blank" }
        require(runId.isNotBlank())  { "runId must not be blank" }
        require(attempt >= 1)        { "attempt must be >= 1, got $attempt" }
    }
}
```

**No port extension. No fingerprint decoding. No fragile payload parsing.** The reader is
a thin adapter:

```text
listForRun(runId) -> DurableOperation
  invocationId = operation.id
  stepKey      = operation.input.stepId      (validated non-blank, first-class)
  status       = operation.status
```

This closes the last place where "no additional durable port" could have been overturned.
A3 needs no RED, no extension, and no new table/schema.

# A2 PARTIALLY RESOLVED — PENDING is dual-use; the distinction must be explicit

`PENDING` appears in TWO different roles, and conflating them would be a defect:

```text
1. PERSISTED status: the coordinator/dev runtime constructs operations as PENDING
     CanonicalDurableRunCoordinator.kt:964   status = OperationStatus.PENDING
     JournaledScriptedOperationRuntime.kt:94
     ScriptedRegistryInvoker.kt:157

2. SYNTHETIC "no row" projection: PENDING is used as a MISSING-RECORD sentinel
     ProductionRetryChildRowReader.kt:67     durable == null -> PENDING to null
     RetryReconciler.kt:122                  filterNot { fingerprint == null && status == PENDING }
     RetryReconciler.kt:220-226              "These represent 'no OperationJournal row'"
```

Consequences for the criterion `observed <=> status != PENDING`:

```text
case 1 (persisted PENDING)  -> not yet entered execution -> NOT observed   (correct)
case 2 (no row)             -> nothing was ever executed -> NOT observed   (correct)
```

So the criterion yields the right answer in both cases, which is reassuring. BUT the reader
must not rely on the sentinel implicitly: it derives observations from `listForRun`, where
case 2 simply does not appear at all (there is no row to return). The sentinel only exists
in retry/waitUntil PROJECTION paths, which are orchestration state and explicitly not
execution authority.

Therefore:

```text
the reader's observed set is built from ROWS RETURNED BY listForRun
  -> a persisted PENDING row is excluded by the criterion
  -> a missing row is absent by construction
the synthetic PENDING sentinel is never an input to the reader
```

Still required before freezing the law (A2 completion): confirm that no recovery path
journals `PENDING` AFTER an operation has entered execution. If such a path exists, the
criterion would under-report silently. This is a characterization test, not a blocker.
