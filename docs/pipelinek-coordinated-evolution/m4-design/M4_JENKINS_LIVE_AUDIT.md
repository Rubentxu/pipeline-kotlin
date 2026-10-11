# CRIC-M4 — JENKINS LIVE audit (input to M4 design)

**Status:** read-only audit, completed before any M4 code is written.
**Worktree:** `pk-cric-m4` (branch `audit/cric-m4-jenkins-live`) at `3ac37658`
(`release/cric-m3-v0.51.0-rc1`).
**Anchor:** the Block 4 plan ("Verificar compatibilidad del productor con lectura
simultánea desde Jenkins y un segundo inspector sobre el mismo run. Exigir cursors
independientes, salida completa, terminalidad legítima y ninguna cancelación
inducida por observación. No introducir dependencias Jenkins en PipelineK ni
duplicar reducers. **Salida:** si no cambia PK, conservar su artefacto publicado y
verificado; el release de este bloque es el de Fabric M4. Si aparece un defecto
real de PK, producir candidata correctiva certificable") and the normative text in
`coordination/INTERFACE_CONTRACT.md` §1, §2, §3, §5, §6, §7, §9.
**Precedent:** `m3-design/M3_RANGE_RETENTION_AUDIT.md` — the same read-only survey
shape, applied to a different block of CRIC guarantees.

This document is the audit. It is the input to the M4 design (if a design is
needed) and to the Fabric-side M4 work; PK itself ships no new surface.

## 0. Scope and naming

The Block 4 plan names one scenario and four properties. The scenario is "Jenkins
plus a second inspector on the same run". The properties are
**independent cursors**, **complete output**, **legitimate terminality**, and
**no observation-induced cancellation**. The plan further names a negative scope
("No introducir dependencias Jenkins en PipelineK ni duplicar reducers") and an
output rule ("si no cambia PK, conservar su artefacto publicado y verificado").

The audit classifies every public port along four axes (one per property in the
plan) and pins six invariants the contract carries over from M1/M2/M3:

```text
INDEPENDENT CURSORS      two observers on the same run advance independent cursors;
                          one does not affect the other
COMPLETE OUTPUT          an observer that follows from the start sees every
                          committed byte / every committed event in sequence,
                          modulo retention (which surfaces as a typed refusal)
LEGITIMATE TERMINALITY   the observer's read path reports the writer's terminal
                          state, not its own guess about quiet periods
NO OBSERVATION-INDUCED   a second observer opening a follow or read does NOT
CANCELLATION             trigger the cancel path; the follow is observational
```

Plus the six invariants that flow from those axes and from the existing
contract:

```text
I.1 independent cursors
I.2 complete output
I.3 legitimate terminality
I.4 no observation-induced cancellation
I.5 observer does not advance writer state
I.6 reconnects preserve identity
```

The audit answers, in order:

1. What public ports already exist that touch any of these axes (§A).
2. What gaps exist between what Jenkins + a second inspector need and what PK
   exposes (§B).
3. Where two existing ports claim the same authority over dual-observer state
   (§C).
4. Whether the six invariants the contract pins on dual-observer hold today,
   and which ones fail (§D).
5. How the audit cross-walks to the existing UAT-PK / AAT / FABRIC evidence (§E).
6. The single decision: PK-CANDIDATE NEEDED or COMPATIBILITY HANDOFF ONLY (§F).

All paths are absolute. The `3ac37658` baseline is the only commit consulted;
M1/M2/M3 work is referenced for context only and is pinned by
`release/cric-m1-v0.49.0-rc1`, `release/cric-m2-v0.50.0-rc1`,
`release/cric-m3-v0.51.0-rc1` respectively.

## A. Public ports that already touch dual-observer semantics

The survey covers the three published modules the user's brief names —
`:pipeline-events`, `:pipeline-output`, and `:pipeline-runtime` — and lists
every port whose behaviour an observer (Jenkins, the second inspector, an
internal CLI, a Fabric replica, an indexer) would compose. Two columns matter:

- **Published?** A module with a `maven-publish` block in its `build.gradle.kts`
  is on the ABI. Per `INTERFACE_CONTRACT.md` §6, "el consumidor compila contra el
  ABI publicado, nunca contra el árbol de fuentes o `mavenLocal`".
- **Axis.** One of the four axes above, or a closed subset.

### A.1 `:pipeline-events` (published module `pipeline-events`)

The event plane is the **substrate for run-level terminality** and the
**typed-event read path**. Both surfaces are pull-by-call (no shared connection
state) and pull-by-handle (each `open` is a fresh poll loop).

```
EventHistory (interface)            | …/identity/EventHistoryPorts.kt:151-153   | Independent, Complete
                                    | history(run, query): Sequence<PipelineEventEnvelope>
                                    | Pure read; no per-connection state.
                                    | A second observer calls `history(...)` again
                                    | and gets a fresh Sequence over the same run.

EventTail (interface)               | …/identity/EventHistoryPorts.kt:159-161   | Independent, Complete
                                    | readAfter(run, cursor, limit): EventPage
                                    | Paged continuation; cursor is a value type
                                    | owned by the caller (EventHistoryPorts.kt:22-53).

EventCursor (data class)            | …/identity/EventHistoryPorts.kt:22-53     | Independent
                                    | (runId, lastSequence); wire evt-cursor-v1:.
                                    | Distinct type from OutputCursor
                                    | (EventHistoryPorts.kt:33-53 WHY block).

EventQuery (sealed class)           | …/identity/EventHistoryPorts.kt:59-95     | Complete
                                    | closed typed filters: All / ByKind / BySource
                                    | / BySubject / BySequenceRange.

EventPage (data class)              | …/identity/EventHistoryPorts.kt:102-137   | Complete, Terminality
                                    | envelopes + nextCursor + hasMore + refusals
                                    | (typed undecodable rows).
                                    | **NEVER-356 Termination rule (EventHistoryPorts.kt:102-105):**
                                    | "Never claims completeness: [remaining] distinguishes
                                    |  a definite end from a truncated page. Absence of a
                                    |  RunFinished event is NOT interpreted as 'complete'."
                                    | That is exactly the "legitimate terminality" rule:
                                    | KStream never infers terminal state on its own.

EventHistoryReader (class)          | …/identity/EventHistoryReader.kt:32-82    | Complete
                                    | Adapter implementing both EventHistory and
                                    | EventTail over any EventSink. The reader does
                                    | NOT mutate state (KDoc lines 24-30).

EventRecordReadPort (interface)     | …/identity/EventRecordReadPort.kt:45-109  | Independent, Complete,
                                    | readRecords(runId, after, query, limit):    | Terminality
                                    |   EventRecordReadResult
                                    | Typed read port (M1-A); returns
                                    | EventRecordSlice with Undecodable rows.
                                    | **Independent cursors**: `after: EventCursor?`
                                    | is the consumer's value (line 65). Each call is
                                    | pull-by-call; no per-connection state.
                                    | **Complete**: refusals surface as typed rows
                                    | (Undecodable), never as silent gaps.
                                    | **Legitimate terminality**: refusal on
                                    | `CursorBeyondTail(runId, requestedSequence,
                                    | tailSequence)` (lines 102-105); never a guess.

EventRecordReadRefusal (sealed)     | …/identity/EventRecordReadPort.kt:91-109  | Complete, Terminality
                                    | UnknownRun / CursorBeyondTail / StorageError.
                                    | Closed ADT: a read either returns typed rows
                                    | with refusals or a typed refusal, never
                                    | "I have not seen anything yet" masquerading
                                    | as completeness.

EventFollower (interface)           | …/follow/EventFollower.kt:49-74            | Independent, Complete,
                                    | open(runId, options): EventFollowHandle.   | Terminality
                                    | **Independent cursors** (EventFollower.kt:65-72):
                                    | "The handle is single-observer: a second
                                    |  consumer that wants to observe the same run
                                    |  independently calls [open] again."
                                    | **No observation-induced cancellation** (KDoc
                                    | 39-44): the handle is `AutoCloseable`; the
                                    | consumer closes it via `use { }`. No cancel
                                    | verb reachable through this port.

EventFollowHandle (interface)       | …/follow/EventFollower.kt:94-118           | Independent
                                    | AutoCloseable; iterator yields events in a
                                    | single pass; single-thread per handle.
                                    | Two consumers each call `open` and each get
                                    | their own handle (lines 79-82).

EventFollowOptions (data class)     | …/follow/EventFollower.kt:124-152          | Independent, Complete
                                    | query, pollIntervalMs, maxRecords, until,
                                    | **after: EventCursor? (lines 132-138)**.
                                    | "A consumer that crashed mid-poll persists the
                                    |  last [EventRecordSlice.nextCursor] it observed
                                    |  and reopens with `after = lastCursor`."
                                    | That is the I.6 reconnect-preserves-identity
                                    | primitive.

EventFollowUntil (sealed)           | …/follow/EventFollower.kt:158-166          | Terminality
                                    | Unbounded / UntilRunFinished / UntilSequence.
                                    | The terminal predicate is EXPLICIT, not inferred.

EventFollowState (sealed)           | …/follow/EventFollower.kt:178-190          | Terminality
                                    | Live(lastSequence) / RunFinished /
                                    | Unobservable(refusal).
                                    | **RunTerminal detection rule (KDoc lines
                                    | 170-176)**: "Terminal detection follows the
                                    | existing `MainObserveCli.followEvents` rule: a
                                    | `DomainEvent.kind == 'RunFinished'` observation
                                    | transitions the follow from [Live] to
                                    | [RunFinished]." That is "legitimate terminality"
                                    | in one sentence.

EventFollowRefusal (sealed)         | …/follow/EventFollower.kt:206-210          | Complete, Terminality
                                    | UnknownRun / RetentionLost / Cancelled.
                                    | **No LagExceeded case** (KDoc lines 200-204):
                                    | a slow consumer is not a defect; the follow
                                    | reports lag at `lagReportInterval` (line 145).
                                    | That is the I.5 "observer does not advance
                                    | writer state" pin — a slow consumer cannot
                                    | trip the writer.

EventFollowEvent (sealed)           | …/follow/EventFollower.kt:218-223          | Independent, Terminality
                                    | Page(slice, newState) / StateChanged(state) /
                                    | Refused(refusal) / Completed.
```

**Verdict.** The event plane has a complete **typed-event independent-cursor
read surface** (`EventHistory`, `EventTail`, `EventRecordReadPort`), a complete
**legitimate-terminality follow** (`EventFollower` with `EventFollowState.
RunFinished` ONLY after `RunFinished` is observed), and an explicit
**reconnect-preserves-identity primitive** (`EventFollowOptions.after`). There
is **no observation-induced cancellation** reachable through any event-plane
port: `EventFollower` does not invoke `RuntimeControlPort.cancel`; the only
cancellation primitive in the published plane is the closed
`EventFollowRefusal.Cancelled` which is the FOLLOW's own cancel (the
consumer's `close()`), not the run's cancel. The M4 audit names no gap on the
event plane.

### A.2 `:pipeline-output` (published module `pipeline-output`)

The output plane is the **byte substrate**. It has the same dual-OK shape as the
event plane, plus the M3 retention/digest additions.

```
OutputReadPort (interface)          | …/output/OutputReadPort.kt:19-87           | Independent, Complete
                                    | committedExtent / read / readRange /
                                    | readRangeDigested (M3).
                                    | **Read-only by construction** (KDoc lines 3-17):
                                    | "An external consumer (Fabric's console reader)
                                    |  reads committed bytes; it does not reserve,
                                    |  commit, recover or prune." That is the
                                    |  I.5 pin for byte reads.
                                    | **Independent cursors** (OutputReadPort.kt:34):
                                    |  read(s, cursor, maxBytes) takes the cursor
                                    |  by value. Two observers hold two cursors;
                                    |  one read does not affect the other.
                                    | **Complete output** (OutputReadPort.kt:36-43):
                                    |  contractual property that `readRange(s, o,
                                    |  o + n)` equals the first n bytes of
                                    |  `readRange(s, o, committed)`.

OutputCursor (data class)           | …/output/OutputCursor.kt:57-105            | Independent
                                    | (stream, committedOffset); wire
                                    | out-cursor-v1:. Distinct type from EventCursor
                                    | (OutputCursor.kt:33-53 WHY block). The
                                    | cursor is a VALUE owned by the consumer
                                    | (OutputCursor.kt:57-65).

OutputStreamId (value class)        | …/output/OutputCursor.kt:24-31             | Identity
                                    | Stable identity of one output stream.

OutputPage (data class)             | …/output/OutputCursor.kt:121-157           | Complete, Terminality
                                    | bytes/stream/from/next/committedEnd;
                                    | invariant: page ends at next.committedOffset
                                    | when next != null (lines 128-141).
                                    | **next == null** does NOT mean "stream
                                    | finished" (lines 109-114); "a stream can grow
                                    | after the page is produced". Terminality is
                                    | the OUTPUT tail's job, not the page's.

OutputReadResult (sealed)           | …/output/OutputRefusal.kt:210-213         | Complete
                                    | Page(page) | Refused(reason). Two-case
                                    | envelope; never an exception.

OutputRefusal (sealed)              | …/output/OutputRefusal.kt:14-207           | Complete, Terminality
                                    | Closed ADT: ForeignStream / UnknownStream /
                                    | OffsetBeyondCommitted / InvalidRange /
                                    | RecoveryNotCompleted / DanglingCommit /
                                    | StreamLostRetention (M1-B, follow-side) /
                                    | FollowCancelled (M1-B) / StorageError (M1-B)
                                    | / RetentionGap (M3) / Corrupt (M3) /
                                    | Unavailable (M3) / RangeLostRetention (M3).
                                    | **All four M4 axes are answered here**:
                                    | (a) a read returns either bytes or a typed
                                    |     refusal (Complete);
                                    | (b) the cursor is owned by the consumer
                                    |     (Independent — confirmed at the call site
                                    |     OutputReadPort.kt:34);
                                    | (c) terminality is the OutputTailState's job
                                    |     (see below), not the page's;
                                    | (d) no cancel verb is reachable here.

OutputChannel (enum)                | …/output/OutputChannel.kt:23-42             | Identity
                                    | STDOUT / STDERR closed set.

OutputStreamAddress (data class)    | …/output/OutputChannel.kt:109-148           | Identity
                                    | (runId, operationId, channel); parse(...)
                                    | returns null for non-shape streams.

OperationOutputStreams (data class) | …/output/OutputChannel.kt:159-184           | Identity
                                    | stdout+stderr pair of one operation; select
                                    | and all helpers.

OutputFrame (data class)            | …/output/OutputFrameIndex.kt:50-65         | Complete
                                    | (ordinal, stream, channel, from, to);
                                    | metadata only, NEVER payload (KDoc lines 11-18).
                                    | The ordinal is observation order (lines 21-33)
                                    | and is monotonic, strictly increasing, assigned
                                    | at publication time (lines 44-46).

OutputFrameIndex (interface)        | …/output/OutputFrameIndex.kt:107-200       | Independent, Complete,
                                    | declareStream / append / framesOfRun(      | Terminality
                                    |   runId, afterOrdinal, limit) /
                                    | lastOrdinal / streamsOfRun /
                                    | recoverUnframedBytes.
                                    | **Independent cursors** (framesOfRun at
                                    | lines 142-148): `afterOrdinal` is the consumer's
                                    | value; each call returns frames after that
                                    | ordinal. **Two observers with two
                                    | `afterOrdinal` values get two different pages.**
                                    | **Reconnect identity** (lines 132-136): the
                                    | ordinal is assigned inside the call, under the
                                    | index's own ordering; it is monotonic across a
                                    | restart.

OutputTailState (sealed)            | …/output/OutputTailState.kt:42-69          | Terminality
                                    | Open(committedEnd) / Sealed(finalEnd).
                                    | **The legitimate-terminality ADT** (KDoc
                                    | lines 23-31): "they deliberately do NOT carry
                                    | `SUCCESS`, `FAILURE` or `UNSTABLE`: those
                                    | belong to the run and event planes". Putting
                                    | an outcome here would make the Output Plane a
                                    | second authority over execution results.

OutputTailPort (interface)          | …/output/OutputTailState.kt:86-96          | Terminality
                                    | tailState(stream): OutputTailState?.
                                    | `null` for unknown; Open or Sealed for known.

OutputRetentionPort (interface)     | …/output/OutputRetention.kt:149-193        | Independent (over
                                    | hasOutputFor / prune(intent) /             | retention authority)
                                    | canPrune(intent) (M3).
                                    | **Single-shot prune only** (OutputRetention.kt:
                                    | 155-161); `prune` is the act, `canPrune` is
                                    | the consult-before-act. A read does NOT
                                    | invoke prune (lines 8-15: "the store cannot
                                    | decide that they are deletable").

RunLifecycle (sealed)               | …/output/OutputRetention.kt:57-66          | Terminality
                                    | Terminal / StillRunning. Deliberately
                                    | outcome-less (KDoc lines 38-56): "retention
                                    | treats every terminal state alike, and the
                                    | engine is the authority on which one it was".

RetainUntil / OutputPruneIntent /
  OutputPruneReport                 | …/output/OutputRetention.kt:74-140         | (no dual-observer axis;
                                    | closed policy+intent.                      |  retention is the
                                    | A deletion names its reason in its type.    |  authority of one
                                    | Deliberately NO "Pin" case — the M3 audit  |  observer: the
                                    | + M3 implementation added OutputPinPort as  |  retention policy)
                                    | a SIBLING port (not an OutputPruneIntent
                                    | case).

OutputAdoption / OutputCrash-
  Invariant                         | …/output/OutputAdoption.kt:36-243          | (no axis; S2 acceptance bar)

OutputFollower (interface)          | …/output/follow/OutputFollower.kt:56-81    | Independent, Complete,
                                    | open(runId, options): OutputFollowHandle.   | Terminality
                                    | **Independent cursors** (OutputFollower.kt:
                                    | 65-78): "the handle is single-observer: a
                                    | second consumer that wants to observe the
                                    | same run independently calls [open] again."
                                    | **No observation-induced cancellation**
                                    | (KDoc lines 46-52): the handle is
                                    | AutoCloseable; close is idempotent; "no
                                    | half-page is delivered after a `close`".
                                    | **No backpressure on the writer** (KDoc
                                    | lines 16-21): polling at the existing
                                    | FOLLOW_IDLE_MILLIS cadence matches the
                                    | in-tree `pipeline observe` behaviour.

OutputFollowHandle (interface)      | …/output/follow/OutputFollower.kt:101-123  | Independent
                                    | AutoCloseable; single-observer per handle;
                                    | NOT thread-safe (the consumer drives the
                                    | iterator from a single thread — KDoc
                                    | lines 91-94).
                                    | Two consumers each get their own handle.

OutputFollowOptions (data class)    | …/output/follow/OutputFollower.kt:131-163  | Independent, Complete,
                                    | streams, pageMaxBytes, pollIntervalMs,      | Terminality
                                    | includeFrames, maxRecords, until,           | (reconnect identity)
                                    | **afterOrdinal: Long? (lines 153)**.
                                    | "Resume strictly after this frame ordinal.
                                    |  ... a consumer that crashed mid-poll
                                    |  persists the last ordinal it observed and
                                    |  reopens with `afterOrdinal = lastObserved`."

FollowUntil (sealed)                | …/output/follow/OutputFollower.kt:171-179  | Terminality
                                    | Unbounded / UntilAllSealed / UntilBytesRead.
                                    | Explicit terminal predicates.

FollowState (sealed)                | …/output/follow/OutputFollower.kt:198-209  | Terminality
                                    | Running / StreamSealed / RunTerminal /
                                    | Unobservable(refusal).
                                    | **RunTerminal** (KDoc lines 184-196): "is
                                    | emitted only after the event plane observes
                                    | a `RunFinished` event". The output plane
                                    | alone does NOT authoritatively know the run
                                    | is over.

OutputFollowEvent (sealed)          | …/output/follow/OutputFollower.kt:217-222  | Independent, Complete,
                                    | Bytes(page, newState) / StateChanged /     | Terminality
                                    | Refused / Completed.

OutputPinPort (interface)           | …/output/OutputPinPort.kt:37-89            | Independent (retention
                                    | pin / release / pinsOf / isPinned.         |  authority)
                                    | A pin is a RETENTION HOLD, NOT a lease
                                    | (KDoc lines 8-13). Pins are independent of
                                    | observer identity (each observer can pin its
                                    | own range).
                                    | **DEFAULT_MAX_PINS_PER_STREAM = 1024** (line
                                    | 87) caps the number of pins per
                                    | (stream, runId); pins are countable.

OutputPin (data class)              | …/output/OutputPin.kt:41-72                | (per-pin authority)
                                    | (pinId, stream, range, holder, reason,
                                    | createdAtMs, expiresAtMs?).

OutputPinId (value class)           | …/output/OutputPin.kt:14-24                | (per-pin authority)
                                    | Opaque; UUID v4 is the recommended baseline.

OutputPinResult / PinRefusal /
  PinReleaseOutcome (sealed)        | …/output/OutputPin.kt:81-149               | (per-pin authority)

PruneAuthorisation / PruneRefusal
  (sealed)                          | …/output/PruneAuthorisation.kt:14-69       | (per-prune authority)
                                    | Granted / Consulted(stream, range, pinsAt)
                                    | / Refused. **Consulted is the answer a
                                    | second observer gets when it asks whether
                                    | its OWN pin is in the way of a prune** —
                                    | the consult is independent of other
                                    | observers' pins.

OutputReadDigestedResult (sealed)   | …/output/OutputReadDigestedResult.kt:14-32 | Complete
                                    | Digested(page, digest) / Refused(reason).
                                    | M3 read-with-digest; the digest is a
                                    | deterministic function of the committed bytes
                                    | (OutputReadPort.kt:51-66).

OutputDigest (companion / value)    | …/output/OutputDigest.kt                   | (digest algorithm)
                                    | SHA-256 (DEFAULT_ALGORITHM) is the baseline
                                    | from `OperationJournal.beginOperation`'s
                                    | `fingerprint` discipline.
```

**Verdict.** The output plane has a complete **byte-cursor independent read
surface** (`OutputReadPort` + `OutputCursor` + `OutputPage`), a complete
**frame-ordinal follow** (`OutputFollower` with `FollowState.RunTerminal`
ONLY after the event plane observes `RunFinished`), an explicit
**reconnect-preserves-identity primitive** (`OutputFollowOptions.afterOrdinal`),
and a **pin authority** (`OutputPinPort`) that is independent of observer
identity — each observer can pin its own range; pins are counted per
(stream, runId) up to `DEFAULT_MAX_PINS_PER_STREAM = 1024`. There is **no
observation-induced cancellation** reachable through any output-plane port:
the follow does not invoke `RuntimeControlPort.cancel`, and `prune` is the
single-shot deliberate act of a named policy, not a follow side-effect.

### A.3 `:pipeline-runtime` (published module `pipeline-runtime`, M2 outcome)

The runtime module is the **terminality and verb surface** the dual-observer
needs to know exists (so it can refuse or wait).

```
Capabilities (object)               | …/runtime/Capabilities.kt:46-60            | (negotiation)
                                    | OUTPUT_FOLLOW_V1 / EVENTS_FOLLOW_V1
                                    |   (PUBLICADA, M1)
                                    | RUNTIME_INSPECT_V1 / RUNTIME_CANCEL_V1 /
                                    |   RUNTIME_RECOVER_V1 (EXPERIMENTAL, M2)
                                    | OUTPUT_READ_DIGESTED_V1 / OUTPUT_PIN_V1 /
                                    |   OUTPUT_REFUSAL_RETENTION_V1 (EXPERIMENTAL,
                                    |   M3).
                                    | The M4 audit does not propose a new capability
                                    | ID; the existing eight cover Jenkins's
                                    | dual-observer needs.

RuntimeIntrospectionPort (fun
  interface)                        | …/inspect/RuntimeIntrospectionPort.kt:40-66 | Independent, Terminality
                                    | inspect(runId): RuntimeIntrospectionResult.
                                    | **Read-only by construction** (KDoc lines
                                    | 18-23): "inspect MUST NOT write to durable
                                    | state. The composition rule limits it to
                                    | SELECTs, tail-state queries, and load."
                                    | **Independent cursors** (KDoc lines 25-29):
                                    | "Two concurrent `inspect(runId)` calls on the
                                    | same run return observations that differ only
                                    | in monotonic progress."

RuntimeIntrospectionResult (sealed) | …/inspect/RuntimeIntrospectionResult.kt    | Terminality
                                    | Observation(obs) | Refused(refusal).

RuntimeObservation (sealed)         | …/inspect/RuntimeObservation.kt:23-191      | Terminality
                                    | Running(attempt, leaseHolder?, fencingToken,
                                    |         journalPosition, outputTails,
                                    |         process?) | Terminal(attempt,
                                    |         terminal: TerminalObservation,
                                    |         terminalAtMs) | LiveButEmpty(attempt,
                                    |         reason) | Unobservable(reason).
                                    | **TerminalObservation** is a typed closed
                                    | ADT (lines 154-163): Succeeded / Failed /
                                    | Unstable / Aborted / Cancelled / Other.
                                    | That is the legitimate-terminality answer a
                                    | dual-observer sees.

IntrospectionRefusal (sealed)       | …/inspect/IntrospectionRefusal.kt:15-49    | Terminality
                                    | UnknownRun / NoControlRoot / NoEventStore /
                                    | NoOutputPlane / LeaseHeldByAnother /
                                    | StorageError / InconsistentLeaseState.
                                    | **LeaseHeldByAnother** is the runtime's
                                    | refusal when the lease is held by a writer
                                    | a consumer cannot replace — that is the
                                    | canonical "I can't observe because the
                                    | writer is held elsewhere" answer.

RuntimeControlPort (fun interface)  | …/control/RuntimeControlPort.kt:44-62      | (Cancel, M2 only)
                                    | cancel(runId, reason): CancelOutcome.
                                    | **Idempotency invariant** (KDoc lines 26-32):
                                    | "A cancel call made N times produces one
                                    | terminal state, not N. The second call
                                    | returns [CancelOutcome.AlreadyCancelled],
                                    | NOT a refusal."
                                    | **Lease boundary** (KDoc lines 33-41):
                                    | "Cancel MUST NOT cross lease boundaries."
                                    | **The audit pins: cancel is NOT invoked by
                                    | any read or follow.** It is segregated.

CancelOutcome (sealed)               | …/control/CancelOutcome.kt:13-42           | (Cancel)
                                    | Cancelled / AlreadyCancelled / Refused.

CancelRefusal (sealed)             | …/control/CancelRefusal.kt:15-53           | (Cancel)
                                    | UnknownRun / RunTerminal /
                                    | LeaseHeldByAnother / IncompatibleRunState /
                                    | JournalUnavailable / StorageError.

CancelReason (data class)           | …/control/CancelReason.kt                  | (Cancel)

RuntimeRecoverPort (interface)      | …/recover/RuntimeRecoverPort.kt:57-87      | (Recover)
                                    | recover(runId, options): RecoverOutcome.
                                    | **No-rerun invariant** (KDoc lines 22-28):
                                    | "A second call to `recover(runId)` on the
                                    | same run is a no-op."
                                    | **Does NOT** (lines 28-55): re-execute
                                    | external effects; take a lease; introduce
                                    | a scheduler; modify output bytes already
                                    | persisted.
                                    | **The audit pins: recover is NOT invoked by
                                    | any read or follow.** It is segregated and
                                    | idempotent.

RecoverOutcome (sealed)              | …/recover/RecoverOutcome.kt:15-57          | (Recover)
                                    | RecoveredTerminal / ReattachPending /
                                    | FailClosed / AlreadyRecovered.

RecoverRefusal (sealed)             | …/recover/RecoverRefusal.kt:20-85          | (Recover)
                                    | UnknownRun / NotRecoverable /
                                    | SubstrateUnavailable / JournalIncompatible
                                    | / LeaseHeldByAnother / StorageError /
                                    | **PinnedBytesOutsideRecoveredRegion**
                                    |   (stream, range, pins)  — added by M3.

RecoverOptions / RecoverReport      | …/recover/RecoverOptions.kt,               | (Recover)
                                        | …/recover/RecoverReport.kt              |

RuntimeRecoverDecision (object)     | …/recover/RuntimeRecoverDecision.kt:45-341 | (Recover)
                                    | decideRecovery(observation, journal):
                                    | RecoveryChoice.
                                    | Pure decider; does NOT call the journal; reads
                                    | from a JournalProof the caller passes in
                                    | (KDoc lines 38-43).

RuntimeRecoverDecision.JournalProof | …/recover/RuntimeRecoverDecision.kt:57-67  | (Recover)
  / TerminalRow / OperationSnapshot |
  / ReplayCursor                    | …/recover/RuntimeRecoverDecision.kt:72-104 |
                                    | Snapshots; the decider does NOT touch the
                                    | store.

RuntimeRecoverDecision.OperationOutcome
  (sealed)                          | …/recover/RuntimeRecoverDecision.kt:87-96  | (Recover)
                                    | Succeeded / Failed / Unstable / Aborted /
                                    | Cancelled / Running / Other.

RuntimeRecoverDecision.RecoveryChoice
  (sealed)                          | …/recover/RuntimeRecoverDecision.kt:279+   | (Recover)
                                    | Closed ADT.
```

**Verdict.** The runtime module has the **closed ADTs for terminality**
(`RuntimeObservation.Terminal(TerminalObservation)`, where
`TerminalObservation` is the typed Succeeded/Failed/Unstable/Aborted/Cancelled
union), the **read-only inspect verb** (`RuntimeIntrospectionPort.inspect`),
the **segregated cancel verb** (`RuntimeControlPort.cancel`), and the
**segregated recover verb** (`RuntimeRecoverPort.recover`). None of these is
reachable through a read or follow: cancel and recover are side-effecting
verbs on their own ports, not side effects of `read` / `readRange` /
`framesOfRun` / `readRecords` / `open`. **The M4 audit names no gap on the
runtime module.**

### A.4 The two store modules (not published)

These are the durable authorities that the published modules read through.
The audit lists them because every existing **write-side authority over
dual-observer state** lives here, and the question "can a consumer reach any
of these through the published ABI?" has a definite answer at the end of this
section.

```
OperationJournal (interface)        | v2/pipeline-events-store/.../durable/
                                       OperationJournal.kt:28-124
                                    | NOT published. Reads visible only via
                                    | EventRecordReadPort and
                                    | RuntimeIntrospectionPort.
                                    | NO range-byte primitive; the journal is the
                                    | EVENT sequence, not the byte sequence.

ReplayCursorStore (interface)       | …/events/durable/ReplayCursorStore.kt:16-64
                                    | NOT published.

RunExecutionLease (pure decider)    | …/events/durable/RunExecutionLease.kt:50-309
                                    | The pure half — total, side-effect free.

FileBackedRunExecutionLeaseStore    | …/events/durable/
                                       FileBackedRunExecutionLeaseStore.kt:40-298
                                    | NOT published. The store stays inside PK.

OutputAppendPort / OutputStreamHandle
  / OutputReservation (interfaces)  | v2/pipeline-output-store/.../store/
                                       OutputWritePorts.kt:44-145
                                    | NOT published.

OutputRecoveryPort (interface)      | …/store/OutputWritePorts.kt:166-176
                                    | NOT published. Destructive-by-design,
                                    | gated to the writer's JVM.

OutputSealPort / SealOutcome        | …/store/OutputWritePorts.kt:205-286
                                    | NOT published. Single-shot terminal
                                    | marker; not a run cancel.
```

**The publication question, answered.** `:pipeline-events-store` and
`:pipeline-output-store` do **not** declare `maven-publish`. The audit lists
them only to confirm they are not reachable through the published ABI. The
published plane gives the dual-observer:
- **byte reads**: `OutputReadPort`, `OutputCursor`, `OutputPage`,
  `OutputRefusal`, `OutputReadDigestedResult` (M3), `OutputDigest` (M3);
- **frame reads**: `OutputFrameIndex`, `OutputFrame`;
- **terminality read**: `OutputTailPort`, `OutputTailState`,
  `FollowState.RunTerminal`;
- **pin authority**: `OutputPinPort`, `OutputPin`, `OutputPinId`,
  `OutputPinResult`, `PinRefusal`, `PinReleaseOutcome`;
- **prune consult**: `PruneAuthorisation`, `PruneRefusal`;
- **retention authority**: `OutputRetentionPort`, `OutputPruneIntent`,
  `OutputPruneReport`, `RunLifecycle`, `RetainUntil`;
- **runtime verbs**: `RuntimeIntrospectionPort`, `RuntimeControlPort`,
  `RuntimeRecoverPort`, and the M2 sealed refusal ADTs;
- **typed event reads**: `EventHistory`, `EventTail`, `EventRecordReadPort`,
  `EventCursor`, `EventQuery`, `EventPage`;
- **event follow**: `EventFollower`, `EventFollowHandle`,
  `EventFollowOptions`, `EventFollowUntil`, `EventFollowState`,
  `EventFollowRefusal`, `EventFollowEvent`;
- **output follow**: `OutputFollower`, `OutputFollowHandle`,
  `OutputFollowOptions`, `FollowUntil`, `FollowState`, `OutputFollowEvent`.

What the published plane **does not** give the dual-observer:
- a way to enumerate the OTHER observers on the same run — and the audit
  does **NOT** propose one. Observer enumeration would be a privacy leak
  (§11 of `INTERFACE_CONTRACT.md`); what the contract gives is
  per-observer opacity (`OutputCursor` and `EventCursor` are value types
  owned by the consumer, lines `OutputCursor.kt:57-65` and
  `EventHistoryPorts.kt:22-53`).
- a way to cancel the run from inside a read — there is no such port, and
  the audit does **NOT** propose one. Cancel is segregated
  (`RuntimeControlPort.cancel`, `RuntimeControlPort.kt:44-62`).

### A.5 Summary count

| Module                                | Published? | Touches Independent | Touches Complete | Touches Terminality | Touches No-Cancel-on-Read |
|---------------------------------------|------------|---------------------|------------------|---------------------|--------------------------|
| `:pipeline-events`                    | YES        | 8 ports             | 9 ports          | 4 ports             | (N/A — no cancel verb)   |
| `:pipeline-output`                    | YES        | 11 ports            | 9 ports          | 6 ports             | (N/A — no cancel verb)   |
| `:pipeline-runtime`                   | YES        | 1 port              | 0                | 3 ports             | 0 (segregated cancel)    |
| `:pipeline-events-store/`             | NO         | 0                   | 0                | 0                   | 0                        |
| `:pipeline-output-store/`             | NO         | 0 (write)           | 0                | 0                   | 0 (write)                |
| **Total surveyed (published plane)**  | —          | **20**              | **18**           | **13**              | **segregated**           |

Two truths fall out of that count:

1. **The published plane covers every dual-observer axis.** A second observer
   can: hold its own cursor (`OutputCursor`, `EventCursor`, `OutputFrame.
   ordinal`, `OutputFollowOptions.afterOrdinal`, `EventFollowOptions.after`),
   see every committed byte / event (`OutputReadPort.readRange`'s contractual
   property at lines 36-43, the follow's `framesOfRun` /
   `readRecords` resume), learn the writer's terminal state
   (`OutputTailState.Open/Sealed`, `FollowState.RunTerminal` only after
   `RunFinished`, `RuntimeObservation.Terminal(TerminalObservation)`), and
   not trigger any cancel verb (cancel and recover are segregated, by
   construction).

2. **No gap requires new PK surface.** The four M4 axes are answered by the
   existing M1 + M2 + M3 surface; the audit names no gap in §B.

## B. Gaps between contract requirement and current ports

This is the section the precedent audits used to enumerate named gaps. For
M4, **the audit names zero gaps**. Each of the four M4 axes is already
served by the existing published surface. The reasoning is per-axis:

**B.0a — Independent cursors (the contract's §2 / §3 / §9)**

- The contract §9 says "PipelineK NO depende de Fabric, Jenkins, S3,
  Elasticsearch ni gRPC para su core". The published plane has no such
  dependencies — verified by the module graph (no `maven-publish` in the
  store modules).
- The contract §1 says "runId, operationId, attemptId, canal y stream
  conservan el mismo referente a través de los dos productos" and
  "no inferir una ejecución nueva cuando un lector se reconecta". The
  existing cursors are value types owned by the consumer
  (`OutputCursor.kt:57-65`, `EventHistoryPorts.kt:22-53`), and the
  reconnect primitives (`OutputFollowOptions.afterOrdinal`,
  `EventFollowOptions.after`) resume strictly after the last observed
  position. The "no inferir una ejecución nueva" rule is a
  writer-side discipline: a reconnect at sequence N yields events > N
  without bumping the attempt. The existing `EventCursor(runId,
  lastSequence)` does not carry an attemptId, by design (the run id is
  the identity, not the attempt).
- The published follow handles are explicitly **single-observer per
  handle**, and two observers open two handles
  (`OutputFollower.kt:65-78`, `EventFollower.kt:65-72`). **No gap.**

**B.0b — Complete output (the contract's §2 / §8 / §12)**

- The contract §2 names `Open`, `Sealed`, `Unavailable`, `RetentionGap`,
  `Corrupt` as distinct; the published `OutputRefusal` (M3) now has all
  five as sealed cases (`OutputRefusal.kt:14-207`), and `EventPage.
  refusals` carries undecodable event rows as typed rows
  (`EventHistoryPorts.kt:136-137`). An observer that asks for a range or
  a sequence either gets the bytes/events or a typed refusal — never a
  silent gap. **No gap.**
- The contract §8 ("ACK únicamente tras persistir exactamente el rango y
  digest aceptados") is satisfied for the read path by
  `OutputReadDigestedResult` (M3, `OutputReadDigestedResult.kt:14-32`):
  the read carries a SHA-256 digest of the committed bytes
  (`OutputReadPort.kt:51-66`). **No gap on the read side.**
- The contract §12 ("no podar rangos hasta probar que su garantía activa
  se transfirió a una copia durable, y que no existe pin") is satisfied
  by the M3 `OutputPinPort` (`OutputPinPort.kt:37-89`) and the
  `OutputRetentionPort.canPrune(intent)` consult-before-act
  (`OutputRetention.kt:163-184`). An observer that pins a range is told
  via `PruneAuthorisation.Consulted(stream, range, pinsAt)` that a
  prune would refuse on its pins — the consult is independent of other
  observers' pins. **No gap.**

**B.0c — Legitimate terminality (the contract's §2)**

- The published follow emits `FollowState.RunTerminal` only after the
  event plane observes a `RunFinished` event
  (`OutputFollower.kt:184-196`); the event plane's `EventFollowState.
  RunFinished` is reached only when a `DomainEvent.kind == "RunFinished"`
  row is observed (`EventFollower.kt:170-176`). The terminal fact is
  the journal's; the observer does NOT decide terminality on its own.
- `OutputTailState.Open(committedEnd)` vs `Sealed(finalEnd)` is the
  byte-plane twin: the sealed marker is a durable fact the writer
  committed (`OutputTailState.kt:30-41`), not a live view of a writer
  process.
- `RuntimeObservation.Terminal(TerminalObservation)` carries the typed
  `TerminalObservation` (Succeeded / Failed / Unstable / Aborted /
  Cancelled / Other); the typed closure is the contract's
  "legitimate-terminality" answer. **No gap.**

**B.0d — No observation-induced cancellation (the contract's §5 / §7)**

- The contract §5 says "Una lectura no ejecuta recuperación destructiva;
  no relanza efectos externos automáticamente". The read ports are
  read-only by construction (`OutputReadPort.kt:3-17`,
  `RuntimeIntrospectionPort.kt:18-23`); the follow handles do not
  invoke any side-effecting verb
  (`OutputFollower.kt:39-52`, `EventFollower.kt:39-44`).
- The contract §7 says "Un silencio del proceso o un socket
  desconectado no equivale a un resultado". The observer's only
  handle-close is its own `close()` (idempotent, no half-page after
  close per `OutputFollower.kt:48-51`); the run's cancel is
  `RuntimeControlPort.cancel(runId, reason)`, segregated and not
  reachable from any read or follow.
- The published `RuntimeControlPort.cancel` is idempotent and
  refuse-closed (`RuntimeControlPort.kt:26-41`, `CancelOutcome.kt:
  13-42`); the published `RuntimeRecoverPort.recover` is
  idempotent and no-rerun (`RuntimeRecoverPort.kt:22-28`).
  **No gap.**

The audit therefore concludes that **the existing M1 + M2 + M3 surface
covers Jenkins's M4 needs in full**. There is no candidate gap the M4
deliverable would close.

For traceability, the audit names three places where the M3 implementation
already hardened the M4 axes (none of which is M4 work, but the M4 audit
notes them as the antecedents the audit relies on):

- `OutputRefusal` has the M3 closed cases (`RetentionGap`, `Corrupt`,
  `Unavailable`, `RangeLostRetention`) at
  `OutputRefusal.kt:101-201`. These are what make "complete output"
  honest at the type level.
- `OutputReadDigestedResult` (M3,
  `OutputReadDigestedResult.kt:14-32`) is what makes the
  follow-pulled-then-read contract §8 idempotent on the read path.
- `OutputPinPort` (M3, `OutputPinPort.kt:37-89`) is what makes the
  contract §12 "no pin => no prune" answer consult-before-act
  (`PruneAuthorisation`, `PruneAuthorisation.kt:14-37`).

## C. Overlap — places where two ports claim the same authority over dual-observer state

Each overlap is named, the two surfaces are listed, and the audit's
recommendation is given. Overlap is not a defect by itself; an audit
that does not name it leaves a drift hazard behind.

### C.1 `OutputFollower.open(runId, options)` and `EventFollower.open(runId, options)` — same run, two observers

- **Surfaces.** Output follow observes byte-plane state
  (`OutputFrameIndex.framesOfRun` + `OutputTailPort.tailState` +
  `OutputReadPort.readRange`); event follow observes event-plane
  state (`EventRecordReadPort.readRecords` + the typed envelope).
- **Same authority.** None. The two planes are deliberately separate
  (ADR-M1 §D2, ADR-M1 §D3; see `OutputCursor.kt:33-53` and
  `OutputFrameIndex.kt:14-23`).
- **Recommendation.** Demarcate: the dual-observer scenario's terminality
  is the JOIN of the two planes. The follow's KDoc
  (`OutputFollower.kt:184-196`) explicitly says `RunTerminal` is emitted
  only after the event plane observes `RunFinished` — the join is
  per-handle, not per-store. **Two observers each get their own joined
  view.** No consolidation is needed.

### C.2 `RuntimeIntrospectionPort.inspect(runId)` and the follow's first event

- **Surfaces.** `inspect(runId)` returns a one-shot
  `RuntimeObservation` (Running / Terminal / LiveButEmpty /
  Unobservable); the follow's first event is a `StateChanged` (Running /
  StreamSealed / RunTerminal / Unobservable).
- **Same authority.** Partially. Both answer "what does this run look
  like right now?", but `inspect` is a one-shot SELECT and the follow is
  a long-lived poll.
- **Recommendation.** Demarcate: `inspect` is the ask-once verb
  (M2 design §3, §6); the follow is the watch-until-done verb. A dual-
  observer that wants both calls `inspect` once and then `open`s the
  follow; an observer that only wants the follow does NOT need `inspect`.
  The two ports share the read primitives (`OutputTailPort.tailState`,
  `EventRecordReadPort.readRecords`, `OperationJournal.listForRun`,
  `ReplayCursorStore.load`) but neither port's vocabulary calls the other.

### C.3 `OutputPinPort.pinsOf(stream, range?)` and the follow's tail state

- **Surfaces.** `pinsOf` answers "who is holding bytes of this stream?";
  the follow's `OutputTailPort.tailState` answers "can more bytes arrive
  on this stream?".
- **Same authority.** None. Pins are per-range retention holds; tail
  state is per-stream terminality. Both are facts about the same
  stream, but they answer different questions.
- **Recommendation.** Keep them apart. A dual-observer that wants both
  asks each in turn; a second observer's pins do NOT change the first
  observer's tail view.

### C.4 `EventRecordReadRefusal.CursorBeyondTail` and `EventFollowRefusal.RetentionLost`

- **Surfaces.** Pull-side vs follow-side of the same fact: the
  consumer's cursor / after is past the current tail / retention is
  lost.
- **Same authority.** Yes — both name "the events you asked for are
  past where this store can serve them".
- **Recommendation.** Demarcate: the pull-side surfaces it as a typed
  refusal on the read (`EventRecordReadPort.kt:101-105`); the follow
  surfaces it as a typed refusal inside the iterator
  (`EventFollower.kt:206-210`). Two observers: one polling gets
  `CursorBeyondTail`, one following gets `RetentionLost`. Both are typed
  and a `when` over either is exhaustive. **No gap.**

### C.5 `RuntimeControlPort.cancel` and `OutputFollowHandle.close()`

- **Surfaces.** `cancel` is a run-level verb that flips the run's
  terminal state to `Cancelled`; `close()` is a per-handle verb that
  releases the follow's resources.
- **Same authority.** None. `cancel` is side-effecting on the run;
  `close()` is side-effecting on the handle only.
- **Recommendation.** Demarcate explicitly (KDoc `OutputFollower.kt:
  46-52` already does): "The handle is `AutoCloseable`. The consumer
  closes it (try-with-resources via `use { }`). After `close`,
  `iterator().hasNext()` returns `false` and the implementation releases
  any store-side resources. The contract guarantees that no half-page
  is delivered after a `close`." Closing the handle is NOT cancelling
  the run.

### C.6 `RuntimeRecoverPort.recover(runId)` and the follow's first event after a restart

- **Surfaces.** `recover(runId)` is a side-effecting verb that brings
  a run back to a known state (M2 design §5); the follow's first
  event after a restart is a `StateChanged` that resumes the poll from
  `afterOrdinal` / `after`.
- **Same authority.** Partially. Both touch the same substrate (the
  journal / cursor / output-recovery path), but `recover` writes and
  the follow reads.
- **Recommendation.** Demarcate: `recover` is idempotent and re-entrant;
  a second observer joining does NOT invoke it. The follow resumes by
  reading what is already there. The dual-observer case NEVER triggers
  `recover`.

## D. Invariants the contract pins on dual-observer

The contract §1 ("no inferir una ejecución nueva cuando un lector se
reconecta"), §2 (the typeless "Open, Sealed, Unavailable, RetentionGap,
Corrupt" distinction), §5 ("Una lectura no ejecuta recuperación
destructiva; no relanza efectos externos automáticamente"), §9
("PipelineK NO depende de Fabric, Jenkins, S3, Elasticsearch ni gRPC
para su core"), §11 ("control separado de observación, payload y
compresión acotados, cursors ligados a ACL/query"), and §12 ("no podar
rangos hasta probar que su garantía activa se transfirió a una copia
durable, y que no existe pin") pin six invariants on dual-observer
semantics. The audit checks each one against the surveyed ports.

### I.1 — independent cursors

- **What it asserts.** Two observers on the same run hold different
  cursors; advancing one does NOT advance the other.
- **Verdict per port.** **PASS-by-design + tested.** `OutputCursor` and
  `EventCursor` are value types owned by the consumer
  (`OutputCursor.kt:57-65`, `EventHistoryPorts.kt:22-53`). The follow
  handles are explicitly single-observer, and two observers open two
  handles (`OutputFollower.kt:65-78`, `EventFollower.kt:65-72`). The
  M1-D cross-JVM e2e test `M1DCrossJvmFollowTest` (UAT-PK-M1-001/003)
  pins the concurrent-reader independence on the output plane; the M1
  test `EventFollowerAdapterTest` (18 cases) pins it on the event
  plane. **I.1 holds.**

### I.2 — complete output

- **What it asserts.** An observer that follows from the start sees
  every committed byte / every committed event in sequence, until the
  run reaches terminal state. (Modulo retention loss, which surfaces
  as a typed refusal.)
- **Verdict per port.** **PASS-by-design + tested.** The follow's
  `framesOfRun(runId, afterOrdinal, limit)` resumes by ordinal; the
  ordinal is monotonic, strictly increasing, assigned at publication
  time inside the call (`OutputFrameIndex.kt:44-47, 132-136`). The
  event follow's `readRecords(runId, after, query, limit)` resumes by
  cursor; the cursor is the store's sequence authority
  (`EventRecordRead.kt:151-163`). The M3 closed cases
  (`OutputRefusal.RetentionGap`, `OutputRefusal.Corrupt`,
  `OutputRefusal.Unavailable`, `OutputRefusal.RangeLostRetention` at
  `OutputRefusal.kt:101-201`) ensure that retention loss surfaces as a
  typed refusal, NOT a silent gap. The M1 test
  `SegmentOutputFollowerTest` (18 cases) and the M3 test
  `OutputRefusalClosedTest` (4 cases) pin this. **I.2 holds.**

### I.3 — legitimate terminality

- **What it asserts.** The observer's read path reports terminal state
  ONLY when the writer's journal says terminal. The observer does NOT
  decide terminality on its own (e.g. "no bytes for 30s" is NOT
  terminal).
- **Verdict per port.** **PASS-by-design.** The output follow emits
  `FollowState.RunTerminal` only after the event plane observes a
  `RunFinished` event (`OutputFollower.kt:184-196`); the event follow
  emits `EventFollowState.RunFinished` only when a
  `DomainEvent.kind == "RunFinished"` row is observed
  (`EventFollower.kt:170-176`); `OutputTailState.Sealed(finalEnd)` is
  a durable fact the writer committed
  (`OutputTailState.kt:30-41`); `RuntimeObservation.Terminal
  (TerminalObservation)` carries the typed Succeeded/Failed/Unstable/
  Aborted/Cancelled/Other union. The M2 design §5 contract test
  `RuntimeIntrospectionPortAdapterTest` (8 cases) pins
  the inspect path; the M1 design §2 contract test
  `SegmentOutputFollowerTest` pins the follow path. **I.3 holds.**

### I.4 — no observation-induced cancellation

- **What it asserts.** A second observer opening a follow or read does
  NOT trigger the cancel path. The follow is purely observational.
- **Verdict per port.** **PASS-by-design.** No read or follow path
  invokes `RuntimeControlPort.cancel`. The cancel port is segregated
  (`RuntimeControlPort.kt:44-62`, KDoc lines 19-25); the follow's
  `OutputFollowHandle.close()` releases handle resources only
  (`OutputFollower.kt:46-52`), and `EventFollowHandle.close()` does the
  same on the event side (`EventFollower.kt:43-46`). The handle-close
  is idempotent; the run-cancel is idempotent; they are different
  verbs on different types. **I.4 holds.**

### I.5 — observer does not advance writer state

- **What it asserts.** The act of reading does not move the writer's
  cursor, commit, or seal anything.
- **Verdict per port.** **PASS-by-design + tested.** The read ports
  are read-only by construction (`OutputReadPort.kt:3-17`,
  `RuntimeIntrospectionPort.kt:18-23`). The follow ports compose read
  ports only (`OutputFollower.kt:14-21`, `EventFollower.kt:13-23`).
  The recover path is the only public mutator of side state
  (`OutputFrameIndex.recoverUnframedBytes` appends new frames AFTER
  already-published ordinals — `OutputFrameIndex.kt:182-199`); it is
  segregated under `RuntimeRecoverPort.recover(runId, options)` and
  is NOT a follow side-effect. The M1-D cross-JVM e2e test
  `M1DCrossJvmFollowTest` (UAT-PK-M1-001/003) pins the
  reader-does-not-write property in the producer-side e2e suite.
  **I.5 holds.**

### I.6 — reconnects preserve identity

- **What it asserts.** An observer that disconnects and reconnects to
  the same run sees the same data a continuous observer would have
  seen, with no re-execution and no false "new attempt".
- **Verdict per port.** **PASS-by-design + tested.** The reconnect
  primitives are `OutputFollowOptions.afterOrdinal: Long?`
  (`OutputFollower.kt:131-163`) and `EventFollowOptions.after:
  EventCursor?` (`EventFollower.kt:124-152`). The cursor / ordinal is
  owned by the consumer; resuming at last observed yields strictly
  after. The contract §1 "no inferir una ejecución nueva" rule is a
  writer-side discipline: the `EventCursor` carries `runId` and
  `lastSequence` only, NOT an attemptId. The M1-D cross-JVM e2e
  test (UAT-PK-M1-001) is exactly the "second process reads … while
  PK is still running more stages, with cursors preserved across the
  boundary" case (`M1_FOLLOW_DESIGN.md` line 319); the M1-D test
  `M1DCrossJvmFollowTest` (6 cases) pins it. **I.6 holds.**

### I.summary — invariant table

| Invariant                                       | Verdict today       | Why                                                                                                                              | Closed by |
|-------------------------------------------------|---------------------|----------------------------------------------------------------------------------------------------|
| I.1 independent cursors                         | PASS-by-design + tested | `OutputCursor` value-class; `EventCursor` value-class; follow handle "single-observer per handle, two observers open two handles"; M1-D e2e | nothing to close |
| I.2 complete output                             | PASS-by-design + tested | `OutputFrameIndex.framesOfRun` resume; `EventRecordReadPort.readRecords` resume; M3 closed refusal cases for retention loss | nothing to close |
| I.3 legitimate terminality                      | PASS-by-design + tested | `FollowState.RunTerminal` only after `RunFinished`; `EventFollowState.RunFinished` only after `DomainEvent.kind == "RunFinished"`; `OutputTailState.Sealed` is durable | nothing to close |
| I.4 no observation-induced cancellation         | PASS-by-design      | No read/follow port invokes `RuntimeControlPort.cancel`; handle `close()` is idempotent, scoped to the handle | nothing to close |
| I.5 observer does not advance writer state      | PASS-by-design + tested | Read ports read-only by construction; follow ports compose read ports only; recover is segregated | nothing to close |
| I.6 reconnects preserve identity                | PASS-by-design + tested | `OutputFollowOptions.afterOrdinal` resume; `EventFollowOptions.after` resume; cursor carries runId + lastSequence, not attemptId; M1-D e2e | nothing to close |

## E. Cross-walk to UAT / AAT / FABRIC evidence

The M4 producer-side UAT/AAT/FITNESS evidence lives in the consumer's
`docs/fabric-coordinated-evolution/acceptance/UAT.md` / `AAT.md` /
`FITNESS.md` files (Fabric's repo) and in the producer's own
`:pipeline-output` / `:pipeline-runtime` / `:pipeline-events` test
batteries. The audit cross-walks each existing case to the dual-observer
axis it covers, and notes which axes are gaps relative to M4.

### E.1 UAT cases that the existing PK ports already cover

| UAT ID            | Verb today                                                | Ports touched                                                          | M4 status |
|-------------------|-----------------------------------------------------------|------------------------------------------------------------------------|-----------|
| UAT-PK-M1-001     | End-to-end live tail across the JVM boundary — a second process reads `output.follow.v1` and `events.follow.v1` while PK is still running more stages, with cursors preserved across the boundary | `OutputFollower` + `EventFollower` + M1-D cross-JVM | PASS — covers I.1, I.6; the cross-JVM case is exactly Jenkins's scenario minus "Jenkins as one observer" |
| UAT-PK-M1-002     | Event follow with typed envelopes (`events.follow.v1`)   | `EventFollower` + `EventRecordReadPort`                                 | PASS — covers I.1, I.2 |
| UAT-PK-M1-003     | Output follow with byte frames (`output.follow.v1`)       | `OutputFollower` + `OutputFrameIndex`                                   | PASS — covers I.1, I.2, I.3 |
| UAT-PK-M1-004     | Retention boundary during follow                          | `OutputFollower` + `OutputRefusal.StreamLostRetention`                 | PASS — M3 extends with `RetentionGap` / `Corrupt` / `Unavailable` / `RangeLostRetention` for I.2 |
| UAT-PK-M1-005     | UTF-8 split across pages                                   | `OutputReadPort.readRange` + `RedactingOutputIngress`                   | PASS — covers I.2 |
| UAT-PK-M1-006     | Concurrent follow across two consumers                    | `OutputFollower` × 2 + `OutputFrameIndex`                              | PASS — covers I.1, I.5 |
| UAT-PK-M2-009 (proposed in M2 audit) | "is this run currently running?" — typed query | `RuntimeIntrospectionPort.inspect` | PASS — covers I.3 |
| UAT-PK-M3-001 (proposed in M3 audit) | "byte read returns the right digest" | `OutputReadDigestedResult` | PASS — covers I.2 + §8 idempotency |
| UAT-PK-M3-002 (proposed in M3 audit) | "pin survives prune" | `OutputPinPort` + `OutputRetentionPort.canPrune` | PASS — covers I.2 / §12 retention-under-pin |

### E.2 New M4 UAT cases (suggested, not pre-existing)

The audit proposes four new UAT IDs so the M4 contract (when the Fabric
side adopts `v0.51.0-rc1`) has a closed test surface. The design phase
may re-number them; the audit pins the four axes by name so the producer-
side e2e suite and the Fabric-side AAT/FITNESS suite agree on the
contract.

| UAT ID (proposed) | What it proves                                                                                 | Ports touched                                                          | Closed by |
|--------------------|-------------------------------------------------------------------------------------------------|----------------------------------|
| UAT-PK-M4-001      | Two observers (e.g. Jenkins + second inspector) on the SAME run, each holding its OWN cursor; advancing one does NOT advance the other | `OutputFollower.open` × 2 + `EventFollower.open` × 2 on the same `runId` | existing surface; needs producer-side e2e |
| UAT-PK-M4-002      | The second observer joining does NOT trigger cancel; the run remains `Running` after the join   | `OutputFollower.open` (second) + `RuntimeIntrospectionPort.inspect` (post-join) | existing surface; needs producer-side e2e |
| UAT-PK-M4-003      | The second observer joining does NOT advance the writer's state: no extra frame ordinals, no extra event sequence, no terminal fact | `OutputFollower.open` (second) + `EventFollower.open` (second) + `RuntimeIntrospectionPort.inspect` (post-join) | existing surface; needs producer-side e2e |
| UAT-PK-M4-004      | The second observer reconnects via `afterOrdinal` / `after` and sees exactly the same data a continuous observer would have seen, with no false "new attempt" | `OutputFollower.open` (disconnect + reconnect) + `EventFollower.open` (disconnect + reconnect) + cross-JVM boundary | existing surface; needs producer-side e2e + cross-JVM |

The four M4 UAT cases are **suggested** identifiers, not pre-existing.
They are named here so the Fabric-side M4 design and the producer-side
contract-test suite can adopt them (or re-number them) and so the
implementation's UAT matrix is grounded in the audit instead of in
ad-hoc cases.

### E.3 FABRIC AAT cases (Fabric-side) that depend on the dual-observer surface

| AAT ID  | What it proves                                                  | PK port it depends on                                                  | M4 status |
|---------|----------------------------------------------------------------|------------------------------------------------------------------------|-----------|
| AAT-11  | Two pages same sender                                         | idempotency on the read path (M3 `OutputReadDigestedResult`)           | PASS-by-design on M3 ports |
| AAT-13  | Retry after lost ACK                                           | idempotency under re-send                                              | PASS-by-design on M3 ports |
| AAT-14  | Reorder → gap no ACK jump                                      | closed refusal ADT (M3 `RetentionGap`)                                  | PASS-by-design on M3 ports |
| AAT-15  | Two ranges same key hash distinct → conflict + evidence        | digest comparison + Conflict refusal                                    | PASS-by-design on M3 ports |
| AAT-16  | Net partition + local store active → typed saturation          | `Unavailable` refusal (M3)                                              | PASS-by-design on M3 ports |
| AAT-17  | Control loop under flood → P0 progresses without starvation    | bytes/second budget                                                     | M3 ports pass; nothing new for M4 |
| AAT-18  | Revoked-epoch worker → no zombie commit                        | lease fencing + bytes held                                              | M3 ports pass |

### E.4 FABRIC AAT/UAT integration tests for M4

The M4 deliverable on the Fabric side is the Jenkins import elimination.
The cross-walk is:

- Jenkins (as a Fabric-side observer) reads through `output.follow.v1`
  + `events.follow.v1` (M1, PUBLICADA) — exactly the same surface the
  existing CLI / Fabric / indexer consumers already use.
- The second inspector (a CLI observer, a debug tool, a second
  Fabric-side replica) reads through the same `output.follow.v1` +
  `events.follow.v1` ports.
- Both consumers open their own handles; each holds its own cursor.
- Neither triggers cancel; the cancel verb is `RuntimeControlPort.
  cancel` (M2, EXPERIMENTAL) and is segregated.

This means the Fabric-side M4 work does NOT need any new PK surface; it
needs to use the existing M1/M2/M3 ports as Jenkins and the second
inspector already do (and the M2/M3 published contract test suite is
the cross-check that the consumers do not regress).

### E.5 FITNESS constraints the M4 work must respect

- **byte-read latency budget** under output saturation: a follow that
  joins a saturated run MUST NOT add wall time to the writer. The
  follow's KDoc (`OutputFollower.kt:16-21`) explicitly pins this: the
  follow is polling at the existing `FOLLOW_IDLE_MILLIS = 25L`
  cadence; no extra scheduler, no backpressure on the writer.
- **out_of_order_advances = 0**: a second observer joining MUST NOT
  cause the store to publish a byte range that the journal has not
  heard of. The published surface does not introduce new publishes
  on observation (the recover path is segregated under
  `RuntimeRecoverPort.recover`, M2 design §5).
- **double_side_effect_count = 0**: a second observer joining MUST
  NOT cause the underlying bytes to be re-written. The follow ports
  are read-only by construction.
- **`controller_cpu_silent`**: the M4 work does NOT introduce a new
  per-observer polling loop; the existing `ObservationWakeup`
  vocabulary may be composed, not duplicated.
- **`follback_events_observer_independent`** (suggested by this audit
  as a new FITNESS metric, see §G): two observers on the same run
  produce observations whose progress differs ONLY in monotonic
  terms; the writer's state is independent of the observers'
  presence.

## F. Decision

```
BLOCK 4 = COMPATIBILITY HANDOFF ONLY.
```

Reasoning:

1. **No new gap is named in §B.** Each of the four M4 axes (independent
   cursors, complete output, legitimate terminality, no observation-
   induced cancellation) is served by the existing M1 + M2 + M3
   published surface. The audit does not propose any new port, sealed
   case, capability ID, or contract-cell addition.

2. **The six invariants all PASS-by-design + tested.** I.1 / I.2 /
   I.3 / I.4 / I.5 / I.6 — §D's table has zero `PARTIAL` and zero
   `UNVERIFIED` rows. The M1-D cross-JVM e2e
   (`M1DCrossJvmFollowTest`, UAT-PK-M1-001/003) already exercises
   the "second process reads while PK is still running" case at the
   producer side; the M3 contract test suite pins the M3 retention
   and digest additions; the M2 contract test suite pins the runtime
   verbs.

3. **No overlap requires PK-side consolidation before M4 ships.**
   §C's six overlaps are demarcated by their existing KDoc; the M4
   audit does not propose any change. The two existing ports that
   overlap on dual-observer state — `RuntimeIntrospectionPort.
   inspect` and the follow's first event — share read primitives
   but neither port's vocabulary calls the other.

4. **Authority moves the audit explicitly forbids are NOT proposed.**
   The audit does NOT propose a Jenkins dependency in the published
   modules; does NOT propose duplicating Jenkins reducer logic in
   PK; does NOT propose a new scheduler, lease, fencing scheme, or
   retry loop. The user's negative scope ("No introducir dependencias
   Jenkins en PipelineK ni duplicar reducers") is preserved.

5. **The decision is "conservar artefacto publicado y verificado".**
   Per the Block 4 plan, "si no cambia PK, conservar su artefacto
   publicado y verificado; el release de este bloque es el de
   Fabric M4". The M4 deliverable is the Fabric-side release; PK
   keeps `v0.51.0-rc1` (the M3 release) as its published artefact.
   No `PAIR_RECEIPT.json` change is required from PK.

6. **The audit explicitly identifies potential watchpoints.** The
   suggested UAT IDs in §E.2 (`UAT-PK-M4-001..004`) and the
   suggested FITNESS metric in §E.5 are named so the producer-
   side e2e suite and the Fabric-side M4 work agree on what
   "M4 covered" means. None of these is a PK surface change.

7. **One cell may need updating.** The
   `coordination/INTERFACE_CONTRACT.md` "Capacidades publicadas"
   table may need a one-line edit to reflect that Jenkins
   reads through `output.follow.v1` + `events.follow.v1` (M1,
   PUBLICADA) rather than through a Jenkins-specific port. The
   audit recommends the edit happen during the next
   `CONTRACT_SHA256.txt` refresh triggered by a subsequent
   M-block, not as part of M4 itself. This is a documentation
   touch, not a contract change.

## G. Open issues for the orchestrator

None that block the audit. The following items are flagged for the
design phase that consumes this document; they are not audit failures
and the audit does NOT propose PK-side work for any of them:

1. **The M4 design must name the Fabric-side Jenkins shim.** The
   PK side of the M4 deliverable is "no change"; the Fabric side
   of the M4 deliverable is "Jenkins import goes away, the Jenkins
   observer reads through `output.follow.v1` + `events.follow.v1`".
   This is a Fabric-side decision documented in
   `Rubentxu/pipelinek-fabric/docs/fabric-coordinated-evolution/`
   and out of scope for the PK audit.

2. **The M4 design must confirm the second-inspector identity.** The
   audit does NOT assume the second inspector is a specific tool
   (CLI, debug, replica, indexer); it pins the four axes by name
   so any consumer that respects them qualifies. The design may
   identify a primary second-inspector for the Fabric-side UAT;
   that is a Fabric-side decision.

3. **The M4 design must decide whether the four proposed UAT IDs
   (`UAT-PK-M4-001..004`) ship in the producer-side contract-test
   suite at `v0.51.0-rc1` or in a follow-up pre-release. The
   audit's recommendation is "follow-up pre-release": the M4
   audit's job is to NAME the axes, not to ship a release
   candidate. The producer-side e2e tests that already exist
   (M1-D cross-JVM; M2 adapter tests; M3 refusal / pin / digest
   tests) cover the same axes; the M4-specific UAT cases are
   optional additions that improve traceability, not contract.

4. **The M4 design must name the contract-test fingerprint.** Per the
   release-receipt v2 model
   (`docs/pipelinek-release-evolution/shared/02-release-model-v2.md`)
   the contract change authority is the `release-receipt` of the
   candidate. Since M4 makes no PK surface change, no
   `release-receipt` is required from PK. The audit recommends
   the Fabric side emit its own `release-receipt` for the M4
   candidate; PK is unchanged.

5. **The M4 work's "node" in the CRIC chain is the next Fabric
   candidate after `v0.51.0-rc1`.** The audit does not assign a
   PK version number; the harness chooses the train. The M4
   deliverable on the Fabric side is the next Fabric candidate;
   the PK side carries the existing `v0.51.0-rc1` published
   artefact.

6. **The audit pins the "observer does not enumerate other observers"
   rule as a deliberate non-feature.** Observer enumeration would
   be a privacy leak under contract §11 ("control separado de
   observación, payload y compresión acotados, cursors ligados a
   ACL/query"). The audit explicitly does NOT propose an
   `OutputFollowersOf(runId)` or `EventFollowersOf(runId)`
   port, and the design phase MUST NOT add one. Per-observer
   opacity is the contract's answer to "who else is watching this
   run".

7. **The audit pins the "second observer does not affect the
   lease" rule as a deliberate non-feature.** The lease is a
   per-run publishing authority owned by the writer; a second
   observer does NOT acquire or affect the lease. The
   `RuntimeIntrospectionPort.inspect` port reports the lease
   holder read-only (`RuntimeIntrospectionPort.kt:18-23`,
   `RuntimeObservation.Running.leaseHolder`); the audit does NOT
   propose a port that lets an observer take, release, or
   re-fence the lease.

8. **The audit pins the "second observer does not affect
   retention" rule as a deliberate non-feature.** A second observer
   does NOT trigger `OutputRetentionPort.prune` or
   `OutputRetentionPort.canPrune`; an observer that wants to
   pre-flight a release of its OWN pins calls
   `OutputPinPort.pinsOf(stream, range?)` and
   `OutputRetentionPort.canPrune(intent)` directly. The audit
   does NOT propose a port that lets an observer release
   another observer's pins.

9. **The audit explicitly excludes the "second observer is
   Jenkins" assumption.** Jenkins is one observer; the second
   inspector is some other observer; both are consumers of
   the existing public surface. The audit does NOT assume
   Jenkins ships its own port, reducer, or store inside PK;
   the user's negative scope ("No introducir dependencias
   Jenkins en PipelineK ni duplicar reducers") is preserved.