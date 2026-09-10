# EVT-2 Delta Spec — Event history ports + local durable adapter

## R1 — Compatibility frontier
- WHEN a producer appends a DomainEvent via EventSink.append THEN the store assigns
  the sequence and an envelope is projected with the STORE-assigned sequence;
  producers construct no ResourceRef and no envelope.

## R2 — Ports and adapters
- EventPublisher / EventHistory / EventTail are the read/write ports; InMemory and
  SQLite adapters produce identical observable envelope sequences for identical input
  (contract parity suite).

## R3 — EventCursor
- Cursor = (runId, lastSequence), opaque token evt-cursor-v1:<runId>:<seq>;
  continuation is sequence > last, ordered by sequence; never timestamp-based
  (INC-021d); EventCursor is a distinct type from ReplayCursor.

## R4 — Pagination
- readAfter(run, cursor, limit) returns a page, nextCursor and hasMore;
  iterating pages yields exactly the full history with no duplicates and no gaps.

## R5 — Local queries (80/20)
- Supported filters: All / ByKind / BySource / BySubject / BySequenceRange.
  No query language, no SQL exposure, no arbitrary predicates.

## R6 — Restart durability
- Store instance A appends history and closes; instance B reopens and reads the
  same ordered history; cursor continuation works across the restart boundary.

## R7 — Completeness honesty
- History reads do NOT claim completeness; EventPage carries hasMore/nextCursor.
  Absence of RunFinished is never reported as "complete".

## R8 — CLI
- `pipeline events --db <path> <runId> [--kind K] [--subject CANONICAL]
  [--limit N] [--after-cursor TOKEN]` prints one JSON envelope per line plus a
  cursor token on stderr; the user inspects structured history without grepping logs.
