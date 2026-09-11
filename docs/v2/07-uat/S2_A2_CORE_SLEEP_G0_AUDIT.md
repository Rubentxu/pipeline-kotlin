# S2-A2 / G0 — Audit & Characterization Receipt

> Cycle: `cycle/lfc2-e1-s2-legacy-catalog-burn-down`
> Slice: S2-A2 (`core.sleep`)
> Gate: **G0 — Audit & Characterization (NO production change)**
> Branch HEAD: `d7e6580b` (S2-A1 closed; trunk `c6783f95`)
> Date: 2026-09-11T12:28Z

## 1. Purpose

G0 characterizes the **current legacy `core.sleep` authority end-to-end** before any
implementation decision. The objective is to answer, with evidence, a single concrete
question:

> **What does `sleep` actually mean in pipeline-kotlin today, especially when someone tries to cancel it?**

G0 produces NO production change. G0 produces only characterization evidence.

## 2. Authority map (the current chain)

```text
DSL sleep(seconds: Long)
  ↓
StepSpec.Sleep(seconds, retry, timeoutMillis)
  ↓
DslCompiledPipelineCompiler.encode → dsl-v1 payload {"kind":"sleep","seconds":N}
  ↓
CanonicalCoreStepDecoder.decode(...)
  ↓
CanonicalCoreStepCommand.Sleep(seconds)
  ↓
StepDescriptor: effects = { Effect.READ_ONLY }; replay = ReplayPolicy.MEMOIZED;
                 location = ExecutionLocation.CONTROLLER
  ↓
LegacyExecutionBoundary.prepare → PreparedLegacyExecution
  ↓
CanonicalNodeDispatcher.dispatch(command, runtime)
  ↓
CanonicalSleepNodeDispatcher.dispatch(command, context)   [BLOCKING, non-suspend]
  ↓
sdk.runtime.sleep(StepContext, seconds, sink, stepIndex)
  ↓
Thread.sleep(seconds * 1000L)
  ↓
StepOutcome.Success
```

Source files (the four legs of the chain):

```text
DSL facade:        v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt:1041
StepSpec.Sleep:    v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt:109
Compiler:          v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/DslCompiledPipelineCompiler.kt:643
Decoder:           v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt:225
Metadata:          v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepMetadata.kt  (key = "core.sleep")
Dispatcher:        v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalSleepNodeDispatcher.kt
SDK runtime body:  v2/pipeline-step-sdk/runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/runtime/StepExecutors.kt:171
                   └─ fun sleep(context, seconds, sink, stepIndex) { Thread.sleep(seconds * 1000L) }
```

## 3. Characterization matrix

Test files:

```text
v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/CoreSleepLegacyCharacterizationTest.kt
  → 7 tests pinning the dispatcher-level behavior (raw seconds values, interrupt, overflow)

v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreSleepCoordinatorCharacterizationTest.kt
  → 3 tests pinning the coordinator-level behavior (fresh + replay, end-to-end through production spine)
```

CLI-level evidence (real distribution, no Gradle test seam):

```text
/tmp/g0-sleep-evidence/timeout-sleep.pipeline.kts    (timeout(2 SECONDS) { sleep(10) })
/tmp/g0-sleep-evidence/parallel-sleep.pipeline.kts   (parallel { a: sleep(10); b: sleep(1) })
/tmp/g0-sleep-evidence/replay-sleep.pipeline.kts    (sleep(2), fresh + replay with same --db)
/tmp/g0-sleep-evidence/overflow-sleep.pipeline.kts   (sleep(9223372036854775807))
```

### 3.1 Per-dimension verdict

