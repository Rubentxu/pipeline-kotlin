# Tasks: canonical-gate-gap-closure (G1 + G3)

Evidence: docs/v2/06-quality/UAT_GATE_GAPS_DIAGNOSIS.md. Repro via installed binary
+ PipelineRule harness (SPIKE-017 passed). Base: `35fc1f86`.

> Implementation MUST follow a design/ADR decision first (per AGENTS v2: Milestone→Backlog→
> exit criterion). These tasks assume the two design decisions are made. G2 is separate.

## Design decisions (2026-09-08, evidence-grounded)

### D1 — structured `error` projection form
Code evidence: `projectInnerScope` (DslCompiledPipelineCompiler ~380-451) only treats
`CatchError`/`WarnError` as structured; the single-shell fast path
(`!hasStructuredChild`, ~381) calls `buildShellScript` which rejects any non
Shell/Echo/WriteFile (else-throw ~506). So an `error` nested under a workflow-control
(no sibling catchError/warnError) reaches `buildShellScript` and fails closed.
Decision: treat `StepSpec.Error` as structured in `projectInnerScope`, projecting it to
the same typed abort form the top-level `error` path produces (to be traced: how
`stepNode` compiles top-level `StepSpec.Error` to a FAILURE — open sub-investigation D1a).
Embedding remains fail-closed; never a silent comment. Optionally extend
`buildShellScript` to map `StepSpec.Error` to an aborting `exit` with a code the
decoder maps to the typed FailureKind, matching top-level `error`.

### D2 — step-name parity contract
Code evidence: the compiler names steps `<type>-<index>` (DslCompiledPipelineCompilerTest
asserts `build/echo-0`); the canonical coordinator emits stage-qualified
`<stage>/<type>-<index>` (PipelineRuleParityTest), while the durable real-process path
emitted unqualified `echo-0` (ErrorHandling subprocess) — two divergent derivations.
Decision: unify on stage-qualified `<stage>/<type>-<index>` as the stable display name
for auto-named DSL steps; keep a distinct immutable per-step operation identity for
call-site determinism (ADR-0066) so this is a display-only alignment. Reconcile
`ErrorHandlingTest` expectations to the agreed contract (assert last step is the echo
step's stable identity, e.g. ends-with `echo-0` / contains `echo`), verified via
PipelineRule + real-process parity.

### G1 apply precondition (D1a)
Trace top-level `error` compilation to FAILURE before implementing nested projection so
both paths use the same typed failure protocol.

> These decisions are recorded pending a design/ADR review; production mutation of the
> compiler (G1) and coordinator (G3) is the apply step that follows.

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
