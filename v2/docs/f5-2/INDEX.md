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

## Follow-up work units (OPEN)

- [`WU_LPR_WC_WORKSPACE_CONTEXTUAL.md`](./WU_LPR_WC_WORKSPACE_CONTEXTUAL.md)
  — replace `pipeline.workspace.root` (system property) with a typed
  `WorkspaceProvider` capability. Characterise first, then migrate the
  `junit.results` handler, then remove the bridge.

- [`WU_LPR_FK_TYPED_FAILURE_PROPAGATION.md`](./WU_LPR_FK_TYPED_FAILURE_PROPAGATION.md)
  — audit `RegistryExecutionBoundary` and decide whether the
  USER → ENGINE transformation is intentional or an unintended loss.
  Lock-in suite first, migration only if justified. No JUnit-specific
  code path; the change is generic across all plugin handlers.

## Status summary

| Item | Status |
| --- | --- |
| `PLUGIN_COMPOSITION_SMOKE` | PASS |
| `CHECKOUT_BUILD_TEST_REPORT_E2E` | PASS |
| `F5.2` | CLOSED_GREEN |
| `WU_LPR_WC` (workspace contextual) | OPEN |
| `WU_LPR_FK` (typed failure propagation) | OPEN |
| `SDKMAN` | WAITING_EXTERNAL |
| `CanonicalDurableRunCoordinator` modifications during F5.2 | NONE |
| `core.sh` modifications during F5.2 | NONE |
| `core.echo` modifications during F5.2 | NONE |
