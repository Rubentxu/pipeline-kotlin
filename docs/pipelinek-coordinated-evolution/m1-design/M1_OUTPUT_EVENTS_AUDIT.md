# CRIC-M1 — Output/Event plane audit (input to M1 design)

**Status:** read-only audit, completed before any M1 code is written.
**Worktree:** `pk-cric-m1` (branch `feat/cric-m1-output-events-live`) at `91579c66`.
**Authoritative scope:** audit only; the design that consumes this report is
`M1_FOLLOW_DESIGN.md` in this directory.

## Audit questions and findings

The full audit is summarised below. The seven questions match the agent
brief; concrete file paths and line numbers were inspected read-only.

### Q1 — Output Plane read API surface

- `v2/pipeline-output/src/main/kotlin/.../output/OutputCursor.kt:24-31`:
  `@JvmInline value class OutputStreamId(val value: String)`.
- `v2/pipeline-output/src/main/kotlin/.../output/OutputCursor.kt:57-105`:
  `OutputCursor(stream, committedOffset)`; wire token `out-cursor-v1:`.
- `v2/pipeline-output/src/main/kotlin/.../output/OutputCursor.kt:121-157`:
  `OutputPage(bytes, stream, from, next, committedEnd)`; `next == null`
  means "right now nothing more", not "stream finished".
- `v2/pipeline-output/src/main/kotlin/.../output/OutputChannel.kt:23-42`:
  `enum class OutputChannel { STDOUT, STDERR }` with tokens `stdout/stderr`.
- `v2/pipeline-output/src/main/kotlin/.../output/OutputChannel.kt:109-184`:
  `OutputStreamAddress(runId, operationId, channel)` and
  `OperationOutputStreams(stdout, stderr)`.
- `v2/pipeline-output/src/main/kotlin/.../output/OutputReadPort.kt:19-44`:
  `committedExtent`, `read(stream, cursor, maxBytes)`, `readRange(stream, from, to)`.
- `v2/pipeline-output/src/main/kotlin/.../output/OutputFrameIndex.kt:50-65`:
  `OutputFrame(ordinal, stream, channel, from, to)` — frame metadata only.
- `v2/pipeline-output/src/main/kotlin/.../output/OutputFrameIndex.kt:107-200`:
  `OutputFrameIndex` with `framesOfRun(runId, afterOrdinal, limit)` (the
  read method most relevant to follow) and `streamsOfRun(runId)`
  returning DECLARED streams even before any byte was written.
- `v2/pipeline-output/src/main/kotlin/.../output/OutputTailPort.kt:86-96`:
  `OutputTailPort.tailState(stream)`.
- `v2/pipeline-output/src/main/kotlin/.../output/OutputTailState.kt:42-69`:
  `sealed interface OutputTailState { Open(committedEnd) | Sealed(finalEnd) }`.
- `v2/pipeline-output/src/main/kotlin/.../output/OutputRefusal.kt:14-66`:
  closed `sealed interface OutputRefusal` with `ForeignStream`,
  `UnknownStream`, `OffsetBeyondCommitted`, `InvalidRange`,
  `RecoveryNotCompleted`, `DanglingCommit`; `sealed interface
  OutputReadResult { Page | Refused(reason) }`.

**Verdict.** The read surface is already complete and byte-cursor-addressed.
There is no `output.follow.v1`-shaped capability today — every method is
request/response. The composition `OutputFrameIndex + OutputReadPort +
OutputTailPort` is already wired and ready to back a follow loop.

### Q2 — Output Plane write/store internals

- `v2/pipeline-output-store/src/main/kotlin/.../output/store/OutputWritePorts.kt:44-145`:
  `OutputAppendPort`, `OutputStreamHandle` (with `DEFAULT_APPEND_WINDOW = 64 KiB`),
  `OutputReservation` (write / copyFrom / commit / abandon).
- `v2/pipeline-output-store/src/main/kotlin/.../output/store/OutputWritePorts.kt:166-249`:
  `OutputRecoveryPort.recover()` and `OutputSealPort.seal(stream)`.
