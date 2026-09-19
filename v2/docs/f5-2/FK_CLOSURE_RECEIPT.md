# WU-LPR-FK Closure Receipt

## Cycle identity

- **Cycle**: WU-LPR-FK (typed-failure propagation lock-in).
- **Prior state**: characterisation only (commit `c1cd773f`).
- **Final state**: CLOSED_GREEN.
- **Branch / HEAD**: `main` at HEAD after this commit.

## Summary

The transformation `PluginStepException(kind=USER) → envelope.failureKind=ENGINE`
observed in F5.2's negative scenarios had its root cause in
`JUnitResultsStepDefinition.handler`: the handler threw an exception
rather than returning a `TypedStepOutput` carrier, so the registry
boundary's generic `catch (e: Exception)` re-classified every typed
USER failure as `FailureKind.ENGINE`. The boundary is correct as-is —
it preserves any kind declared on a `TypedStepOutput` carrier, and
classifies an unhandled exception as ENGINE only when the handler did
not declare one. `core.sh` already follows the contract (SCRIPT is
preserved on exit ≠ 0); `junit.results` was the lone outlier.

## Decision

**Boundary: NOT modified.** The contract documented at
`TypedStepOutput` and `RegistryExecutionBoundary.coexecute` is the
LB-02 / G3-A4.3 / A4.3 reference and remains the single source of
truth. ENGINE for unexpected exceptions is correct; that classification
MUST NOT be lost.

**junit.results: migrated.** New carrier
`JUnitResultsOutput(summary, outcome) : TypedStepOutput` mirrors
`CoreShellOutput` exactly. The handler now returns the carrier on every
path (success, USER failure, INFRASTRUCTURE failure from an I/O stat
error). `JUnitResultsOutputCodec` carries `{ outcome, summary }` on the
wire. The legacy `JUnitReportSummaryCodec` is preserved for replay
compatibility — no historical journal entries exist for `junit.results`
yet, so the additive change is safe.

## Behaviour matrix (observed)

