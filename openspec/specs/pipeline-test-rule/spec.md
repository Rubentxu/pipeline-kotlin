# Spec: pipeline-test-rule

**Harness Fidelity: HF1 (In-Process)** per ADR-0072.

In-process test harness capability (JenkinsRule-style) for DSL-semantics UATs.
Consumed by `:pipeline-application` test sources. Pure test-support; no runtime/process
adapter changes. Derived from SPIKE-017 and the `pipeline-rule-inprocess-harness` proposal.

## Requirements

### R1 — In-process DSL compile + canonical run
- The capability SHALL compile a DSL pipeline script string in-process
  (`Kotlin24ScriptingHost` + `DslCompiledPipelineCompiler`), mirroring Main.kt's
  no-`--db` branch, and run the compiled pipeline through
  `CanonicalDurableRunCoordinator` with in-memory journal + cursor store + event store.
- It SHALL return the typed `RunOutcome` and the ordered `List<DomainEvent>` timeline.

### R2 — Credential seam is fail-closed
- A coordinator requires a `CredentialScopePort`. When the script has no
  `withCredentials`, the harness SHALL pass a stub that returns
  `Unavailable(StoreUnavailable)` (fail-closed, never dispatches a credential body).

### R3 — Parity with the real-process path
- For a given script, the typed event kinds/order and `RunOutcome` SHALL match what the
  installed-binary durable run produces, for the linear canonical subset (no replay).

### R4 — Deterministic + fast
- Each run SHALL use in-memory stores and a real short `DurableShellExecutor`; no
  installed binary, no `java -cp`, no 300s timeout. Target <5s per script after warm-up.

### R5 — Timeline assertions
- The capability SHALL expose the event timeline so tests can assert ordering and
  per-step names (e.g. `StepStarted`/`StepFinished` stepName) directly, without parsing
  JSON or spawning a process.

## Scenarios

- **S1** ERR-S-001 script (`catchError { sh exit 1; echo after-failure }`; `echo after-catch`)
  runs in-process; asserts `RunOutcome` and records the observed `StepFinished`
  step names to surface the G3 step-name divergence (currently `echo-0` vs expected `echo`).
- **S2** Parity: the same script's event kinds via `PipelineRule` equal those from the
  installed-binary durable run.
- **S3** A `withCredentials` script with no store yields `RunOutcome.Failure` (fail-closed),
  body not dispatched.

## Acceptance
- S1 completes in-process in <5s (vs >=300s subprocess) and reproduces the G3 divergence.
- S2 parity holds for the linear canonical subset.
- S3 fail-closed behavior holds.