- `v2/pipeline-output-store/src/main/kotlin/.../output/store/SegmentOutputStore.kt:76-892`:
  production implementation. Layout: `<root>/streams/<safe-streamKey>/{cur.seg,
  cur.cmt, cur.res, stream.seal, cur.own, segments/<base>-<length>.seg}`. `cur.cmt`
  is the global committed offset (O2 authority). `cur.own` is the cross-process
  FileLock for live-writer ownership per ADR-OBS-002.
- `v2/pipeline-output-store/src/main/kotlin/.../output/store/SegmentOutputStore.kt:283-339`:
  recovery is destructive and gated (`recoveryPermitted = true`); readers open
  via `OutputPlaneProvider.storeForReading(...)` with `recoveryPermitted = false`.
- `v2/pipeline-output-store/src/main/kotlin/.../output/store/SegmentReader.kt:44-160`:
  sealed-segment read; sealed name format `{base}-{length}.seg`; serves a
  `[from, to)` window from sealed segments in base order, then the current
  segment.
- `v2/pipeline-output-store/src/main/kotlin/.../output/store/SegmentFrameIndex.kt:67-415`:
  per-run files `<safe-run>.streams` and `<safe-run>.frames` under `frames/`;
  ordinal allocation uses an OS `FileLock` per run; measured against 6
  concurrent writers.
- `v2/pipeline-output/src/main/kotlin/.../output/OutputPlaneProvider.kt:115-136`:
  `streamId(runId, opId[, channel])`, `streamsOf(runId, opId)`.

**Offset model.** Pure byte offsets, global per stream. No line numbers
anywhere. The only non-byte order is the per-run frame ordinal.
**Open vs sealed.** A stream is "open" iff `stream.seal` marker is absent;
reader asks via `OutputTailPort.tailState(stream)`. **Incremental follow.**
Today, follow is supported by composition: `OutputFrameIndex.framesOfRun +
OutputReadPort.read per frame + OutputTailPort.tailState per declared
stream`. Nothing in the writer/store is push/poll-aware; all three ports
are pull-only.

### Q3 — DomainEvent read API surface

- `v2/pipeline-events/src/main/kotlin/.../events/DomainEvent.kt:15-1436`:
  `sealed interface DomainEvent` with `eventId, runId, sequence, kind,
  occurredAt` plus a wide sealed hierarchy (`RunStarted`, `Compilation*`,
  `RunFinished`, `Stage*`, `Step*`, `ParallelBranch*`, `Retry*`,
  `Timeout*`, `StepFailed`, `EchoOutputCaptured`, `Credential*`, `Git*`,
  `File*`, `Artifact*`, `Dir*`, `WsCleaned`, `CatchError*`,
  `StageMarkedUnstable`, `WorkflowLoaded`, `WaitUntil*`, `PwdResolved`,
  `UnixDetected`, `Milestone*`, `TimeoutTriggered`, `Timestamps*`,
  `StepAdmissionObserved`, `Stash*`, `HtmlReport*`, `Directive*`,
  `GateEvaluated`, `PostConditionSelected`, `Lock*`, `InputRequested/*`,
  `HttpRequest*`, plus the deprecated `AgentResolved`).
- `v2/pipeline-events/src/main/kotlin/.../events/EventStore.kt:40-165`:
  `EventStore` with `append`, `appendAssigned`, `eventsFor`, `readSlice`,
  `readRecords`. `readSlice` is `readRecords(...).requireFullyDecoded()`;
  `readRecords` is the authoritative paged read that can carry
  `Undecodable` rows.
- `v2/pipeline-events/src/main/kotlin/.../events/EventStore.kt:31-35`:
  `data class EventSlice(events, nextCursor, hasMore)`.
- `v2/pipeline-events/src/main/kotlin/.../events/EventRecordRead.kt:67-191`:
  `sealed interface EventRecordRead { Decoded | Undecodable(sequence, kind,
  eventId, reason) }`, `UndecodableReason { MalformedPayload | UnknownKind }`,
  `data class EventRecordSlice(records, nextCursor, hasMore)` with
  `decoded`, `refusals`, `requireFullyDecoded()`.
