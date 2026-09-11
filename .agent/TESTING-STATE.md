

## Active Change — RETRY-D durable retry reconciliation (2026-09-10, HEAD `e4cca233`)

**Status: DESIGN GATE / NO PRODUCTION EDITS.** The public installed-distribution retry acceptance previously reproduced a real defect: `fail → success → rerun` with the same DB/control state appended a second `retry-ok`. Timeout and parallel installed UATs remain green, but E-EM-11 must remain OPEN.

**Grounded SUT:** `CanonicalDurableRunCoordinator.dispatchBody` derives deterministic child identities using `parentBodyPath + BlockSegment(attempt, "retry-attempt") + child segment`, then calls canonical `dispatch()`. It has no retry-control journal fact and starts at attempt 1 on each fresh coordinator invocation. `OperationJournal` has `beginOperation`, `append`, and exact `get(opId, attempt)`, but no transaction or fault seam.

**Design artifacts, uncommitted:** `openspec/changes/retry-d-durable-reconciliation/{proposal,design,tasks}.md` and proposed `docs/v2/04-adrs/ADR-0075-retry-control-durable-reconciliation.md`. They require a retry control row keyed by the full inherited canonical OpId plus journal attempt ordinal, a pure reconciliation ADT, canonical child re-entry only, and an R5 test-only `OperationJournal` decorator that throws after delegating the exact child terminal append. No production fault port and no events as durable authority.

**Verification executed:** docs-only `git diff --check` PASS. No Gradle run was relevant because production/test sources are unchanged.

**Next after explicit design/ADR approval:** implement only the typed retry reconciliation, then progressively run R1/R3-R6 focused tests and the real `installDist` R2 acceptance. Do not add RetryAttemptFinished/TimeoutScheduled, change timeout/parallel, reopen grammar UATs, alter `StepExecutors.kt`, or commit before R2, R5, and R6 are green.

## CTX-P4-EX handoff (2026-09-10)
- examples/run.sh = real-CLI gate: expected exit+outcome matrix, event contracts 07-10.
  Full gate GREEN (exit 0), commit d0ccf4b5.
- CLI: flags MUST precede script path (`run --db X script`); trailing flags silently
  ignored — strictness is an OPEN item.
- Durable rerun: default ReusePriorRun; --rerun fresh; --resume continues. CLI reprints
  prior journal with ORIGINAL timestamps → scope new events by occurredAt > max(prev run).
- P6 parallel test note: UatDsl003ParallelTest P6 passes via the same CLI (verified fresh XML).

## Event Spine integration handoff (2026-09-10)
- Branch docs/event-spine-evolution-integration (f3bf32e1 + 3c12ec19), pusheada; NO mergeada a main.
- EVT/POL integrados en ROADMAP.md (tras EM, antes de M5), backlog EVT-00..16/POL-00..09,
  MILESTONES replacement, ADR-0077..0080 PROPOSED, design docs 06-design, UAT_EVT_REAL_EXAMPLES,
  examples/contracts YAML (proposales), openspec changes event-spine-evolution + policy-guardrails.
- AGENTS_CANDIDATE NO fusionado (merge guide paso 6: solo tras receipts EVT/POL).
- Solo EVT-0 autorizado a arrancar en primer ciclo de código.
- Pendiente: revisión ADR-0077..0079, merge a main tras aprobación.

## Corrección pack EVT (2026-09-10, 5adfe0cf)
- Reemplazado pack por revisión corregida grounded en d0ccf4b5 (P4-EX closed).
- Cambio clave: 07-10 son baseline CLOSED; EVT-08 = migración con paridad
  diferencial old(run.sh)/new(harness); ley de migración EVT-3 en
  docs/v2/00-context/EVT_P4_EX_BASELINE.md. No recrear examples nunca.

## EVT-0 ciclo SDDK cerrado (2026-09-10)
- sddk cycle evt-0-grounding (B-direct): CLOSED, ledger seq 851, todos los gates PASSED.
- Branch docs/evt-0-grounding @ cfd83619 pusheada (EVT/POL integration + EVT-0 receipt).
- Prioridad acordada con usuario: EVT-0..3 P0; luego LFC-2 ∥ EVT-4; M4 reactivado después;
  POL shadow P2; CloudEvents SDK/NATS deferred (EVT-5).
- Próximo ciclo: EVT-1 (ResourceRef + PipelineEventEnvelope) — mismo patrón sddk.

## EVT-0 cierre en trunk (2026-09-10, 1f39d17d)
- main == origin/main == 1f39d17d (FF de docs/evt-0-grounding @22f199c0 + disposition).
- Pack staging: retenido solo como provenance, README apunta a docs/v2 (disposition note).
- Cycle evt-0-grounding CLOSED; branches merged conservadas (convención del repo).
- SIGUIENTE: EVT-1 ResourceRef+Envelope, slice behavior-preserving, leyes 1-12 del usuario.

