# CRIC-M2 — INSPECT / RECOVER / CANCEL audit (input to M2 design)

**Status:** read-only audit, completed before any M2 code is written.
**Worktree:** `pk-cric-m2` (branch `audit/cric-m2-inspect-recover-cancel`) at `74ad6480`.
**Anchor:** the Block 2 plan ("auditar primero las interfaces públicas existentes, la autoridad
única de recovery, el journal privado y las políticas de efectos") and the normative text in
`coordination/INTERFACE_CONTRACT.md` §5: *"Runtime inspeccionable: `inspect/recover/cancel/follow`
mediante interfaces segregadas, reutilizando los puertos públicos REALES de PipelineK. Una lectura
no ejecuta recuperación destructiva; no relanza efectos externos automáticamente."*
**Precedent:** `m1-design/M1_OUTPUT_EVENTS_AUDIT.md` — the same read-only survey shape, applied
to a different block of verbs.

This document is the audit. The design that consumes it is the next file in this directory.

## 0. Scope and naming

The Block 2 plan names three verbs and one constraint. The verbs classify every existing port
along three axes; the constraint is the test every new port must pass before it is allowed to
touch durable state.

```text
INSPECT    read-only, non-destructive: "what does this run look like right now?"
RECOVER    idempotent, re-entrant:     "bring this run back to a known state, no re-run of effects"
CANCEL     terminal, single-shot:      "stop this run, mark it cancelled, ensure no further effects"

CONSTRAINT (INTERFACE_CONTRACT §5)
           a read MUST NOT execute destructive recovery against a live writer
           a read MUST NOT re-trigger external side effects
           the verbs MUST be segregated interfaces, NOT one wide port
           the existing REAL public ports of PipelineK are reused, NOT duplicated
```

`FOLLOW` is the M1 verb. It is already published and certified. It is included in this audit
only where it overlaps or constrains M2 (overlap §B, invariants §D).

The audit answers, in order:

1. What public ports already exist that touch any of these verbs (§A).
2. What gaps exist between what Fabric needs and what PK exposes (§B).
3. Where two existing ports claim the same authority and need consolidation or formal demarcation (§C).
4. Whether the three invariants the contract pins on these verbs hold today, and which one fails (§D).
5. How the audit cross-walks to the existing UAT-PK / AAT / FITNESS evidence (§E).
6. The single decision: PK-CANDIDATE or COMPATIBILITY HANDOFF ONLY (§F).

All file paths are absolute. The `74ad6480` baseline is the only commit consulted; everything
downstream is M1 work and is named for context only.

## A. Public ports that already touch INSPECT, RECOVER or CANCEL

The survey covers the four modules the user's brief names — `:pipeline-events/identity/`,
`:pipeline-output/`, `:pipeline-application/durable/` + `observation/`, and
`:pipeline-step-sdk/runtime/durable/` — and the two store modules that hold the durable
authorities they compose with: `:pipeline-output-store/` and `:pipeline-events-store/`.

Two columns matter:

- **Published?** The published modules are those with a `maven-publish` block in their
  `build.gradle.kts`. A type that lives in a non-published module is reachable inside PK but
  is **not** part of the ABI Fabric can compile against. Per the INTERFACE_CONTRACT §6, the
  consumer compiles against the published ABI, never against sources or `mavenLocal`.
- **Verb(s) it touches.** One of {Inspect, Recover, Cancel, Follow} or a closed subset.

### A.1 `:pipeline-events/identity/` (published module `pipeline-events`)

```
EventHistory (interface)             | …/identity/EventHistoryPorts.kt:151-153  | Inspect
                                     history(run, query): Sequence<PipelineEventEnvelope>; pure read, no effects.
EventTail (interface)                | …/identity/EventHistoryPorts.kt:159-161  | Follow
                                     readAfter(run, cursor, limit): EventPage; paged continuation; refuses via Undecodable.
EventPublisher (interface)           | …/identity/EventHistoryPorts.kt:143-145  | (write-side)
                                     inverse of EventTail; listed for completeness, not in scope of M2.
EventCursor (data class)             | …/identity/EventHistoryPorts.kt:22-53    | Follow
                                     opaque (runId, lastSequence); wire evt-cursor-v1:.
EventQuery (sealed class)            | …/identity/EventHistoryPorts.kt:59-95    | Inspect
                                     closed typed filters: All / ByKind / BySource / BySubject / BySequenceRange.
EventPage (data class)               | …/identity/EventHistoryPorts.kt:102-137  | Follow
                                     envelopes + nextCursor + hasMore + refusals (typed undecodable rows).
EventHistoryReader (class)           | …/identity/EventHistoryReader.kt:32-82   | Inspect, Follow
                                     adapter implementing both EventHistory and EventTail over any EventSink.
EventInspection (object)             | …/identity/EventInspection.kt:25-104     | Inspect, Follow
                                     PURE projection (no IO): projectFields, contextWindow, tail, follow(seq).
EventField (sealed interface)        | …/identity/EventInspection.kt:112-151   | Inspect
                                     closed whitelist (Sequence / Kind / EventId / Subject / Source).
EventViewProjection + ViewMode + OutputFormat
                                     | …/identity/EventViewProjection.kt:57-166 | Inspect
                                     pure view+format projection over envelopes; CONSOLE view is deprecated.
EnvelopeProjector (object)           | …/identity/EnvelopeProjector.kt:82-256  | Inspect
                                     DomainEvent -> PipelineEventEnvelope; subject computation per closed ADT.
EnvelopeCodec (object)               | …/identity/PipelineEventEnvelope.kt:194-208 | Inspect
                                     JSON encode/decode for envelopes; serializer bundle.
PipelineEventEnvelope (data class)   | …/identity/PipelineEventEnvelope.kt:30-60 | Inspect
                                     v1 envelope: eventRef/kind/occurredAt/sequence/subject/causation/correlation/provenance.
ProviderProvenance (data class)      | …/identity/ProviderProvenance.kt:32-40  | Inspect
                                     audit projection of StepProviderMetadata; nullable, additive.
EnvelopeProjectingEventSink (class)  | …/identity/EnvelopeProjectingEventSink.kt:17-44 | (write-side)
                                     decorator over EventSink that publishes envelopes.
```

**Verdict.** The event plane has a full read + follow vocabulary. **There is no `Cancel`-shaped
port, no run-level `Inspect` port, and no `Recover` port.** The closest "is this run terminal?"
answer is `RunFinished` (`DomainEvent.kind == "RunFinished"`) consulted via `eventsOf(runId).any
{ it is RunFinished }` (`MainObserveCli.kt:467`), which is a method on the application-level
`ObserveLanes` port — itself an internal composition, not a published PK port.

### A.2 `:pipeline-output/` (published module `pipeline-output`)

```
OutputReadPort (interface)           | …/output/OutputReadPort.kt:19-44          | Inspect
                                     committedExtent / read / readRange; total, returns OutputReadResult.
OutputTailPort (interface)           | …/output/OutputTailState.kt:86-96         | Follow
                                     tailState(stream): OutputTailState? — Open or Sealed, null for unknown.
OutputTailState (sealed)             | …/output/OutputTailState.kt:42-69         | Follow
                                     Open(committedEnd) / Sealed(finalEnd); terminality of a stream, NO outcome.
OutputCursor (data class)            | …/output/OutputCursor.kt:57-105           | Follow
                                     committed-byte-offset cursor with start()/encode/decode; out-cursor-v1:.
OutputStreamId (value class)         | …/output/OutputCursor.kt:24-31            | Follow
                                     stable identity of one output stream.
OutputPage (data class)              | …/output/OutputCursor.kt:121-157          | Follow
                                     bytes/stream/from/next/committedEnd; invariant: page ends at next.committedOffset.
OutputReadResult (sealed)            | …/output/OutputRefusal.kt:62-66           | Inspect
                                     Page | Refused(reason).
OutputRefusal (sealed)               | …/output/OutputRefusal.kt:14-60           | Inspect
                                     closed refusal ADT: ForeignStream / UnknownStream / OffsetBeyondCommitted /
                                     InvalidRange / RecoveryNotCompleted / DanglingCommit.
OutputChannel (enum)                 | …/output/OutputChannel.kt:23-41           | Inspect
                                     STDOUT / STDERR with fromToken.
OutputStreamAddress (data class)     | …/output/OutputChannel.kt:109-148         | Inspect
                                     (runId, operationId, channel); of / parse; channel rides on stream id.
OperationOutputStreams (data class)  | …/output/OutputChannel.kt:159-184         | Inspect
                                     stdout+stderr pair of one operation; select(channels), all.
OutputFrame (data class)             | …/output/OutputFrameIndex.kt:50-65       | Inspect
                                     ordinal/stream/channel/from/to; observation-order metadata, NO payload.
OutputFrameIndex (interface)         | …/output/OutputFrameIndex.kt:107-200     | Inspect
                                     declareStream / append / framesOfRun / lastOrdinal / streamsOfRun /
                                     recoverUnframedBytes.
OutputRetentionPort (interface)      | …/output/OutputRetention.kt:149-162       | (terminal-only retention, NOT Cancel)
                                     hasOutputFor + prune(intent); refuses to prune a live run by type.
RunLifecycle (sealed)                 | …/output/OutputRetention.kt:57-66         | Inspect
                                     Terminal | StillRunning — only for retention policy, NOT exposed as a query.
RetainUntil / OutputPruneIntent / OutputPruneReport
                                     | …/output/OutputRetention.kt:74-140       | (retention vocabulary)
                                     closed policy+intent; deletion names its reason in its type.
OutputAdoption / OutputCrashInvariant | …/output/OutputAdoption.kt:36-243        | Inspect
                                     the acceptance bar for "S2 is implemented"; mayClaimCrashConsistency.
```

**Verdict.** The output plane has a full read + follow vocabulary, and a retention port that is
terminal-only by type (cannot prune a live run). **There is no `Cancel` port, no run-level
`Inspect` port, and no `Recover` port in the published module.** Recovery of the output store
lives in `:pipeline-output-store` (see A.5) and is **not** part of the published ABI — that is
the deliberate split `OutputReadPort` documents at lines 11-15.

### A.3 `:pipeline-application/durable/` (NOT published)

```
CanonicalDurableRunCoordinator (class) | …/durable/CanonicalDurableRunCoordinator.kt:58-…   | (single entry, not a port)
                                       ONE public method: suspend fun run(pipeline, runId): RunOutcome.
                                       NO inspect, recover or cancel surface.
CoordinatorCaps (data class)           | …/durable/CoordinatorCaps.kt:46-98                  | (composition only)
                                       bundle of named dependencies (journal, cursorStore, clock, replay policy, …).
RunLifecycleEngine (internal class)    | …/durable/RunLifecycleEngine.kt:35-179             | (lifecycle bookkeeping)
                                       openRun/stageStarted/stageFinished/closeRun; emits RunStarted/RunFinished bookends.
                                       NO inspect, recover or cancel API.
RunningSubprocessRecovery (internal)   | …/durable/RunningSubprocessRecovery.kt:65-80        | Recover (internal)
                                       observe(operationId): RunningSubprocessObservation.
                                       INTERNAL — unreachable from outside this module.
RunningSubprocessObservation (internal)| …/durable/RunningSubprocessRecovery.kt:131-166      | Recover (internal)
                                       Observed / Unavailable(UnobservableCause) / ReattachWindowExpired.
UnobservableCause (internal)          | …/durable/RunningSubprocessRecovery.kt:176-184      | Recover (internal)
                                       NoControlRootConfigured.
RecoveryInterpretationEngine (internal)| …/durable/RecoveryInterpretationEngine.kt:65-348    | Recover (internal)
                                       interpret(resolution, request) — effectful half of recovery.
                                       INTERNAL.
RecoveredExecutionMaterializer (internal object) | …/durable/RecoveredExecutionMaterializer.kt:60-…| Recover (internal)
                                       materialize(definition, input, terminal) -> RecoveryMaterialisation.
                                       INTERNAL.
RecoveryMaterialisation (internal)     | …/durable/RecoveredExecutionMaterializer.kt:131-…  | Recover (internal)
                                       Materialised / InsufficientEvidence / NoProjection / MalformedInput.
OutputPlaneProvider (object)           | …/durable/OutputPlaneProvider.kt:39-148             | (Re-)provider
                                       storeForWriting (calls recover() once); storeForReading (NEVER recovers).
                                       Recovery is invoked here but NOT exposed as a public port.
RunOutputRetention (class)             | …/durable/RunOutputRetention.kt:107-155             | (terminal-only, NOT Cancel)
                                       onRunTerminal applies OutputRetentionPort.prune only when policy authorises.
RunOutputDisposition (sealed)         | …/durable/RunOutputRetention.kt:19-41               | (NOT Cancel)
                                       Retained / Released(report) / ReleaseFailed(cause).
OpId (data class)                      | …/durable/OpId.kt:24-157                            | Inspect (identity)
                                       typed op id (runId-s{stage}-{step}[-b{branch}][-bp{N}-{seg}…]).
FileBasedRetryControlJournal / FileBasedWaitUntilControlJournal
                                       | …/durable/{FileBasedRetryControlJournal,FileBasedWaitUntilControlJournal}.kt | (NOT Inspect/Cancel)
                                       per-step control journals; consumed by the engine, not exposed as read ports.
BodyChildDispatcher (fun interface)    | …/durable/BodyExecutionEngine.kt:47-…               | (composition only)
                                       the single seam by which body children re-enter the coordinator's dispatch.
```

**Verdict.** The durable run coordinator has only **one public method**: `run`. Every
recover-shaped surface in this module is `internal`. There is **no** public `Inspect`,
`Recover` or `Cancel` port that a consumer can call from outside the JVM that owns the run.

### A.4 `:pipeline-application/observation/` (NOT published)

```
ObservationOutputReader (interface)            | …/observation/ObservationOutputReader.kt:52-111   | Inspect, Follow
                                               readOutput / tailStatesOf / readTail over OutputFrameIndex + OutputReadPort + OutputTailPort.
ObservationOutputRead (sealed)                 | …/observation/ObservationOutputReader.kt:114-121  | Inspect, Follow
                                               Page(page) | Refused(reason).
ObservationOutputPage (data class)             | …/observation/ObservationOutputReader.kt:134-153  | Follow
                                               records / lastOrdinal / moreFrames.
FrameIndexedObservationOutputReader (class)    | …/observation/ObservationOutputReader.kt:169-317  | Inspect, Follow
                                               concrete composition over OutputFrameIndex + OutputReadPort + OutputTailPort.
ObservationOutputFollower (class)              | …/observation/ObservationOutputFollower.kt:34-117  | Follow
                                               replay(runId, afterOrdinal, query, limit): ObservationReplayResult.
ObservationReplayResult / ObservationReplayLimit | …/observation/ObservationOutputFollower.kt:119-164| Follow
                                               Complete(truncated, decision) | Refused(reason); DEFAULT 256 records.
LiveOutputDrain (class)                        | …/observation/LiveOutputDrain.kt:50-133           | Follow
                                               drain(runId, shouldStop, emit) polling committed bytes.
LiveOutputDrainResult (sealed)                 | …/observation/LiveOutputDrain.kt:135-156          | Follow
                                               Drained / Stopped | Refused(reason).
ObservationWakeup (sealed) + coalesceWakeup    | …/observation/ObservationWakeup.kt:35-89          | Follow (signal)
                                               EventsCommitted / OutputAdvanced; coalescing per lane.
                                               NOT WIRED — no producer publishes this yet.
FollowDecision (sealed) + followDecision (fun) | …/observation/ObservationWakeup.kt:121-196        | Follow
                                               ReadAgain | Finished.
ObservationRecord (sealed)                     | …/observation/ObservationRecord.kt:46-97          | Inspect
                                               Event(domainEvent) | Output(frame, bytes, text).
ObservationQuery / CompiledObservationQuery / compileQuery / selectRecords | …/observation/ObservationQuery.kt:60-179 | Inspect
                                               closed AND-across / OR-within-dimensions filter.
ObservedOutcome (sealed) + fromToken           | …/observation/ObservedOutcome.kt:54-116           | Inspect
                                               Success / Unstable / Failure / Skipped / Aborted / Other(token).
ObservationView (enum) + ObservationFormat (enum) | …/observation/ObservationView.kt:45-150         | Inspect
                                               NORMAL/EVENTS/CONSOLE(declined)/QUIET/FULL(refused); TEXT/JSON_LINES/JSON.
ViewParseResult / FormatParseResult (sealed)   | …/observation/ObservationView.kt:113-126          | Inspect
                                               Parsed / Invalid / Unavailable.
ObservationJsonLines (object)                  | …/observation/ObservationJsonLines.kt:50-134     | Inspect
                                               encodeOne / writeTo / parse / decodeStrictly — wire encoding of records.
RunObservationOutput (object) + Stream (inner)  | …/observation/RunObservationOutput.kt:52-213      | Inspect
                                               whole-run and streaming encoding by view+format+query+budget.
FailureContextReader (class)                   | …/observation/FailureContext.kt:239-309           | Inspect
                                               read(runId, events): FailureContextRead — journal-first failure projection.
FailureContext (data class)                    | …/observation/FailureContext.kt:190-209           | Inspect
                                               runId / operations / facts / runOutcome.
FailureContextRead (sealed)                    | …/observation/FailureContext.kt:212-223           | Inspect
                                               Page(context) | Refused(reason).
FailureStatus (object)                         | …/observation/FailureContext.kt:81-114            | Inspect
                                               isFailure(status) — closed predicate over OperationStatus.
FailedOperationIdentity / FailedOperation      | …/observation/FailureContext.kt:124-182           | Inspect
                                               selected failures + bounded tails.
ConsoleReadService (object)                    | …/application/MainConsoleCli.kt:45-…              | Inspect, Follow
                                               read(controlDirRoot, runId, opId, after, maxBytes); byte-cursor over ONE operation.
ConsoleReadRequest / ConsoleCliRefusal (sealed)| …/application/MainConsoleCli.kt:285-…            | Inspect
                                               Paged / Range; typed CLI refusal vocabulary.
FollowControl (interface)                       | …/application/MainObserveCli.kt:423-428          | Follow (control)
                                               shouldStop() / idle() — the consumer's own interrupt; cannot reach into the run.
ObserveLanes (interface)                       | …/application/MainObserveCli.kt:431+             | Inspect, Follow
                                               hasEventStore / hasOutputPlane / eventsOf(runId) / runFinished(runId) — application-level composition.
MainObserveCli.follow() (function in object)   | …/application/MainObserveCli.kt:260+             | Follow
                                               public follow() over ObserveLanes + FollowControl; the M1 follow verb.
```

**Verdict.** The observation package is rich. It composes the existing read ports into a
follow verb; it defines the typed CLI view+format vocabulary; it owns the journal-first
failure-context projection (`FailureContextReader`). **It has no `Cancel` port, no run-level
`Inspect` port, and no `Recover` port.** The `runFinished(runId)` method on `ObserveLanes`
(`MainObserveCli.kt:467`) is a defaulted method that reads `RunFinished` events; that is the
closest the application layer gets to "is this run terminal?" and it is not promoted to a
named port.

### A.5 `:pipeline-step-sdk/runtime/durable/` (NOT published)

```
EffectReplayPolicy (interface) + isReusableCompletion (ext fun) | …/durable/EffectReplayPolicy.kt:63-95 | Recover
                                                                       decide(policy, effects, hasJournalEntry, journaledOutcome): ReplayDecision.
DefaultEffectReplayPolicy (class)    | …/durable/EffectReplayPolicy.kt:102-177 | Recover
                                       concrete implementation of the normative decision matrix (rules 1..6).
ReplayDecision (enum)                | …/durable/ReplayDecision.kt:8-17      | Recover
                                       SKIP / RERUN / ABORT.
RunnerTrustProfile (sealed)          | …/durable/RunnerTrustProfile.kt:29-59 | (NOT Cancel/Recover)
                                       TrustedSingleTenant / MultiTenantConstrained.
StepReconcilerL1 (class) + Classification (sealed)
                                       | …/durable/StepReconcilerL1.kt:63-263 | Recover
                                       classify / classifyControlDir / classifyRunning / reconcile(runId) / shouldRerun.
StepReconcilerL1.Classification (sealed) | …/durable/StepReconcilerL1.kt:72-105 | Recover
                                       Complete(exitCode) / Reattach(controlDir) / TimedOut(controlDir, logPath) / Lost.
terminalFromReconciliation (ext fun) | …/durable/DurableTaskTerminalAdapter.kt:18-41 | Recover
                                       reconcile() -> DurableTaskTerminal? (null when Reattach).
DurableShellLaunching (interface)    | …/durable/DurableShellLaunching.kt:48-182 | (write-side, listed for completeness)
                                       launch / detach / pollResult / isAlive / kill / cleanup / buildWrapper.
                                       kill() is the subprocess kill primitive, not a run cancel.
DurableShellExecutor (class)         | …/durable/DurableShellExecutor.kt:132-…  | (write-side)
                                       ProcessBuilder + setsid implementation of DurableShellLaunching.
```

**Verdict.** The SDK runtime owns the effect-aware replay policy and the L1 reconciler
classification. **`EffectReplayPolicy` is a Recover primitive (the decision) but it has no
public caller** — it is consumed only by the internal `RecoveryInterpretationEngine`. The
subprocess `kill()` is **not** a run cancel; it stops a single subprocess whose parent is the
coordinator's writer, and calling it from outside the run is unsafe (no fencing, no journal
write, no terminality guarantee).