| Dimension | Current observed authority | Verdict |
|---|---|---|
| input seconds | `Long` (no range validation at any layer: DSL, StepSpec, compiler, decoder) | **under-validated** |
| zero | `Thread.sleep(0)` returns immediately; dispatcher returns `StepOutcome.Success`; no events emitted | OK |
| negative | `seconds * 1000L = negative` → `Thread.sleep(negative)` throws `IllegalArgumentException` → propagated by dispatcher → coordinator surfaces as `RunOutcome.Failure` (exit=1); CLI does NOT emit a `StepFailed` event before `RunFinished(failure)` | **leaks as infrastructure failure** |
| overflow (`Long.MAX_VALUE`) | `seconds * 1000L` overflows Long → negative → same as negative path | **leaks as infrastructure failure** |
| overflow (`Long.MIN_VALUE`) | `seconds * 1000L` overflows to a small positive Long → `Thread.sleep(small_positive)` blocks for ~292 years in theory (interruptible in practice) | **asymmetric: silently blocks for ~forever** |
| normal completion | blocks for `seconds` seconds (≈2.0–2.5s for `seconds=2` with overhead); emits `StepStarted` + `StepFinished` only (no `StepFailed`, no events from the dispatcher itself) | OK |
| interrupt (`Thread.interrupt()`) | propagates `InterruptedException` to the caller; the dispatcher is non-suspend, so the exception escapes into the caller frame | OK at JVM level, but **no coroutine-cooperative unwinding** |
| timeout interaction (`timeout(2 SECONDS) { sleep(10) }`) | `TimeoutScheduled(timeoutSeconds=2, action="abort")` event emitted; `childShOptions.timeoutMs` projected; `core.sleep` is NOT a subprocess, so the Sh watchdog does NOT apply; the sleep runs to its full 10 seconds; `RunFinished.outcome="success"` | **timeout DOES NOT interrupt sleep** |
| cancellation (coroutine `cancel()`) | `CanonicalNodeDispatcher.dispatch(...)` is `suspend` but the inner `sleepDispatcher.dispatch(...)` is non-suspend; cancellation at the coroutine level does NOT propagate into `Thread.sleep` | **no cooperative cancellation** |
| parallel branches | both branches run concurrently on `Dispatchers.Default`; branch with `sleep(10)` does NOT cancel the other; total wall-clock ≈ max(sleep durations), not sum | OK in itself, but **confirms no cross-branch cancellation** |
| fresh durable | one terminal `SUCCEEDED` operation (CLI: `StepStarted` → `StepFinished` → `RunFinished(success)`); wall-clock ≈ N seconds | OK |
| replay (same `--db`, same `runId`, default policy) | wall-clock ≈ 0 seconds for the sleep invocation; the log shows `StageStarted` → `StageFinished` (no `StepStarted`/`StepFinished` for the sleep step); the sleep authority is NOT invoked again | **MEMOIZED + READ_ONLY contract honored** |
| resume / recovery | not exercised in G0 (the legacy `core.sleep` does not produce a RUNNING-durable row in normal operation; it completes synchronously) | n/a for happy path |
| effects | `Effect.READ_ONLY` (declared); the dispatcher emits NO events of its own (no `StepOutputCaptured`, no `WaitUntilPolled`-style telemetry) | OK |
| capabilities | none declared (legacy has no capability contract); the dispatcher uses only `StepContext` + `EventSink` + an int | n/a legacy |
| events | `StepStarted`, `StepFinished`, `RunFinished(success/failure)` only; no `StepFailed` for negative / overflow (the failure surfaces at the RunFinished level) | **failure event visibility gap** |
| failure kind | for `seconds < 0` or overflow: the failure is captured as a Run-level failure (`exit=1`, `RunFinished.outcome="failure"`) with no typed `failureKind` (because no `StepFailed` is emitted); the legacy authority does not project a typed `PipelineFailure` for these input-validation failures | **failure typedness gap** |

### 3.2 Key findings (machine-pinned)

