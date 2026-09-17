# CAMPAIGN-XCA — autonomous mandate (XCA-2 -> XCA-3 -> P3.0.1)

**Start:** branch `cycle/wu-g5b`, published HEAD `642c6170`, clean, in-sync, no merges, no tags.

## Authorization

Work AUTONOMOUSLY through:

```text
XCA-2A -> XCA-2B -> XCA-2C -> XCA-2D -> XCA-2-GATE -> XCA-3 -> P3.0.1
```

Do NOT stop between slices when the decision is local, reversible, respects ADRs/laws, is
resolvable by tests, involves no push/tag/release/history-destruction, and does not
materially change the product. Normal local commits are authorized. Do not stop merely to
report that a phase ended: commit, verify, continue. Commits are checkpoints, NOT stop
conditions.

## Only valid reasons to stop

```text
1 real contradiction between approved ADRs/requirements
2 need to materially change scope
3 irreversible external action
4 secrets/credentials
5 unauthorized push/tag/release
6 need to delete certified work
7 demonstrated technical impossibility of preserving a fundamental law
```

Implementation alternative, naming, package, ADT, internal signature, test organisation or
small refactor is NOT a reason to stop. Resolve by evidence.

## Known starting facts (verified)

```text
OperationJournal already exposes  fun listForRun(runId: String): List<DurableOperation>
  documented ordered by created_at ASC  == canonical durable execution order
  => NO additional durable port unless the DurableOperation -> StepKey path fails
observed candidate:  OperationStatus != PENDING   (PENDING MUST be characterized first)
control journals (retry/waitUntil) are NOT authority for Step execution
```

## Workstream A — Runtime Evidence Core (owner: reader, evidence ADTs, LAW-001 tests)

```text
A1 anti-bypass RED first: upper XCA layers may not reach SQLite, SQL/schema, concrete
   journal adapter, retry/waitUntil journals, or event persistence. Sole path:
   XCA -> RunExecutionEvidenceReader -> canonical OperationJournal. Falsify before GREEN.
A2 characterize PENDING: locate every write/transition of all OperationStatus values and
   let real behaviour decide the law, not the KDoc. An invocation counts as executed if it
   crossed the execution boundary (RUNNING/SUCCEEDED/FAILED/ABORTED/DIVERGENT/LOST are
   candidates). Never confuse execution with success.
A3 StepKey gap: do NOT assume DurableOperation.id is the StepKey. Investigate existing
   fields, fingerprint/encoded input, canonical invocation identity, persisted metadata,
   P2 seams. If derivable canonically, reuse. If NOT derivable without parsing private or
   fragile payloads: raise a characterizing RED, then extend minimally and generically as
   execution-domain metadata. Never readStepKeysForCertification(). No second table,
   journal, SQL parser or parallel schema.
A4 ADTs: ExecutedInvocationEvidence { invocationId, stepKey, status }; reuse existing
   RunId/InvocationId/StepKey types; no parallel Strings; keep per-invocation info; do NOT
   reduce internally to Set<StepKey>.
A5 reader: thin adapter over listForRun. Distinguish RunFound(empty) / RunFound(evidence)
   / RunNotFound if the durable model permits. Never treat an unknown run as an empty one
   without demonstrating it.
```

## Workstream B — Real CLI Harness (owner: runner, temp env, RunId capture)

```text
B1 all runtime evidence from the INSTALLED distribution, never in-process Gradle as final
   proof. Each run: unique temp dir, unique DB, unique control-root, unique RunId.
B2 execute ONCE per FIXTURE, never per surface. fixture run -> observed evidence ->
   observed StepKeys -> N expectations. Freeze with a test.
B3 canary: 12-error-handling.pipeline.kts runs once and satisfies all associated
   expectations individually. Four runs for four surfaces = FAIL.
B4 canary: 20-pwd-tmp.pipeline.kts (core.pwd / core.pwd.tmp STOPPED_G7) must not satisfy
   any CERTIFIED expectation. Do not rationalise after.
B5 falsification: CLI exits SUCCESS but the expected StepKey never crosses the execution
   boundary -> evidence missing. Exit code never certifies a surface.
```

## Workstream C — Evidence Reconciliation (starts once A and B produce evidence)

