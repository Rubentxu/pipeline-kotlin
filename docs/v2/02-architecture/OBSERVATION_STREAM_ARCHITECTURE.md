# Observation Stream Architecture — local, durable, non-blocking

Status: PROPOSED

## 1. Core invariant

**Ningún consumidor de observabilidad (renderer, terminal, pipe, agente, IDE) puede ejercer backpressure sobre la ejecución del pipeline.**

Esto no elimina el coste intrínseco de persistir hechos/transcript; elimina la dependencia entre velocidad del consumidor y velocidad del execution plane.

## 2. Authorities

```text
OperationJournal        = durable execution/control authority
DomainEvents            = semantic observation facts
ConsoleTranscript       = high-volume observable process output
TypedStepValue          = return value used by program
Diagnostics             = compile/config/product diagnostics
ObservationStream       = read-side projection of the above
```

No se unifican físicamente por conveniencia.

## 3. Execution plane vs observation plane

```text
                         EXECUTION PLANE
                               │
               ┌───────────────┴───────────────┐
               │                               │
          DomainEvent                     OutputChunk
               │                               │
               ▼                               ▼
       EventIngress                     StreamingRedactor
               │                               │
               ▼                               ▼
       bounded single-writer             TranscriptWriter
               │                               │
               ▼                               ▼
       SQLite/WAL history                  console.log
               │                               │
               └───────────────┬───────────────┘
                               │ cursor/offset + wakeups
───────────────────────────────┼──────────────────────────
                         OBSERVATION PLANE
                               │
                        ObservationReader
                               │
                   filters / projections / tail
                               │
                   text / jsonl / json renderer
                               │
                       terminal / agent / IDE
```

## 4. Domain event writer

Target design:

- one long-lived SQLite writer connection per local run database;
- prepared statement reuse;
- bounded MPSC ingress;
- micro-batching by count or short time window;
- one transaction per batch;
- WAL retained;
- sequence assignment at the event-store boundary;
- sequence resumes from durable `MAX(sequence)` for an existing run; restart never reuses lower sequence values;
- terminal flush barrier on `RunFinished` and orderly process shutdown;
- no event is silently discarded.

Queue saturation is an exceptional observability degradation, not normal flow. The implementation must expose it as typed health/ObservationStatus and have an explicit fallback/fail policy; never hide loss.

## 5. Console stream

High-volume stdout/stderr must not become one DomainEvent per line/chunk and must not be accumulated in memory.

The process pump:

1. reads bounded chunks;
2. redacts streaming secrets across chunk boundaries;
3. appends bytes to a buffered durable transcript;
4. emits an optional cheap wakeup/span hint;
5. immediately continues draining the process.

A renderer never sits in this chain.

### Cursor

Console consumers use an offset-like durable cursor:

```text
ConsoleCursor(runId, operationId, byteOffset)
```

Event consumers use existing sequence cursor semantics:

```text
EventCursor(runId, lastSequence)
```

They are intentionally different authorities.

## 6. Wakeups are hints, durable storage is authority

Live rendering can receive in-memory notifications to avoid polling latency. Notifications can be coalesced and, for console, even dropped when the durable transcript is intact. A gap is recovered by reading from last cursor/offset.

Event notifications must only advertise committed/persisted sequence or otherwise carry an explicit uncommitted status. The simplest first implementation publishes after batch commit.

## 7. Fast path / recovery path

```text
FAST: committed item -> notification -> renderer
RECOVERY: gap/reconnect -> readAfter(cursor/offset) -> catch up
```

This prepares future controller/Jenkins integration without connecting remote transport to the coordinator.

## 8. Ordering

- DomainEvents: total order only by store-assigned sequence within a run.
- Console transcript: presentation order by transcript append order.
- Event-vs-console: no fabricated causal total order. A `full` view interleaves for UX but marks it as presentation ordering.
- Parallel branches retain their typed identities; timestamp alone is never continuation authority.

## 9. Output duplication burn-down

`EchoOutputCaptured(content)` is acceptable for semantic `echo` text, but process output from `sh` must migrate out of high-volume DomainEvent payloads. For shell/process execution, events should carry lifecycle + bounded metadata/reference (bytes/digest/operation ref when useful) while the transcript owns the bytes.

Compatibility tests must prove the migration before removing any existing event assertions.

## 10. No speculative distributed bus

LPR intentionally does not introduce Kafka/NATS/Reactive observer SPI. Local-first primitives are sufficient:

- bounded queue;
- single writer;
- batch transaction;
- append-only transcript;
- cursor/offset;
- lightweight wakeup.

EVT-4 remains the place to prove a detached live relay when remote topology becomes a product requirement.