```text
F1 — Input validation gap.
    The legacy `core.sleep` accepts any Long for `seconds`, including negatives and
    values that overflow `seconds * 1000L`. The DSL, StepSpec, compiler, and decoder
    perform no range check. Failure surfaces as an untyped Run-level failure.

F2 — Timeout does not cancel sleep.
    `timeout(N SECONDS) { sleep(M seconds) }` does NOT interrupt sleep when M > N.
    Empirically (timeout(2) wrapping sleep(10)): the sleep runs all 10 seconds, and
    `RunFinished.outcome = "success"`. The `TimeoutScheduled(timeoutSeconds=2, action=abort)`
    event is emitted, but `childShOptions.timeoutMs` only governs `core.sh` subprocesses.

F3 — No cooperative coroutine cancellation.
    The dispatcher (`CanonicalSleepNodeDispatcher.dispatch`) is non-suspend and blocks
    the coroutine worker thread via `Thread.sleep`. A coroutine `cancel()` does NOT
    interrupt a running sleep. Only `Thread.interrupt()` (OS-level) propagates through
    `Thread.sleep` and surfaces as `InterruptedException`. There is NO use of
    `kotlinx.coroutines.delay` in this authority (confirmed by code inspection).

F4 — Failure event visibility gap.
    For seconds < 0 or overflow, the dispatcher throws `IllegalArgumentException`
    (from `Thread.sleep`). The `StepExecutionBoundary.execute(...)` catches only
    `PipelineStepException`; any other Throwable is propagated. Empirically the run
    terminates with `exit=1` and `RunFinished.outcome="failure"`, but NO `StepFailed`
    event is emitted. The user has no observability into WHICH step failed and why.

F5 — Asymmetric overflow behavior.
    `Long.MAX_VALUE` (overflow → negative millis) → throws IllegalArgumentException
    quickly.
    `Long.MIN_VALUE` (overflow → small positive millis) → blocks until Thread.interrupt.
    The legacy authority does not detect either overflow case; behavior depends on
    JVM arithmetic asymmetry.

F6 — Replay contract is honored.
    MEMOIZED + READ_ONLY is preserved end-to-end through the production spine.
    Wall-clock for replay is dominated by compilation + cursor advance (≈5s on this
    machine), with the sleep invocation itself taking 0 additional seconds.

F7 — waitUntil has the same temporal seam issue.
    `CanonicalWaitUntilNodeDispatcher` uses `Thread.sleep(periodMs)` between polls
    AND checks `Thread.currentThread().isInterrupted`. It also imports
    `kotlinx.coroutines.delay` (line 9 of the file) but DOES NOT use it. Both
    `core.sleep` and `core.waitUntil` would benefit from the same temporal seam fix,
    but only `core.sleep` is in S2-A2 scope.
```

## 4. Evidence (test results)

### 4.1 In-process tests

```text
$ ./gradlew -p v2 :pipeline-application:test --tests 'CoreSleep*'

TEST-dev.rubentxu.pipeline.v2.application.durable.CoreSleepLegacyCharacterizationTest.xml
  tests="7"  failures="0"  errors="0"   time="2.418s"

TEST-dev.rubentxu.pipeline.v2.application.CoreSleepCoordinatorCharacterizationTest.xml
  tests="3"  failures="0"  errors="0"   time="4.523s"
```

Test list and observed timings:

```text
CoreSleepLegacyCharacterizationTest
  sleep accepts seconds == 0 and returns Success without producing events()                    0.001s
  sleep with seconds == -1 throws IllegalArgumentException at Thread sleep(...)                0.101s
  sleep multiplication overflow with seconds == Long MAX_VALUE throws IllegalArgumentException()  0.001s
  sleep with seconds == Long MIN_VALUE does not throw(...)                                     0.213s
  sleep(2) blocks for at least 1900ms and returns Success without events()                     2.005s
  sleep(5) responds to Thread interrupt by throwing InterruptedException immediately()         0.203s
  sleep returns exactly StepOutcome dot Success (no typed output carrier)()                   0.000s

CoreSleepCoordinatorCharacterizationTest
  fresh sleep(2) executes the legacy authority once and returns Success()                      2.484s
  fresh sleep(-1) is NOT validated by the decoder and fails at the dispatcher level(...)       0.011s
  replay of a previously succeeded sleep reuses the operation without re-blocking()           2.008s
```

### 4.2 CLI evidence (real distribution)

