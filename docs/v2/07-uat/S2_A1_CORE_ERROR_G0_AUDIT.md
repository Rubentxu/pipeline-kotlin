# S2-A1 — core.error — G0 Baseline Audit

**Baseline SHA (trunk):** `c6783f9505db8f6dc065f28724e035fefe693210` (`main == origin/main`)
**Cycle branch:** `cycle/lfc2-e1-s2-legacy-catalog-burn-down`
**Date:** 2026-09-11T09:32Z

## Pre-S2 machine-derived proof (re-confirmed at this SHA)

```text
LEGACY_PLUGIN_IDS  : 12     sha256 b0d33e63c911e012be66c220d9b00f1a8824780fa67cfaa64db041b30b1de2c2
  core.error, core.sleep, core.file.writeFile, core.emit.event,
  core.milestone, core.deleteDir, core.cleanWs, core.load,
  core.pwd, core.isUnix, core.waitUntil, core.archiveArtifacts

metadata rows       : 12
dispatcher classes  : 12
```

After S2-A1 the expected counter is `12 → 11` across all three sets.

## G0 — Current observable behavior of `core.error`

### Step identity & authority

- **PluginStepId:** `core.error`
- **DSL façade:** `StageScope.error(message, failureKind = ...)` lowers to
  `StepSpec.Error(message, failureKind)` (`PipelineDsl.kt:1035`).
- **DSL compiler:** `StepSpec.Error` lowers via `DslCompiledPipelineCompiler.kt:488` and `:639` to a canonical
  `StepNode` whose `pluginStepId.value = "core.error"` and payload `kind = "error"`,
  `message = <text>`, `failureKind = <name>`.

### Legacy decoder branch

`CanonicalCoreStepDecoder.decode` (line 217-225) maps `core.error` →

```kotlin
CanonicalCoreStepCommand.Error(payload.requiredString("message"), failureKind)
```

with `FailureKind` validated against `FailureKind.entries` and rejected for unknown values.

### Legacy dispatcher

`CanonicalErrorNodeDispatcher.dispatch(command)` returns
`StepOutcome.Failure(PipelineFailure(command.failureKind, command.message))`.

### Legacy metadata row

```kotlin
"core.error" to StepMetadata(setOf(Effect.ABORTS_PIPELINE), ReplayPolicy.NEVER),
```

### DSL ⇒ runtime flow

```text
DSL error("boom", failureKind = "USER")
  → StepSpec.Error(message = "boom", failureKind = "USER")
  → StepNode(pluginStepId = "core.error", payload = dsl-v1)
  → CanonicalCoreStepDecoder.decode → CanonicalCoreStepCommand.Error
  → CanonicalErrorNodeDispatcher.dispatch
  → StepOutcome.Failure(PipelineFailure(FailureKind.USER, "boom"))
  → RunOutcome.Failure(failure)
  → ScriptedRuntime.failureKindToException: USER → UserStepException
  → PipelineOutcome.Failure(...)
  → CLI exit code 1
```

### Observable contract (preserved by G3)

Tests that pin the contract:

- `UatStep003ErrorAbortTest` — runs `error-abort.pipeline.kts` via installed distribution:
  - exit code == 1
  - exactly one `StepStarted` with `stepType == "error"`
  - exactly one `StepFinished` with `stepType == "error"`
  - exactly one `StepFailed` event with `failureKind == USER` and `message == "boom"`
  - `RunFinished.outcome == "failure"`, `diagnostics` empty
  - "Replay aborted" MUST NOT appear in the failure message
  - ReplayPolicy.NEVER: a fresh run MUST execute; replay of journaled error MUST NOT re-execute

- `CanonicalErrorNodeDispatcherTest` — pins:
  - `dispatch(Error("boom", USER)) == Failure(PipelineFailure(USER, "boom"))`
  - `failure.kind == FailureKind.USER`, `failure.message == "boom"`

- `ErrorHandlingTest`, `CliCompileErrorExitsOneTest`, `UatLocal012ErrorHandlingTest`,
  `UatComp002ErrorSourceMappedTest` — additional observable contracts.

### Real scenario (preserved by G8)

`v2/compatibility/15-error.pipeline.kts`:

```kotlin
pipeline {
    stages {
        stage("error-step") {
            error("test error message", failureKind = "USER")
        }
    }
}
```

Driven by `CompatibilityCorpusTest`. No new example is added in S2-A1.

### Replay policy decision (preserved, not copied)

`ReplayPolicy.NEVER` is the correct semantic for `core.error`, NOT a copy from echo/sh.
Justification: an error has **no reproducible effect to re-execute** — its only observable
output is the typed failure event/exception, which is already durably recorded. Re-executing
the handler would either (a) re-emit a duplicate `StepFailed` (event spam, no value) or
(b) bypass the recorded outcome. Replay must therefore abort before handler invocation if
a durable record exists; fresh execution proceeds normally. This matches the existing
`ReplayPolicy.NEVER` row in metadata and the `UatStep003ErrorAbortTest` hardening assertions
("Replay aborted" rejection diagnostic must never appear for fresh runs).

`Effect.ABORTS_PIPELINE` is preserved: an error handler's outcome aborts the pipeline
(no later step executes after a `core.error`). This is what makes it terminal.

### Capabilities

