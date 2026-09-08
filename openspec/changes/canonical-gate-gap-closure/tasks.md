# Tasks: canonical-gate-gap-closure (G1 + G3)

Evidence: docs/v2/06-quality/UAT_GATE_GAPS_DIAGNOSIS.md. Repro via installed binary
+ PipelineRule harness (SPIKE-017 passed). Base: `35fc1f86`.

> Implementation MUST follow a design/ADR decision first (per AGENTS v2: Milestone→Backlog→
> exit criterion). These tasks assume the two design decisions are made. G2 is separate.

## G1 — structured-step (`error`) projection in workflow-control
- D1: decide projection form (typed shell `exit` carrying a typed failure vs a typed node
  the coordinator dispatches); ADR entry. Keep fail-closed, never a silent comment.
- T-G1-1: modify `DslCompiledPipelineCompiler.projectInnerScope`/`buildShellScript` so a
  structured `error` nested under timeout/retry projects instead of throwing.
- T-G1-2: regression: `grammar-full` and `timeout-retry` fixtures compile+run; `error`
  still yields a typed FAILURE (not silently swallowed).
- Gate: L1/L2 via PipelineRule + compiler test; run the two fixtures in-process.

## G3 — step-name parity (canonical coordinator + real-process path)
- D2: decide naming contract (DSL-declared step id when present, else `<type>-<index>`),
  consistent across both paths; reconcile with ADR-0066 call-site identity.
- T-G3-1: align the coordinator/real-process stepName emission to the agreed contract.
- T-G3-2: fix stale `ErrorHandlingTest` expectations to the agreed contract; port ERR-S-001
  to PipelineRule; assert last step == declared identity.
- Gate: ErrorHandling ERR-S-001..008 green via PipelineRule + real-process parity.

## Harness dependency (already passing)
- `PipelineRule` (SPIKE-017) covers fast re-verification; reuse for G1/G3 regression.

## Full gate
- Only the affected DSL-semantics families + coordinator suites (module-wide env failures
  for unrelated suites are documented and out of this change).