- `v2/pipeline-events/src/main/kotlin/.../events/EventHistoryPorts.kt:22-161`:
  `EventCursor(runId, lastSequence)` (wire `evt-cursor-v1:`), `EventQuery`
  (`All`, `ByKind(kind)`, `BySource(ResourceRef)`, `BySubject(ResourceRef)`,
  `BySequenceRange(from, to)`), `EventPage`, `EventPublisher.publish`,
  `EventHistory.history(run, query)`, `EventTail.readAfter(run, cursor?, limit)`.
- `v2/pipeline-events/src/main/kotlin/.../events/EventHistoryReader.kt:32-82`:
  `class EventHistoryReader` implements `EventHistory` and `EventTail`.
  S5.4 split `readAfter` so it surfaces `EventRecordSlice.refusals`
  instead of throwing.
- `v2/pipeline-events/src/main/kotlin/.../events/PipelineEventEnvelope.kt:30-208`:
  `PipelineEventEnvelope` (version=1, additive `provenance`), `EnvelopeCodec`,
  `ResourceRefSerializer`, `EventRefSerializer`.

**Verdict.** The event plane already supports a resumable paged read
(`EventTail.readAfter`) with refusals. There is no `events.follow.v1`-shaped
capability — every read is request/response. Terminal-fact detection for
follow end is `RunFinished` (`DomainEvent.kind == "RunFinished"`) decided
in `MainObserveCli.followEvents` (`v2/pipeline-application/.../observation/MainObserveCli.kt:309`).

### Q4 — Identity model

- `v2/pipeline-domain/src/main/kotlin/.../domain/PipelineIds.kt:8-83`:
  `@JvmInline @Serializable value class` for `DefinitionId`, `RunId`,
  `StageId`, `StepId`, `PluginStepId`, `AttemptId(Int)`, `OperationId`,
  `BlockSegment(encoded)`. **None of these typed IDs appear in the read-side
  ports of either plane.**
- `v2/pipeline-domain/src/main/kotlin/.../domain/identity/ResourceRef.kt:10-74`:
  `enum class ResourceKind { PIPELINE_DEFINITION, RUN, STAGE, STEP, OPERATION,
  PLUGIN, PLUGIN_RELEASE, STEP_DEFINITION, PLUGIN_FAMILY }`;
  `data class ResourceRef(kind, segments)` with `canonicalText()` =
  `v1:<kind-name-lowercase>:<seg0>/<seg1>/...`.
- `v2/pipeline-domain/src/main/kotlin/.../domain/identity/ResourceRefs.kt:21-98`:
  builders: `pipeline/`, `run/`, `stage(runId, stageIndex)/`,
  `step(runId, stageIndex, stepIndex)/`,
  `operation(runId, stageIndex, stepIndex, opKey)/`, `plugin/`,
  `pluginRelease/`, `stepDefinition/`, `pluginFamily/`. **No `Attempt`,
  no `Branch`, no `Retry` kind.**
- `v2/pipeline-application/src/main/kotlin/.../application/durable/OpId.kt:24-156`:
  `data class OpId(runId, stageIndex, stepIndex, branchIndex?, bodyPath: List<BlockSegment>)`,
  `format()` → `{runId}-s{stageIndex}-{stepIndex}[-b{branchIndex}][-bp{N}-{idx}:{pluginId}...]`.
  This is what reaches the Output Plane as `operationId`.

**Cross-plane inconsistency.** The two planes use different primitive
identity for the run:
- Output Plane: `runId: String` in `OutputStreamAddress`, plus
  `operationId: String` (an `OpId.format()` value).
- Event Plane: `runId: String` in `EventCursor`, plus store-assigned
  `sequence: Long`.
- Domain typed IDs: `RunId`, `OperationId`, etc. exist but are unused in
  the read-side ports of either plane.
- Cross-system projection: `ResourceRef(kind, segments)` is the only
  thing that carries a typed kind.

**There is no single file defining the cross-plane identity contract.**

### Q5 — Existing follow / streaming / live-tail machinery

All live-tail machinery lives in `v2/pipeline-application/.../observation/`:

