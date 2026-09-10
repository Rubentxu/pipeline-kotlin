# EVT-2 Design — Event history ports + local durable adapter

## Ports (inner, hexagonal)
`EventPublisher` / `EventHistory` / `EventTail` live in pipeline-events identity
package; domain does NOT depend on them (direction inward preserved; fitness-verified).

## Adapters
- InMemoryEventSink + EventHistoryReader: shared read implementation over EventSink.
- SQLite journal: read path decodes payload → projects envelope → sequence from row.
  No schema migration, no SQL exposed beyond the adapter.

## Typed contracts
- EventQuery is a sealed ADT (All/ByKind/BySource/BySubject/BySequenceRange).
- EventPage { envelopes, nextCursor?, hasMore } — never claims completeness.
- EventCursor opaque value; codec fail-closed on malformed tokens; no temporal component.

## Sequence authority
Store is the single sequence authority. EnvelopeProjectingEventSink re-reads the
assigned sequence after append and re-stamps before projection (SequenceAssigner,
exhaustive over 44 kinds). Non-zero producer sequences remain honored by the store.

## Total projection
EnvelopeProjector is exhaustive over all 44 event kinds; subject derived typed:
stage+step→STEP, stage-only→STAGE, stepIndex without stage→RUN, else→RUN.
UnprojectableEventException retained for future kinds (fail-closed).

## Zero semantic diff
Journal/fingerprint/replay untouched; producers untouched (delta: only new identity
files, projector rewrite, CLI wiring, tests).
