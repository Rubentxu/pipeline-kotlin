# XCA-2A — Runtime Execution Evidence Reader (approved directive)

**Start boundary:** `659b59c0`, branch `cycle/wu-g5b`, clean tree, stack review CLOSED, GO.

Preconditions already demonstrated:

```text
fake_impossible_audit_assertions   0
active_textual_ledger_consumers    0
legacy_residual                    0
no merge commits introduced by this cycle
no new tags
XCA-2A not yet implemented
```

Do not reopen XCA-0/XCA-1 unless a falsifiable test reveals a real contradiction.

## Objective

The minimum runtime vertical that observes which `StepKey`s a REAL installed-CLI
execution actually executed.

```text
installed distribution -> real CLI invocation -> RunId
  -> canonical OperationJournal abstraction
  -> RunExecutionEvidenceReader
  -> List<ExecutedInvocationEvidence>
  -> observed StepKeys
```

XCA-2A STOPS THERE. Explicitly not yet:

```text
E n O / E - O / O - E      classification
ledger mutation            CERTIFIED changes
coverage verdict changes   automatic remediation
XCA-2B canaries            XCA-3 cleanup
```

## LAW-001 (reinforced) — single consumption path

```text
upper XCA/certification layers
  -> RunExecutionEvidenceReader
  -> canonical OperationJournal abstraction
  -> journal implementation
```

Forbidden from any XCA layer:

```text
SQLite adapter | raw SQL | journal tables/schema
retry journal internals | waitUntil journal internals
event log as execution authority
```

Events remain observability. The durable journal remains the execution authority.

## Two design decisions to preserve (both are subtle false-green traps)

1. **Return INVOCATIONS, not a `Set<StepKey>`.** Projection
   `List<ExecutedInvocationEvidence>.map { it.stepKey }` happens in the upper layer.
   Preserves identity and replay information.
2. **An executed-but-FAILED Step counts as observed.** The criterion must capture
   EXECUTION, not SUCCESS. Otherwise XCA-2B is born with "executed = finished well",
   which is another subtle false green.

## Slices

```text
2A.0  architecture anti-bypass RED + fitness (falsify deliberately, keep the RED evidence)
2A.1  evidence ADTs + reader contract (reuse canonical RunId/InvocationId/StepKey if present;
      no parallel String representations; no classification/certification/UI fields)
2A.2  RunExecutionEvidenceReader port (deterministic; canonical durable execution order;
      does not read step-certification.yaml, examples, or events; no re-execution; no writes)
2A.3  adapter over the EXISTING OperationJournal abstraction
      (no XcaJournal / second table / second schema / parallel SQL reader;
       if the port is insufficient, characterise the gap with RED first and extend the
       canonical port minimally and generically -- e.g. "read persisted invocation records
       for RunId", never "readStepKeysForCertificationAudit()")
2A.4  define what constitutes "executed": characterise existing journal semantics first.
      Distinguish declared / scheduled / started / completed / failed / replayed / skipped.
      No heuristics like "has output" or "terminal success" -- a FAILED Step still executed.
2A.5  replay law: a resumed/replayed durable invocation with stable identity yields ONE
      evidence record. Two legitimate invocation IDs of the same StepKey yield TWO records.
2A.6  installed-distribution acceptance: real distribution, real CLI, real RunId, real
      pipeline (small, deterministic, >= 2 distinct StepKeys, e.g. echo + sh).
      The test MUST NOT inspect the certification YAML.
2A.7  negatives: unknown RunId fails closed with a typed result (do not equate empty list
      with unknown run); declared-but-unexecuted is NOT observed; failed Step IS observed;
      replay does not duplicate a logical invocation.
2A.8  architecture fitness: upper layers cannot reach the journal backend; reader cannot
      depend on SQLite impl, YAML ledger, event persistence, or retry/waitUntil journals;
      evidence ADTs contain no certification concepts. Check real structure, not comment text.
2A.9  receipt with reproducible facts (RED falsification, GREEN, CLI command, fixture,
      RunId, journal authority used, evidence returned, failed-step evidence, replay
      evidence, unknown-run behaviour, test counts, working-tree state, commit SHA).
      Do NOT claim coverage/certification complete.
```

## Stop conditions (all simultaneously true before XCA-2B)

```text
anti_bypass_violations                    0
direct_backend_consumers_from_XCA         0
event_authority_violations                0
parallel_evidence_stores                  0

installed_CLI_to_RunId                    PASS
RunId_to_canonical_journal                PASS
journal_to_evidence_reader                PASS
reader_to_invocation_evidence             PASS
failed_step_observed                      PASS
declared_but_unexecuted_not_observed      PASS
replay_no_duplicate_logical_invocation    PASS
unknown_run_fail_closed                   PASS

NO E n O implementation | NO classification | NO ledger mutation
NO certification-state mutation | NO XCA-3 cleanup
```

## Autonomy

Local reversible commits are authorized. Human approval required for: additional push,
tags/releases, history rewrite/destruction, material scope change, genuine contradiction
between approved requirements, irreversible external actions.

## The final rule

The correct outcome of XCA-2A is NOT "we know which Steps should be covered". It is only:

> Given the RunId of a real execution performed with the installed distribution, a single
> canonical durable path returns the Step invocations actually executed, without consulting
> certification, examples, or events as authority.

Only after that sentence is demonstrated does XCA-2B open.