- `ObservationOutputReader.kt:52-317`:
  `interface ObservationOutputReader` with
  `readOutput(runId, afterOrdinal, frameLimit)`, `tailStatesOf(runId)`,
  `readTail(runId, tailBytes)`. Closed sealed returns.
  `class FrameIndexedObservationOutputReader` is the production composition.
- `ObservationOutputFollower.kt:34-164`:
  `class ObservationOutputFollower(reader, frameLimit, runFinished)`;
  pure POLLING, explicitly no wakeup source.
  `ObservationReplayLimit.DEFAULT = 256`. `FollowDecision.ReadAgain/Finished`.
- `LiveOutputDrain.kt:50-156`:
  `class LiveOutputDrain(reader, frameLimit, pollIntervalMs)`; drains
  committed bytes; `LiveOutputDrainResult.Drained/Stopped/Refused`.
- `ObservationWakeup.kt:35-196`:
  `sealed interface ObservationWakeup { EventsCommitted(runId, lastSequence)
  | OutputAdvanced(runId, lastOrdinal) }`; `coalesceWakeup`;
  `sealed interface FollowDecision { ReadAgain | Finished }`;
  `fun followDecision(moreFrames, tailStates, runFinished)`.
- `EventPageDrain.kt:51-257`:
  `EventPageDrain.drain` returns `Outcome.Answered(page) | .Stalled(page,
  stuckAfter)`; `drainTyped` is the typed variant.
- `MainObserveCli.kt:260-414`:
  `fun follow(...)` splits per lane (`followOutput` / `followEvents`);
  idle interval `FOLLOW_IDLE_MILLIS = 25L`; the `--follow` flag lives
  only in `pipeline observe`.

**Verdict.** All current live-tail code is polling, not push. Three
orthogonal pieces are already wired together. An `output.follow.v1` /
`events.follow.v1` contract should sit ON TOP of these, not parallel.

### Q6 — Failure-model / refusal ADTs

Project convention is **closed `sealed interface` per port**, not
`Either`/`Outcome`/`Result` singletons.

- `OutputRefusal.kt:14-66`:
  `ForeignStream(expected, actual) | UnknownStream(stream) |
  OffsetBeyondCommitted(requested, committed) | InvalidRange(from, to) |
  RecoveryNotCompleted | DanglingCommit(requestedEnd, readableBytes)`.
- `OutputRetention.kt:57-141`:
  `RunLifecycle { Terminal | StillRunning }`;
  `RetainUntil { RunTerminalPlus | ExplicitReleaseOnly | Forever }`;
  `OutputPruneIntent { RunReachedTerminalState(runId) |
  OperatorReleased(runId, requestedBy) }`;
  `OutputPruneReport(streamsRemoved, bytesReleased, streamsRetained)`.
- `OutputTailState`: `Open(committedEnd) | Sealed(finalEnd)` plus `null`
  for unknown.
- `EventRecordRead.kt:67-219`:
  `Decoded(event) | Undecodable(sequence, kind, eventId, reason, rawRowPresent=true)`;
  `UndecodableReason { MalformedPayload(detail) | UnknownKind(kind) }`;
  `class UndecodableEventRecordException(runId, sequence, kind?, reason?, message)`.
- `EventPageDrain.Outcome.Answered(page) | .Stalled(page, stuckAfter)`.

**Verdict.** New contracts should pick one of the existing refusal
vocabularies (or extend them with a new sealed case) and emit typed
refusals rather than throwing. The two existing refusal ADTs do NOT
share a base type — they are independent closed types per port.

### Q7 — Public capability contract

