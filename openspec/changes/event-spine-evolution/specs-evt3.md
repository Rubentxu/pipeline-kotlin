# EVT-3 Delta Spec — Event Harness (typed protocol contracts over local history)

## R1 — Contract ADT (closed)
- `EventContract { version: Int, name, expect: ExpectedOutcome, constraints: List<EventConstraint> }`.
- `EventConstraint` sealed: `Exactly(selector, count)`, `Never(selector)`,
  `Before(first, second, scope)`, `TerminalOutcome(expected)`.
- `ExpectedOutcome`: PASSED | FAILED (AcceptanceOutcome); distinct from PipelineOutcome.
- Unknown constraint type or version → typed decode failure with diagnostic (fail-closed).

## R2 — Selectors (typed, structured)
- `EventSelector { kind: String, where: List<FieldMatch> }`.
- `FieldMatch` closed ADT: `CatchBuildResult(String)`, `RetryOutcome(String)`,
  `AttemptNumber(Int)`, `BranchIndex(Int)`, `MessageContains(String)`,
  `Outcome(String)`, `StageIndex(Int)`, `StepIndex(Int)`.
- Matching resolves typed payload fields of the decoded DomainEvent; no `Map<String,Any>`,
  no query language, no dynamic expressions.

## R3 — VerificationResult ADT
- `sealed VerificationResult { Valid; Invalid(violations: List<EventViolation>) }`.
- `EventViolation { constraint index, kind, message, relevantTrace: List<SequenceWindow> }` with
  bounded counterexample window (violated selectors + nearest related events + small context).
- No exceptions for contract failure; exceptions only for corrupt contract / codec / internal invariant.

## R4 — Universal protocol grammar (laws, partial order)
- RunFinished requires prior RunStarted (same run).
- StageFinished(S) requires prior StageStarted(S).
- ParallelBranchFinished(B) requires prior ParallelBranchStarted(B).
- RetryAttemptFinished(A) requires prior RetryAttemptStarted(A).
- One execution attempt cannot have contradictory terminal lifecycle outcomes
  (e.g. StepFinished success and StepFailed for same step attempt).
- Parent structural completion cannot precede required child terminal events
  (StageFinished after all its branch Finished).
- NO global order is imposed; parallel verified via happens-before per typed key
  (branchKey = parentStageIndex:branchIndex). Both interleavings of two branches are Valid.

## R5 — YAML codec (serialization only, versioned)
- `version: 1` header; `expect.runOutcome`; `constraints:` list forms `exactly`, `never`,
  `before` (with optional `same`/scope keys). Unknown fields/constraint forms rejected
  fail-closed with diagnostic. YAML is deserialization of the ADT; never interpreted/executed.
- Existing sidecars 07–10 updated to the v1 schema as golden fixtures.

## R6 — Input = persisted history (post-run only)
- Verifier input: ordered `List<PipelineEventEnvelope>` (whole run, or sub-history delimited
  by sequence/cursor — never timestamps). The harness NEVER mutates run state, journal,
  outcomes, or emits DomainEvents. No AcceptanceOutcome persisted as DomainEvent in EVT-3.

## R7 — Differential parity
- For real executions 07, 08, 09, 10: legacy P4-EX verdict == harness verdict.
- Coexistence until parity proven on 2 consecutive real runs; only then run.sh may delegate.

## R8 — Determinism / idempotence
- verify(sameHistory, sameContract) == same result, across processes and adapters
  (InMemory vs SQLite parity for semantically equal envelopes).

## R9 — Anti-false-green (mutation)
- Deterministic pure transformations of captured real histories: missing Started,
  duplicate terminal, inverted parent/child order, ResourceRef corruption, fabricated
  StepStarted in replay trace → each yields Invalid with bounded counterexample.

## R10 — CLI (minimal, post-run)
- `pipeline events verify --db <path> --run <runId> --contract <yaml>` → prints
  VerificationReport JSON + exit code 0 (PASSED) / 1 (FAILED). Verify persisted history
  without re-executing the pipeline.
