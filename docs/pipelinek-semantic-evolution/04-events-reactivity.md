# Event Spine and Reactivity

## 1. Events are part of semantics

A supported Step or directive is incomplete if its semantic effect cannot be observed externally.

Events are not logging. They are the public reactive projection of durable state changes.

## 2. Event envelope

Introduce/normalize a stable envelope independent from concrete payload families:

```kotlin
data class EventEnvelope(
    val eventId: EventId,
    val runId: RunId,
    val stageId: StageId?,
    val operationId: OperationId?,
    val causationId: EventId?,
    val correlationId: CorrelationId,
    val sequence: EventSequence,
    val occurredAt: Instant,
    val producer: EventProducer,
    val eventKey: EventKey,
    val schemaVersion: EventSchemaVersion,
    val payload: EncodedEventValue,
)
```

`occurredAt` is observational and must never be used as replay identity.

## 3. Open event registry

Core lifecycle events and plugin events share one extension model:

```kotlin
data class EventDefinition<E : Any>(
    val key: EventKey,
    val schemaVersion: EventSchemaVersion,
    val codec: EventCodec<E>,
    val category: EventCategory,
)
```

Plugins register definitions through `EventDefinitionContributor`.

No `when(pluginEventKey)` in core.

## 4. Event categories

```kotlin
sealed interface EventCategory {
    data object Lifecycle : EventCategory
    data object Observation : EventCategory
    data object Decision : EventCategory
    data object Effect : EventCategory
    data object Security : EventCategory
    data object Reaction : EventCategory
}
```

Examples:

- `StageGuardEvaluated` -> Decision
- `StageSkipped` -> Lifecycle
- `AgentLeaseAcquired` -> Effect/Observation
- `PostConditionSelected` -> Decision
- `DirEntered` -> Effect
- `CredentialLeaseBound` -> Security (without secret material)

## 5. Single emission authority

For each event family, exactly one layer owns emission.

Examples:

- process transcript/output events -> shell execution substrate;
- stage lifecycle -> coordinator/stage interpreter;
- directive evaluation -> directive interpreter;
- plugin-specific semantic event -> plugin handler via declared event capability **only if** no lower substrate already owns it.

Certification must reject double emission.

## 6. Reactive consumers

Separate observation from control.

### 6.1 Observers

Read-only consumers:

```kotlin
fun interface EventObserver<E : Any> {
    suspend fun on(event: E)
}
```

Examples:

- CLI renderer;
- JSONL projection;
- metrics;
- IDE/dashboard;
- external agent monitor.

Observers cannot change run outcome.

### 6.2 Reactors

Control-affecting reactions must be explicit durable programs:

```kotlin
interface ReactorDefinition<E : Any, C : Any> {
    val key: ReactorKey
    val event: EventDefinition<E>
    val commandCodec: Codec<C>
    fun decide(event: E, state: ReactorState): ReactorDecision<C>
}

sealed interface ReactorDecision<out C> {
    data object Ignore : ReactorDecision<Nothing>
    data class EmitCommand<C>(val command: C) : ReactorDecision<C>
}
```

A reactor never performs effects in `decide`; commands go through the normal admission/interpreter path.

## 7. Delivery semantics

Do not claim exactly-once external delivery.

Use:

```text
append once to authoritative event store
+ at-least-once consumer delivery
+ durable consumer offset
+ idempotency/reaction key
```

Reaction identity:

```text
ReactionId = hash(reactorKey, reactorVersion, eventId)
```

A replayed/duplicated delivery cannot trigger duplicate durable commands.

## 8. Backpressure

- event store append is on the durable critical path;
- external observers consume asynchronously through bounded channels/tailing;
- a slow dashboard/agent cannot block Step execution;
- synchronous policy reactions must be declared as directives/policies, not hidden subscribers.

## 9. Replay

Events must distinguish:

- original durable facts already stored;
- replay projection to a new observer;
- newly emitted facts from rerun.

Do not emit duplicate semantic facts merely because the process restarted unless the Step replay policy explicitly reruns the effect and the event represents that new effect.

## 10. External agent workflows

Agents consume events through stable projections:

```text
PipelineK execution
-> durable EventStore
-> JSONL / local API / MCP adapter
-> agent
```

The agent observes typed run/stage/step/directive outcomes; it does not scrape console text to infer control state.