### A.6 The two store modules (not published)

These are the durable authorities that the published modules read through. The audit lists them
because every existing `Recover` path lives here, and the question "can a consumer reach recovery
through the published ABI?" has a definite answer at the end of this section.

```
OperationJournal (interface)         | pipeline-events-store:…/events/durable/OperationJournal.kt:28-124 | Inspect, Recover
                                     append / get / get(op,attempt) / listForRun / getDeadlineMs /
                                     getEndedAt / getStartedAt / beginOperation.
                                     NO public method that returns "current state of a run".
ReplayCursorStore (interface)        | pipeline-events-store:…/events/durable/ReplayCursorStore.kt:16-64 | Recover
                                     load / advance / advancePastParallelFrame; idempotent CAS.
ReplayCursor (data class)            | pipeline-events-store:…/events/durable/ReplayCursor.kt:16-22 | Recover
                                     runId / lastOpId / stageIndex / savedAt.
FileBackedRunExecutionLeaseStore     | pipeline-events-store:…/events/durable/FileBackedRunExecutionLeaseStore.kt:40-253 | (per-run ownership)
                                     OS file lock + fencing token; acquire / release / authorise.
RunExecutionLease (pure decider)     | pipeline-events-store:…/events/durable/RunExecutionLease.kt:50-309 | (per-run ownership)
                                     LeaseAcquisition / PublishAuthority / Release — typed decisions.
OutputAppendPort (interface)         | pipeline-output-store:…/output/store/OutputWritePorts.kt:44-53  | (write-side)
                                     open(stream).
OutputStreamHandle / OutputReservation | …/output/store/OutputWritePorts.kt:60-145 | (write-side)
                                     reserve / appendFrom / write / copyFrom / commit / abandon.
OutputRecoveryPort (interface)       | pipeline-output-store:…/output/store/OutputWritePorts.kt:166-176 | Recover (PRIVATE)
                                     recover(): OutputRecoveryReport — O3 entry point; idempotent.
                                     Not in the published module — readers call read() and get
                                     OutputRefusal.RecoveryNotCompleted until this completes.
OutputSealPort (interface)           | pipeline-output-store:…/output/store/OutputWritePorts.kt:205-218 | (terminal, not Cancel)
                                     seal(stream): Long — idempotent terminal marker; not a run cancel.
OutputRecoveryReport (data class)    | pipeline-output-store:…/output/store/OutputWritePorts.kt:229-249 | Recover
                                     streamsReconciled / streamsOwned / committedBytes / bytesReleased /
                                     reservationsReleased / bytesUnbacked.
```

