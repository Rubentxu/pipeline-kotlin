# Tasks: pipeline-rule-inprocess-harness

Specs: `openspec/specs/pipeline-test-rule/spec.md`. Base: `b69abbfa`.
Only test-support; no production/runtime changes.

## T1 — `PipelineRule` harness helper (R1, R2, R4)
- Add `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/support/PipelineRule.kt`
  (or nearest existing test-support package) with `run(script: String): PipelineRun` returning
  `RunOutcome` + `List<DomainEvent>`.
- Reuse Main.kt no-`--db` compile recipe (Kotlin24ScriptingHost + `DslCompiledPipelineCompiler.compile`
  + `analyzeCanonicalDurableExecution`) and `CanonicalDurableRunCoordinator` with in-memory stores,
  a `noOpCredentialScopePort()` stub, `ShOptions.EMPTY`-style config, real short shell executor.
- L0 `:pipeline-application:compileTestKotlin` green.

## T2 — ERR-S-001 parity proof (R3, S1, S2)
- Port `ErrorHandlingTest` ERR-S-001 script into a `PipelineRule`-based test that asserts
  `RunOutcome` and records observed `StepFinished` step names; assert wall time <5s (R4).
- Record the observed step-name list to confirm/refute the G3 divergence (`echo-0` vs `echo`)
  in-process (no subprocess).
- L1/L2: run the new test green; if it exposes G3 divergence, assert it as an expected red and
  log evidence (do NOT weaken).

## T3 — Fail-closed credential scenario (R2, S3)
- `PipelineRule` test with a `withCredentials` script and no store -> `RunOutcome.Failure`,
  credential body not dispatched.

## T4 — Parity harness documentation (R3)
- Update SPIKE-017 status to `passed` with before/after wall-time + parity evidence once S1/S2 green.
- Optionally fold the helper into a shared test-support module only if reuse across suites appears.

## Gate
- L1/L2 targeted tests green; do NOT run full module gate (pre-existing env failures G1-G3 documented).
