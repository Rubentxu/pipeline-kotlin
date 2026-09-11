# S2-A1 / G7 — Contract Certification Receipt

> Cycle: `cycle/lfc2-e1-s2-legacy-catalog-burn-down`
> Slice: S2-A1 (`core.error`)
> Gate: **G7 — Contract Certification**
> SHA: `55b6b1bb`
> Date: 2026-09-11T12:04Z

## 1. Purpose

G7 certifies `core.error` against the canonical Step contract suite. Per LFC-2E1 burn-down
template (ADR-0070..0074; STEP_CONSTITUTION), G7 is the formal evidence row that every
required Step contract is held by the Step — i.e. registration, codecs, descriptor metadata,
registry resolution, capability admission, durable execution, replay semantics, observability,
and the typed carrier projection.

G7 does NOT touch production code. G7 produces only certification evidence.

## 2. State at G7 entry

```text
REGISTERED         = true    (CoreErrorStep.definition in production registry)
REGISTRY_PRIMARY   = true    (closed at G5, sha 04138db0)
LEGACY_UNREACHABLE = true    (closed at G5, sha 04138db0)
LEGACY_REMOVED     = true    (closed at G6, sha 3945c87e)
CONTRACT_SUITE     = PASS    (this receipt)
CERTIFIED          = false   ← awaits G8
```

Counters unchanged from G6 close:
```text
LEGACY_PLUGIN_IDS     = 11   (CanonicalCoreStepDecoder.kt:59)
CanonicalCoreStepMetadata rows = 11
per-Step dispatchers  = 11
CoreErrorStep.definition in registry = YES
```

## 3. Contract matrix

The matrix reflects **what `core.error` actually declares**, not an arbitrary extension of
Echo's matrix. The constitution is universal; the per-Step contracts are the applicable
subset.

```text
COMMON CONTRACT                              core.error     STATUS
------------------------------------------------------------------------
identity                                     REQUIRED        PASS
descriptor completeness                       REQUIRED        PASS
input codec round-trip                        REQUIRED        PASS
input malformed/wrong discriminant            REQUIRED        PASS (x2)
output codec round-trip                       REQUIRED        PASS
canonical envelope                            REQUIRED        PASS
production registry resolution                REQUIRED        PASS
fresh factory consistency                     REQUIRED        PASS
capability declaration                        REQUIRED        PASS
                                              (empty)
capability admission                          REQUIRED        PASS
                                              (empty succeeds)
typed outcome projection                      REQUIRED        PASS
fresh durable execution                       REQUIRED        PASS
replay semantics                              REQUIRED        PASS
                                              (NEVER → ABORT)
observability                                 REQUIRED        PASS
failure kind/message preservation             REQUIRED        PASS
real registry seam execution                  REQUIRED        PASS
                                              (RegistryExecutionPreparation +
                                               RegistryExecutionBoundary.coexecute)

missing-capability rejection                  NOT_APPLICABLE
```

### 3.1 Missing-capability rejection — NOT_APPLICABLE (declared, not absent)

```kotlin
// v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreErrorStep.kt
override val contract: StepContract<CoreErrorInput, CoreErrorOutput> = StepContract(
    key = KEY,
    descriptor = ...,
    inputCodec = ...,
    outputCodec = ...,
    requiredCapabilities = emptySet(),  // ← handler is pure; no journal/event/process reach
    handler = ...,
)
```

`core.error` declares `requiredCapabilities = emptySet()`. A "missing-capability rejection"
assertion would require fabricating a capability that the contract does NOT declare, in order
to withdraw it. That would be testing a contract `core.error` does not have, not a contract
it has. Per the strict-typed-functional / TypedStepOutput discipline, the test is
**NOT_APPLICABLE**, not absent — it does not need to be written because there is nothing
the contract claims it could fail on.

`EchoStepContractSuiteTest`'s `missing capability` test exists because `core.echo` declares
`EVENT_SINK_CAPABILITY`. `core.error` declares nothing → no `missing capability` test.

### 3.2 Applicable contract count

```text
applicable contracts = 16
passed               = 17   ← (the input-codec-rejection contract is split into two methods:
                              "non-error payload kind" + "unknown failureKind". Both apply.
                              Splitting a single matrix row into two tests is a stronger
                              assertion than the matrix requires, and is in keeping with the
                              user's "discrete failures" preference for counter tests.)
failed               =  0
not applicable       =  1
```

Reporting per the user's directive: **17 PASS / 1 N.A. / 0 FAIL**.

## 4. Suite

### 4.1 File

```text
v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/ErrorStepContractSuiteTest.kt
```