**The publication question, answered.** `:pipeline-events-store` and `:pipeline-output-store`
do **not** declare `maven-publish`. `OperationJournal`, `OutputRecoveryPort`, `OutputSealPort`,
`FileBackedRunExecutionLeaseStore` and `RunExecutionLease` are **internal-to-PK**. A consumer
that wants any of them must reach into the JVM that owns the run, which is not a public API
boundary. The published plane gives the consumer `OutputReadPort`, `OutputFrameIndex`,
`OutputTailPort`, `OutputRetentionPort`, `EventHistory`, `EventTail` and the envelope codec —
and refuses on `OutputRefusal.RecoveryNotCompleted` until somebody inside the JVM has invoked
`recover()` first.

### A.7 Summary count

| Module                       | Published? | Touches Inspect | Touches Recover | Touches Cancel | Touches Follow |
|------------------------------|-------------|-----------------|-----------------|-----------------|-----------------|
| `:pipeline-events/identity/` | YES         | 9 ports         | 0               | 0               | 5 ports         |
| `:pipeline-output/`          | YES         | 8 ports         | 0               | 0               | 4 ports         |
| `:pipeline-output-store/`    | NO          | 0               | 2 ports (private) | 0            | 0               |
| `:pipeline-events-store/`    | NO          | 1 port (journal reads) | 3 ports (journal, cursor, lease) | 0 | 0 |
| `:pipeline-application/durable/` | NO       | 1 type (OpId)   | 3 ports (internal) | 0          | 0               |
| `:pipeline-application/observation/` | NO   | 14 types         | 0               | 0               | 8 ports         |
| `:pipeline-step-sdk/runtime/durable/` | NO | 0               | 3 ports (internal) | 0          | 0               |
| **Total surveyed**           | —           | **33**          | **11**          | **0**           | **17**          |

