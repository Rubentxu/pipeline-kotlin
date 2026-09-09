# LEG-1 — `PipelineRun` / `PipelineOrchestrator` legacy execution burn-down

Status: IN PROGRESS (LEG-1.1). Backlog authority:
`docs/v2/05-roadmap/IMPLEMENTATION_BACKLOG.md` § "LEG-1 legacy execution burn-down"
(2026-09-09, APPROVED). Grounding: `LB02_EP_F2_5_STEPSPEC_EXECUTION_DECOUPLING.md`.

## Why deletion (not modernization)

```text
direct StepSpec execution, production reachable = 0
canonical coordinator path, production authority = 1
```

The public CLI is fail-closed onto `CanonicalDurableRunCoordinator` (`System.exit(2)`
otherwise). `PipelineOrchestrator` was constructed in `Main.kt` but never invoked
(LF-0205). Its only reachable consumer was `walkPipelineSpecDurable` in `PipelineRun.kt`
plus legacy UAT harnesses.

Target rule:

```text
DSL → StepSpec → compiled canonical representation → CanonicalDurableRunCoordinator
```

Disappears:

```text
StepSpec → PipelineRun → direct execution
```

## Slice ledger

| Slice | Status | Commit | Evidence |
| --- | --- | --- | --- |
| LEG-1.0 traceability | DONE | (this commit) | backlog entry + this doc |
| LEG-1.1 delete `PipelineOrchestrator` + Main construction | DONE | see ledger update | L4 suites green |
| LEG-1.2 delete `StepExecutors.executeBranch` | PENDING | — | — |
| LEG-1.3 delete `walkPipelineSpecDurable` + direct-exec whens + legacy UATs | PENDING | — | — |
| LEG-1.4 execution-authority fitness | PENDING | — | — |
| LEG-1.5 docs sync | PENDING | — | — |

## LEG-1.1 details

Deleted:
- `v2/pipeline-application/src/main/kotlin/.../application/durable/PipelineOrchestrator.kt`
  (229 lines; class + run() delegating to `walkPipelineSpecDurable`).
- `Main.kt`: the `orchestrator` construction block (built but never referenced; LF-0205
  redirect comment retained in history). Unused imports pruned.

UAT note: `UatDurable001..007,009` still reference `PipelineOrchestrator`; they are the
C3 legacy harnesses scheduled for deletion/migration in LEG-1.3. LEG-1.1 therefore keeps
them compiling by NOT breaking their import before they are handled: decision recorded —
they were deleted together with LEG-1.3, not in this slice (see ledger).

## Preservation obligation

Durable semantics proven by the legacy harnesses (fresh / replay / divergence / kill /
resume / branch) remain owned by:
- canonical-path coordinator tests (`CanonicalDurableRunCoordinatorTest`, contract suites),
- `EP_F26_GenericProductionPathProofTest`,
- CLI durable UATs on the canonical spine (`UatDurableDefaultReuseCliTest`, etc.).
Before LEG-1.3 deletes a legacy UAT, an owning canonical-path test must cover the same
semantics, or the deletion is blocked.