**No public capability contract exists as an enum, manifest, or probe.**
No file declares capability IDs of the form `output.read.v1`,
`output.tail.v1`, `events.read.v1`, `output.follow.v1`,
`events.follow.v1`. The only reference to those exact tokens is in
`docs/pipelinek-coordinated-evolution/coordination/INTERFACE_CONTRACT.md`
(line 6 of "Compatibilidad y negociación": *"ausencia de `output.follow.v1`
requiere fallback anunciado o refusal tipado, nunca un falso LIVE"*).

What exists under the `Capabilities` name is **internal step
capabilities**, not public consumer capabilities:
`v2/pipeline-application/.../application/Capabilities.kt` declares
`StepCapability` keys (`EVENT_SINK_CAPABILITY`, `WORKSPACE_OPERATIONS_*`,
`STAGE_IDENTITY_CAPABILITY`, etc.) — these are step-runtime DI tokens,
not external API capabilities.

What is published as API today:
- `pipeline-output` — `maven-publish` publication `sdk`
  (`pipeline-output/build.gradle.kts:54-66`). Public ABI:
  `OutputStreamId, OutputCursor, OutputPage, OutputReadPort,
  OutputReadResult, OutputRefusal`, plus
  `OutputTailPort, OutputFrameIndex, OutputRetentionPort`.
- `pipeline-events` — `maven-publish` publication `sdk`
  (`pipeline-events/build.gradle.kts:62-78`). Public ABI:
  `DomainEvent`, `EventStore`, `EventSink`, `EventSlice`,
  `EventRecordRead`, `EventRecordSlice`,
  `UndecodableEventRecordException`, `EventCursor`, `EventPage`,
  `EventQuery`, `EventHistory`, `EventTail`, `EventPublisher`,
  `PipelineEventEnvelope` (with serializer), `EventRef`, `EventId`,
  `ProviderProvenance`, `ResourceRef`.

No `@Deprecated` on the public output types; only `AgentResolved`
(`DomainEvent.kt:187-200`) and `EventViewProjection.Deprecated_Console`
are deprecated. There is no `EXPERIMENTAL`/`STABLE` annotation
precedent.

The example consumer at
`/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/examples/fabric-contract-consumer/`
exists and round-trips envelopes; it depends on the published
`pipeline-events` artifact.

**Verdict.** `output.read.v1`, `output.tail.v1`, `events.read.v1`,
`output.follow.v1`, `events.follow.v1` are **first-of-kind** capability
identifiers. There is no precedent in the repo for an EXPERIMENTAL vs
STABLE flag — this is a new design decision.

## Summary of "already there" vs "must define"

| Concept | Already exists (re-use) | Does NOT exist (must define) |
|---|---|---|
| Output byte-cursor read | `OutputReadPort`, `OutputCursor`, `OutputPage`, `OutputReadResult`, `OutputRefusal` | — |
| Output tail/sealed answer | `OutputTailPort`, `OutputTailState.Open/Sealed`, `OutputFrameIndex.streamsOfRun` | — |
| Output frame-ordered read | `OutputFrameIndex.framesOfRun(runId, afterOrdinal, limit)` | — |
| Output writer / store / seal | `OutputAppendPort`, `OutputSealPort`, `OutputRecoveryPort`, `OutputRetentionPort`, `SegmentOutputStore`, `SegmentReader`, `SegmentFrameIndex` | — |
| Event paged typed read | `EventStore.readSlice` / `readRecords`, `EventSlice`, `EventRecordSlice`, `UndecodableEventRecordException`, `EventTail.readAfter` | — |
| Event terminal-fact detection | `DomainEvent` `RunFinished.kind == "RunFinished"` | — |
| Run identity (Output side) | `runId: String` + `OutputStreamAddress` | typed `RunId` unused in published ports |
| Run identity (Event side) | `runId: String` + `EventCursor` | typed `RunId` unused in `EventCursor` |
| Operation identity | `OpId.format()` (runtime) | typed `OperationId` unused by read ports |
| Cross-plane identity projection | `ResourceRef(kind, segments)` + `ResourceRefs.*` builders | — |
| Wakeup ADT (closed) | `ObservationWakeup.EventsCommitted/OutputAdvanced`, `coalesceWakeup` | no emitter wired yet |
| Follow decision (closed) | `FollowDecision.ReadAgain/Finished`, `fun followDecision(...)` | — |
| Existing follow loop | `ObservationOutputFollower.replay`, `LiveOutputDrain.drain`, `EventPageDrain.drain/drainTyped` | — |
| `output.follow.v1` capability ID | — | must be defined |
| `events.follow.v1` capability ID | — | must be defined |
| Push/poll/subscribe wakeup signal | — | ADT exists; no emitter |
| Public capability registry | — | no `Capabilities` enum/manifest/probe exists |
| EXPERIMENTAL / STABLE flagging | — | no precedent in repo |