```text
$ "$BIN" run --db /tmp/g0-sleep-evidence/timeout.db --control-root /tmp/g0-sleep-evidence/timeout-control \
       /tmp/g0-sleep-evidence/timeout-sleep.pipeline.kts

EXIT=0   ELAPSED_MS=14942   (~15s; sleep(10) ran to completion despite timeout(2))
Events:
  TimeoutScheduled(timeoutSeconds=2, action="abort", stepType="core.timeout")
  StepStarted(stepType="sleep", @12:26:37.993Z)
  StepFinished(stepType="sleep", @12:26:47.998Z)   <- 10.005s elapsed
  StageFinished(outcome="success")
  RunFinished(outcome="success")

$ "$BIN" run --db /tmp/g0-sleep-evidence/parallel.db --control-root /tmp/g0-sleep-evidence/parallel-control \
       /tmp/g0-sleep-evidence/parallel-sleep.pipeline.kts

EXIT=0   ELAPSED_MS=15264   (~15s; sleep(10) in branch a, sleep(1) in branch b, branches concurrent)
Events (excerpt):
  ParallelBranchStarted(a) / ParallelBranchStarted(b)        @12:27:07.386Z
  StepStarted(sleep in a) / StepStarted(sleep in b)          @12:27:07.429Z
  StepFinished(sleep in b)                                   @12:27:08.435Z   (1.006s)
  ParallelBranchFinished(b, outcome=success)
  StepFinished(sleep in a)                                   @12:27:17.434Z   (10.005s)
  ParallelBranchFinished(a, outcome=success)

$ "$BIN" run --db /tmp/g0-sleep-evidence/replay.db --control-root /tmp/g0-sleep-evidence/replay-control \
       /tmp/g0-sleep-evidence/replay-sleep.pipeline.kts

FRESH EXIT=0  ELAPSED_MS=7129    (compilation + 2s sleep + overhead)
  Events include StepStarted(sleep), StepFinished(sleep) at @12:27:32.860Z.

REPLAY (same db/ctl) EXIT=0  ELAPSED_MS=5138    (compilation + cursor advance, NO StepStarted/StepFinished for sleep)
  Events include only StageStarted, StageFinished, RunFinished(success).
  The sleep authority was NOT invoked a second time — MEMOIZED + READ_ONLY honored.

$ "$BIN" run --db /tmp/g0-sleep-evidence/overflow.db --control-root /tmp/g0-sleep-evidence/overflow-control \
       /tmp/g0-sleep-evidence/overflow-sleep.pipeline.kts    (sleep(9223372036854775807))

EXIT=1   ELAPSED_MS=4996    (~5s; overflow -> IllegalArgumentException, run terminates as failure)
Events:
  StepStarted(sleep) @12:27:55.794Z
  StepFinished(sleep) @12:27:55.798Z    (4ms later; Thread.sleep rejected negative millis)
  RunFinished(outcome="failure")        (NO StepFailed event)
```

### 4.3 Regression sanity (no change to existing tests)

```text
$ ./gradlew -p v2 :pipeline-application:test --tests 'EchoStepContractSuiteTest' \
                                                --tests 'CoreErrorStepUnitTest' \
                                                --tests 'CoreErrorRegistryPrimaryFitnessTest' \
                                                --tests 'CoreErrorStepContractSuiteTest' \
                                                --tests 'CanonicalSleepNodeDispatcherTest' --rerun

EchoStepContractSuiteTest           17/17/0   (S1 regression sanity)
CoreErrorStepUnitTest                20/20/0
CoreErrorRegistryPrimaryFitnessTest  14/14/0
CoreErrorStepContractSuiteTest       17/17/0
CanonicalSleepNodeDispatcherTest      1/1/0    (pre-existing happy-path test still green)
```

## 5. Production code touched in G0

**None.** G0 added only characterization tests and CLI evidence files in `/tmp/`.

```text
git status --porcelain (working tree at G0 close)
?? v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/CoreSleepLegacyCharacterizationTest.kt
?? v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreSleepCoordinatorCharacterizationTest.kt
```

No edits to:

- `CoreSleepStep.kt` — does not exist yet.
- `CoreStepRegistryFactory.kt`
- `CanonicalCoreStepDecoder.kt`
- `CanonicalCoreStepMetadata.kt`
- `CanonicalCoreStepCommand.kt`
- `CanonicalNodeDispatcher.kt`
- `CanonicalSleepNodeDispatcher.kt`
- `LegacyExecutionBoundary.kt`
- `CanonicalDurableRunCoordinator.kt`
- `LEGACY_PLUGIN_IDS` (still 11)
- `STEP_INVENTORY_LFC2E0.md`