```text
C1 mathematics first: satisfied = E∩O, missing = E-O, extra = O-E. No opaque procedural
   classifier.
C2 separate the two axes: fixture execution state (FixtureFailed / NoCanonicalEvidence /
   CanonicalEvidence) VS expectation relation (EXPECTED_AND_EXECUTED /
   EXPECTED_BUT_NOT_EXECUTED / EXECUTED_SUPPORTING / EXECUTED_UNKNOWN). Never one enum.
C3 mandatory falsifications: (1) swap an expected StepKey for another valid one ->
   EXPECTED_BUT_NOT_EXECUTED; (2) Step under an impossible branch -> static says EXERCISED,
   runtime says not observed, gate FAILs; (3) a stale journal must not be able to credit
   the current run (isolated RunId/workspace); (4) an omitted fixture must surface as
   missing execution evidence.
```

## Workstream D — Evidence Schema Migration (owner: step-certification.yaml + typed model)

```text
D1 retire real_fixtures; migrate to structured evidence keeping three dimensions separate:
   SOURCE / EXPECTATION / VERIFICATION, e.g. source{kind,path}, expectation{kind,step_key},
   verification{mode,status}.
D2 ABSOLUTE: schema migration != runtime proof. Every migrated claim starts as
   STATIC_CANDIDATE. Only real execution may promote to EXECUTED.
D3 provenance kinds: PRODUCT_EXAMPLE, REGRESSION_CORPUS, STRUCTURAL_CONTRACT.
   REGRESSION_CORPUS keeps governance = ADR-0050. DO NOT move its files.
```

## Workstream E — XCA-2 full run

Run every UNIQUE fixture once. Do NOT hardcode 30; derive from the authority. Expected
conceptually N=30 executable + M=1 structural, but gates must derive it. Never change tests
to reach those numbers; if reality differs, investigate.

## XCA-2 closure gates

```text
claimed-but-not-executed 0        unknown evidence provenance 0
missing fixture paths 0           runner-unregistered maintained fixture 0
stale journal evidence 0          direct journal backend bypass 0
event authority violations 0      control-journal authority violations 0
```

Receipt must include: installed CLI version/hash, unique fixture count, unique runs,
RunIds, expectations, observed keys, missing/extra sets, replay checks, canary results,
test counts, HEAD, working tree.

## XCA-3 — permanent laws (continue WITHOUT stopping if XCA-2 closes green)

Do not reimplement the runner. Freeze laws as Kotlin architecture/fitness tests:

```text
ledger_standard_yaml_parse                 PASS
ledger_typed_schema_parse                  PASS
active_textual_ledger_consumers            0
duplicate_manual_certification_authorities 0
unknown_evidence_provenance                0
static_only_certified_execution_evidence   0
ADR_0050_corpus_pins                       PASS
execution_evidence_complete                PASS
direct_backend_bypass                      0
event_authority_violations                 0
```

`xca1a_fix_ledger_claims.py` must be deleted, archived clearly outside active paths, or
made non-executable, so `textual_consumers_of_certification_authority = 0` is literally
true. Do not hide it via arbitrary fitness exclusion.

## P3.0.1 — continue automatically if XCA-3 closes fully green

Strengthen the generic plugin-event carrier law BEFORE P3.1:

```text
plugin_domain_event_cases_in_core   0
generic_plugin_carriers_in_core     1   // deliberate RED until P3.1
plugin_specific_serialization       0
plugin_specific_persistence         0
```

P3.0.1 may end with exactly the deliberate RED for the not-yet-existing carrier. Do NOT
implement P3.1 in this campaign without a further explicit authorization.

## Parallelization

Separate worktrees/agents recommended. Lanes: A runtime core, B harness, C certification
model (may design + write migration RED tests but must NOT write `EXECUTED` until A+B
produce evidence), D fitness/governance. ONE lane owns each shared file: OperationJournal*,
step-certification.yaml, the CLI runner, central architecture fitness files. Integrate in
the logical order: A core evidence -> B installed execution -> C reconciliation/schema ->
E full corpus -> D/XCA-3 closure.

## Campaign final gate

May stop successfully only at:

```text
XCA-2 runtime evidence   CLOSED
XCA-3 permanent fitness  CLOSED
P3.0.1 strengthened RED  READY
working tree clean | local commits coherent | no merges introduced | no tags created
```

NO PUSH without authorization.

## Operating principle

When in doubt between "stop and ask" and "run a reversible investigation/RED and let the
evidence decide", choose the second. The campaign exists so architecture, tests and ADRs
resolve ordinary decisions without constant human intervention.