Single class, `@Timeout(15)` per rule, 17 active `@Test` methods, no helpers shadowing JUnit
APIs (assertSame, assertEquals, assertInstanceOf, assertThrows are the canonical imports).
Harness reuses `CanonicalDurableRunCoordinator` (production seam) wired through
`CoreStepRegistryFactory.registry()` and `InMemoryEventStore` / `InMemoryOperationJournal`.

### 4.2 Test methods (canonical)

```text
 1. identity -- CoreErrorStep KEY is core dot error and unique within the registry
 2. descriptor completeness -- key, descriptor, input codec, output codec, required capabilities
 3. input codec -- encode and round-trip preserve message, failureKind, and envelope shape
 4. input codec -- decode rejects a non-error payload kind
 5. input codec -- decode rejects an unknown failureKind
 6. output codec -- encode and round-trip preserve failureKind and message via CoreErrorOutput from
 7. canonical envelope -- input codec envelope is byte-identical to legacy dsl-v1 error envelope
 8. registry resolution -- production factory contains core dot error
 9. registry resolution -- production factory registry is fresh per call and consistent across calls
10. capability declaration -- core error declares empty required capabilities
11. capability admission -- admission succeeds when the runtime exposes zero capabilities
12. typed outcome projection -- CoreErrorOutput outcome equals StepOutcome Failure of the same failure
13. fresh durable -- first execution of core dot error writes one terminal FAILED operation with USER kind
14. replay -- a previously FAILED core error ABORTS without re-running the handler
15. observability -- every core dot error run emits StepStarted StepFailed StepFinished trio with USER kind
16. failure preservation -- every FailureKind round-trips through the handler without remapping
17. real registry seam -- core error flows end-to-end through RegistryExecutionPreparation and RegistryExecutionBoundary coexecute
```

### 4.3 Replay-specific contract (NOT a copy of Echo's)

The Echo replay law is:
```text
fresh / no durable entry   → execute
existing SUCCEEDED         → SKIP (re-use)
```

The `core.error` replay law is:
```text
fresh / no durable entry             → execute (typed USER failure, terminal)
existing durable history (any status) → ABORT (handler MUST NOT execute;
                                       replay-abort surfaces as typed INFRASTRUCTURE failure
                                       with message "Replay aborted for '<opId>'")
```

`ReplayPolicy.NEVER` (`v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/ReplayPolicy.kt`)
plus `Effect.ABORTS_PIPELINE` (`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreErrorStep.kt`
descriptor) drives `DefaultEffectReplayPolicy.decide()` to return `ReplayDecision.ABORT`
on any prior durable entry.

The replay test asserts:
- First execution: 1 `StepFailed` with `FailureKind.USER`, message `"boom"`.
- Replay at same runId: typed `RunOutcome.Failure(failure)` with `FailureKind.INFRASTRUCTURE`,
  message contains `"Replay aborted"`.
- Handler-invocation invariant: count of `USER`-kind `StepFailed` events stays at 1 (handler
  was NOT invoked a second time).
- Replay-abort surface: exactly 1 `INFRASTRUCTURE`-kind `StepFailed` event with message
  containing `"Replay aborted"`.

The total `StepFailed` count goes from 1 → 2 across the two runs (handler run + replay-abort
typed carrier). That is correct: `StepExecutionBoundary.execute` always emits the lifecycle
events; what distinguishes "handler ran" from "replay aborted" is the `failureKind` and the
`message`, NOT the event count.

### 4.4 Real registry seam (test 17)

End-to-end production seam, no fabrication:

```kotlin
val registry = CoreStepRegistryFactory.registry()
val input = CoreErrorInput(failure.message, failure.kind)
val encoded = CoreErrorStep.definition.contract.inputCodec.encode(input)

val preparation = RegistryExecutionPreparation.prepare(
    registry = registry,
    key = CoreErrorStep.KEY,
    encodedInput = encoded,
    availableCapabilities = emptySet(),
)
val ready = assertInstanceOf(ExecutionPreparation.Ready::class.java, preparation)
val prepared = assertInstanceOf(PreparedRegistryExecution::class.java, ready.prepared)

val context = CanonicalRuntimeContext(
    opId = OpId("g7-error-seam", 0, 0),
    runId = "g7-error-seam",
    stageName = "build", stageIndex = 0, stepIndex = 0,
    shOptions = ShOptions.EMPTY,
    controlDirRoot = Files.createTempDirectory("g7-error-seam-"),
    eventSink = InMemoryEventStore(),
)
val result = RegistryExecutionBoundary.coexecute(prepared, context)

assertEquals(StepOutcome.Failure(failure), result.outcome)
```

This exercises the same seam that production `CanonicalDurableRunCoordinator` exercises
under `StructuralStepFamily.Registry` classification, with no mocks, stubs, or fabricated
`PreparedRegistryExecution`.

