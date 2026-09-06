# Failure and Interruption Model

## 1. Goals

The runtime must distinguish:

- expected step contract failures;
- infrastructure/worker failures;
- user/application errors;
- cancellation/interruption;
- invalid persisted/input schema;
- replay incompatibility;
- internal engine invariants.

A single `FailureKind` enum is insufficient as the only control-flow representation.

## 2. Durable record vs runtime exception

### 2.1 Persisted representation

```kotlin
data class FailureRecord(
    val code: String,
    val kind: FailureKind,
    val message: String,
    val origin: FailureOrigin,
    val retryable: Boolean,
    val operationId: String,
    val workerId: String? = null,
    val taskId: String? = null,
    val details: Map<String, String> = emptyMap(),
)
```

It must be serialization-safe and versioned.

It must not require Java/Kotlin exception serialization.

### 2.2 Runtime representation

```kotlin
sealed class PipelineExecutionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
```

Representative subclasses:

```text
PipelineStepException
  ├─ ShellExitException
  ├─ InfrastructureStepException
  ├─ NetworkStepException
  ├─ UserStepException
  └─ PluginStepException

PipelineInterruptedException
  ├─ TimeoutInterruption
  ├─ UserAbortInterruption
  ├─ ParentCancellationInterruption
  └─ SupersededRunInterruption

ReplayCompatibilityException
StepSchemaException
EngineInvariantViolation
```

A runtime exception may contain both:

- a durable `FailureRecord`/`InterruptionRecord`;
- an ephemeral original cause.

## 3. Failure kinds

Recommended persisted categories:

```text
SCRIPT
USER
INFRASTRUCTURE
NETWORK
PLUGIN
SCHEMA
REPLAY_COMPATIBILITY
ENGINE
UNKNOWN
```

`TIMEOUT` may remain as a reporting category for backwards compatibility, but timeout
control flow is represented as an interruption record rather than an ordinary step
failure.

## 4. Interruption record

```kotlin
data class InterruptionRecord(
    val kind: InterruptionKind,
    val message: String,
    val operationId: String,
    val causedBy: String?,
    val deadlineEpochMillis: Long? = null,
    val details: Map<String, String> = emptyMap(),
)
```

```text
TIMEOUT
USER_ABORT
PARENT_CANCELLED
SUPERSEDED
SHUTDOWN
```

Whether a wrapper catches an interruption is a wrapper contract, not a reclassification
to `SCRIPT`.

## 5. Result semantics

### Shell

```text
non-zero default              → ShellExitException / SCRIPT
non-zero returnStatus=true    → normal Int return
launch failed                 → InfrastructureStepException
durable task definitively lost→ InfrastructureStepException
timeout cancellation          → PipelineInterruptedException(TIMEOUT)
manual cancellation           → PipelineInterruptedException(USER_ABORT)
```

### Schema

Only malformed/incompatible step payloads or schemas use `SCHEMA`.

### Engine invariant

Examples:

- terminal await API returns a nonterminal type through an impossible adapter;
- scope stack corruption;
- impossible journal transition;
- duplicate terminal publication.

These use `EngineInvariantViolation`, not schema failure.

Existing scope-stack `IllegalStateException` behavior may remain until deliberately
migrated; do not accidentally catch it as a user step failure.

## 6. Step lifecycle boundary

One layer owns lifecycle emission:

```kotlin
suspend fun <R> executeStepBoundary(..., body: suspend () -> R): R {
    emitStepStarted()

    return try {
        val result = body()
        emitStepFinished(success = true)
        result
    } catch (e: PipelineInterruptedException) {
        emitStepInterrupted(e.record)
        emitStepFinished(success = false)
        throw e
    } catch (e: PipelineStepException) {
        emitStepFailed(e.record)
        emitStepFinished(success = false)
        throw e
    } catch (e: EngineInvariantViolation) {
        emitEngineFailure(...)
        emitStepFinished(success = false)
        throw e
    }
}
```

Exact event set can preserve existing event compatibility, but ownership is singular.

## 7. `StepFailed` rule

Emit `StepFailed` when the **step contract** failed.

Do not infer this directly from low-level process exit state.

Examples:

| Scenario | StepFailed? |
|---|---|
| `sh("false")` | yes |
| `sh(script="exit 7", returnStatus=true)` | no |
| `sh(returnStdout=true)` exit 7 | yes |
| launch failure | yes |
| definitive lost task | yes |
| timeout interruption | event model may use StepInterrupted + failed finish; do not mislabel SCRIPT |
| user abort | interruption, not SCRIPT |
| `catchError { sh("false") }` inner sh | inner step failed; catchError body handles exception; enclosing catchError step succeeds after applying result policy |

## 8. Build/stage result

Build/stage result is separate from exception category.

Use an ordered result lattice:

```text
SUCCESS < UNSTABLE < FAILURE < ABORTED
```

Exact Jenkins parity must be verified by differential UAT where behavior is subtle.

A block such as `catchError` may consume an exception and worsen build/stage result
while allowing control flow to continue.

## 9. Retryability

Retryability is not synonymous with failure kind.

A `FailureRecord` may expose a conservative hint, while block retry conditions make
the final decision.

Examples:

- agent disappeared → likely retryable infrastructure;
- deterministic compiler error → not retryable;
- network 503 → possibly retryable;
- shell exit 1 → retried by vanilla Jenkins `retry`, but a typed condition can choose
  otherwise;
- user abort → never retried by default.

## 10. Failure provenance across future remote boundaries

The durable record is transport-ready:

```text
controller-independent application
          ↑
FailureRecord / InterruptionRecord
          ↑
worker/runtime adapter
          ↑
native Throwable / OS error / process status
```

Do not expose Java exception class names as protocol semantics.

## 11. Observability

All failures/interruption events should include stable identifiers:

- run id;
- operation id;
- step id;
- attempt;
- task id;
- worker id when applicable;
- failure/interruption code;
- classification;
- retry decision;
- source location.

Stack traces belong to diagnostic logs, not canonical identity.

## 12. Tests

- exact mapping for each durable terminal result;
- `returnStatus` non-zero produces no StepFailed;
- original in-process cause is attached when available;
- persisted replay reconstructs equivalent typed exception without requiring original
  Throwable;
- scope leak exception is not swallowed;
- engine invariant is not mapped to schema;
- catchError catchInterruptions true/false;
- retry excludes user abort;
- timeout record survives restart.