The handler needs **no capability**. It returns a typed failure outcome and emits a
`StepFailed` event through the canonical `CommonExecutionBoundary` (mirroring how
`CoreShellStep` consumes `SHELL_OPERATIONS_CAPABILITY` only for effectful work).
The boundary itself owns event projection; the handler does not reach a coordinator or event
sink directly.

`requiredCapabilities = setOf()` is the correct declaration. No fictional capability
introduced.

## What G1 must produce

1. `CoreErrorStep.kt` — `StepDefinition<ErrorInput, Nothing>` (output `Nothing` because
   the typed handler outcome is `StepOutcome.Failure` directly, not a value).
2. `ErrorStepCodec` — encodes/decodes the WHOLE input payload (dsl-v1 envelope with
   `{"kind":"error","message":"...","failureKind":"USER"}`) to keep durable fingerprint
   identical to the legacy decoder output.
3. `CoreStepRegistryFactory` registers `CoreErrorStep` alongside `CoreEchoStep` and `CoreShellStep`.
4. `StepContract<ErrorInput, Nothing>` with:
   - `descriptor = StepDescriptor(stepId="core.error", effects=[ABORTS_PIPELINE], replayPolicy=NEVER)`
   - `requiredCapabilities = setOf()` (no capability)
5. Real scenario G8: `v2/compatibility/15-error.pipeline.kts` (existing) is the
   authoritative acceptance fixture.

## What G4-G6 must remove (after G3 parity)

- `CanonicalCoreStepCommand.Error` (data class) — keep only `Sleep`/`WriteFile`/etc.
- The `"core.error"` branch in `CanonicalCoreStepDecoder.decode`.
- The `CanonicalErrorNodeDispatcher.kt` file.
- The `"core.error"` row in `CanonicalCoreStepMetadata.table`.
- The `"core.error"` literal in `LEGACY_PLUGIN_IDS`.

## What G7 must add (after G6)

`ErrorStepContractSuiteTest` covering the contractual rows:

1. identity (KEY == PluginStepId("core.error"))
2. contract completeness (descriptor + codecs + capabilities)
3. codec input round-trip
4. codec output round-trip (typed `Nothing`/absent — adapter value `Unit`)
5. canonical envelope (dsl-v1 `{kind:error,...}` byte-identical to legacy)
6. registry resolution
7. capability admission (no capabilities required → trivially admitted)
8. success-via-failure semantics (typed `StepOutcome.Failure(PipelineFailure(...))`)
9. typed failure preservation (FailureKind, message)
10. fresh durable (no prior record → handler runs once)
11. replay (prior journaled record → `ReplayDecision.ABORT`, handler never runs)
12. divergence (typed outcome reaches boundary unchanged)
13. observability (StepStarted/StepFailed/StepFinished/RunFinished events emitted with correct fields)
14. missing capability N/A (no capability declared → skipped, not a row)
15. architecture fitness (canonical path used; no legacy reachable)
16. real DSL scenario (`15-error.pipeline.kts` → exit 1, exactly one `StepFailed{USER,"test error message"}`)

The exact count is "rows covered", not "tests written". Some rows may collapse into a
single test (e.g. 1+2 may share a fixture).

## What G4 architecture fitness must assert (structural)

`S3ErrorLegacyRemovedFitnessTest` must prove, against code (not text):

- `"core.error"` is registered in the production `CoreStepRegistryFactory.registry()`.
- `"core.error" !in CanonicalCoreStepDecoder.LEGACY_PLUGIN_IDS`.
- No `"core.error"` row in `CanonicalCoreStepMetadata.table`.
- No `CanonicalErrorNodeDispatcher.kt` file exists.
- No branch in `CanonicalCoreStepDecoder.decode` matches `"core.error"` (`when` over `pluginStepId`).
- `StructuralFamilyResolver.classify("core.error", registry) == Registry`.

## Counter delta expected at G8

```text
LEGACY_PLUGIN_IDS  : 12 → 11  (drop core.error)
metadata rows       : 12 → 11  (drop core.error)
dispatcher classes  : 12 → 11  (delete CanonicalErrorNodeDispatcher.kt)
```

## Forbidden in S2-A1

- No conversion to a generic `RuntimeException`/`IllegalStateException`. The contract is
  typed `PipelineFailure` → typed `UserStepException` (for `USER` kind) and the analogous
  `*StepException` per `FailureKind` (via `ScriptedRuntime.failureKindToException`).
- No privileged core execution path added to the coordinator.
- No new `StepSpec.Error` subclass changes outside the canonical lowering.
- No production edit to `core.echo`/`core.sh` (already CERTIFIED + LEGACY_REMOVED).
- No EVT-4/M4 modification.
- No `echo`-style `EFFECT.ABORTS_PIPELINE` confusion with `ReplayPolicy.NEVER`.

## Slice close gate

```text
core.error:
  delivery:    CORE
  execution:   REGISTRY_PRIMARY
  legacy:      REMOVED
  certification: CERTIFIED

proof (machine-derived):
  - G4 architecture fitness: S3ErrorLegacyRemovedFitnessTest  N/N GREEN
  - G7 StepContractSuite:    ErrorStepContractSuiteTest      M/M GREEN
  - core.error test suite:   <existing tests, refixtured>    P/P GREEN
  - real scenario:           v2/compatibility/15-error.pipeline.kts
                              exit=1, exactly 1 StepFailed(USER,"test error message")
```

Then `STOP` and report before opening S2-A2 (core.sleep).