## 5. Evidence

```text
$ ./gradlew -p v2 :pipeline-application:test --tests 'ErrorStepContractSuiteTest'

TEST-dev.rubentxu.pipeline.v2.application.ErrorStepContractSuiteTest.xml
tests="17" skipped="0" failures="0" errors="0"
```

```text
$ ./gradlew -p v2 :pipeline-application:test --tests 'CoreErrorStepUnitTest'

tests="20" skipped="0" failures="0" errors="0"
```

```text
$ ./gradlew -p v2 :pipeline-application:test --tests 'CoreErrorRegistryPrimaryFitnessTest'

tests="14" skipped="0" failures="0" errors="0"
```

```text
$ ./gradlew -p v2 :pipeline-architecture-tests:test --tests 'S3ErrorLegacyRemovedFitnessTest'

tests="12" skipped="0" failures="0" errors="0"
```

Regression sanity (per user's "echo/sh still CERTIFIED" rule):

```text
$ ./gradlew -p v2 :pipeline-application:test --tests 'EchoStepContractSuiteTest'

tests="17" skipped="0" failures="0" errors="0"
```

```text
$ ./gradlew -p v2 :pipeline-architecture-tests:test --tests 'S3EchoLegacyRemovedFitnessTest'

tests="13" skipped="0" failures="0" errors="0"   (UP-TO-DATE — no source changes since last green)
```

## 6. Gate scoreboard (per user's directive)

```text
ErrorStepContractSuiteTest          17 PASS / 1 N.A. / 0 FAIL    ← G7 evidence
CoreErrorStepUnitTest               20/20                          ← G1 evidence (preserved)
CoreErrorRegistryPrimaryFitnessTest 14/14                          ← G5 evidence (preserved)
S3ErrorLegacyRemovedFitnessTest     12/12                          ← G6 evidence (preserved)

EchoStepContractSuiteTest           17/17                          ← S1 regression sanity
S3EchoLegacyRemovedFitnessTest      13/13                          ← S1 regression sanity

legacy counters                     11 / 11 / 11
StructuralFamily(core.error)        Registry                       ← StructuralFamilyResolver.classify
requiredCapabilities                emptySet()                     ← CoreErrorStep.definition.contract
effects                             ABORTS_PIPELINE                ← StepDescriptor
replayPolicy                        NEVER                          ← StepDescriptor
```

## 7. Production code touched in G7

**None.** G7 added only a new test file. The test file is the sole G7 deliverable.

```text
git status --porcelain
?? v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/ErrorStepContractSuiteTest.kt
```

No edits to:
- `CoreErrorStep.kt`
- `CoreErrorInput.kt` / `CoreErrorOutput.kt` / `CoreErrorCodec.kt`
- `CoreStepRegistryFactory.kt`
- `CanonicalCoreStepDecoder.kt`
- `CanonicalDurableRunCoordinator.kt`
- `CanonicalNodeDispatcher.kt` / `StructuralStepFamily.kt`
- `RegistryExecutionPreparation.kt` / `RegistryExecutionBoundary.kt`
- `LEGACY_PLUGIN_IDS` (still 11, unchanged)

## 8. State after G7

```text
REGISTERED         = true
REGISTRY_PRIMARY   = true
LEGACY_UNREACHABLE = true
LEGACY_REMOVED     = true
CONTRACT_SUITE     = PASS    ← G7 closed
CERTIFIED          = false   ← awaits G8 (real CLI scenario)
```

## 9. Hand-off to G8

G8 is the certification gate. It must demonstrate:
- Fresh execution of a real `core.error` invocation through the **production CLI** (installed
  distribution) with the **production durable spine** (`CanonicalDurableRunCoordinator`,
  `FileBasedRetryControlJournal`, on-disk journal).
- Replay of the same durable invocation through the production CLI with the same
  `--db` / `--control-root` — must NOT re-run the handler; must surface typed
  INFRASTRUCTURE replay-abort.
- Real `.pipeline.kts` scenario (e.g. `15-error.pipeline.kts`) executed via
  `./v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application run
  --db <path> --control-root <path> <script>` (positional script arg).

On G8 close:
```text
CERTIFIED = false
      ↓
CERTIFIED = true
```

S2-A1 fully closed; `core.error` is the second legacy key to reach CERTIFIED + LEGACY_REMOVED
after `core.echo` (S1) and `core.sh` (LB-02 S6.8).

Next slice: **S2-A2 = `core.sleep`**, LEGACY_PLUGIN_IDS 11 → 10. New cycle branch after
S2-A1 fully CERTIFIED.

---

**G7 closed.**