Two truths fall out of that count:

1. **0 public `Cancel` ports in the surveyed code base.** Every close-shaped surface
   (`OutputSealPort.seal`, `DurableShellLaunching.kill`, the journal's terminal append) is
   either non-cancel or non-public, or both.
2. **Every `Recover` port is either internal to PK or in a non-published module.** The
   `Recover` machinery that exists today (recovery policy, journal reattach, subprocess
   reattach, output store recovery) is a single authority kept behind PK's runtime control
   boundary. Fabric cannot call it through any published port.

## B. Gaps between contract requirement and current ports

Each gap is numbered. (a) what Fabric needs, (b) what PK has, (c) the missing shape, (d) the
proposed new port or extension, (e) the ADT it would carry, (f) the authority it must NOT
introduce. Gaps are derived from the survey in §A; no gap claims a behaviour the audit did not
see in the code.

### B.1 No public INSPECT port for run state

- **(a) Fabric needs** "what does this run look like right now?" — a typed answer that names
  the run's lifecycle (still-running / terminal with which outcome / unobservable because the
  journal is absent), the operation at the cursor (if any), the lease holder (if any), and the
  latest terminality of every declared output stream (Open / Sealed / Unknown).
- **(b) PK has** every primitive Fabric would compose: `EventHistory.history`, `EventTail.readAfter`,
  `OutputFrameIndex.streamsOfRun`, `OutputTailPort.tailState`, `OperationJournal.listForRun` and
  `getEndedAt`, `ReplayCursorStore.load`, `RunExecutionLease` (pure decider) and
  `FileBackedRunExecutionLeaseStore` (the OS-lock + fencing-token store). None of these is
  exposed as a single `RuntimeIntrospectionPort`.
- **(c) Missing shape.** A `RuntimeIntrospectionPort.inspect(runId)` that returns a closed
  ADT covering Running(attempt, leaseHolder, latestSequence), Terminal(outcome, terminalAtMs,
  finalExtent), Unobservable(reason: InspectionFailure). The reason must be a sealed ADT that
  names the missing substrate (NoEventStore / NoControlRoot / UnknownRun / LeaseHeldByAnother
  + the live holder's fencing token), not an exception.
- **(d) Proposed new port.** `RuntimeIntrospectionPort` in `:pipeline-events` (or a new
  `:pipeline-runtime` module if the segregation must be physical). Single method `inspect(runId)`.
- **(e) ADT it would carry.** `sealed interface RuntimeObservation { data class Running(...);
  data class Terminal(...); data class Unobservable(val reason: InspectionFailure) }` —
  the shape `PK-SPEC-02-RUNTIME-OBSERVATION.md` already pins at lines 22-28, which this audit
  re-affirms verbatim.
- **(f) Authority it must NOT introduce.** No new lease store, no new fencing scheme, no new
  scheduler. Inspection MUST compose the existing journal read paths and the existing pure
  decider; it MUST NOT add a write-side surface. Inspection MUST be safe to call from any
  number of concurrent observers without changing the durable state.

### B.2 No public CANCEL port

- **(a) Fabric needs** "stop this run, mark it cancelled, ensure no further effects" — a
  terminal, single-shot operation that flips a run's durable state to CANCELLED, that signals
  the coordinator's writer to stop appending events and bytes, that is idempotent under
  concurrent calls, and that publishes `RunFinished(outcome = "cancelled")` as the last
  lifecycle event the journal sees.
- **(b) PK has** no public port named cancel. The closest surfaces are:
  - `DurableShellLaunching.kill(process, controlDir)` — kills ONE subprocess of the writer;
    has no journal write, no terminality guarantee, no fencing against a future owner. A
    consumer that called this directly would corrupt the journal.
  - `OutputSealPort.seal(stream)` — terminal marker for a stream; refuses appends after seal;
    not a run cancel.
  - `OperationJournal.append` with a `Cancelled` outcome — the write path the coordinator
    uses to close a run today, but reachable only through the internal `RunLifecycleEngine`,
    which has no external entry.
- **(c) Missing shape.** A `RuntimeControlPort.cancel(runId, reason): CancelOutcome` that
  flips the run's terminality once, is idempotent (a second call returns
  `CancelOutcome.AlreadyCancelled` rather than re-firing), and surfaces the journal's own
  row at the end so the caller can confirm the terminal fact was committed.
