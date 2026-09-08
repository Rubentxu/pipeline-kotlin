# UAT gate gaps — diagnosis (2026-09-08)

Evidence-driven classification of the pre-existing `:pipeline-application` module
failures. These are **product gaps surfaced by fail-closed rejections / semantic
divergence**, not slowness or sandbox failures. All confirmed pre-existing at
committed base `62917d83` (git stash comparison); none introduced by EM-7/P4+P5.

## Repro method

```
BIN=v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application
$BIN run v2/pipeline-application/build/resources/test/<fixture>.pipeline.kts
```
Each failing case prints a fail-closed `IllegalStateException` (compiler) or a
typed event divergence (coordinator). Working fixtures: `error-abort`, `sleep-timing`,
`multi-step`, `hello`, `echo-capture`, `sh-exec`.

## Gaps

### G1 — `error` inside workflow-control is not projected (blocks grammar-full, timeout-retry)
- Repro: `grammar-full.pipeline.kts`, `timeout-retry.pipeline.kts`.
- Failure: `DslCompiledPipelineCompiler.buildShellScript(...:506): buildShellScript
  cannot embed structured step 'error' into a workflow-control shell wrapper; the
  pre-compiler rewrite must project it. Refusing to emit a silent shell comment.`
- Effect: many DSL-semantics UATs (`UatDsl001`, `UatDsl005`) fail.
- Decision needed: how to project structured `error` (typed shell `exit`? typed node
  in the linear rewrite?) consistent with STEP SEMANTICS and ADR-0069. This is a
  compiler feature, needs design + exit criterion.

### G2 — parallel stage with sibling steps (blocks parallel fixture)
- Repro: `parallel.pipeline.kts`.
- Failure: `DslCompiledPipelineCompiler.stageNode(...:106): Stage 'ParallelTest'
  cannot mix a parallel body with sibling steps`.
- Triage: is the fixture invalid Jenkins declarative (parallel must be the whole
  stage body) or is the restriction over-strict? Record decision; fix fixture or
  compiler accordingly.

### G3 — step-name divergence (blocks ErrorHandlingTest ERR-S-001..008)
- Repro: `ErrorHandlingTest` ERR-S-001.
- Failure: coordinator emits indexed step names (`[... echo-0]`) but the DSL
  contract/test expects `echo`; real-process path and canonical coordinator diverge.
- Effect: catchError/ordering assertions fail.
- Needs: confirm whether stepName should be the DSL-declared step id (Jenkins parity)
  in the canonical coordinator.

## Cross-cutting enabler

All three are slow and environment-sensitive to iterate on because the affected UATs
spawn installed binaries / `java -cp` with up to 300s timeouts. SPIKE-017 proposes an
in-process `PipelineRule` harness to reproduce and fix them fast/deterministically.

## Owner / traceability
- SPIKE-017: harness proof (proposed).
- G1/G2/G3: each needs a Milestone/Backlog item with exit criterion + Gate before
  implementation (per AGENTS v2 prime directive).
