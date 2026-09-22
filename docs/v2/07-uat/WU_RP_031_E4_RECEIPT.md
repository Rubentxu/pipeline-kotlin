# WU-RP-031 E4 Receipt — DurableStepExecutor extraction (WU closure)

Base: `0ac304d6` (E3). Date: 2026-09-22.

## What

Fourth and final extraction slice: the Execute-branch effective-execution
block (`beginOperation`, `StepExecutionBoundary`-wrapped
`CommonExecutionBoundary` call, terminal journal write, cursor advance)
moved verbatim into new `DurableStepExecutor.kt` (internal). The coordinator
receives the terminal `StepOutcome` as data and keeps context/continuation
control. This is now the ONLY path invoking the effective executor;
reuse/divergence/recover never reach it. Coordinator: 1811 → 1785 lines
(full arc: 2346 → 1785 across E1..E4). Public constructor signature
unchanged throughout.

## WU-RP-031 ladder result

```text
E1 CanonicalStructuralDecisions      done (d011b4be)
E2 DurableInvocationResolver         done (88651cc6)
E3 DurableTypedInputPreparation      done (0ac304d6)
E4 DurableStepExecutor               done (this receipt)
```

Each slice: fresh local oracle + CI 7/7 on the exact SHA before the next.

## Verification (this working state, fresh runs)

| Check | Result |
| --- | --- |
| `:pipeline-application:compileKotlin` | BUILD SUCCESSFUL |
| durable + arch/fitness suites | 547/547 GREEN (XML-verified) |
| kill/resume/UAT-local | 148/148 GREEN |

## Notes

- One ordering issue during wiring: `stepExecutor` initially referenced
  `executionBoundary` before its property initialization; moved after the
  `ExecutionBoundaryFactory.build` fallback. Pure ordering, no semantic change.

## Next

WU-RP-032 (semánticas DSL) per ROADMAP §5 RP-3.