- **(d) Proposed new port.** `RuntimeControlPort` in `:pipeline-events` (or `:pipeline-runtime`)
  with `suspend fun cancel(runId, reason): CancelOutcome` and `suspend fun isCancellable(runId):
  Cancellability`. `RuntimeControlPort` is the cancel sibling of `RuntimeIntrospectionPort` —
  segregation by verb, as the contract §5 mandates.
- **(e) ADT it would carry.** `sealed interface CancelOutcome { data object Cancelled :
  CancelOutcome; data object AlreadyCancelled : CancelOutcome; data class Refused(val reason:
  CancelRefusal) : CancelOutcome }` and `sealed interface CancelRefusal { data object
  UnknownRun : CancelRefusal; data class RunTerminal(val outcome: String) : CancelRefusal;
  data class LeaseHeldByAnother(val ownerId: RunOwnerId, val token: FencingToken) :
  CancelRefusal; data class JournalUnavailable(val cause: String) : CancelRefusal }`.
- **(f) Authority it must NOT introduce.** Cancel must be **fail-closed** — it MUST NOT
  silently fall through to `start()` or `run()`. A `CancelOutcome.Refused` is the explicit
  answer the contract expects. No new lease authority; cancel reuses the existing
  `FileBackedRunExecutionLeaseStore` (or its pure-decider `RunExecutionLease`) to verify
  authority before it writes.

### B.3 No public RECOVER port — the recoverable path is internal to PK

- **(a) Fabric needs** "bring this run back to a known state without re-running effects" — an
  idempotent, re-entrant port that, given a run whose writer is dead or paused, returns a
  typed snapshot of what is known (`Recoverable` / `ReuseTerminal` / `FailClosed` with the
  reason), and on `Recoverable` advances the durable state to the terminal that the substrate
  observed WITHOUT re-executing the external effect (no second network call, no second file
  write).
- **(b) PK has** the entire decision tree, behind `internal`:
  - `RecoveryInterpretationEngine.kt:65` — the effectful half (writes the journal, emits
    lifecycle events).
  - `RecoveredExecutionMaterializer.kt:60` — the pure adapter that turns an observed terminal
    into a typed Step value.
  - `RunningSubprocessRecovery.kt:65` — the substrate observer (`internal fun interface`).
  - `RunningSubprocessObservation.kt:131-166` — the typed result the observer returns.
  - `EffectReplayPolicy.kt:63` — the normative decision matrix (Rerun/Skip/Abort), pinned by
    `EffectReplayPolicyTableFitnessTest`.
  - `StepReconcilerL1.kt:63` — the L1 reconciler with `Classification.Complete / Reattach /
    TimedOut / Lost`.
  - `OutputRecoveryPort.kt:166-176` — the output store's private `recover()` (in the
    non-published `:pipeline-output-store`).
  The contract surface that comes closest is `OutputRefusal.RecoveryNotCompleted`, which a
  consumer sees when it reads from a store whose `recover()` has not been called yet — that
  is the *signal* of unreadiness, not a way to act on it.
- **(c) Missing shape.** A `RuntimeRecoverPort.recover(runId): RecoveryOutcome` that returns
  a closed ADT covering `RecoveredTerminal(terminal)` / `ReattachPending(deadlineMs)` /
  `FailClosed(reason: RecoveryFailure)` / `AlreadyRecovered`. The decision logic is the
  composition of the existing pure deciders; the effect is journal-write-once, then return.
- **(d) Proposed new port.** `RuntimeRecoverPort` in `:pipeline-events` (or `:pipeline-runtime`)
  with `suspend fun recover(runId): RecoveryOutcome`. Segregated from `RuntimeControlPort` and
  `RuntimeIntrospectionPort`, per the contract's `inspect/recover/cancel` segregation rule.
- **(f) Authority it must NOT introduce.** No new scheduler, no new lease, no new fencing
  scheme. Recover MUST compose `EffectReplayPolicy.decide` + the journal's existing
  `beginOperation` / `append` path + the output store's existing `recover()` — it MUST NOT
  reimplement any of them. The contract's `OperationJournal` interface (already public inside
  PK) gains no new methods.

### B.4 No RUN-LEVEL view of "is the run terminal?" as a typed port

- **(a) Fabric needs** a single typed answer, not a derived lambda. The application layer
  today answers it via `ObserveLanes.runFinished(runId) = eventsOf(runId).any { it is
  RunFinished }` (`MainObserveCli.kt:467`), which is fine for the CLI but is NOT a published
  port and is a per-event scan, not an indexed one.
- **(b) PK has** `OperationJournal.listForRun(runId)` (returns the whole ordered list — O(n)
  per call), `RunFinished` as a `DomainEvent` (the terminal fact), and the journal's
  `ended_at` column per row (the timestamp the journal would surface as the terminal at-ms).
- **(c) Missing shape.** A read-side method on the journal that returns "is there a
  `RunFinished` (or any other terminal outcome) row for this run, and when was it?" — an
  indexed lookup, not a list scan. The shape that PK-SPEC-02 §"Modelo funcional" sketches
  (`RecoveredTerminal(attempt, terminal: TerminalObservation)`) is the right shape; the
  current implementation derives it by enumeration.
- **(d) Proposed new port.** `RuntimeIntrospectionPort.inspect(runId)` returning the closed
  `RuntimeObservation` ADT (§B.1 above) — same port, same call; this is a piece of B.1, not
  a separate gap.

### B.5 The output plane's `RecoveryNotCompleted` refusal needs an inspectable counterpart

- **(a) Fabric needs** "is this output store currently in need of recovery, or has it been
  recovered?" — so a Fabric that polls a freshly-resumed run knows whether the next read will
  be refused or not.
- **(b) PK has** the refusal `OutputRefusal.RecoveryNotCompleted` (`OutputRefusal.kt:14-60`)
  on every read path; the store's recovery status itself is not surfaced anywhere.
- **(c) Missing shape.** A typed query `OutputRecoveryStatus(storeId): RecoveryStatus` that
  returns one of `NotNeeded` / `Pending` / `Completed(at)` / `Unavailable(reason)` — the
  inspect-shaped twin of the existing `OutputRecoveryPort.recover()` write-side.
- **(d) Proposed new port.** A new method on `RuntimeIntrospectionPort`, or a new sibling port
  `OutputPlaneInspectionPort` if the segregation rule is in spirit.

### B.6 The `Recover`/`Recoverable` vocabulary is split across two layers

- **(a) Fabric needs** one vocabulary for "this run can be brought to a known state". Today
  `OperationJournal.listForRun` returns the journal rows and `OperationStatus` (`SUCCEEDED /
  FAILED / UNSTABLE / RUNNING / ABORTED / DIVERGENT / LOST / FAILED_TIMEOUT`) carries the
  decisioning labels. But "Recoverable" — the **policy** that decides which labels a fresh
  step may reuse — lives in `EffectReplayPolicy.decide(...)` and is computed by an internal
  call. There is no public function `(project, callable, runId) -> RecoveryDecision` that a
  consumer can call to ASK "what would happen if I recovered this run right now?".
- **(b) PK has** the decider (`EffectReplayPolicy.decide`) and the types (`ReplayDecision`,
  `OperationStatus.isReusableCompletion()`); both are internal-or-shared but neither is exposed
  as a public "what would you do" call.
