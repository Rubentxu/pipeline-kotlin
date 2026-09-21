# Change: event-spine-evolution

## Why

At `d0ccf4b5`, CTX-P4-EX already proves a 10/10 real-CLI examples gate and scenario-specific event assertions for 07–10. The current event implementation provides local value but couples several responsibilities behind
`EventSink/EventStore`; the next need is to generalize that proven verification and serve future M4/M6 consumers through a
stable identity/envelope, post-run history and detached live consumption.

## Outcomes

- typed ResourceRef and PipelineEventEnvelope;
- transport/storage-independent event ports;
- preserve and adapt existing local SQLite history;
- Event Harness that preserves and generalizes the existing P4-EX real-example oracle;
- detached live relay proof with failure/resource isolation;
- CloudEvents-ready mapping without selecting a broker prematurely.

## Non-goals

Controller, Jenkins plugin, Cedar enforcement, NATS/Kafka production selection, generic companion SPI.

## Success

Success is measured by `UAT_EVT_REAL_EXAMPLES.md` and EVT-0..EVT-5 exit gates, not by new class count.
