# Proposal: Canonical gate-gap closure — structured-step projection (G1) + step-name parity (G3)

## Intent
Pre-existing, evidence-confirmed (UAT_GATE_GAPS_DIAGNOSIS) product gaps block the
`:pipeline-application` DSL-semantics UAT gate and are expensive to iterate on without
the new in-process `PipelineRule` harness (SPIKE-017, passed). This change closes the
two tractable runtime/compiler gaps: G1 (`error` inside workflow-control is rejected,
not projected) and G3 (coordinator step-name divergence from the DSL contract, and
between the two execution paths). G2 (parallel+siblings) is out of scope pending triage.

## Scope
### In Scope
- G1: project a structured `error` nested in a workflow-control shell wrapper so the
  fail-closed `IllegalStateException` becomes a typed, Jenkins-faithful failure — unblocks
  `grammar-full` and `timeout-retry` fixtures. Must NOT emit a silent shell comment.
- G3: reconcile step names across the canonical coordinator and the real-process path so
  `StepFinished`/`StepStarted` stepName matches the DSL-declared identity where one exists.
- Regression coverage via `PipelineRule` (fast in-process) + existing compiler tests.
### Out of Scope
- G2 parallel+siblings: triage fixture-vs-compiler validity in a separate item.
- Durable replay/kill semantics.

## Capabilities
### New Capabilities
- `structured-step-projection`: fail-closed projection of structured steps (e.g. `error`,
  later `unstable`/`warnError`) nested under workflow-control into typed, Jenkins-faithful
  execution instead of a compiler rejection.
- `canonical-step-identity`: deterministic, Jenkins-parity step naming consistent across
  the canonical coordinator and the real-process path.
### Modified Capabilities
- None.

## Approach
- G1: in `DslCompiledPipelineCompiler`'s `projectInnerScope`/`buildShellScript`, when a
  structured step like `error` is nested under a workflow-control wrapper, project it to the
  typed linear form (e.g. a shell that exits with a typed failure OR a typed node the
  coordinator dispatches) rather than throwing. Preserve fail-closed intent (never a silent
  comment). Decide exact form during design; keep STEP SEMANTICS + ADR-0069.
- G3: decide the naming contract (DSL step id when declared; `<type>-<index>` otherwise)
  and make both the direct coordinator and the durable/real-process path emit it identically.
  Fix stale `ErrorHandlingTest` expectations to the agreed contract.

## Affected Areas
| Area | Impact | Description |
|------|--------|-------------|
| `v2/.../application/DslCompiledPipelineCompiler.kt` | Modified | G1 projection of structured steps in workflow-control |
| `v2/.../application/durable/CanonicalDurableRunCoordinator.kt` | Modified | G3 step-name emission |
| `ErrorHandlingTest` + DSL fixture UATs | Modified | expectations aligned to agreed contract |
| PipelineRule tests | New | fast regression coverage |

## Risks
| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Changing `error` semantics subtly breaks abort behavior | Med | Keep fail-closed; assert abort still FAILURE via fixtures |
| Naming change breaks call-site identity/determinism (ADR-0066) | Med | Preserve stable per-step identity; change only display name |
| Fixture expectations churn | Med | Use PipelineRule to re-verify fast |

## Rollback Plan
Compiler + coordinator are production; revert the specific projection/naming hunks. New
regression tests isolate behavior. The PipelineRule harness makes re-verification fast.

## Dependencies
- SPIKE-017 / `PipelineRule` harness (passed) for fast coverage.
- Design decisions: G1 projection form; G3 naming contract (ADR).

## Success Criteria
- [ ] `grammar-full` and `timeout-retry` fixtures compile+run (no fail-closed reject), error
      still aborts with a typed FAILURE (G1).
- [ ] A DSL step with a declared id emits that id as stepName on BOTH coordinator and
      real-process path; `ErrorHandlingTest` green (G3).
- [ ] All via `PipelineRule` in <5s each; module gate green for the affected families.
