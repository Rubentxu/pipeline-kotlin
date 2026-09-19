# F5.2 — Index

This directory closes `F5.2 = CLOSED_GREEN` and files the two follow-up
work units discovered during the cycle.

## Closure receipt

- [`RECEIPT.md`](./RECEIPT.md) — commit chain, certified artefact, test
  evidence, E2E evidence, negative-path evidence, fixture description,
  hypothesis correction, deliberate out-of-scope follow-ups.

## Defect (closed)

- [`DEFECT_JUNIT_WORKSPACE_ROOT.md`](./DEFECT_JUNIT_WORKSPACE_ROOT.md) —
  the real root cause of the failure observed mid-cycle. Originally
  hypothesised as a `dir`/`sh` cwd propagation issue; characterisation
  disproved that and pinpointed the workspaceRoot resolution in
  `JUnitResultsStepDefinition.handler`. Closed in commit `7e0e5953`.

## Follow-up work units

- [`WU_LPR_WC_WORKSPACE_CONTEXTUAL.md`](./WU_LPR_WC_WORKSPACE_CONTEXTUAL.md)
  — `CLOSED_GREEN` 2026-09-19. Closure receipt:
  [`WC_CLOSURE_RECEIPT.md`](./WC_CLOSURE_RECEIPT.md). Characterisation
  evidence: [`WC_CHARACTERISATION.md`](./WC_CHARACTERISATION.md).
  Out-of-scope sibling follow-up: `GitCheckoutStepDefinition` (F5.1
  SCM/Git) still reads the system property and must migrate to the
  typed seam in a separate WU.

- [`WU_LPR_FK_TYPED_FAILURE_PROPAGATION.md`](./WU_LPR_FK_TYPED_FAILURE_PROPAGATION.md)
  — `CLOSED_GREEN` 2026-09-19. Closure receipt:
  [`FK_CLOSURE_RECEIPT.md`](./FK_CLOSURE_RECEIPT.md). Characterisation
  evidence: [`FK_CHARACTERISATION.md`](./FK_CHARACTERISATION.md).

## Status summary

| Item | Status |
| --- | --- |
| `PLUGIN_COMPOSITION_SMOKE` | PASS |
| `CHECKOUT_BUILD_TEST_REPORT_E2E` | PASS |
| `F5.2` | CLOSED_GREEN |
| `WU_LPR_FK` (typed failure propagation) | CLOSED_GREEN 2026-09-19 |
| `WU_LPR_WC` (workspace contextual) | CLOSED_GREEN 2026-09-19 |
| `SDKMAN` | WAITING_EXTERNAL |
| `CanonicalDurableRunCoordinator` modifications during F5.2 / FK / WC | NONE |
| `core.sh` modifications during F5.2 / FK / WC | NONE |
| `core.echo` modifications during F5.2 / FK / WC | NONE |
| `RegistryExecutionBoundary` modifications during FK / WC | NONE (boundary contract preserved) |
| `JUnitResultsStepDefinition` modifications during FK / WC | migrated to `TypedStepOutput` carrier (FK); migrated to `WORKSPACE_IDENTITY_CAPABILITY` seam (WC) |
| `Main.kt` `System.setProperty("pipeline.workspace.root", ...)` | REMOVED during WC |
| Concurrent workspace isolation | LOCKED by `JUnitWorkspaceIsolationTest` |
| Typed-failure propagation contract | LOCKED by `RegistryExecutionBoundaryFailureKindTest` |
