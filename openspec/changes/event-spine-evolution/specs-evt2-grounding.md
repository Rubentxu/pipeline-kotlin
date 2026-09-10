# EVT-2 Grounding Characterization (pre-production evidence)

Base: 8a681d93. Cycle: p-733fb505b5a6bd2d/evt-2-event-history-ports.

## Q1 — Where does DomainEvent→Envelope happen without touching 45 producers?
At composition (Main.kt): wrap the existing EventSink instance in an
`EnvelopeProjectingEventSink : EventSink` decorator. `append(event)` delegates to the
inner store (store assigns sequence), then projects `event.copy(sequence=assigned)`
via EnvelopeProjector and publishes the envelope to `EventPublisher`. Producers keep
calling `EventSink.append(DomainEvent)`. Zero producer edits.

## Q2 — Can SQLite reconstruct envelopes without schema migration?
YES with one caveat. Rows store (kind, run_id, sequence, occurred_at, payload JSON)
and JsonEventLog.decodeEvent covers all 44 kinds. Reconstruction = decode payload →
EnvelopeProjector.project → override sequence from the row (store authority).
CAVEAT/COUNTEREXAMPLE: EnvelopeProjector currently handles only 12/44 kinds and
fails closed (UnprojectableEventException). History of a real run would break.
RESOLUTION: extend EnvelopeProjector with typed derivation rules for all 44 kinds,
grouped by available identity fields (stageIndex+stepIndex / stepIndex only /
run-level). No schema change. No migration.

## Q3 — Sequence authority before/after the new frontier?
Store assigns: per-run AtomicLong at append when event.sequence==0
(SqliteEventStore.append L120-140; InMemoryEventStore mirrors it). After EVT-2 the
decorator projects the event WITH the store-assigned sequence. One observable
authority: the store/boundary. Envelope.sequence = projection.

## Q4 — Can EventHistory/EventTail be implemented over both stores without divergent semantics?
YES: both stores expose `eventsFor(runId): Sequence<DomainEvent>` and both assign
sequences identically. History/tail adapters read DomainEvents, project, filter in
Kotlin (local volume: fine per EVT-0 volumes). Contract suite proves parity.
Optimization trigger (recorded, not built): if local volume grows, push kind/subject
filtering into SQL with an index on (run_id, sequence, kind) + subject columns.
This is a future optimization, NOT schema evolution now.

## Q5 — Minimal Main/CLI change for history?
Add `events` command: `pipeline events --db <path> <runId> [--kind K] [--limit N]
[--after-cursor C] [--source/--subject REF]`. Reuses existing db parsing; prints
envelopes as JSON lines to stdout (history ≠ console transcript: reading history is
a query command, its own stdout is the CLI's output, not a DomainEvent stream).

## Cursor decision
EventCursor = opaque token carrying (runId, lastSequence). Continuation = sequence >
lastSequence, ordered by sequence. NOT timestamp-based (INC-021d). Distinct from
ReplayCursor; no shared type, no shared storage.