- **(c) Missing shape.** A pure, read-only function `decideRecovery(observation: RuntimeObservation,
  journal: JournalProof): RecoveryChoice` — exactly the shape `PK-SPEC-02-RUNTIME-OBSERVATION.md`
  sketches at lines 22-37 (`Reattach`, `ReuseTerminal`, `FailClosed`). Exposing this makes the
  decision observable and testable without running the run.
- **(d) Proposed new port.** A new pure-decider object `RuntimeRecoverDecision` co-located
  with `RuntimeRecoverPort`; it takes the public types and returns the public
  `RecoveryChoice` ADT, with no side effects.

### B.7 The cancel primitive cannot be reached without writing through the coordinator

- **(a) Fabric needs** to be able to cancel a run **without** standing up a new
  `CanonicalDurableRunCoordinator`. The coordinator's only public verb is `run(...)`; cancel
  today can only happen as a side-effect of the writer's `finally` clause.
- **(b) PK has** the lifecycle bookends (`RunLifecycleEngine.openRun` / `closeRun`,
  `…/durable/RunLifecycleEngine.kt:35-179`) but the close path is `internal` and only emits
  `RunFinished` with the outcome the run already folded; there is no separate cancel path.
- **(c) Missing shape.** A write-side path through the journal (or a sibling journal) that
  records the cancel intent durably and is idempotent; the coordinator's writer subscribes to
  it as part of its normal `append` cycle.
- **(d) Proposed new port.** `RuntimeControlPort.cancel(runId, reason)` (§B.2) is the
  consumer-facing verb; its implementation lives next to `RunLifecycleEngine` but is
  reachable from outside the JVM that owns the writer.

### B.8 Fabric's M2 needs do not include a new lease; PK's M2 work must not invent one

This is the negative gap. The Block 2 plan says: *"No mover leases, fencing o scheduler a
PipelineK."* The audit confirms the existing lease authority (`RunExecutionLease` +
`FileBackedRunExecutionLeaseStore`, in the non-published `:pipeline-events-store`) is already
what PK uses for its run-publishing authority, and is NOT what Fabric's worker scheduler will
use — Fabric's lease authority is its own concern. The audit therefore:

