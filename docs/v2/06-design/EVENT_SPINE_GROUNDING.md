# Event Spine Grounding — local-first history, live consumers, future controller

Status: PROPOSED  
Program: EVT

## 1. Why this exists

`pipeline-kotlin` already emits typed events and has a local SQLite implementation, but the current
abstraction must not make SQLite, console logs, Jenkins or any remote transport part of the execution
model. The evolution is justified only if it solves concrete problems now and preserves future goals.

### Problems we must solve now

- real examples can be green while an invalid event lifecycle slips through;
- event assertions are duplicated across UATs;
- event history should be compact, structured and queryable rather than embedded in large logs;
- local runs should keep historical traces for debugging/reverification;
- an event verifier must be able to fail independently from the pipeline execution.

### Future goals that must not force a rewrite

- live Stage/Step/Parallel updates in the future Jenkins plugin;
- remote workers sending live execution state to a controller;
- reconnect/replay after transient disconnection;
- Cedar policy audit over live or historical runs;
- CloudEvents interoperability at system boundaries.

## 2. Architectural law

> The Event Spine is a boundary, not a backend.

The engine publishes typed pipeline facts. Storage, live relay, CloudEvents, controller integration and
verification are adapters/consumers. Observers are never execution authorities.

```text
Canonical execution
      |
      v
DomainEvent
      |
      v
PipelineEventEnvelope
      |
      +------> local EventLog (history/replay)
      |              |
      |              +------> POST_RUN consumers
      |              |
      |              +------> live cursor/tail
      |                            |
      |                            v
      |                     detached EventRelay
      |                            |
      |                            +--> controller/Jenkins (future)
      |                            +--> live Cedar audit (future)
      |
      +------> execution continues independently of consumer health
```

## 3. Event envelope

The domain envelope must be stable enough to map to CloudEvents without depending on a CloudEvents SDK:

```kotlin
data class PipelineEventEnvelope(
    val id: EventId,
    val source: ResourceRef,       // normally the Run or emitting runtime context
    val subject: ResourceRef?,     // Stage/Step/Operation/Branch affected
    val runId: RunId,
    val sequence: Long,            // monotonic inside the run/event source contract
    val type: EventType,
    val schemaVersion: EventSchemaVersion,
    val occurredAt: Instant,
    val causation: EventRef?,
    val correlationId: CorrelationId?,
    val payload: DomainEvent,
)
```

`EventRef` is `(source: ResourceRef, eventId: EventId)`. An event is an occurrence/fact, not automatically
a resource. Resource identity belongs to `source`/`subject`; the event has its own `EventId`.

CloudEvents mapping later:

| pipeline-kotlin | CloudEvents |
|---|---|
| `id` | `id` |
| `source.toUriReference()` | `source` |
| `subject` | `subject` |
| `type` | `type` |
| `occurredAt` | `time` |
| `schemaVersion` | `dataschema` or versioned `type` strategy |
| `sequence`, `causation`, `correlationId` | extension attributes |
| `payload` | `data` |

CloudEvents defines event interoperability, not broker durability or delivery guarantees.

## 4. Ports, not SQLite

Split write/read/tail responsibilities instead of growing `EventSink : EventStore` forever:

```kotlin
fun interface EventPublisher {
    suspend fun publish(event: PipelineEventEnvelope): PublishResult
}

interface EventHistory {
    suspend fun readRun(runId: RunId): List<PipelineEventEnvelope>
}

interface EventTail {
    fun tail(runId: RunId, after: EventCursor? = null): Flow<PipelineEventEnvelope>
}
```

A local adapter may implement all three using SQLite today. That is an implementation decision, not a
domain constraint. A future adapter can use a segmented log, NATS JetStream, Kafka or another backend.

`OperationJournal` remains durable execution truth. `EventHistory` remains observable history/projection.
They may share a physical database locally, but they are separate ports and separate authorities.

## 5. Two consumption timings from one history

### POST_RUN

Used for Event Harness, event/journal consistency, replay/metamorphic checks and mutation verification.
It consumes zero verifier CPU while the pipeline is executing.

```text
run -> RunFinished -> read complete event history -> verify -> AcceptanceOutcome
```