## EVT-1 identity slice (2026-09-10, HEAD 5a50319c)
- Added v2/pipeline-events identity pkg: ResourceKind/ResourceRef/ResourceRefs/EventRef/
  PipelineEventEnvelope/EnvelopeProjector + EnvelopeCodec + CloudEvents characterization.
  Tests: ResourceRefDeterminismTest (9) + EnvelopeProjectionTest (9) = 18 GREEN; module
  :pipeline-events:test 124/124 GREEN. P4-EX oracle (examples/run.sh) 10/10 exit 0.
- CRITICAL oracle gotcha: run.sh durable examples share scratch/durable-shell/ op-state
  across runs; deleting only the *.db leaves stale retry op dirs -> coordinator skips
  steps silently and contract 09 fails. Clean `durable-shell/` dir + /tmp/pipeline-retry-done
  before oracle runs. NOT a code regression (contamination reproduced on pre-branch binary).
- Known launcher note: TMPDIR here = ~/.jcode/scratch so oracle scratch lives at
  $TMPDIR/pipeline-examples.

## EVT-1 CLOSED (2026-09-10, trunk 4ca1dfed)
- Cycle evt-1-resource-ref-envelope CLOSED (SDDK seq 864, all gates PASSED).
- Identity ownership FINAL: ResourceKind/ResourceRef/ResourceRefs in pipeline-domain
  (dev.rubentxu.pipeline.v2.domain.identity); EventRef/Envelope/Projector in pipeline-events.
- Module gates: pipeline-domain 360 tests, pipeline-events 117 tests, all green.
- P4-EX oracle now hermetic per-run (run.sh --control-root mktemp); INC-EVT-H1 filed
  as BASELINE harness debt. Contract-09 flake = stale durable-shell state, never EVT-1.

## EVT-3 CLOSED + LFC-2E integrated on trunk (2026-09-11)

### EVT-3 closure cycle (cycle evt-3-event-harness)
- Branch: docs/evt-3-event-harness, base 1b950074, HEAD df22ff01 + closure commit d0aedb5d
- Receipt: docs/v2/07-uat/EVT_3_CLOSURE_RECEIPT.md (210 lines)
- L0 compile GREEN (38 tasks UP-TO-DATE)
- L1-L2 :pipeline-event-harness:test --rerun-tasks = 19/19 GREEN (HF0 9/9 + mutation 10/10)
- L3 examples/run.sh x2 consecutive = 10/10 + 4/4 parity each run
- L4 FArch020EventHarnessIsolationTest = 4/4 GREEN
- L4 CanonicalEmitEventNodeDispatcherTest = 5/5 GREEN
- L5 ./gradlew -p v2 check = same 8 pre-existing failures as base SHA, 0 regressions
- Rule-16: introduced failures = 0
- FF-merge to main: main == origin/main == d0aedb5d
- EVT-3 status in EVENT_SPINE_EVOLUTION.md: CLOSED @ df22ff01
- EVT-4 status: PENDING-DEFERRED-BY-LOCAL-FIRST-PRIORITY
- EVT-5 status: PENDING-DEFERRED (no transport selected)

### LFC-2E documentation integration cycle
- Source: PR #22 / branch origin/docs/lfc2-step-ecosystem-expansion @ 2bf7bb5d (7 doc commits)
- Integration branch: docs/lfc2-step-ecosystem-expansion-integration
- 7 PR commits rebased cleanly on d0aedb5d (only 1 conflict on EVENT_SPINE_EVOLUTION.md)
- 2 conflict zones resolved semantically:
  - Status line: keep EVT-3 CLOSED SHA from EVT-3, keep Placement text from LFC-2E
  - EVT-4 status: combine PENDING label (EVT-3) + start condition (LFC-2E)
- 1 ROADMAP.md amendment commit (a14e5e6f): EVT section now shows closure status + priority chain
- FF-merge to main: main == origin/main == a14e5e6f
- 8 required files preserved (per user law #4):
  - docs/v2/01-product/STEP_ECOSYSTEM_MATRIX.md
  - docs/v2/03-specifications/STEP_ECOSYSTEM_POLICY.md
  - docs/v2/03-specifications/STEP_PLUGIN_SDK.md
  - docs/v2/05-roadmap/EVENT_SPINE_EVOLUTION.md (reconciled)
  - docs/v2/05-roadmap/LFC2_HONEST_DSL_CLOSURE.md
  - docs/v2/05-roadmap/LFC2_STEP_ECOSYSTEM_EXPANSION.md
  - docs/v2/05-roadmap/ROADMAP.md (amended)
  - openspec/changes/lfc2-step-constitution-plugin-seam/tasks.md

### Next cycle: LFC-2E0 — certify existing families
- Step inventory must be MACHINE-DERIVED, not matrix-hypothesis
- Required fields per Step: delivery, DSL present?, StepDefinition present?,
  canonical execution?, legacy executable path?, typed input?, typed output?,
  capabilities?, replay policy?, real example?, Event Harness contract?, certification state?
- STEP_ECOSYSTEM_MATRIX.md is the hypothesis to verify, not the evidence
- Priority order E0..E10 (E0 first: certify existing; E1: universal core freeze)
- Do NOT open EVT-4 during these cycles unless explicit reprioritization
