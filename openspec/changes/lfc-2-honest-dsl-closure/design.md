# Design: LFC-2 honest closure recovery

## Context Reuse Check

Native OpenSpec work at `7c9ce5c790389e73cf8c8c099ada52e62646e9f5`, no CLI cycle, publication or implementation authorized. Requested framework 1.120.0 is absent; read installed `framework/current` (1.121.0) design authority. Launch-plan fields are not supplied. Scope and invariants come from the user, AGENTS.md, LFC2 roadmap and E-EM-11. Status: PARTIAL, not a closure receipt.

## Technical Approach

Restore truthful admission before implementing missing capabilities. Seven disabled UAT methods are seven unproved acceptance obligations, not green evidence. Details and executed probes belong in `evidence-2026-09-08.md`.

## First implementable slice: LFC2-H01

**Trace:** LFC-2 → backlog LFC2-H01 → no unsupported admitted IR can launch effects → compiler/coordinator admission tests plus real CLI negative probes. This is bounded containment, not E-EM-11 completion.

| Decision | Choice and rationale |
|---|---|
| Admission | Remove `core.retry`/`core.timeout` from promoted body capabilities until semantics land. Reject stage retry options. Stage timeout options are a distinct implemented surface and remain supported. |
| Traversal | Exhaustively match `StageBody`, reject whole-stage parallel rather than `continue`. Traverse all stages and nested blocks before any dispatch. The current cast skips parallel during analysis, then fails only during execution. |
| Compiler | Reject unsupported retry/timeout and stage retry options before producing executable IR. Do not encode `{}` or route to legacy fallback. Preserve typed diagnostic through existing compiler/CLI failure boundary. |
| Defense in depth | Coordinator entry checks the entire compiled plan before `RunStarted`/effects, including callers bypassing Main. Main's existing gate must remain on both ephemeral and durable paths. |
| Scope | No new IR, ports, journal schema, events, plugins or disabled tests. Keep payload serialization and runtime promotion for the corresponding EM slices. |

### File Changes (planned, not applied)

| File under `v2/pipeline-application/src/` | Action |
|---|---|
| `main/kotlin/dev/rubentxu/pipeline/v2/application/DslCompiledPipelineCompiler.kt` | Explicit supported-shape validation before projection. |
| `main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt` | Total admission, recursive shape checks and entry guard. |
| `test/kotlin/dev/rubentxu/pipeline/v2/application/DslCompiledPipelineCompilerTest.kt` | Negative contracts for unsupported blocks/options. |
| `test/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinatorTest.kt` | Later/nested unsupported node prevents earlier shell effect, including direct caller. |
| `test/kotlin/dev/rubentxu/pipeline/v2/application/Lfc2AdmissionCliTest.kt` | Proposed new bounded CLI acceptance class with `@Timeout`. |

## Data Flow / Interfaces

DSL → compiler supported-shape validation → typed compiled IR → whole-plan canonical admission → dispatcher. Adapters depend on existing domain ADTs. Expected rejection must use a closed typed result at public seams, not boolean-plus-null or `Any?`. Diagnostic variant names are to follow the current compile result algebra.

## Testing Strategy

For H01: `timeout 600 ./gradlew -p v2 :pipeline-application:compileTestKotlin`, then exact newly added compiler/coordinator methods (`--tests`), then owning classes with `--fail-fast`. Proposed CLI cases: unsupported block, nested block, parallel stage after marker-writing stage, retry options, each with and without `--db`. Assert nonzero exit, explicit unsupported diagnostic, no marker and no dispatched step events. Positive controls: hello, dir and stage-timeout. Do not run full check in this diagnostic phase. Later final gate uses recorded derived budget.

## Migration / Rollout

H01 intentionally converts false success into explicit rejection. It does not rewrite journals. E-EM-11 promotion restores only independently proven capabilities. Never label quarantine as completion.

## Architecture Model

Impact: local for H01. Evidence: actual compiler and coordinator source at the pinned SHA. No dependency change, manifest or render required. Later composable execution/identity work is boundary impact and separately design-gated.

## Open Questions / ADR Candidates

- Retry needs attempt-path identity for nested retry, persisted outcomes and 1-based event numbering, not simply a loop around memoized children.
- Timeout needs persisted block deadline, minimum enclosing deadline, cancellation and restart reconciliation, not a per-child reset.
- Parallel: recommend composable canonical node to honor existing sibling fixtures. Stage-terminal-only cannot close those criteria unchanged. IR/journal compatibility trade-off requires ADR.
- `pwd`/`isUnix` and condition closures need a runtime-value boundary. Injecting host config does not make build-time values executor values.