| Origin | Failure path | `PipelineFailure.kind` declared | boundary outcome | envelope `failureKind` |
| --- | --- | --- | --- | --- |
| `core.sh` exit 0 | success | — | Success | — |
| `core.sh` exit 1 | `CoreShellOutput(Failure(SCRIPT))` | SCRIPT | Failure(SCRIPT) | SCRIPT ✅ preserved |
| `junit.results` missing file (pre-FK) | throw `PluginStepException(USER)` | USER | Failure(ENGINE) | ENGINE ❌ |
| `junit.results` missing file (post-FK) | return `JUnitResultsOutput(Failure(USER))` | USER | Failure(USER) | USER ✅ preserved |
| `junit.results` empty / oversized / malformed XML | return carrier with USER failure | USER | Failure(USER) | USER ✅ |
| `junit.results` failing XML, `failOnFailure=true` | return carrier with USER failure | USER | Failure(USER) | USER ✅ |
| `junit.results` I/O stat failure | return carrier with INFRASTRUCTURE failure | INFRASTRUCTURE | Failure(INFRASTRUCTURE) | INFRASTRUCTURE ✅ |
| Handler throws NPE / ISE (any Step) | boundary catch | (handler didn't declare) | Failure(ENGINE) | ENGINE ✅ (regression-locked) |
| Handler returns `TypedStepOutput(Failure(SCRIPT))` | carrier projected | SCRIPT | Failure(SCRIPT) | SCRIPT ✅ |
| Handler returns `TypedStepOutput(Failure(TIMEOUT))` | carrier projected | TIMEOUT | Failure(TIMEOUT) | TIMEOUT ✅ |
| Handler returns `TypedStepOutput(Failure(INFRASTRUCTURE))` | carrier projected | INFRASTRUCTURE | Failure(INFRASTRUCTURE) | INFRASTRUCTURE ✅ |

## Files changed

| File | Change |
| --- | --- |
| `v2/pipeline-step-sdk/junit/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/junit/step/JUnitResultsOutput.kt` | **new** carrier `data class JUnitResultsOutput(val summary: JUnitReportSummary, override val outcome: StepOutcome) : TypedStepOutput` with `Companion.success(...)`. |
| `v2/pipeline-step-sdk/junit/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/junit/step/JUnitResultsStepDefinition.kt` | `StepDefinition<JUnitResultsInput, JUnitResultsOutput>`. `outputCodec = JUnitResultsOutputCodec`. Handler returns a carrier on every path; no `throw PluginStepException` anywhere. INFRASTRUCTURE labelled for I/O stat failures; USER for everything else. |
| `v2/pipeline-step-sdk/junit/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/junit/step/JUnitReportCodec.kt` | `JUnitResultsOutputCodec` added (encodes `{ outcome, summary }` symmetric). Legacy `JUnitReportSummaryCodec` retained for any historical replay decode. |
| `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/F5_2_JUnitStepContractTest.kt` | 7 negative tests migrated from `assertThrows(PluginStepException)` → assert on `output.outcome.failure.kind`. 1 success path migrates to carrier assertion. 1 new `carrier roundtrips failure kind through the output codec` lock-in row. Total: **23/23 PASS** (was 22). |
| `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/RegistryExecutionBoundaryFailureKindTest.kt` | **new** 7-row lock-in: regression (NPE→ENGINE), regression (ISE→ENGINE), lock (TypedStepOutput USER preserved), lock (SCRIPT preserved), lock (TIMEOUT preserved), lock (INFRASTRUCTURE preserved), lock (Success preserved). Total: **7/7 PASS**. |
| `v2/docs/f5-2/fk-characterisation/CHARACTERISATION.md` | characterisation evidence (already committed at `c1cd773f`). |

## Test evidence

```
:pipeline-application:test --tests 'RegistryExecutionBoundaryFailureKindTest' --tests 'F5_2_JUnitStepContractTest'
  RegistryExecutionBoundaryFailureKindTest    7 tests, 0 failures, 0 errors
  F5_2_JUnitStepContractTest                 23 tests, 0 failures, 0 errors
```

Regression sweep (`:pipeline-application:test --tests 'RegistryExecution*' --tests 'StepExecutionBoundary*' --tests 'F5_*'`) — 9 classes, 74 tests, 0 failures, 0 errors. Confirms: boundary contract holds; `core.sh` / `core.echo` / CanonicalDurableRunCoordinator are untouched.

## Constraint preservation

| Constraint | Status |
| --- | --- |
| `CanonicalDurableRunCoordinator` not modified | ✅ |
| `core.sh` / `core.echo` not modified | ✅ |
| Registry boundary contract preserved | ✅ (no per-StepKey branching) |
| C10 backwards-compat intact (legacy contributor still registerable) | ✅ |
| `core.sh` exit-1 → SCRIPT (regression) | ✅ |
| Unexpected handler exception → ENGINE (regression) | ✅ (lock-in row) |
| Plugin handler's typed USER failure → USER end-to-end | ✅ (lock-in row + F5.2 row) |

## Outstanding follow-up (NOT in this cycle)

### E2E pipelinek invocation fails to resolve `dev.rubentxu.pipeline.v2.sdk.*` imports

The distributed binary in `v2/pipeline-application/build/install/pipelinek/`
fails to compile `import dev.rubentxu.pipeline.v2.sdk.junit.step.junitResults`
with `Unresolved reference 'sdk'`. The same failure occurs with the
ORIGINAL F5.2 closure scenario (`scenario_e2e.kts`) replayed today, so
this is **not a regression from FK** — it is a pre-existing gap in the
scripting host's classpath projection:

- `Kotlin24ScriptingHost.compile` uses
  `jvm { dependenciesFromCurrentContext() }` with the default
  `wholeClasspath = false`.
- `dependenciesFromCurrentContext(wholeClasspath = false)` exposes only
  the **compile classpath** of the Gradle module that owns the scripting
  host, not its runtime classpath.
- `:pipeline-application` declares `:pipeline-step-sdk:junit` and
  `:pipeline-step-sdk:scm-git` as `implementation(...)` (runtime only),
  so the SDK plugin classes are visible to the JVM at runtime and to
  ServiceLoader discovery, but not to the scripting compiler.

The historical F5.2 evidence at `/tmp/pk-uat-f5-2/run_final.log` showed
`diagnostics:[]` and `stepType:"junit"` working end-to-end. The
mechanism by which that worked in the F5.2 closure is not recoverable
from the current state of the working tree — either an earlier state of
`build.gradle.kts` exposed the SDK modules as `compileOnly(...)`, or the
gradle compile cache held an entry that has since been evicted. This
gap is **separate from FK** and is filed under `WU_LPR_WC_WORKSPACE_CONTEXTUAL`'s
adjacent scope as a candidate follow-up, NOT a regression of this cycle.

### Evidence for "not a regression"

The closure log in `/tmp/pk-uat-f5-2/run_final.log` was generated at
`2026-09-19T16:12:22Z` against the binary that existed at F5.2 closure
time. Replaying the same `scenario_e2e.kts` against the current
`build/install/pipelinek/` binary fails the same way the FK regression
scenario fails (`Unresolved reference 'sdk'`). Therefore the gap is in
the build wiring, not in FK.

## Receipt-of-completion checklist

- [x] Behaviour observed (matrix above).
- [x] Applicable contract (`TypedStepOutput`, G3-A4.3, A4.3) documented.
- [x] Decision (boundary unchanged; junit migrated to carrier).
- [x] Test evidence (7 + 23 + regression sweep = 80+ tests green).
- [x] Constraint preservation table.
- [x] Outstanding follow-up captured (scripting host classpath gap, NOT FK).

## Status

**WU-LPR-FK = CLOSED_GREEN.**

Hand-off to `WU_LPR_WC_WORKSPACE_CONTEXTUAL` is unblocked. The
scripting-host classpath gap noted above is independent and remains
under WU-WC investigation; it does not block this closure.