The verifier may fail `AcceptanceOutcome`, but it must not rewrite the already-computed `PipelineOutcome`.

### LIVE_DETACHED

Used for future Jenkins dashboards, controller status, UI, metrics and Cedar shadow audit.

```text
local EventLog -> cursor/tail -> detached relay -> remote/local consumer
```

The relay/consumer is isolated from the execution process. If it crashes, stalls or disconnects, the
pipeline continues. Reconnect resumes from a cursor when the selected backend supports replay.

## 6. Isolation and resource budgets

Do **not** implement production live observers as `launch(Dispatchers.Default)` inside the coordinator.
That shares heap/scheduler and turns an observer into a resource competitor.

Target isolation for live consumers:

- separate process for the relay/observer host when running live;
- no shared coroutine scope or cancellation tree with pipeline execution;
- bounded memory/queues;
- low-priority/bounded cgroup when supported by the OS;
- consumer failure becomes an Assessment/health signal, never a pipeline exception;
- slow consumer lag is observable and recoverable by cursor/replay.

Linux cgroup v2 can later enforce CPU/memory bounds (`cpu.weight`, `cpu.max`, `memory.high`, `memory.max`).
Do not expose those budgets as arbitrary values controlled by `.pipeline.kts`; runtime/platform owns them.

## 7. Local durable first, live relay second

The 80/20 runtime path is:

```text
execution -> compact local event append -> execution continues
                      |
                      +-> history
                      +-> independent relay/tail
```

Network latency/retries must never be on the event publication critical path for ordinary observability.
If the controller/Jenkins is unavailable, local execution continues and the relay catches up from the
last acknowledged cursor.

Event persistence failure is not silently ignored: the run gets an **ObservationStatus** (`COMPLETE`,
`DEGRADED`, `INCOMPLETE`) separate from `PipelineOutcome`. Verification profiles can fail acceptance
when the trace is incomplete without pretending that the pipeline effect itself did not occur.

## 8. Output is not event history

Never put shell stdout/stderr chunks into DomainEvents just to stream them remotely.

```text
Domain Event Plane              Execution Output Plane
------------------              ----------------------
StageStarted                    stdout/stderr/transcript
StepStarted                     paged/streamed separately
RetryAttemptStarted             potentially high volume
StepSucceeded                   independent QoS
```

Both planes can share ResourceRef/correlation later, but they must have different storage, transport and
backpressure strategies.

## 9. DSL activation

Prefer a semantically narrow declaration over a generic plugin system:

```kotlin
pipeline {
    observe(eventsHarness())
    observe(cedarAudit())       // future, audit/shadow only

    stages { ... }
}
```

`observe(...)` is declarative and creates an `ObservationPlan` separate from `CompiledPipeline`.
It MUST NOT change OpIds, execution fingerprints, replay decisions, journal facts or effect ordering.

Convention-based files:

```text
build.pipeline.kts
build.events.yaml
build.policies.cedar
```

`eventsHarness()` can discover `build.events.yaml`; `cedarAudit()` can discover
`build.policies.cedar`. Explicit overrides remain possible.

Mandatory organization/platform guardrails are **not** enabled by a line developers can delete; they are
injected by runtime/control-plane policy configuration.

## 10. What we deliberately do not build yet

- generic Companion/Observer plugin SPI;
- NATS or Kafka as mandatory dependencies;
- synchronous CloudEvents network publishing from the coordinator;
- Jenkins controller/plugin implementation;
- controller command protocol;
- Cedar enforcement;
- generalized LTL/CEP engine;
- DomainEvents for console output.

Extract shared extension infrastructure only after at least EventHarness + EventRelay + CedarAudit expose
real common lifecycle needs.

## 11. Grounding gates

No architecture is accepted merely because it looks extensible. EVT must prove:

1. a real local `.pipeline.kts` leaves queryable structured history;
2. a live tail observes `StageStarted` before `RunFinished`;
3. killing the live subscriber does not fail/cancel the pipeline;
4. reconnect from cursor continues without semantic duplicates;
5. the same history can be verified post-run by the Event Harness;
6. no observer/network code appears in `CanonicalDurableRunCoordinator`;
7. event history and `OperationJournal` remain different authorities.
