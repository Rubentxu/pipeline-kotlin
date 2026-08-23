# `pipeline-events-spine` Specification

## Purpose

Observable contract for the V2 event spine (`v2/pipeline-events`).
Public types SHALL NOT import `kotlin.script.experimental.*`
(F-ARCH-003 containment).

## Domain Language

- `DomainEvent` — sealed: `RunStarted`, `CompilationStarted`,
  `CompilationFinished`, `RunFinished`. Carries `eventId`, `runId`,
  `sequence` (monotonic per run, starts at 1), `kind`, `occurredAt`.
- `EventStore` — append-only: `append(event)` +
  `eventsFor(runId): Sequence<DomainEvent>`. No mutating ops.
- `InMemoryEventStore` — production impl (CLI + tests).
- `SqliteEventStore` — JDK 21 stdlib `java.sql` reference.
- `EventSink` — appender façade; `NullEventSink` when no store.
- `PipelineRun` — emits `RunStarted`/`RunFinished`.

## Requirements

### Requirement: DomainEvent sealed hierarchy

`DomainEvent` SHALL be sealed with four variants (`RunStarted`,
`CompilationStarted`, `CompilationFinished`, `RunFinished`). Each
SHALL carry `eventId`, `runId`, `sequence: Long`, `kind`,
`occurredAt: Instant`.

#### Scenario: emit RunStarted with monotonic sequence

- GIVEN an `InMemoryEventStore` + fresh `runId`
- WHEN `RunStarted` is appended
- THEN `sequence == 1` AND `kind == "RunStarted"` AND `eventId`
  non-empty

### Requirement: EventStore append-only contract

`EventStore` SHALL expose `append(event)` and
`eventsFor(runId): Sequence<DomainEvent>`. No delete, mutate, or
reorder. `append` SHALL assign `sequence` monotonically per `runId`.

#### Scenario: append assigns monotonic sequence per run

- GIVEN an empty `InMemoryEventStore`
- WHEN three events are appended under one `runId`
- THEN `eventsFor(runId)` returns them with `sequence` `1`/`2`/`3`

#### Scenario: eventsFor is isolated per runId

- GIVEN events for `runA` and `runB`
- WHEN `eventsFor("runA")` runs
- THEN it returns only `runA`'s events in `sequence` order

### Requirement: InMemoryEventStore

The system SHALL ship `InMemoryEventStore` as a thread-safe
implementation — default CLI + test binding this cycle.

#### Scenario: concurrent append is safe

- GIVEN an `InMemoryEventStore`
- WHEN N threads each append one event under one `runId`
- THEN all N events are present AND every `sequence` is unique

### Requirement: SqliteEventStore reference

The system SHALL ship `SqliteEventStore` using JDK 21 stdlib
`java.sql` (no external driver), exercised by a JUnit round-trip.

#### Scenario: SQLite round-trip preserves order

- GIVEN a temp SQLite file
- WHEN four events are appended under one `runId`
- THEN a fresh `SqliteEventStore` at the same file returns them in
  original order

### Requirement: Scripting host emits events via injected sink

`Kotlin24ScriptingHost` SHALL accept an injected `EventSink`
(nullable; default `NullEventSink`). On every `compile(...)` it
SHALL emit `CompilationStarted` before delegating and
`CompilationFinished` after — success and failure alike.

#### Scenario: happy compile emits start + finished

- GIVEN a host bound to a recording `EventSink`
- WHEN `compile(definition)` succeeds
- THEN the sink receives `CompilationStarted` then
  `CompilationFinished` referencing the result's `cacheKey`

#### Scenario: failed compile still emits finished with diagnostics

- GIVEN a host bound to a recording `EventSink`
- WHEN `compile(definition)` fails
- THEN the sink receives `CompilationStarted`/`Finished` with
  non-empty `diagnostics`

### Requirement: CompilationFinished payload carries cacheKey.version

`CompilationFinished` SHALL carry `cacheKey` as an object with
`value: String` (64-char hex) and `version: String` (`"v1"`).

#### Scenario: every CompilationFinished has version v1

- GIVEN a host bound to a recording `EventSink`
- WHEN two definitions with different classpaths compile
- THEN both `CompilationFinished` carry `cacheKey.version == "v1"`
  AND `cacheKey.value` 64-char hex

### Requirement: CLI prints JSON event log

`pipeline validate` / `pipeline run` SHALL wrap execution in a
`PipelineRun` emitting `RunStarted`/`RunFinished`. The sequence
SHALL be serialised as a JSON array on stdout, deterministic across
runs for the same inputs.

#### Scenario: pipeline run prints valid JSON array

- GIVEN `hello.pipeline.kts`
- WHEN the CLI runs the script
- THEN stdout is one line `[…]` AND parses as a JSON array of ≥ 4

### Requirement: F-ARCH-003 containment of pipeline-events

`v2/pipeline-events/src/main/kotlin` SHALL NOT import
`kotlin.script.experimental.*`. The scanner allowlist SHALL extend
to `/pipeline-events/`.

#### Scenario: scanner finds no experimental imports

- GIVEN the post-R2 V2 tree including `pipeline-events`
- WHEN F-ARCH-003 runs `SourceScanner.findImports` with the updated
  allowlist
- THEN no finding is reported

## Out of Scope

- Worker runtime + durable replay (M3).
- Step / Stage events — M2 DSL + Step SDK.
- Protobuf envelope (M4); JSON is local-CLI only.
- Sequence gaps, fencing, ACK — M3+.