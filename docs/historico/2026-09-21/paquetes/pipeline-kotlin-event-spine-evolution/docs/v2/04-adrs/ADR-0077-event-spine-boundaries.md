# ADR-0077: Event Spine as transport-agnostic boundary

Status: PROPOSED  
Date: 2026-09-10

## Context

V2 already has typed DomainEvents and local `EventSink`/`SqliteEventStore`. Future requirements include
post-run verification, local execution history, live Jenkins/controller updates and remote workers.
Binding the model to SQLite or directly to a network/broker would make one deployment topology the architecture.

## Decision

1. Domain events are wrapped in a stable `PipelineEventEnvelope` before crossing the event boundary.
2. Event publication, history and live-tail/replay capabilities are distinct ports.
3. SQLite remains a valid local adapter; it is not the Event Spine contract.
4. The default local-first path persists compact structured events locally and relays them independently.
5. Network/controller latency is never on the ordinary observability critical path.
6. POST_RUN and LIVE_DETACHED consumers use the same event history/identity model.
7. Observer failures/lag never cancel or fail pipeline execution. They produce observation health/assessments.
8. Execution output (stdout/stderr/transcript) stays on a separate channel.
9. CloudEvents is the target interoperability mapping at external boundaries, not a domain dependency.

## Consequences

- Enables local history now and live controller/Jenkins later without changing DomainEvents.
- Makes consumer reconnect/cursor semantics testable before selecting NATS/Kafka/etc.
- Requires explicit trace completeness/observation health semantics.
- A future transport spike can compare NATS JetStream, HTTP CloudEvents and alternatives without touching the coordinator.

## Rejected

- events encoded into console logs;
- synchronous network publish from coordinator;
- `Dispatchers.Default` live observers inside execution process as production isolation model;
- mandatory NATS/Kafka now;
- using EventStore as durable execution authority.
