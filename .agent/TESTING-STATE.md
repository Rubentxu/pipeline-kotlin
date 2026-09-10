

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
