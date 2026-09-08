# Proposal: LFC-2 — honest Jenkins-like DSL closure

> Status correction at `7c9ce5c7` (2026-09-08): OPEN. Seven disabled UAT methods are not PASS.
> The historical ground-truth claims below are not fresh base-vs-head evidence for this round.
> `design.md` and `evidence-2026-09-08.md` describe recovery without narrowing the original gate.

## Intent
Close LFC-2 ("Honest Jenkins-like DSL: familiar DSL with no fake runtime values") as a tracked
milestone with an itemized list and an explicit exit gate. LFC-2 was worked via the M2/ML/EM lines
but never formalized or gate-closed. Its residual gaps sit on the DSL surface and are demonstrably
pre-existing (fail on the clean committed Part B). This change scopes, triages and closes them.

## Ground truth (fresh runs, 2026-09-08, HEAD 65f75fcc)
Confirmed pre-existing DSL failures (also red on clean Part B, no local catchError change involved):
- `UatDsl001JenkinsFamiliarityTest` ×3-4: full-grammar script "CLI exited with 1" / empty stdout
  (a real compile failure of the full grammar), and the mutating-fixture timeline test.
- `UatEvt001ReplayTest`: G3 step-naming — a timeline test still expects `echo` while the canonical
  coordinator emits `<stage>/<type>-<index>` (e.g. `hello/echo-0`); not reconciled to the G3
  contract the coordinator/durable path already follows.
- `UatDsl003ParallelTest` (G2): `parallel` + sibling steps rejected by the DSL compiler — the honest
  DSL surface for `parallel` is not open, even though the durable parallel engine is closed (M3-R4.4).
- `ErrorHandlingTest ERR-S-004` (folded from EM-5/6): stage observability — canonical coordinator
  emits no `StageStarted`/`StageFinished`, so a stage-level `unstable()` has no `StageFinished`.

## Scope
### In Scope
- Triage + itemize LFC-2 into tracked work units with one exit gate.
- Fix the confirmed DSL gaps above (parallel G2 compiler surface, UatDsl001 full-grammar compile,
  UatEvt001 G3-naming reconciliation, ERR-S-004 stage bookends rebaseline on the DSL/corpus surface).
- Audit the LFC-2 gate list: `@DslMarker` narrow receivers, closed `StageBody`, formal
  `.pipeline.kts` `@KotlinScript`, incomplete steps (`post`, `when`, `waitUntil`, `pwd`, `isUnix`,
  ...), git/scmGit duplicate construction, shell dollar handling, durable `script {}` boundary.
- Formalize the LFC-2 item list + exit gate (representative Jenkins fixtures compile to expected IR;
  no fake-return DSL fitness violation) in the roadmap docs.
### Out of Scope
- Plugin API (LFC-3), durable execution spine rework (EM track), release (LFC-9), ecosystem (LFC-8).

## Approach
Treat LFC-2 as a bounded vertical-slice closure over the DSL surface. Order: (1) triage the confirmed
pre-existing red DSL tests to root cause and itemize; (2) fix parallel DSL surface (G2); (3) reconcile
step-naming contract (G3, UatEvt001); (4) land stage bookends + rebaseline the DSL/corpus event
timelines (ERR-S-004); (5) close the remaining LFC-2 gate items (steps, shell dollar, script
boundary); (6) record the LFC-2 item list + gate + closure in `docs/v2/05-roadmap`.

## Affected Areas
| Area | Impact |
|------|--------|
| DSL surface (`parallel`, incomplete steps, naming) | Modified |
| DslCompiledPipelineCompiler / compiler rejects | Modified |
| Canonical coordinator stage bookends | Modified (ERR-S-004 folded) |
| DSL/event-timeline/corpus tests | Evolved to the honest contract |
| `docs/v2/05-roadmap` LFC-2 item list + gate | New (formalized) |

## Risks
| Risk | Mitigation |
|------|------------|
| Enormous residual red surface | Triage first; scope by confirmed root cause; do NOT attempt unrelated pre-existing classes |
| Tests currently asserting fake/legacy behavior | Evolve per the "tests evolve with legitimate changes" rule; keep no-fake-return gate |
| Entanglement with EM spine | Keep LFC-2 to the DSL surface + observability fold only |

## Dependencies
- Clean Part B baseline (65f75fcc) as the comparison point for pre-existing failures.
- The EM-5/6 catchError milestone (closed) so this does not overlap it.

## Success Criteria
- [x] LFC-2 itemized list + exit gate recorded in the roadmap (`LFC2_HONEST_DSL_CLOSURE.md` + backlog).
- [ ] Confirmed DSL gaps green (stage bookends/G3 focused evidence exists; seven DSL UATs remain disabled).
- [x] Legacy-surface parallel/retry/timeout UATs quarantined and traceable to E-EM-11 (not DSL-fake).
- [ ] Gate: representative Jenkins fixtures compile to expected IR; no fake-return DSL fitness violation
      (honest linear subset green; DSL debt rows tracked as follow-ups, no silent placeholder on canonical path).
- [x] No unjustified regression in coordinator/EM suites.
- [ ] Full-grammar + parallel UATs re-open and green on canonical path once E-EM-11 lands.