## 6. State machine (S2-A2 at G0 close)

```text
LEGACY_PLUGIN_IDS              = 11   (unchanged)
CanonicalCoreStepMetadata rows = 11   (unchanged)
per-Step dispatchers           = 11   (unchanged)

core.sleep:
  execution     = LegacyCore
  registry      = absent
  certification = legacy / uncertified
  characterization evidence = THIS RECEIPT (10 in-process tests GREEN + 4 CLI scenarios documented)
```

## 7. Decision

### TEMPORAL_SEAM_REQUIRED = **YES**

The legacy authority is **unsafe in three independent dimensions** that all share the
same root cause: **the temporal seam is `Thread.sleep`, with no coroutine cooperation,
no input validation, and no observability of failures**. The current authority cannot
be migrated to the open-world registry seam by `Thread.sleep` because:

1. **A registry-side `CoreSleepStep` that wraps `Thread.sleep`** would merely consolidate
   the unsafe semantic into the registry path. The user's three-state hypothesis (F1,
   F2, F3, F4 above) would still hold.

2. **A registry-side `CoreSleepStep` that uses `kotlinx.coroutines.delay`** would fix
   F3 (cooperative cancellation) and partially F2 (timeout via `withTimeout` in the
   coordinator). It would NOT fix F1 (input validation) and F4 (failure observability),
   both of which are separate, narrow, additive contracts.

3. **A `TemporalCapability` is NOT required** as a separate capability. The user's
   hypothesis is supported: `requiredCapabilities = emptySet()` if the cancel/timeout
   semantics come from the coroutine context.

### What this implies for G1

The minimum viable `CoreSleepStep` for S2-A2 / G1+ is:

```kotlin
val contract = StepContract(
    key = CoreSleepStep.KEY,
    descriptor = StepDescriptor(
        name = "sleep",
        effects = setOf(Effect.READ_ONLY),
        replayPolicy = ReplayPolicy.MEMOIZED,
        recoveryPolicy = RecoveryPolicy.None,    // atomic from caller's POV
    ),
    inputCodec = CoreSleepInputCodec,
    outputCodec = CoreSleepOutputCodec,
    requiredCapabilities = emptySet(),          // cancel/timeout from coroutine context
    handler = CoreSleepHandler,
)

class CoreSleepHandler(...) {
    suspend fun execute(input: CoreSleepInput, ctx: StepHandlerContext): CoreSleepOutput {
        // input validation: require(seconds > 0) { "core.sleep requires seconds > 0" }
        delay(input.seconds.toMillis())           // kotlinx.coroutines.delay
        return CoreSleepOutput.Completed
    }
}
```

The handler validates `seconds > 0` at the input boundary (fixes F1, F5). It uses
`kotlinx.coroutines.delay` so that the coroutine's `Job.cancel()` interrupts the wait
immediately (fixes F3). The `timeout` block (already in the coordinator) can wrap the
sleep in `withTimeout` at the parent boundary to fix F2, OR the block-level deadline
can be honored by `withTimeout` in the `dispatchBody` for `core.timeout` (fixes F2).
The output carrier projects `StepOutcome.Success` on completion and projects the typed
failure on cancellation as `FailureKind.TIMEOUT` or `FailureKind.CANCELLED` (fixes F4).

**`waitUntil` is NOT in scope for S2-A2.** It is the natural second consumer of any
temporal seam fix, but its dispatch is currently a stub (`dispatchStub`) on the
canonical path; the real polling path is in-memory only. We do NOT refactor `waitUntil`
here. We note it as the canonical second consumer in the audit so that, if a future
S2-A-N needs to generalize, the evidence is in one place.

## 8. Awaiting GO

G0 is closed. The next gate requires explicit GO. The user's directive was:

> **STOP y tráeme el resultado de G0. Con esa evidencia decidimos si G1 es un `CoreSleepStep` muy simple y suspendible o si necesitamos antes una seam temporal mínima.**

That decision is in §7. Awaiting GO for G1 of S2-A2.

---

**G0 closed.**
