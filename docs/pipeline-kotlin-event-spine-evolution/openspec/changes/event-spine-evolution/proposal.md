# Change: event-spine-evolution

## Why

The current event implementation provides local value but couples several responsibilities behind
`EventSink/EventStore`, while upcoming real-example verification and future M4/M6 consumers require a
stable identity/envelope, post-run history and detached live consumption.

## Outcomes

- typed ResourceRef and PipelineEventEnvelope;
- transport/storage-independent event ports;
- preserve and adapt existing local SQLite history;
- Event Harness over real examples;
- detached live relay proof with failure/resource isolation;
- CloudEvents-ready mapping without selecting a broker prematurely.

## Non-goals

Controller, Jenkins plugin, Cedar enforcement, NATS/Kafka production selection, generic companion SPI.

## Success

Success is measured by `UAT_EVT_REAL_EXAMPLES.md` and EVT-0..EVT-5 exit gates, not by new class count.