- **does NOT recommend** moving `RunExecutionLease` or `FileBackedRunExecutionLeaseStore` into
  `:pipeline-events` (that would be a PK-internal move and a publish boundary change, both
  explicitly out of scope per the user's "No mover leases" rule).
- **does** note that any new `RuntimeControlPort.cancel` MUST consult the existing
  `RunExecutionLease.acquire` to confirm authority before it writes, and MUST refuse on
  `LeaseAcquisition.AlreadyOwned` with `CancelRefusal.LeaseHeldByAnother(...)`.

## C. Overlap — places where two ports claim the same authority

Each overlap is named, the two surfaces are listed, and the audit's recommendation is given.
Overlap is not a defect by itself; an audit that does not name it will leave a drift hazard
behind.

### C.1 `OutputTailPort.tailState` and `OutputReadPort.committedExtent`/`read`

- **Surfaces.** `OutputTailPort.tailState(stream): OutputTailState?` (Open | Sealed | null)
  vs `OutputReadPort.committedExtent(stream): Long?` (the byte offset) and the `next == null`
  signal on `OutputPage`.
- **Same authority.** "Can more bytes arrive on this stream?"
- **Recommendation.** Demarcate formally: `OutputReadPort` is the byte-level port; `OutputTailPort`
  is the terminality port. The KDoc on `OutputReadPort.kt:11-15` already states this split;
  the audit restates it. A reader that wants "is this stream done?" MUST go through
  `OutputTailPort.tailState`, not through `next == null`. The M1 follow machinery already
  uses `tailState` via `followDecision(...)` (`ObservationWakeup.kt:121-196`).

### C.2 `EventHistory.history` and `EventTail.readAfter`

- **Surfaces.** `EventHistory.history(run, query): Sequence<PipelineEventEnvelope>` (full
  sequence, unbounded) vs `EventTail.readAfter(run, cursor, limit): EventPage` (paged,
  with refusals).
- **Same authority.** "What events has this run produced, in what order?"
- **Recommendation.** Demarcate: `EventHistory.history` is for bounded queries (the CLI replays
  the whole run into a stream; `--limit N` is the only size cap); `EventTail.readAfter` is for
  long-running reads and follow. A consumer that wants a follow MUST go through `EventTail`,
  not through `EventHistory.history` followed by an in-memory filter — that is exactly the
  pattern `EventInspection.follow(...)` (`EventInspection.kt:100-103`) was added to avoid.

### C.3 `EventInspection.follow(envelopes, afterSequence)` and `EventTail.readAfter`

- **Surfaces.** The pure projection `EventInspection.follow(envelopes, afterSequence)` vs the
  effectful port `EventTail.readAfter(run, cursor, limit)`.
- **Same question.** "What comes after sequence N?"
- **Recommendation.** Demarcate by effect: `EventInspection.follow` is pure (operates on an
  already-fetched list, returns a filtered list); `EventTail.readAfter` is effectful (calls the
  store, returns an `EventPage` with refusals). They are complementary, not redundant.

### C.4 `ConsoleReadService.read` and `ObservationOutputReader.readOutput`

- **Surfaces.** `ConsoleReadService.read(controlDirRoot, runId, opId, after, maxBytes)` (byte-
  cursor over ONE operation's two channels) vs `ObservationOutputReader.readOutput(runId,
  afterOrdinal, frameLimit)` (frame-ordinal over a RUN, multiple operations interleaved).
- **Same authority.** "The bytes a run produced."
- **Recommendation.** Demarcate: `ConsoleReadService` is for the per-operation CLI verb
  (`pipeline console`); `ObservationOutputReader` is for the per-run query (`pipeline observe
  --view console`). Both stay; the KDoc on `ObservationOutputReader.kt:17-34` already explains
  the split.

### C.5 `OperationJournal.get(opId)` / `get(op, attempt)` / `listForRun(runId)`

- **Surfaces.** Three reads on the same journal: per-op latest, per-op-and-attempt, per-run
  ordered.
- **Same authority.** "What is in the journal?"
- **Recommendation.** Keep all three; they answer different questions. No action.

### C.6 `ReplayCursorStore.load(runId)` and `OperationJournal.listForRun(runId)`

- **Surfaces.** The cursor store is "where to resume from" (runId / lastOpId / stageIndex /
  savedAt); the journal is "what happened" (rows).
- **Same authority.** "What is the durable state of this run?"
- **Recommendation.** The audit does NOT recommend merging them — they are different
  authorities over different shapes. But it does recommend that `RuntimeIntrospectionPort.inspect(runId)`
  reconcile both into a single `RuntimeObservation` so a consumer does not have to call them
  separately and merge the answers by hand.

### C.7 `OutputRecoveryPort.recover` (private) and `RunOutputRetention.onRunTerminal` (public)

- **Surfaces.** `OutputRecoveryPort.recover()` (`:pipeline-output-store`, not published) vs
  `RunOutputRetention.onRunTerminal` (`:pipeline-application/durable`, public class). Both
  authorise output-side effects on a run that has reached terminality; both are invoked from
  the coordinator's path (`OutputPlaneProvider.storeForWriting` calls the former; the
  coordinator's `finally` calls the latter).
- **Same authority.** "Make this run's output state consistent with its declared terminality."
- **Recommendation.** Keep the split — recovery is destructive and gated to the writer's JVM,
  retention is the post-terminal pruning the runtime calls. Demarcate explicitly in
  `OutputRecoveryPort`'s KDoc (when the recovery port is moved into the published module as
  part of M2) and in `RunOutputRetention`'s KDoc. Today neither port is reachable through the
  published ABI, so the demarcation is invisible to consumers — that is itself a gap
  (§B.5).

## D. Invariants the contract pins on these verbs

The contract §5 reads: *"Una lectura no ejecuta recuperación destructiva; no relanza efectos
externos automáticamente."* That pins three invariants on the verbs. The audit checks each one
against the surveyed ports.

### D.1 "A read MUST NOT execute destructive recovery"

- **What it asserts.** Any port classified as `Inspect` (or `Follow`, which is read-shaped)
  must not invoke `OutputRecoveryPort.recover()`, must not advance the lease, must not modify
  the journal or the frame index.
- **Verdict per port.** PASS, with two exceptions that are NOT classified as Inspect:
  - `EventHistory.history` / `EventTail.readAfter` — PASS, both are pure reads.
  - `OutputReadPort` / `OutputTailPort` / `OutputFrameIndex.framesOfRun` / `OutputRetentionPort.hasOutputFor`
    — PASS; the only state-modifying methods (`OutputRetentionPort.prune`,
    `OutputFrameIndex.append`, `OutputFrameIndex.declareStream`) are NOT Inspect, and a
    consumer that wants Inspect gets only the read-shaped methods.
  - `OperationJournal.get*` / `listForRun` / `getDeadlineMs` / `getEndedAt` / `getStartedAt`
    — PASS, every one of these is a SQL SELECT.
  - `EventInspection` (object) — PASS, the KDoc at lines 22-24 says "pure; the CLI is the
    effectful boundary".
  - **Exception 1.** `OutputRecoveryPort.recover()` itself — this is destructive by design,
    but is NOT classified as Inspect and is not reachable through the published ABI today
    (§B.3). The M2 implementation of `RuntimeRecoverPort.recover` MUST NOT add an Inspect
    shortcut that calls `recover()` internally.
  - **Exception 2.** The current application-level `MainObserveCli.follow(...)` reads from a
    freshly-opened `OutputPlaneProvider.storeForReading(...)` (`MainObserveCli.kt:578-585`),
    which by design refuses to call `recover()`. PASS on the contract; PASS on the
    documented law at `OutputPlaneProvider.kt:115-148`.

### D.2 "A read MUST NOT re-trigger external side effects"

- **What it asserts.** A read classified as `Inspect` (or `Follow`) must not invoke any
  `Effect`-tagged operation: no subprocess relaunch, no network call, no file write outside
  the reader's own scratch.
- **Verdict per port.** PASS across the surveyed ports. The only `Effect`-tagged operations
  live behind `EffectReplayPolicy.decide` (which is a decision, not an effect), `StepReconcilerL1`
  (which only inspects a control directory), and the writer-side `OutputAppendPort` /
  `OutputStreamHandle` / `OutputReservation` — none of which is reachable through any
  read-shaped port.

### D.3 "Cancel MUST be exactly-once and idempotent"

- **What it asserts.** A cancel call made N times must produce one terminal state, not N.
  Two concurrent cancel calls must produce one terminal state, not a race.
- **Verdict per port.** CANNOT BE TESTED — no public `Cancel` port exists today (§B.2).
  The audit flags this invariant as **UNVERIFIED** and recommends that the M2 implementation
  pin it with a contract test (table-exhaustive over `CancelOutcome.Cancelled` /
  `CancelOutcome.AlreadyCancelled` / `CancelOutcome.Refused` from §B.2) before the port is
  advertised.

### D.4 "Recovery MUST NOT re-execute external side effects"

- **What it asserts.** A recover call must not call the subprocess again. The recovered
  terminal — observed by `StepReconcilerL1.classify` or by the journal's terminal row — is
  the answer the recover path returns.
- **Verdict per port.** PARTIALLY VERIFIABLE. The internal `RecoveryInterpretationEngine.interpret`
  (`RecoveryInterpretationEngine.kt:206-238`, the `RecoverRunning` arm) reuses the observed
  terminal and emits the typed value through `RecoveredExecutionMaterializer`; the
  `Execute` arm (`RecoveryInterpretationEngine.kt:261`) is the one that DOES re-execute, and
  is reached only when the recovery decider returns `InvocationReconciliation.Execute`. The
  audit cannot verify at the published ABI that the decider is pure (it is internal); the
  M2 work MUST pin `RuntimeRecoverPort.recover` so that the public `RecoveryOutcome.RecoveredTerminal`
  case is the only path that does not re-execute, and the public `RecoveryOutcome.FailClosed`
  case carries the reason when the substrate cannot be observed.

### D.5 "The verbs MUST be segregated interfaces, NOT one wide port"

- **What it asserts.** `RuntimeIntrospectionPort`, `RuntimeControlPort`, `RuntimeRecoverPort`,
  `OutputFollower`, `EventFollower` are five different types — not one `RuntimePort` with
  every verb on it.
- **Verdict per port.** PASS-by-design — none of the surveyed types is a "wide port", and
  `OperationJournal` is the only place that carries both read and write shapes, and that is
  documented at length at `OperationJournal.kt:18-27`. The M2 ports MUST follow the
  same segregation rule.

### D.6 "The existing REAL public ports of PipelineK are reused, NOT duplicated"

- **What it asserts.** M2 MUST compose existing read ports; it MUST NOT re-implement them.
- **Verdict.** PASS-by-design for the proposed ports in §B: every proposed new method
  composes `EventHistory` / `EventTail` / `OutputReadPort` / `OutputTailPort` /
  `OutputFrameIndex` / `OperationJournal` / `OutputRecoveryPort` /
  `RunExecutionLease` / `FileBackedRunExecutionLeaseStore`. No port in §B proposes a parallel
  hierarchy; every proposal is a thin composition over ports that exist today.

## E. Cross-walk to the UAT / AAT / FITNESS evidence

The existing acceptance evidence is in `docs/pipelinek-coordinated-evolution/acceptance/`
(`UAT.md`, `AAT.md`, `FITNESS.md`). The audit cross-walks each existing case to the verb it
covers, and notes which gaps the M2 work would close.

### E.1 UAT cases that the existing ports already cover

| UAT ID      | Verb(s) today                       | Ports touched                                  | Status      |
|-------------|-------------------------------------|-------------------------------------------------|-------------|
| UAT-PK-001  | Inspect + Follow                    | `OutputReadPort` / `EventTail`                   | Already PASS (v0.48.0-rc3) |
| UAT-PK-002  | Follow                              | `OutputReadPort` + `OutputFrameIndex`            | Already PASS |
| UAT-PK-003  | Inspect                             | `EventTail` / `EventHistory`                     | Already PASS |
| UAT-PK-004  | Recover (recover from observer kill) | `OutputReadPort` + `ReplayCursorStore`            | Already PASS — but only because the existing read path is non-destructive; §D.1 |
| UAT-PK-005  | Recover (writer SIGKILL, idempotent) | `OutputAppendPort` (private) + `OutputReadPort`  | Already PASS — but again, only through the internal writer JVM. §B.3 |
| UAT-PK-006  | Inspect (no secret in console)      | `OutputReadPort` + `RedactingOutputIngress`       | Already PASS |
| UAT-PK-007  | Inspect (UTF-8 boundaries)          | `OutputReadPort.readRange`                       | Already PASS |
| UAT-PK-008  | Inspect (typed value not exported)  | `EventHistory` + event-sink pathway              | Already PASS |
| UAT-RP-003  | Follow                              | `EventTail.readAfter`                            | Already PASS (M1) |
| UAT-RP-004  | Follow                              | `OutputTailPort` + `OutputReadPort`              | Already PASS (M1) |

### E.2 UAT cases that are gaps relative to M2's verbs

| UAT ID      | What is missing                                                          | Closed by gap    |
|-------------|--------------------------------------------------------------------------|------------------|
| UAT-PK-009  | "is this run currently running?" — typed query, not event-scan           | B.1 / B.4        |
| UAT-PK-010  | "cancel this run from outside the writer JVM, ensure no further effects"  | B.2 / B.7        |
| UAT-PK-011  | "recover this run, observe the terminal, do not re-execute the subprocess"| B.3 / B.6        |
| UAT-PK-012  | "multiple concurrent inspectors do not race the writer"                  | §D.1 (already PASS-by-design; needs contract test) |
| UAT-PK-013  | "cancel races with itself exactly-once"                                   | §D.3 (UNVERIFIED today; needs contract test) |
| UAT-PK-014  | "recover races with itself exactly-once, terminal never published twice"  | §D.4 (needs contract test) |

These UAT IDs are **suggested** numbers, not pre-existing. They are named here so the M2 design
can adopt them (or re-number them) and so the implementation's UAT matrix is grounded in the
audit instead of in ad-hoc cases.

### E.3 AAT cases that intersect with the M2 audit

| AAT ID   | Layer                                | Verdict in this audit                            |
|----------|--------------------------------------|--------------------------------------------------|
| AAT-01   | Output plane — SIGKILL on prepare/commit | Already PASS-by-design on the read path; §D.1 |
| AAT-02   | Output plane — 2 JVM, 1 writer + 2 readers | Already PASS-by-design on the read path; §D.1 |
| AAT-03   | Output plane — millions of frames, small tail | Already PASS via `OutputFrameIndex`; AAT-03 is what proves it |
| AAT-04   | Output plane — UTF-8 + secrets + deadlocks | Already PASS via `OutputReadPort` + `RedactingOutputIngress` |
| AAT-37   | Secrets + retention + indexer (cross-cutting) | Touches both retain and prune; the new M2 ports MUST respect `OutputRetentionPort`'s terminal-only-by-type rule |
| AAT-43   | fsync/IO latency                     | The M2 read ports are pull-only by design; the same baseline applies |

### E.4 FITNESS metrics the M2 work must respect

- `p0_cancel_delivery_p95 < 2 s` under output saturation — a cancel port MUST NOT block on
  the writer's bytes to drain; it MUST publish a terminal fact and let the writer catch up.
- `double_side_effect_count = 0` — a recover that re-executes an external effect is the
  primary way this counter goes non-zero; the M2 port must close it (§D.4).
- `out_of_order_advances = 0` — a cancel that arrives mid-append MUST NOT cause the journal
  to publish a fact with a sequence less than the cancel fact's.
- `controller_cpu_silent` — the new M2 read port MUST NOT introduce a per-customer polling
  loop with a fixed interval; reuse the existing `ObservationWakeup` ADT and the existing
  `MainObserveCli.FOLLOW_IDLE_MILLIS = 25L` baseline.

## F. Decision

```
BLOCK 2 PK-CANDIDATE NEEDED.
```

Reasoning:

1. **No public `Inspect` port exists** (§B.1, §B.4). The contract requires Fabric to ask
   "what does this run look like right now?"; the only answer PK gives today is a derivation
   inside `MainObserveCli.follow(...)`. A typed `RuntimeIntrospectionPort.inspect(runId)`
   returning the closed `RuntimeObservation` ADT the contract already sketches is needed.

2. **No public `Cancel` port exists** (§B.2, §B.7). The contract requires cancel to be
   terminal, single-shot, and idempotent (§D.3); the only kill primitive in the codebase is
   `DurableShellLaunching.kill(...)`, which is not a cancel and is not reachable through the
   published ABI. A typed `RuntimeControlPort.cancel(runId, reason)` with the
   `CancelOutcome` ADT is needed.

3. **The existing `Recover` machinery is internal** (§B.3, §B.6). The decision tree
   (`EffectReplayPolicy.decide`), the substrate observer (`RunningSubprocessRecovery`),
   the materialiser (`RecoveredExecutionMaterializer`), and the interpretation engine
   (`RecoveryInterpretationEngine`) are all `internal` today. A typed
   `RuntimeRecoverPort.recover(runId)` with the `RecoveryOutcome` ADT and a pure-decider
   `RuntimeRecoverDecision.decideRecovery(observation, journal)` companion is needed.

4. **The existing invariants are mostly PASS-by-design but the cancel idempotency and
   recover-no-rerun invariants are UNVERIFIED** (§D.3, §D.4) because the ports that would
   pin them do not exist. M2 work MUST ship those pins.

5. **The decision is NOT a Fabric-only compatibility handoff.** The three public ports the
   contract requires — `inspect`, `recover`, `cancel` — are real PK API evolution. Per the
   release-receipt v2 model, that evolution is one PK candidate (`v0.50.0-rc1` or the next
   pre-release), the same shape M1 used (`v0.49.0-rc1`) to publish
   `output.follow.v1` / `events.follow.v1`.

6. **No overlap requires PK-side consolidation before M2 ships.** §C's seven overlaps are
   demarcated by their existing KDoc; the M2 ports sit ON TOP of the demarcation rather
   than re-doing it. The one consolidation candidate — the `OutputRecoveryPort` /
   `RunOutputRetention` split (§C.7) — is **not** a Block 2 deliverable; the audit notes it
   as a follow-up.

7. **Authority moves the audit explicitly forbids are NOT proposed.** §B.8 confirms the M2
   work introduces no new lease, no new fencing scheme, no new scheduler; cancel consults
   the existing `RunExecutionLease.acquire` to verify authority, recover uses the existing
   `EffectReplayPolicy.decide`, and inspect composes the existing read ports. The user's
   "No mover leases, fencing o scheduler a PipelineK" rule is preserved.

## G. Open issues for the orchestrator

None that block the audit. The following items are flagged for the design phase that
consumes this document; they are not audit failures:

1. The M2 design must name the home module for `RuntimeIntrospectionPort`,
   `RuntimeControlPort`, and `RuntimeRecoverPort`. Two options are consistent with the
   contract: (a) keep all three in `:pipeline-events`, mirroring the M1 shape; (b) split them
   into a new `:pipeline-runtime` module. The audit makes no recommendation; the design must
   pick one and justify it against the segregation rule (§D.5).

2. The M2 design must decide whether `RuntimeIntrospectionPort.inspect(runId)` is allowed to
   report `Running` for a run whose lease is held by another process. The audit's stance is
   "yes, name the live holder and its fencing token" (the `LeaseHeldByAnother(...)` case in
   §B.1) — but that requires the introspection port to call the existing
   `RunExecutionLease.acquire` (or its effectful adapter
   `FileBackedRunExecutionLeaseStore`), and the lease store is not published. The design must
   either publish the lease store or add a read-side twin for it.

3. The M2 design must decide whether `RuntimeControlPort.cancel(runId, reason)` is allowed
   to flip a run whose lease is held by another process. The audit's stance is "no, refuse
   with `CancelRefusal.LeaseHeldByAnother(...)`" — the cancel port MUST NOT cross lease
   boundaries; that authority belongs to Fabric.

4. The UAT case IDs in §E.2 are suggested; the M2 design may re-number them to match the
   package's `UAT-PK-M2-###` convention introduced by M1 (`UAT-PK-M1-001..006`).

5. The contract change required is a one-cell update to `INTERFACE_CONTRACT.md`'s
   "Capacidades publicadas" table to add three rows once M2 ships. This is identical in
   shape to the M1 promotion and requires the same `CONTRACT_SHA256.txt` refresh and
   `release-receipt` evidence.