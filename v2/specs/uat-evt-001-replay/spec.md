# `uat-evt-001-replay` Specification

## Purpose

Observable contract for the `UatEvt001ReplayTest` JUnit scenario
that closes M1's second exit criterion: a `.pipeline.kts` run is
reconstructible from its event log. The test invokes the CLI, captures
its stdout JSON event log, re-parses it, and asserts that the
reconstructed timeline equals the originally emitted timeline.

## Domain Language

- `UAT-EVT-001` — user-acceptance test for M1 second exit criterion
  (`docs/v2/05-roadmap/ROADMAP.md` L52: "UAT-EVT-001: run reconstruible
  desde event log").
- `JsonEventLog` — array of `DomainEvent` serialised to JSON. The CLI
  writes one line, one array. The test re-parses it byte-for-byte.
- `Timeline equality` — two event sequences are equal iff they have
  the same length, the same event types in the same order, and the
  same scalar fields per event (kind, runId, sequence, cacheKey.value,
  cacheKey.version, plus diagnostic count for `CompilationFinished`).
- `Fixture` — `hello.pipeline.kts` from the M1-R1 cycle (seed-level
  pipeline DSL). The test uses the same fixture so the M1-R1 +
  M1-R2 evidence chain is reproducible.

## Requirements

### Requirement: CLI invocation produces a JSON event log

`UatEvt001ReplayTest` SHALL invoke the CLI command that runs
`hello.pipeline.kts` as a subprocess and SHALL capture its stdout.
The captured stdout SHALL be a single line whose first character is
`[` and whose last character is `]`, and SHALL parse as a JSON
array.

#### Scenario: CLI run emits a parseable JSON array

- GIVEN the CLI installed via the V2 Gradle build (`./gradlew -p v2
  :pipeline-application:installDist` or equivalent)
- WHEN `UatEvt001ReplayTest` invokes the CLI with `hello.pipeline.kts`
- THEN stdout is captured
- AND the first non-whitespace character is `[`
- AND the captured payload parses with the JSON parser without
  exception

### Requirement: Re-parsing the log yields the same timeline

`UatEvt001ReplayTest` SHALL re-parse the captured JSON array into a
`List<DomainEvent>` (via the V2 event spine's canonical JSON
deserialiser) and SHALL assert it equals the originally emitted
timeline element-wise (same `kind`, `runId`, `sequence`, scalar
fields, and `cacheKey` when present).

#### Scenario: re-parsed timeline equals original

- GIVEN a captured JSON log containing 4 events
  (`RunStarted`, `CompilationStarted`, `CompilationFinished`,
  `RunFinished`)
- WHEN the test deserialises the log
- THEN the deserialised list has size `4`
- AND `events[0].kind == "RunStarted"`
- AND `events[1].kind == "CompilationStarted"`
- AND `events[2].kind == "CompilationFinished"`
- AND `events[3].kind == "RunFinished"`
- AND `events[2].cacheKey.version == "v1"`
- AND `events[2].cacheKey.value` is 64-char hex

### Requirement: Replay equality is stable across two runs

The test SHALL execute the CLI twice with the same input and SHALL
assert the two re-parsed timelines are structurally equal (same
event types in the same order with the same scalar fields, ignoring
`eventId` and `occurredAt` which are inherently non-deterministic).
The test SHALL pass on both runs.

#### Scenario: two CLI invocations yield equal timelines

- GIVEN the CLI invocation described above
- AND a second CLI invocation with the same arguments
- WHEN both invocations are replayed into event lists
- THEN the two lists have the same length
- AND for each index `i`, the two events share `kind`, `runId`,
  `sequence`, and `cacheKey` fields
- AND the test passes

## Out of Scope

- Cross-run correlation, causation, or deduplication — local CLI runs
  only.
- Replay with side-effects (running a second time and asserting cache
  hits) — that's M3 durable replay.
- CLI argument variations (different flags, different fixtures) —
  M1-R2 covers the canonical happy path; M3 covers the matrix.
- Visual / interactive UAT — this is a JUnit executable scenario,
  not a manual run.