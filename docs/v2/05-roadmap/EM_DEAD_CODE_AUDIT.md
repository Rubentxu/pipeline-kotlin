# EM Dead-Code / Deprecation Audit

> **Cycle:** `p-733fb505b5a6bd2d/em-0-execution-model-contract-freeze` (requirement R5)
> **Date:** 2026-09-06 · **Base SHA:** `0ad4be3` + uncommitted EM tree
> **Mandate:** product-owner decision 2026-09-06 — trace dead/transitional
> lines introduced or superseded by the execution-model work; classify each
> as delete / deprecate / keep-adapter.
> **Deletion rule:** per EXECUTION_MODEL_MIGRATION EM-10, transitional code is
> deleted only after its replacement proves stable; this audit freezes the
> inventory and the trigger conditions.

## Inventory

| # | Symbol / path | Location | Classification | Migration target | Trigger |
|---|---|---|---|---|---|
| A1 | `ShExecution.runShellCommand(...): String` legacy projection | `v2/pipeline-application/.../durable/ShExecution.kt` | **DEPRECATED — retained** | `ShExecution.invokeShell(...)` (typed, same class) | EM-10; no active production caller remains. Retain only as the explicitly deprecated outer compatibility adapter until compatibility fixtures migrate. |
| A2 | `PipelineRun.toPipelineLifecycleOutcome(...)` lifecycle interpretation | `v2/pipeline-application/.../PipelineRun.kt` | keep — product-live lifecycle seam | closed `ShellInvocationResult` from `ShExecution` | EM-10 vertical slice complete: normal, parallel branch, and branch `withEnv` paths exhaustively interpret the ADT. This is not a deprecated shell API and does not deprecate `PipelineRun`. |
| A3 | `PipelineDsl.pwd()` fake placeholder (returns `"<workspace>"`) | `pipeline-scripting-api/.../PipelineDsl.kt:1438` | deprecate-at-EM-8 | `ScriptedScope` runtime-returning `pwd()` (EM-8) | EM-8 exit criteria; L9 UAT coverage still anchors current behavior |
| A4 | `PipelineDsl.isUnix()` fake placeholder (returns `true` unconditionally) | `PipelineDsl.kt:1453` | deprecate-at-EM-8 | `ScriptedScope` runtime-returning `isUnix()` (EM-8) | EM-8 exit criteria; note: placeholder is actively wrong on Windows |
| A5 | `ScriptScope.commands` shell-text accumulator + `line()` | `PipelineDsl.kt:1725` | deprecate-at-EM-6 | real block/body execution (EM-4/EM-6); `rewriteWorkflowControl` removal | EM-6 |
| A6 | `DslCompiledPipelineCompiler.rewriteWorkflowControl` (CatchError/WarnError → shell text) | `application/DslCompiledPipelineCompiler.kt:131,140,175` | deprecate-at-EM-6 | real catchError/warnError (EM-6); `StepSpec.CatchError/WarnError` already `@Deprecated` (LFC1-007) | EM-6 |
| A7 | `BlockStepFlattener` deprecated-block rewrites | `pipeline-step-sdk/api/.../BlockStepFlattener.kt:122,128,182` | deprecate-at-EM-4 | `BlockStepNode` first-class body execution (EM-4) | EM-4 |
| A8 | `OperationStatus.FAILED_TIMEOUT` runtime semantics | `domain/durable/OperationStatus.kt:8-29` | keep-adapter | canonical `INTERRUPTED` per ADR-0068 (draft, R3); watchdog deadline path keeps reporting state per ADR-0047 | ADR-0068 acceptance + EM-5 |
| A9 | `DurableShellState`, `DurableShellResult`, `DurableShellExecutor.execute(...)`, `executeDurableShell`, and terminal adapters | `pipeline-step-sdk/runtime/.../durable/DurableShellExecutor.kt`, `DurableTaskTerminalAdapter.kt` | **DEPRECATED — retained** | `DurableShellExecutor.executeTerminal(...)` returning `DurableTaskTerminal` | EM-10; `PipelineRun` now consumes `ShellInvocationResult` on normal and branch paths. `executeDurableShell` has no application production caller and remains only as an externally compatible projection. |
| A10 | `StepSpec.CatchError` / `WarnError` / `Unstable` (already `@Deprecated`) | `PipelineDsl.kt:1130,1131,1378,1407,1427` | keep (deprecated, legacy fixture deserialization only) | canonical IR marker events (LFC1-007) | EM-10 after corpus v-next |
| A11 | scripted runtime not wired to production entry | `application/scripted/*` (zero references from `Main.kt`/`PipelineRun.kt`) | NOT dead — deliberate EM-8 staging | production `script {}` cut-over (EM-8) | EM-8 |

## Immediate actions taken this cycle

- **A1/A2**: `runShStep` and `executeBranchStep` now return
  `ShellInvocationResult`; `PipelineRun` exhaustively maps it at its
  product-live lifecycle seam for normal, parallel-branch, and branch
  `withEnv` execution. `runShellCommand` is the sole deprecated String
  compatibility adapter and has no active production caller.
- **A9**: durable-shell execution now has one terminal-producing core.
  `DurableShellExecutor.executeTerminal(...)` is the canonical production
  interface; `DurableShellExecutor.execute(...)`, `executeDurableShell`, and
  legacy result/state adapters are deprecated projections. No active
  `PipelineRun` path reaches a legacy SDK result or String shell projection.

## Deferred actions (traceable, not forgotten)

- A3/A4 annotations land together with EM-8 runtime implementations so the
  migration target exists before the warning appears (no naked deprecation).
- A5/A6/A7 annotations land at their replacing phases (EM-6/EM-4) per the
  "no test weakening, no transitional hardening" rule.

## Method

Greps executed 2026-09-06 over `v2/**/*.kt` excluding test sources for:
`runShellCommand(`, `rewriteWorkflowControl`, `ScriptScope`, `fun pwd(`,
`fun isUnix(`, `FAILED_TIMEOUT`, `toLegacyStatus`, `executeDurableShell(`,
`DurableShellResult`, and `toDurableTaskTerminal`; line-number inspection of
`ShExecution.kt`, `DurableShellExecutor.kt`, `DurableTaskTerminalAdapter.kt`,
and `PipelineDsl.kt` at the cited offsets. Line numbers refer
to the uncommitted EM tree and must be re-verified after the first commit of
this cycle.
