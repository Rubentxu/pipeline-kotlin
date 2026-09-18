# Design — Observation Stream data model and seams

Status: PROPOSED / implementation guidance, not frozen API.

The goal is to make implementation direction concrete without freezing premature class names. Types below illustrate ownership and laws.

## 1. Write-side semantic events

```kotlin
fun interface EventIngress {
    /** Fast producer call. Never renders, filters or talks to terminal/network. */
    fun offer(event: DomainEvent): EventIngressResult
}

sealed interface EventIngressResult {
    data object Accepted : EventIngressResult
    data class Degraded(val reason: ObservationDegradation) : EventIngressResult
}
```

The actual implementation may use a channel/JDK queue; specialized MPSC is benchmark-gated. No `DROP_OLDEST` for semantic events.

## 2. Single event writer

```kotlin
interface EventBatchWriter : AutoCloseable {
    fun start()
    suspend fun flush(barrier: EventFlushBarrier): EventFlushResult
}

sealed interface EventFlushBarrier {
    data class RunTerminal(val runId: RunId) : EventFlushBarrier
    data object Shutdown : EventFlushBarrier
}
```

The writer owns connection, prepared statement, batching and sequence assignment. Sequence is initialized from persisted state for reopened runs.

## 3. Console transcript sink

```kotlin
fun interface ConsoleTranscriptSink {
    suspend fun append(operation: OperationRef, chunk: OutputChunk): ConsoleAppendResult
}
```

Implementation requirements:

- process pump never calls renderer;
- chunk passes streaming secret redactor;
- append is buffered;
- no whole-output String/List;
- output `returnStdout` typed value remains separate.

A future optimization can expose byte arrays/slices to reduce copies, but only after profiler evidence and lifetime safety.

## 4. Console cursor

```kotlin
data class ConsoleCursor(
    val runId: RunId,
    val operation: OperationRef,
    val byteOffset: Long,
)
```

The token presented by CLI should be opaque/versioned even if internal representation is simple.

## 5. Observation record

Read-side projection only:

```kotlin
sealed interface ObservationRecord {
    data class Event(val envelope: PipelineEventEnvelope) : ObservationRecord
    data class Console(
        val operation: OperationRef,
        val offset: Long,
        val channel: ConsoleChannel,
        val bytes: ByteArray,
    ) : ObservationRecord
    data class Diagnostic(val diagnostic: CliDiagnostic) : ObservationRecord
}
```

Do not persist this union as a new authority. It is a presentation/read model.

## 6. Observation query ADT

Prefer a typed request over arbitrary expression strings:

```kotlin
data class ObservationQuery(
    val run: RunId,
    val stages: Set<StageSelector> = emptySet(),
    val steps: Set<StepSelector> = emptySet(),
    val eventKinds: Set<String> = emptySet(),
    val outcomes: Set<OutcomeSelector> = emptySet(),
    val channels: Set<ObservationChannel> = emptySet(),
    val textContains: String? = null,
    val eventCursor: EventCursor? = null,
    val consoleCursor: ConsoleCursor? = null,
    val limit: Int? = null,
)
```

Closed selectors can evolve as evidence appears. No `Map<String, Any?>` query state.

## 7. View policy

```kotlin
sealed interface ObservationView {
    data object Normal : ObservationView
    data object Events : ObservationView
    data object Full : ObservationView
    data object Console : ObservationView
    data object Quiet : ObservationView
}
```

Projection is pure where possible:

```text
records + view + query -> selected records -> renderer
```

Normal failure-tail retrieval is an explicit read-side lookup triggered by a failure observation, not a producer mutation.

## 8. Renderer

```kotlin
interface ObservationRenderer {
    suspend fun render(record: ObservationRecord)
    suspend fun finish(summary: RunSummary)
}
```

Renderer implementation owns text/color/JSONL. It is isolated in its own consumption scope. Its cancellation cannot cancel pipeline execution; the CLI parent can still decide overall process lifecycle after execution terminal state.

## 9. Wakeup protocol

Local live source can expose a cheap signal such as:

```kotlin
sealed interface ObservationWakeup {
    data class EventsCommitted(val runId: RunId, val lastSequence: Long) : ObservationWakeup
    data class ConsoleAdvanced(val operation: OperationRef, val endOffset: Long) : ObservationWakeup
}
```

Wakeups carry no irreplaceable payload. On loss/reconnect, reader uses durable cursors.

## 10. Backpressure laws

- semantic EventIngress: bounded; no silent drop; saturation is typed degradation/fallback;
- console durable append: must keep up with process drain under supported throughput; benchmark-gated;
- wakeup queue: may coalesce because durable data remains authority;
- renderer queue: may lag/coalesce console notifications; cannot backpressure writer/pump;
- JSON output pipe: renderer stalls independently while execution continues.

## 11. Copy minimization

Optimization order:

1. remove duplicate serialization/persistence;
2. batch DB writes;
3. remove avoidable coroutine blocking bridges;
4. buffer file writes;
5. only then profile byte-array copies.

Avoid unsafe zero-copy tricks before these higher-value fixes.
