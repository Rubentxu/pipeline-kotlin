# XCA-2A — Execution observer design (pre-implementation)

**Base:** `89fe0dd2`
**Status:** design recorded; implementation not started.

## Grounding fact that shrinks the slice

`JournalStepOutputResolver` (built in P2) already depends on a domain port, not SQLite:

```kotlin
class JournalStepOutputResolver(
    private val published: Map<StepOutputKey, StepOutputPublication>,
    private val journal: OperationJournal,          // dev.rubentxu.pipeline.v2.events.durable
    ...
)
```

**So no port extraction is required.** The durable abstraction already exists. XCA-2 needs a
**second use case over the same authority**, not a second reader:

```text
              OperationJournal  (existing domain port)
                      │
          ┌───────────┴───────────┐
          ▼                       ▼
JournalStepOutputResolver    RunExecutionEvidenceReader
        P2                          XCA-2
```

One canonical interpretation of the journal. Not necessarily one class for all use cases.

Explicitly forbidden: `sqlite3 SELECT`, a bespoke table/schema parser, or any second
interpretation of durable records. That would recreate exactly the defect removed from the
certification side by XCA-YAML.

## LAW — evidence source

```text
certification_execution_evidence MUST come from the operation journal
certification_execution_evidence MUST NOT be inferred from control journals
```

The two durable surfaces are not interchangeable:

```text
Operation journal (SQLite, via OperationJournal port)
    execution facts / Step identity          <- the only authority for XCA-2

Control journals (FileBasedRetryControlJournal, FileBasedWaitUntilControlJournal)
    retry / waitUntil ORCHESTRATION STATE    <- never evidence of "which StepKey executed"
```

## LAW — CLI success is not evidence

```text
CLI success != certification evidence
failing to observe an expected StepKey MUST yield EXPECTED_BUT_NOT_EXECUTED
```

Probably the single most important rule in XCA-2.

## Result algebra — nested ADTs, never one flat enum

The six concepts mix two different levels. A single enum would permit conceptually absurd
states:

```kotlin
sealed interface FixtureEvidenceResult {
    data class FixtureFailed(val exitCode: Int) : FixtureEvidenceResult
    data object NoCanonicalEvidence : FixtureEvidenceResult
    data class CanonicalEvidence(
        val expected: Set<PluginStepId>,
        val observed: List<ExecutedInvocationEvidence>,
    ) : FixtureEvidenceResult
}
```

Fixture-level outcomes are decided first; only a `CanonicalEvidence` can relate
expected to observed.

## Classification is PURE and SET-DERIVED

```text
E = expected StepKeys
O = observed StepKeys (projected from evidence)

satisfied = E ∩ O   -> EXPECTED_AND_EXECUTED
missing   = E − O   -> EXPECTED_BUT_NOT_EXECUTED
extra     = O − E   -> EXECUTED_SUPPORTING  if known supporting
                        EXECUTED_UNKNOWN     otherwise
```

Only `extra` requires a supporting/unknown distinction. Verdicts are mathematically derived
from two sets, with no procedural classification logic — which makes silently absorbing a
class (the counter defect) structurally harder.

## Raw evidence vs derived projection

Do NOT collapse the observer output to `Set<StepKey>`. Retain at least:

```text
RunId, InvocationId, StepKey, terminal state/outcome
```

```text
raw canonical evidence   List<ExecutedInvocationEvidence>   <- evidence
derived certification view  Set<StepKey>                     <- projection
```

Rationale: later semantics (a StepKey present but never reaching terminal execution; retry
producing multiple invocation identities) must remain answerable. If the reader discards
this, XCA will have to return to the journal.

## Canaries — registered BEFORE the observer, as acceptance invariants

### Canary 1 — STOPPED_G7 cannot close a certification

```text
20-pwd-tmp.pipeline.kts targets core.pwd / core.pwd.tmp (STOPPED_G7)
MUST NOT satisfy any CERTIFIED expectation.
If it does: FAIL observer semantics. Not rationalised afterwards.
```

### Canary 2 — shared fixture, one execution, N expectations

```text
12-error-handling.pipeline.kts is claimed by 4 surfaces.
execution_count MUST == 1, with 4 independent expectations evaluated against the
observed set from that single run. Not "run the fixture once per surface".
```

This freezes XCA-2B before it is implemented.

### Canary 3 — success with no observed StepKeys

```text
fixture exits SUCCESS but the journal contains none of its expected StepKeys
MUST yield EXPECTED_BUT_NOT_EXECUTED, never PASS
```

## XCA-2A exit criteria (without running all 30 candidates)

```text
1  reads the operation journal through the canonical durable port
2  never reads control journals for Step execution evidence
3  can enumerate invocation/StepKey evidence for one run
4  classifier is pure and set-derived
5  fixture failure != missing evidence
6  successful CLI with a missing expected StepKey FAILS
7  STOPPED_G7 canary cannot satisfy a CERTIFIED expectation
8  shared-fixture model supports N expectations from ONE execution
```

## Mandatory falsification tests

```text
F1  change a ledger expected_step_key to another VALID StepKey
    -> EXPECTED_BUT_NOT_EXECUTED        (source scan would still look fine)

F2  put a Step call under an unreachable branch (e.g. if (false) { writeYaml(...) })
    -> static audit says EXERCISED
    -> runtime observer says EXPECTED_BUT_NOT_EXECUTED
```

F2 is the proof that XCA-2 adds information rather than re-deriving XCA-0.

## Sequence

```text
XCA-2A  observer                       <- design frozen here
XCA-2B  execute once per fixture, compare N expectations
XCA-2C  regression corpus treated identically (provenance, no privilege)
XCA-2D  migrate real_fixtures -> structured evidence; every claim STATIC_CANDIDATE
```

XCA-2D must NOT let the migration produce green: `path migrated => EXECUTED` is forbidden.
Only XCA-2 execution produces `EXECUTED`.

---

# XCA-LAW-001 — evidence-source constraint (mechanically checkable)

Permanent, ratcheted by XCA-3. Stated once, enforced, never re-litigated:

> **Execution evidence comes exclusively from the OperationJournal. Control journals may
> explain control-flow, never demonstrate that a Step executed.**

## Scope

```text
RunExecutionEvidenceReader
the execution-evidence package / module
```

## Required dependency (type-level, not textual)

```text
RunExecutionEvidenceReader MUST depend on OperationJournal
```

## Forbidden imports / references

```text
FileBasedRetryControlJournal
FileBasedWaitUntilControlJournal
RetryControlJournal
WaitUntilControlJournal
```

## Forbidden implementation knowledge

```text
sqlite3
JDBC SELECT
SELECT ... operation_journal
bespoke DB schema decoding
```

## Why a grep is NOT enough

A textual scan would miss the real defect, which is a *type dependency*: a reader that
compiles against `OperationJournal` but is wired with a concrete persistence adapter, or
that reaches durable state through the control journals, reintroduces a second truth while
still containing no forbidden token. The gate must therefore assert the **dependency
direction**:

```text
RunExecutionEvidenceReader
  MUST depend on   OperationJournal
  MUST NOT depend  concrete journal persistence adapters
                   retry / waitUntil control journals
                   SQLite / JDBC
```

## Fourth cheap falsification (added)

```text
unexpected StepKey in O - E
  -> may yield SUPPORTING / extra evidence ONLY from real extra evidence

missing must NEVER produce SUPPORTING
```

i.e. an absent observation cannot be dressed as a supportive one.

## Pipeline separation inside the observer

Four stages, NOT fused into `RunExecutionEvidenceReader`:

```text
raw journal evidence
      -> normalized execution evidence   (RunExecutionEvidence: runId, invocations,
                                          observedStepKeys, operationEvidence, provenance)
      -> pure set comparison             (executed = E n O; missing = E - O; unexpected = O - E)
      -> classification                  (FixtureEvidenceResult)
```

`RunExecutionEvidenceReader` returns **observed evidence**, and must NOT decide PASS/FAIL.

## XCA-2A Definition of Done

```text
installed distribution real
CLI real
RunId real
reader over the existing OperationJournal
zero SQLite knowledge
zero control-journal evidence
Canary 1 / 2 / 3
F1 / F2 (+ the fourth cheap falsification above)
architectural forbidden-dependency gate (XCA-LAW-001)
```

## Stop rule

XCA-2B does NOT start until this single chain is demonstrated:

```text
CLI run -> RunId -> OperationJournal -> correct observed StepKeys
```

If it fails, fix the OBSERVER. Never adjust the classification to make fixtures pass.

---

# XCA-LAW-001 — closing the upper-layer bypass

The constraint above governs `RunExecutionEvidenceReader`'s own dependencies. That is
necessary but **not sufficient**: it can hold locally while the system still has a second
path to the same evidence, because a layer ABOVE the reader may consult the concrete
backend directly and never go through it. That is the same failure shape as the duplicate
certification authority removed at `95188c44`, one layer over.

## Additional requirement

```text
The RunExecutionEvidenceReader is the ONLY path to execution evidence.

No layer above it may:
  consult the operation journal backend directly
  consult a concrete journal persistence adapter directly
  derive "a Step executed" from control journals, events, logs, or CLI exit status
```

## Enforcement, not prose

Stated negatively and mechanically, the gate must fail if:

```text
1. any module outside the evidence-reader package imports the concrete journal
   persistence adapter
2. any module outside the evidence-reader package builds its own SQLite/JDBC query
   against operation-journal tables
3. any code derives execution evidence from control journals, event streams, console
   logs, or process exit codes
```

(3) is the one a dependency-only check would miss entirely, and it is precisely the
`CLI success != certification evidence` law restated at the system level rather than the
function level.

## Consequence for the DoD

Add to the XCA-2A DoD:

```text
execution-evidence funnel proven unique:
  every observed StepKey used for certification traces to the reader
  no second path exists in the same run's evidence assembly
```

Without this, LAW-001 is satisfiable in isolation while remaining violated globally —
which is exactly the class of defect that the XCA detour has been eliminating.
