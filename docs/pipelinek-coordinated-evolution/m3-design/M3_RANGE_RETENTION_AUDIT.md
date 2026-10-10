# CRIC-M3 — RANGE / RETENTION audit (input to M3 design)

**Status:** read-only audit, completed before any M3 code is written.
**Worktree:** `pk-cric-m3` (branch `audit/cric-m3-range-retention`) at `4dce18e3`.
**Anchor:** the Block 3 plan ("Cerrar las garantías genéricas necesarias para la
replicación de Fabric: offsets byte, lectura exacta de rangos, frames, secuencia
estable, refusals, lector concurrente, recuperación sin escritura destructiva y
retención de bytes bajo garantías activas … No implementar ACK remoto, scheduler
de replicación, S3, gRPC o spools distribuidos dentro del core de PipelineK") and
the normative text in `coordination/INTERFACE_CONTRACT.md` §2, §8, and §12.
**Precedent:** `m2-design/M2_INSPECT_RECOVER_CANCEL_AUDIT.md` — the same
read-only survey shape, applied to the third block of CRIC guarantees.

This document is the audit. The design that consumes it is the next file in
this directory.

## 0. Scope and naming

The Block 3 plan names four M3 PK-side guarantees Fabric needs to ship its
**durable multi-stream replication**, plus the negative scope ("No implementar
ACK remoto, scheduler de replicación, S3, gRPC o spools distribuidos dentro del
core de PipelineK"). The audit classifies every public port along four axes:

```text
BYTES / RANGE       read by exact byte offset or half-open range
                        with no skipping or padding
FRAME / SEQUENCE     read by frame ordinal, with stability across a
                        writer restart
CONCURRENT READER    two independent observers on the same run,
                        neither affecting the other's cursor
RECOVER / NO DEST    a recover brings a run back to a known state
                        WITHOUT overwriting or invalidating data
RETENTION UNDER PIN  a pinned range survives any prune; an
                        unpinned range may be pruned
```

The fifth invariant the user pins is implicit in "frame sequence estable, refusals,
lector concurrente": loss must surface as a typed refusal, never as an empty page.

The audit answers, in order:

1. What public ports already exist that touch any of these axes (§A).
2. What gaps exist between what Fabric needs for M3 and what PK exposes (§B).
3. Where two existing ports claim the same authority over ranges/retention (§C).
4. Whether the six invariants the contract pins on ranges/retention hold
   today, and which ones fail (§D).
5. How the audit cross-walks to the existing UAT-PK / AAT / FABRIC evidence (§E).
6. The single decision: PK-CANDIDATE NEEDED or COMPATIBILITY HANDOFF ONLY (§F).

All paths are absolute. The `4dce18e3` baseline is the only commit consulted;
M1 and M2 work is referenced for context only and is pinned by
`release/cric-m1-v0.49.0-rc1` and `release/cric-m2-v0.50.0-rc1` respectively.

## A. Public ports that already touch BYTES, FRAME, RECOVER or RETENTION

The survey covers the four modules the user's brief names — `:pipeline-events`,
`:pipeline-output`, `:pipeline-runtime`, and `:pipeline-step-sdk` — plus the
two store modules that hold the durable authorities they compose with:
`:pipeline-events-store` and `:pipeline-output-store`.

Two columns matter:

- **Published?** A module with a `maven-publish` block in its `build.gradle.kts`
  is on the ABI. A type that lives in a non-published module is reachable inside
  PK but is NOT part of the contract Fabric can compile against. Per
  `INTERFACE_CONTRACT.md` §6 ("el consumidor compila contra el ABI publicado,
  nunca contra el árbol de fuentes o `mavenLocal`"), the published modules are
  the only ones whose ADTs Fabric's M3 replication consumes.
- **Axis.** One of {Bytes, Frame, Concurrent, Recover, Retention} or a closed
  subset.

### A.1 `:pipeline-output` (published module `pipeline-output`)

```
OutputReadPort (interface)
  | v2/pipeline-output/.../output/OutputReadPort.kt:19-44                 | Bytes, Concurrent, Recover
  | read-only port: committedExtent(s) -> Long?, read(s, cursor, maxBytes)
  | -> OutputReadResult, readRange(s, from, to) -> OutputReadResult.
  | readRange is half-open `[from, to)` and contractual (readRange(s, o, o+n)
  | must equal the first n bytes of readRange(s, o, committed)).

OutputCursor (data class)        | …/output/OutputCursor.kt:57-105           | Bytes
  | (stream, committedOffset); wire token `out-cursor-v1:`. encode/decode;
  | stream must match committed stream — ForeignStream refusal otherwise.

OutputStreamId (value class)      | …/output/OutputCursor.kt:24-31           | Bytes
  | stable stream id; not a run id, not an operation id; survives recovery.

OutputPage (data class)           | …/output/OutputCursor.kt:121-157         | Bytes
  | (bytes, stream, from, next?, committedEnd); invariant: page ends at
  | next.committedOffset when next != null; next == null means "nothing more
  | right now", NOT "stream finished" (that distinction lives in OutputTailState).

OutputReadResult (sealed)         | …/output/OutputRefusal.kt:101-105        | Bytes
  | Page(page) | Refused(reason).

OutputRefusal (sealed)            | …/output/OutputRefusal.kt:14-99          | Bytes, Frame, Retention
  | closed ADT: ForeignStream | UnknownStream | OffsetBeyondCommitted |
  | InvalidRange | RecoveryNotCompleted | DanglingCommit | StreamLostRetention
  | (M1-B) | FollowCancelled (M1-B) | StorageError (M1-B).
  | **NO case names RetentionGap, Corrupt, or Unavailable** — the contract
  | §2 names these distinctions and PK today has none of them.

OutputChannel (enum)              | …/output/OutputChannel.kt:23-42          | Bytes
  | STDOUT(token="stdout") | STDERR(token="stderr"); closed set; no "merged".

OutputStreamAddress (data class)  | …/output/OutputChannel.kt:109-148        | Bytes
  | (runId, operationId, channel); stream = "{run}/{opId}/{token}".
  | parse(stream) returns null for non-shape streams; identity is OPAQUE.

OperationOutputStreams (data)     | …/output/OutputChannel.kt:159-184        | Bytes
  | stdout+stderr pair of one operation; select(channels), all.

OutputFrame (data class)          | …/output/OutputFrameIndex.kt:50-65      | Frame
  | (ordinal, stream, channel, from, to); metadata only — NEVER a payload
  | (a frame that carried bytes would be the double-authority ADR-M1 §D2
  | removes). ordinal is the order PK observed, NOT causal order, NOT byte
  | order, NOT comparable with event sequence.

OutputFrameIndex (interface)      | …/output/OutputFrameIndex.kt:107-200     | Frame, Recover
  | declareStream | append | framesOfRun(runId, afterOrdinal, limit) |
  | lastOrdinal | streamsOfRun | recoverUnframedBytes.
  | framesOfRun is the read method most relevant to M3's "read by frame
  | ordinal with stability across restart" — the afterOrdinal resume is
  | the cross-restart continuation; the ordinal is assigned at publication
  | time inside the call (M1 design §2.1 / follow port).

OutputTailState (sealed)          | …/output/OutputTailState.kt:42-69       | Frame, Retention
  | Open(committedEnd) | Sealed(finalEnd); null for unknown.
  | **NO Unavailable case** at the type level (a stream whose store is
  | unreachable surfaces as null, conflating "never seen" with "cannot see").

OutputTailPort (interface)        | …/output/OutputTailState.kt:86-96        | Frame, Retention
  | tailState(stream): OutputTailState? — Open or Sealed or null.
  | "null means unknown" is one answer and this method has no other way to
  | express it (KDoc lines 91-94).

OutputRetentionPort (interface)   | …/output/OutputRetention.kt:149-162     | Retention
  | hasOutputFor(runId): Boolean; prune(intent): OutputPruneReport.
  | **NO pin primitive.** A consumer cannot tell the store "this range
  | must not be pruned until I release it"; `prune` is a single-shot,
  | terminal-triggered action with NO consult-before-prune check.

RunLifecycle (sealed)             | …/output/OutputRetention.kt:57-66        | Retention
  | Terminal | StillRunning. Deliberately outcome-less (KDoc lines 57-66):
  | "the runtime owns outcome, this plane owns "may we delete"."

RetainUntil (sealed)              | …/output/OutputRetention.kt:74-102       | Retention
  | RunTerminalPlus | ExplicitReleaseOnly | Forever.
  | authorize(runId, lifecycle) -> OutputPruneIntent? — RETURNS NULL for
  | Forever and for ExplicitReleaseOnly-while-live. There is NO case that
  | holds a range because an EXTERNAL consumer (Fabric's replica) said so.

OutputPruneIntent (sealed)        | …/output/OutputRetention.kt:110-125      | Retention
  | RunReachedTerminalState(runId) | OperatorReleased(runId, requestedBy).
  | **NO Pin(runId, range) case.** Pruning is gated by the run lifecycle
  | and by a named operator, not by a held contract from a consumer.

OutputPruneReport (data class)    | …/output/OutputRetention.kt:128-140      | Retention
  | (streamsRemoved, bytesReleased, streamsRetained).

OutputAdoption (object)           | …/output/OutputAdoption.kt:36-243        | Recover
  | the acceptance bar for "S2 is implemented"; mayClaimCrashConsistency.
```

**Verdict.** The output plane has a complete **byte-cursor read surface**
(`OutputReadPort.read` + `readRange` + `committedExtent`), a complete **frame
index** (`OutputFrameIndex` with stable ordinals and a cross-restart resume via
`afterOrdinal`), a complete **terminality port** (`OutputTailPort` with
`Open`/`Sealed`/null), and a complete **single-shot retention port**
(`OutputRetentionPort.prune(intent)`). **There is no PIN-authority on the
read side, no DIGEST on the read side, and no `RetentionGap` / `Corrupt` /
`Unavailable` cases in the closed `OutputRefusal` ADT.** These three
absences are the M3 gaps and are named in §B.

### A.2 `:pipeline-events` (published module `pipeline-events`)

```
EventHistory (interface)          | …/identity/EventHistoryPorts.kt:151-153  | Bytes (event sequence)
  | history(run, query): Sequence<PipelineEventEnvelope>.

EventTail (interface)             | …/identity/EventHistoryPorts.kt:159-161  | Bytes (event sequence)
  | readAfter(run, cursor, limit): EventPage.

EventCursor (data class)          | …/identity/EventHistoryPorts.kt:22-53    | Bytes (event sequence)
  | (runId, lastSequence); wire evt-cursor-v1:. Distinct type from
  | OutputCursor (deliberately — see WHY block at lines 33-53).

EventQuery (sealed)               | …/identity/EventHistoryPorts.kt:59-95    | Inspect
  | All | ByKind | BySource | BySubject | BySequenceRange.

EventPage (data class)            | …/identity/EventHistoryPorts.kt:102-137  | Bytes (event sequence)
  | envelopes + nextCursor + hasMore + refusals.

EventHistoryReader (class)        | …/identity/EventHistoryReader.kt:32-82   | Inspect
  | adapter implementing both EventHistory and EventTail over any EventSink.

EventRecordReadPort (interface)   | …/identity/EventRecordReadPort.kt        | Inspect, Bytes
  | readRecords(runId, after, query, limit): EventRecordReadResult
  | (the M1-A typed read port; returns EventRecordSlice with Undecodable rows).

EventFollower (interface)         | …/follow/EventFollower.kt                | Inspect
  | open(runId, options): EventFollowHandle; iterator pulls typed pages
  | until StateChanged(RunFinished) (events.follow.v1).
```

**Verdict.** The event plane covers the **event-sequence** half of the M3
contract. It has nothing to say about byte ranges, frames, or retention
of bytes — events name a run and a sequence but never carry bytes. The
event plane's `EventCursor` is a different authority from `OutputCursor`
(identity, range, sequence are all distinct). For M3, the event plane is
the **secondary** substrate (it carries `RunStarted`/`RunFinished` and the
typed observations the consumer reconciles byte ranges against). It is
not in scope for a range/retention audit beyond noting what is NOT here.

### A.3 `:pipeline-runtime` (published module `pipeline-runtime`, M2 outcome)

```
Capabilities (object)                     | …/runtime/Capabilities.kt:…              | (negotiation)
  | RUNTIME_INSPECT_V1 = "runtime.inspect.v1"
  | RUNTIME_CANCEL_V1  = "runtime.cancel.v1"
  | RUNTIME_RECOVER_V1 = "runtime.recover.v1"
  | All three are EXPERIMENTAL in v0.50.0-rc1.

RuntimeIntrospectionPort (fun interface)  | …/runtime/inspect/RuntimeIntrospectionPort.kt:40-66 | Inspect, Recover
  | inspect(runId): RuntimeIntrospectionResult.
  | READ-ONLY by construction — composition table §6 of M2 design names
  | OutputTailPort.tailState + OutputFrameIndex.streamsOfRun +
  | OperationJournal.listForRun + ReplayCursorStore.load as the SELECTs.

RuntimeObservation (sealed)                | …/runtime/inspect/RuntimeObservation.kt:23-191  | Inspect
  | Running(attempt, leaseHolder?, fencingToken, journalPosition,
  |         outputTails, process?) | Terminal(...) | LiveButEmpty(...) | Unobservable(...).

RuntimeIntrospectionResult (sealed)       | …/runtime/inspect/RuntimeIntrospectionResult.kt | Inspect
  | Observation(obs) | Refused(refusal).

IntrospectionRefusal (sealed)             | …/runtime/inspect/IntrospectionRefusal.kt       | Inspect
  | UnknownRun | NoControlRoot | NoEventStore | NoOutputPlane |
  | LeaseHeldByAnother(heldBy, fencingToken) | StorageError | InconsistentLeaseState.

RuntimeControlPort (fun interface)        | …/runtime/control/RuntimeControlPort.kt:…       | (Cancel, M2 only)
  | cancel(runId, reason): CancelOutcome.
  | CancelOutcome = Cancelled | AlreadyCancelled | Refused(CancelRefusal).
  | CancelRefusal = UnknownRun | RunTerminal | LeaseHeldByAnother |
  | IncompatibleRunState | JournalUnavailable | StorageError.

RuntimeRecoverPort (fun interface)        | …/runtime/recover/RuntimeRecoverPort.kt:…       | Recover
  | recover(runId, options): RecoverOutcome.
  | RecoverOutcome = RecoveredTerminal | ReattachPending | FailClosed |
  | AlreadyRecovered.
  | RecoverRefusal = UnknownRun | NotRecoverable | SubstrateUnavailable |
  | JournalIncompatible | LeaseHeldByAnother | StorageError.

RuntimeRecoverDecision (object)           | …/runtime/recover/RuntimeRecoverDecision.kt     | Recover
  | decideRecovery(observation, journal): RecoveryChoice.
  | PURE decider, exhaustive over RuntimeObservation cases (M2 design §5.5.1).
```

**Verdict.** M2 introduced three runtime verbs (inspect / cancel / recover) and
the closed ADTs that go with them. None of them is **a RANGE or RETENTION
verb**. The audit's reason for listing them here is two fold:

1. The **read-only invariant of `inspect`** (composition table §6 of M2 design)
   is exactly the same invariant M3 needs for byte reads: a consumer that calls
   `inspect` must not invoke `OutputRecoveryPort.recover()`. M3 reads (and any
   M3 follow) MUST comply with the same constraint, by composition, not by
   re-pinning.
2. The **lease-crossing refusal vocabulary** (`LeaseHeldByAnother(...)`) is
   the same vocabulary M3 must use when a replica cannot reach a range
   because a writer holds the lease. The M2 sealed ADTs already cover this
   case; M3 does not need a new refusal type for it, only a new caller.

### A.4 `:pipeline-step-sdk` (read-side types relevant to M3)

```
EffectReplayPolicy (interface)       | …/runtime/durable/EffectReplayPolicy.kt:63-95  | Recover
  | decide(policy, effects, hasJournalEntry, journaledOutcome): ReplayDecision.
  | Normative matrix pinned by EffectReplayPolicyTableFitnessTest; the
  | per-effect replay decision the M2 decider wraps.

ReplayDecision (enum)                | …/runtime/durable/ReplayDecision.kt:8-17       | Recover
  | SKIP | RERUN | ABORT.
```

**Verdict.** The replay policy is a **recover** primitive; M3 ranges and
retention are downstream of it (a range that survived the recover path
should still be readable afterwards). This audit does not propose changes
here; the M2 ports compose the existing matrix.

### A.5 The two store modules (not published)

These are the durable authorities that the published modules read through.
The audit lists them because every existing **write-side authority over
ranges/retention** lives here, and the question "can a consumer reach
retention authority through the published ABI?" has a definite answer at
the end of this section.

```
OperationJournal (interface)
  | v2/pipeline-events-store/.../durable/OperationJournal.kt:28-124        | Bytes (event sequence), Recover
  | append(op, deadlineMs?) | get(opId) | get(opId, attempt) |
  | listForRun(runId) | getDeadlineMs | getEndedAt | getStartedAt |
  | beginOperation(opId, attempt, fingerprint, inputJson, deadlineMs?).
  | NOT published (M2 audit §A.6); reads visible to the consumer only via
  | EventRecordReadPort and RuntimeIntrospectionPort.
  | NO range-byte primitive; the journal is the EVENT sequence, not the
  | byte sequence (different authorities).

ReplayCursorStore (interface)
  | …/events/durable/ReplayCursorStore.kt:16-64                            | Recover
  | load(runId) | advance(runId, opId, stageIndex) | advancePastParallelFrame.
  | NOT published. The cursor is "where to resume" — not a byte range cursor.

RunExecutionLease (pure decider object)
  | …/events/durable/RunExecutionLease.kt:216-309                           | (per-run ownership)
  | acquire(current, request): LeaseAcquisition;
  | authorisePublish(observed, held): PublishAuthority;
  | release(current, ownerId): LeaseRecord.
  | The pure half — total, side-effect free. Adapter for IO lives in
  | FileBackedRunExecutionLeaseStore (NOT published).

FileBackedRunExecutionLeaseStore (class)
  | …/events/durable/FileBackedRunExecutionLeaseStore.kt:40-298             | (per-run ownership)
  | acquire(request): LeaseAcquisition (OS file lock + fencing token);
  | release(ownerId); isKnown(runId); authorise(runId, held): PublishAuthority;
  | observe(runId): LeaseRecord? (M2 §4.7.1 read-only twin).
  | NOT published. The store stays inside PK; the introspection and cancel
  | ports reach it through application-layer adapters.

OutputAppendPort (interface)
  | v2/pipeline-output-store/.../store/OutputWritePorts.kt:44-53            | (write-side)
  | open(stream): OutputStreamHandle.

OutputStreamHandle (interface) / OutputReservation (interface)
  | …/store/OutputWritePorts.kt:60-145                                      | (write-side)
  | reserve(minBytes) | appendFrom(source) | write | copyFrom | commit | abandon.

OutputRecoveryPort (interface)
  | …/store/OutputWritePorts.kt:166-176                                    | Recover (PRIVATE to PK)
  | recover(): OutputRecoveryReport — O3 entry point, idempotent,
  | destructive-by-design, gated to the writer's JVM.
  | NOT published. A consumer that calls this through any PK public port
  | would see OutputRefusal.RecoveryNotCompleted until somebody inside
  | the JVM has invoked recover() first.

OutputSealPort (interface)
  | …/store/OutputWritePorts.kt:205-236                                    | (terminal marker, not pin)
  | seal(stream): SealOutcome — Sealed | AlreadySealed | NeverOpened | Failure.

SealOutcome (sealed)                  | …/store/OutputWritePorts.kt:248-286
  | Sealed(end) | AlreadySealed(end) | NeverOpened | Failure(cause, end?).

OutputRecoveryReport (data class)     | …/store/OutputWritePorts.kt:297-317            | Recover
  | (streamsReconciled, streamsOwned, committedBytes, bytesReleased,
  |  reservationsReleased, bytesUnbacked).
  | The `bytesUnbacked != 0` case is what an M3 recover MUST honour: the
  | audit pins this in §D.4 (no destructive recover, refuse on unbacked).
```

**The publication question, answered.** `:pipeline-events-store` and
`:pipeline-output-store` do **not** declare `maven-publish`. `OperationJournal`,
`OutputRecoveryPort`, `OutputSealPort`, `FileBackedRunExecutionLeaseStore`,
`RunExecutionLease`, and `ReplayCursorStore` are **internal-to-PK**. A consumer
that wants any of them must reach into the JVM that owns the run, which is
not a public API boundary. The published plane gives the consumer:
- **byte reads**: `OutputReadPort`, `OutputCursor`, `OutputPage`, `OutputRefusal`;
- **frame reads**: `OutputFrameIndex`, `OutputFrame`;
- **concurrent observer independence**: by construction of `OutputCursor`
  being a value type (a cursor is owned by the consumer, not by the store);
- **terminality read**: `OutputTailPort`, `OutputTailState`;
- **retention authority**: `OutputRetentionPort`, `OutputPruneIntent`,
  `OutputPruneReport`, `RunLifecycle`, `RetainUntil`;
- **runtime verbs**: `RuntimeIntrospectionPort`, `RuntimeControlPort`,
  `RuntimeRecoverPort`, and the M2 sealed refusal ADTs;
- **typed event reads**: `EventHistory`, `EventTail`, `EventRecordReadPort`,
  `EventCursor`, `EventQuery`, `EventPage`.

What the published plane **does not** give the consumer:
- a **digest** on a read result (`OutputReadResult.Page` carries bytes + offset,
  no SHA-256 or any content hash);
- a **pin** primitive on a range (no `Pin(range, holder)` in `OutputRetentionPort`;
  no case in `OutputPruneIntent` that holds a range because a holder requested it);
- **`RetentionGap` / `Corrupt` / `Unavailable` refusal cases** (the closed
  `OutputRefusal` ADT today is `ForeignStream | UnknownStream |
  OffsetBeyondCommitted | InvalidRange | RecoveryNotCompleted | DanglingCommit
  | StreamLostRetention | FollowCancelled | StorageError`; contract §2
  names five distinct conditions and PK surfaces four of them at most, as
  `StorageError` with a free-text `cause`).

### A.6 Summary count

| Module                                  | Published? | Touches Bytes | Touches Frame | Touches Concurrent | Touches Recover | Touches Retention |
|-----------------------------------------|------------|---------------|---------------|--------------------|-----------------|-------------------|
| `:pipeline-output`                      | YES        | 8 ports       | 2 ports       | (by cursor val)    | 1 port          | 5 ports           |
| `:pipeline-events`                      | YES        | 0 (event-side)| 0             | 0                  | 0               | 0                 |
| `:pipeline-runtime`                     | YES        | (M2 inspect reads the tail) | (M2 inspect reads the index) | 0 | 3 ports (M2)      | (M2 inspect sees Terminal only) |
| `:pipeline-step-sdk/runtime/durable/`   | NO         | 0             | 0             | 0                  | 3 ports         | 0                 |
| `:pipeline-output-store/`               | NO         | 0 (write)     | 0             | 0                  | 2 ports (private) | 1 port (sealed; not pin) |
| `:pipeline-events-store/`               | NO         | 0             | 0             | 0                  | 3 ports         | 0                 |
| **Total surveyed**                      | —          | **8**         | **2**         | **(by value)**     | **9**           | **6**             |

Two truths fall out of that count:

1. **PK has a complete byte-cursor + frame-ordinal + sealed-marker surface.**
   A consumer that wants a `readRange(from, to)` and `framesOfRun(afterOrdinal)`
   and `tailState(stream)` is already served by the M1-published ports. The
   contract §2 first sentence is honoured with what is in `:pipeline-output`.

2. **PK is missing three things Fabric's M3 replication needs**, each of
   which is an authority over ranges the audit must name:
   - a typed refusal shape that distinguishes "bytes were here, but are
     gone" (`RetentionGap`) from "bytes here are damaged" (`Corrupt`) from
     "the store cannot answer right now" (`Unavailable`) — these three
     separate FAILURES, named in the contract §2, are collapsed into
     `OutputRefusal.StorageError` today;
   - a digest on the read path so a replica can detect
     "`(a,b,digestA)` reapareció" (`AlreadyCommitted`) vs "`(a,b,digestB)`"
     (`Conflict`) per contract §8 — `OutputReadPort.readRange` returns
     bytes but no content hash;
   - a pin / retention-hold primitive so that "no podar rangos hasta
     probar que su garantía activa se transfirió a una copia durable, y
     que no existe pin" (`INTERFACE_CONTRACT.md` §12) can be honoured —
     `OutputRetentionPort.prune` has no consult-before-prune API and
     `OutputPruneIntent` has no `Pin(runId, range)` case.

## B. Gaps between contract requirement and current ports

Each gap is numbered C.1..C.N. The numbering format follows the M2 audit
(B.1..B.8) — `C` for the M3 block. (a) what Fabric needs, (b) what PK has,
(c) the missing shape, (d) the proposed new port or extension, (e) the ADT
it would carry, (f) the authority it must NOT introduce. Gaps are derived
from the survey in §A; no gap claims a behaviour the audit did not see
in the code.

### B.1 No public digest on the read path — the idempotency key cannot be computed

- **(a) Fabric needs** a typed content hash (the contract §8 says
  "`(a,b,digestA)` repetido ⇒ `AlreadyCommitted`; digest distinto ⇒ `Conflict`")
  so a replica that re-sends the same `[from, to)` range can tell same-bytes
  from same-coords-different-bytes. The idempotency key in
  `docs/fabric-coordinated-evolution/specifications/SPEC-03-REPLICATION-AND-OUTBOX.md`
  is `(tenant, run, plane, start, endExclusive, digest, epoch)` and the digest is
  load-bearing.

- **(b) PK has** `OutputReadResult.Page(page: OutputPage)` where `OutputPage` is
  `(bytes, stream, from, next?, committedEnd)` (`OutputCursor.kt:121-157`).
  There is no `digest` field. The audit grep over `v2/pipeline-output*/src/main/kotlin/`
  for `digest|Digest` returns zero matches.

- **(c) Missing shape.** Either:
  - a `DigestedOutputPage(page, digest: ByteString)` (or `String`-shaped SHA-256)
    that the read port surfaces, **OR**
  - a pure helper `OutputDigest.compute(stream, from, to): ByteString` that
    the consumer can call against the read port and the store can re-verify.

  Either choice must be a **deterministic** function of the committed bytes
  in `[from, to)`; SHA-256 (or a stronger hash) is the existing PK
  precedent — `OperationJournal.beginOperation(opId, attempt, fingerprint: String, ...)`
  already takes a SHA-256 fingerprint of operation input.

- **(d) Proposed new port.** A new sibling type `OutputReadPort.readRangeDigested(stream,
  from, to): OutputReadDigestedResult` (a sealed envelope), **OR** an additive
  field on the existing `OutputPage`. The additive field is smaller but
  changes a published type's shape; a new sibling port is the M2 precedent
  for "extend, never duplicate" (the audit pins each new M2 port as
  additive on top of the existing published surface, M2 design §2.1).

- **(e) ADT it would carry.** `sealed interface OutputReadDigestedResult {
  data class Page(val page: OutputPage, val digest: ByteString) : OutputReadDigestedResult;
  data class Refused(val reason: OutputRefusal) : OutputReadDigestedResult }`
  — OR an extension of `OutputReadResult` with the optional digest on the
  existing case.

- **(f) Authority it must NOT introduce.** No new content-hash algorithm
  (use SHA-256, the same algorithm `OperationJournal.beginOperation`'s
  `fingerprint` uses). No new scheduler. No new lease. The store does
  the read; the digest is computed by the same path the read used.

### B.2 No public `RetentionGap` / `Corrupt` / `Unavailable` refusal cases — three named failures are one

- **(a) Fabric needs** three distinct refusals, named verbatim in
  `INTERFACE_CONTRACT.md` §2: `Unavailable`, `RetentionGap`, `Corrupt`. SPEC-03
  uses the same three cases as the typed answer for "byte range `[a,b)` of
  this stream cannot be served and here is why". The contract §12 ("no
  podar rangos hasta probar que su garantía activa se transfirió a una copia
  durable, y que no existe pin") depends on a `RetentionGap` refusal to
  force the consumer to discover that a replicated range was pruned.

- **(b) PK has** `OutputRefusal.StorageError(cause: String)`
  (`OutputRefusal.kt:96-99`), introduced in M1-B as a catch-all.
  `OutputRefusal.StreamLostRetention(stream, lastCommitted)`
  (`OutputRefusal.kt:71-74`) covers the follow-side variant of "the tail
  went away", but it is follow-specific (named in the M1-B comment to
  "follow's declared stream lost retention between two polls"). The M3
  read-side surface has only `StorageError`, whose `cause` is a free-text
  `String` the consumer cannot pattern-match on.

- **(c) Missing shape.** Three new sealed cases on the existing
  `OutputRefusal` hierarchy, as the M1 design already added new cases
  (`StreamLostRetention`, `FollowCancelled`, `StorageError`) to the same
  closed type (§3 of `M1_FOLLOW_DESIGN.md` adds them as additive cases,
  not as a parallel hierarchy).

  ```
  data class RetentionGap(
      val stream: OutputStreamId,
      val requestedRange: LongRange,
      val lastCommitted: Long,
  ) : OutputRefusal

  data class Corrupt(
      val stream: OutputStreamId,
      val requestedRange: LongRange,
      val reason: String, // bounded diagnostic
  ) : OutputRefusal

  data object Unavailable : OutputRefusal  // the store cannot answer right now
  ```

  `RetentionGap` is the consumer-facing twin of the existing terminal-side
  `OutputRetentionPort.prune`; it is what a read sees when the bytes it named
  were PRUNED by a `prune(RunReachedTerminalState(runId))` call after the
  range was committed. `Corrupt` is what a read sees when the store has a
  commit record that the payload cannot back (today, that condition surfaces
  as `OutputRefusal.DanglingCommit` for the write side, but the read side
  currently has no equivalent — see §B.5). `Unavailable` is the typed
  answer for "I cannot tell"; the audit recommends `data object` for it
  (one shape, no payload), mirroring `RecoveryNotCompleted`.

- **(d) Proposed new port.** Extend the existing `OutputRefusal` closed
  ADT (no new port — additive). The M1 design §3 (`OutputRefusal` extension
  policy) is the precedent: new failure modes ARE new sealed cases on the
  SAME closed type, never a parallel `Either`/`Result`. The same rule
  applies in M3: the Audit MUST NOT propose a new `OutputReadM3Refusal`
  or similar parallel hierarchy.

- **(e) ADT it would carry.** Three new sealed cases on the published
  `OutputRefusal`; no new port.

- **(f) Authority it must NOT introduce.** No new "I checked and the bytes
  are gone" hierarchy in `:pipeline-output-store`. The `RetentionGap` case
  must be derivable from the store's own facts (`OutputRetentionPort` +
  the bytes store's tracked commit record); no new durable authority.

### B.3 No public retention-pin primitive — the contract §12 invariant cannot be honoured

- **(a) Fabric needs** a way to tell PK "this range of this stream must
  not be pruned, because an external replica is holding it". The contract
  §12 is explicit: "no podar rangos hasta probar que su garantía activa
  se transfirió a una copia durable, y que no existe pin". SPEC-03 names
  the same authority under "outbox-by-ref opt requires … pin forbids
  prune/GC". Without a pin, an M3 Fabric replica that holds a range in
  its local spool has no way to prevent PK from pruning the same range
  on the producer side.

- **(b) PK has** `OutputRetentionPort.prune(intent): OutputPruneReport`
  where `intent` is `OutputPruneIntent = RunReachedTerminalState(runId) |
  OperatorReleased(runId, requestedBy)`. Both are *single-shot*, *terminal-
  gated* (the first, by `RunLifecycle.Terminal`) or *operator-gated*
  (the second, by a `requestedBy` String). There is no `Pin(runId,
  stream, range, holder, expiresAt?)` case; there is no
  `hasPins(stream)` query; there is no `releasePin(pinId)` inverse to
  `prune`; there is no consult-before-prune API.

- **(c) Missing shape.** A **pin authority** over ranges, in two parts:
  1. A new sealed case on the existing `OutputPruneIntent` ADT (or a
     new sibling `OutputRetentionPin`) that names a range and a holder.
  2. A **consult-before-prune** API on `OutputRetentionPort` that the
     store calls (or that the consumer calls) BEFORE it acts on a
     `prune(intent)` — the call returns "this intent would touch N
     pinned ranges, here is their identity; release or fail-closed".

  The audit recommends (a) `fun canPrune(intent: OutputPruneIntent):
  PruneAuthorisation` returning a closed `sealed interface PruneAuthorisation
  { Granted | RefusedPinned(refused: List<OutputPinId>) | StorageError(...) }`,
  plus (b) a sibling port `OutputPinPort.pin(stream, range, holder,
  reason): OutputPinId` and `unpin(pinId): UnpinOutcome`. Two
  siblings compose: `canPrune(intent).RefusedPinned` names the pin ids
  the caller must release before re-trying.

- **(d) Proposed new port.** `OutputPinPort` in `:pipeline-output`
  (additive). `OutputRetentionPort.canPrune(intent): PruneAuthorisation`
  added to the existing port (additive). `OutputPinId` carried in the
  new refusal `PruneAuthorisation.RefusedPinned`.

- **(e) ADT it would carry.**
  ```
  interface OutputPinPort {
      fun pin(stream, range, holder, reason): OutputPinResult
      fun unpin(pinId: OutputPinId): UnpinOutcome
      fun pinsOf(stream: OutputStreamId, range: LongRange? = null): List<OutputPin>
  }
  sealed interface OutputPinResult {
      data class Pinned(val pinId, val expiresAtMs?): OutputPinResult
      data class Refused(val reason: PinRefusal): OutputPinResult
  }
  sealed interface PinRefusal { UnknownStream | RangeBeyondCommitted | AlreadyPinnedAt | CapExceeded | StorageError(...) }
  sealed interface UnpinOutcome { Released | UnknownPin | WasAlreadyReleased }
  sealed interface PruneAuthorisation { Granted | RefusedPinned(...) | StorageError(...) }
  ```
  The audit pins `holder: String` as the durable identity (matching the
  existing `RunOwnerId`-style shape — no typed identity at the public
  surface; the lease store and pin store reach for `String`s today).

- **(f) Authority it must NOT introduce.** The audit MUST NOT propose a
  pin store inside `:pipeline-output-store` that duplicates the lease
  store's fence logic — pins are NOT a lease; they are a retention hold,
  the authority is "the bytes may not be GC'd", not "the writer is the
  only one". The M3 design is responsible for distinguishing
  **per-run lease** (already exists: `RunExecutionLease` +
  `FileBackedRunExecutionLeaseStore`, internal) from **per-range pin**
  (does not exist, this gap proposes it). The two authorities do not
  share a store; sharing would conflate "you may write" with "you may
  hold bytes", which is exactly the kind of conflation `ADR-M1 §D2`
  forbids.

### B.4 No public inspect for "is this range currently pinned?" — the M3 replica query needs an answer

- **(a) Fabric needs** to ask PK "is the range `[a,b)` of stream `X` currently
  held by anyone, and if so by whom?" without first attempting a `prune`
  call. SPEC-03 describes the replica as querying the pin state to decide
  whether a local spool entry is "the only copy"; that is an
  **inspect**-shaped question.

- **(b) PK has** no `pinsOf(stream, range?)` query and no
  `pinsOfRun(runId)` query. The closest inspection is
  `RuntimeIntrospectionPort.inspect(runId)` (M2), which reports
  terminality, lease, journal position, and tail state, but does not name
  pins or ranges. The audit grep confirms `pin|Pin` returns zero matches
  in `OutputRetention.kt`.

- **(c) Missing shape.** A new method on the same `OutputPinPort` (§B.3):
  `pinsOf(stream, range?)` returning a `List<OutputPin>` ordered by pin id
  (deterministic order is required for the contract test — pins are a
  list, not a set). The audit pins the order as `pinId.lexicographic`
  so the test is reproducible.

- **(d) Proposed new port.** Same `OutputPinPort` (§B.3); no new port.

- **(e) ADT it would carry.**
  ```
  data class OutputPin(
      val pinId: OutputPinId,
      val stream: OutputStreamId,
      val range: LongRange,
      val holder: String,
      val reason: String,           // bounded diagnostic
      val createdAtMs: Long,
      val expiresAtMs: Long?,
  )
  ```

- **(f) Authority it must NOT introduce.** Pins are **NOT a lease**. A pin
  does not fence a writer; it holds bytes against GC. Conflating the
  two authorities in the audit would be a category error; the design
  phase must keep them apart.

### B.5 `DanglingCommit` is write-side only; the read-side analogue is missing

- **(a) Fabric needs** a typed refusal on the read path that names the
  exact "bytes were committed but are not present" condition. Today,
  `OutputRecoveryReport.bytesUnbacked != 0` (the **store** has an honest
  count of how many bytes it cannot serve), but the consumer never sees
  this — the read returns either an empty page or a `StorageError`, both
  of which are wrong answers about which bytes are missing.

- **(b) PK has** `OutputRefusal.DanglingCommit(requestedEnd, readableBytes)`
  on the **write** path (`OutputRefusal.kt:56-59`). The KDoc says
  exactly this: "A committed offset exists that the payload cannot back. I4."
  The READ path has only `OutputRefusal.DanglingCommit` reached via a
  read that crosses the unbacked boundary — and only via `readRange`
  crossing `readableBytes`, not via any dedicated "unbacked start"
  answer.

- **(c) Missing shape.** Two changes (additive, no new port):
  1. `OutputRefusal.DanglingCommit` becomes a **read-path** case too,
     surfacing when a `read` or `readRange` lands in an unbacked region.
  2. A new `OutputRefusal.Corrupt(stream, requestedRange, reason)`
     (§B.2) carries the same fact at a finer granularity — by range,
     not by `requestedEnd`.

- **(d) Proposed new port.** No new port; one existing case is broadened
  to the read path and a new case carries the same fact at finer
  granularity. The M1 design's "additive on `OutputRefusal`" rule
  (`M1_FOLLOW_DESIGN.md` §3) is the precedent.

- **(e) ADT.** Existing `OutputRefusal.DanglingCommit` + new
  `OutputRefusal.Corrupt(...)`.

- **(f) Authority it must NOT introduce.** No new "read-side dangling"
  port; the existing `OutputReadPort`/`OutputRefusal` is the whole read
  surface (M2 audit §A.2 already documented this). No new durability
  for the unbacked-bytes fact.

### B.6 `OutputRefusal.StreamLostRetention` is follow-side only — read-side needs the same fact

- **(a) Fabric needs** to learn that a range it named in a `readRange`
  call has been lost to retention, without having to drive a follow. The
  M1 follow contract (`OutputRefusal.StreamLostRetention(stream,
  lastCommitted)` at `OutputRefusal.kt:71-74`) carries this fact for the
  follow port, but a Fabric that polls rather than follows has no way
  to see it.

- **(b) PK has** `OutputRefusal.StreamLostRetention` only on the follow
  port. The M1 KDoc (`OutputRefusal.kt:62-70`) explicitly says
  "Surfaced by [dev.rubentxu.pipeline.v2.output.follow.OutputFollower.open]
  when a stream the consumer was tailing was pruned by the retention
  policy". The pull-side `OutputReadPort.readRange` has no equivalent:
  when a cursor names bytes that have been GC'd, the read returns
  `OutputRefusal.OffsetBeyondCommitted(requested, committed)` —
  which conflates "you named a byte that does not exist yet" with
  "the bytes you named were GC'd".

- **(c) Missing shape.** A new sealed case on the existing
  `OutputRefusal` ADT for the read-side lost-retention fact, distinct
  from `OffsetBeyondCommitted`:

  ```
  data class RangeLostRetention(
      val stream: OutputStreamId,
      val requestedRange: LongRange,
      val lastCommitted: Long,
  ) : OutputRefusal
  ```

  This is distinct from `StreamLostRetention` (follow-side, one stream's
  tail) because the read-side fact is **a range of a stream** may be
  gone, not the whole tail. The audit pins the distinction explicitly
  to avoid the conflation that would let a consumer treat "tail gone"
  and "range gone" as the same answer.

- **(d) Proposed new port.** No new port; new sealed case on the existing
  `OutputRefusal` ADT.

- **(e) ADT.** Above.

- **(f) Authority it must NOT introduce.** The store's retention
  authority is single (`OutputRetentionPort`); the M3 design MUST NOT
  propose a second place that decides "this range may be GC'd".

### B.7 The CRC / content-fingerprint shape is split: `Fingerprint` exists in the journal, no equivalent exists for output bytes

- **(a) Fabric needs** an authenticated verifier for "is this range the
  same range I had?" — the contract §8 idempotency law
  ("`(a,b,digestA)` repetido ⇒ `AlreadyCommitted`") is a content-keyed
  dedupe. SPEC-03 names the digest algorithm explicitly in the
  idempotency key. A consumer that cannot compute the digest from
  PK's own commitments cannot honour the law.

- **(b) PK has** `Fingerprint` (a value class wrapping the SHA-256 hex
  of an operation input, used by `OperationJournal.beginOperation(opId,
  attempt, fingerprint: String, ...)`). The journal has its own
  content-hash shape; the output plane has NO content-hash shape; the
  two are not the same authority.

- **(c) Missing shape.** A sibling `OutputDigest` (or `OutputFingerprint`)
  type at the published `:pipeline-output` surface, computed by the
  store at read time and carried by either the new
  `OutputReadDigestedResult` (§B.1) or the existing `OutputPage`. The
  same SHA-256 algorithm that `Fingerprint` already uses is the
  obvious baseline.

- **(d) Proposed new port.** Same port shape as §B.1.

- **(e) ADT.** Same as §B.1.

- **(f) Authority it must NOT introduce.** No new hash algorithm. SHA-256
  is already in PK (`Fingerprint.hex`); reusing it avoids the "two
  digest shapes on the contract surface" hazard.

### B.8 Fabric's M3 needs do not include leases, fencing, or schedulers in PK

This is the negative gap. The Block 3 plan says: *"No implementar ACK
remoto, scheduler de replicación, S3, gRPC o spools distribuidos dentro
del core de PipelineK."* The audit confirms the existing PK lease/fence
authority (`RunExecutionLease` + `FileBackedRunExecutionLeaseStore` +
`OperationJournal` + `OutputRecoveryPort`, in the non-published stores)
is already what PK uses for its run-publishing authority and is NOT
what Fabric's M3 will use for its replication scheduler. Fabric's lease
authority is its own concern. The audit therefore:

- **does NOT recommend** moving any lease / fence / scheduler into
  `:pipeline-output` or `:pipeline-runtime`;
- **does** note that any new `OutputPinPort.pin(stream, range, holder)`
  MUST consult the existing `RunExecutionLease.acquire` to confirm
  authority before it pins (the holder's run id and fencing token come
  from the existing pure decider);
- **does** note that any new `OutputRetentionPort.canPrune(intent)`
  MUST respect the existing `RunExecutionLease` decision: a writer that
  holds the lease may not be told "your pinned range is in the way",
  because that conflates lease authority with retention authority.

## C. Overlap — places where two ports claim the same authority over ranges / retention

Each overlap is named, the two surfaces are listed, and the audit's
recommendation is given. Overlap is not a defect by itself; an audit
that does not name it leaves a drift hazard behind.

### C.1 `OutputRetentionPort.prune(intent)` and the new `OutputRetentionPort.canPrune(intent)` (proposed §B.3)

- **Surfaces.** `prune(intent)` is the act; `canPrune(intent)` (proposed)
  is the consult-before-act.
- **Same authority.** "May this deletion proceed, given current pins?"
- **Recommendation.** Demarcate: `prune` performs the deletion and
  returns what was done; `canPrune` returns the authorization without
  performing it. A caller that wants to PRE-FLIGHT a release (the
  Fabric M3 replica flow) calls `canPrune` first; a caller that wants
  to ACT calls `prune` directly. The two methods do NOT share state —
  `canPrune`'s answer is a point-in-time observation; `prune`'s answer
  is a record of what happened.

### C.2 `RuntimeIntrospectionPort.inspect(runId)` and the new `OutputPinPort.pinsOf(stream, range?)`

- **Surfaces.** `RuntimeIntrospectionPort.inspect(runId)` reports the
  *terminality + lease + journal position + output tail state* of a
  run (`RuntimeObservation.Running.outputTails`); a future pin-aware
  variant of inspect would report the *pins*.
- **Same authority?** No. `RuntimeIntrospectionPort.inspect` answers
  "what does this run look like right now?"; `OutputPinPort.pinsOf`
  answers "who is holding bytes right now?". They are different
  questions with different shapes and different consumers.
- **Recommendation.** Demarcate explicitly: pins are a
  **range/resource** authority; introspection is a **run/lifecycle**
  authority. The M2 `RuntimeObservation` ADT does NOT need a
  `pins: List<OutputPin>` field — adding one would force every
  consumer to enumerate pins to answer "is this run alive?", which
  is the wrong shape.

### C.3 `EventTail.readAfter` and the new `OutputReadDigestedResult` (proposed §B.1)

- **Surfaces.** `EventTail.readAfter(run, cursor, limit)` advances over
  the **event** sequence; `OutputReadDigestedResult` advances over
  **byte** ranges.
- **Same authority?** No. Event cursor ≠ byte cursor (M1 design §2.3,
  `ADR-M1 §D3`); the two ports are independent.
- **Recommendation.** The new port does NOT extend `EventTail`. The
  digests are about byte ranges, not event records.

### C.4 `OutputFrameIndex.framesOfRun(runId, afterOrdinal, limit)` and `OutputReadPort.readRange(stream, from, to)`

- **Surfaces.** `framesOfRun` enumerates frames after a given ordinal;
  `readRange` returns the bytes for a given offset range.
- **Same authority?** No. Frames are metadata; bytes are bytes. The
  two are deliberately separate per ADR-M1 §D2 ("a frame that also held
  bytes would be the second copy [ADR-M1 §D2] gives to exactly one
  plane"). The contract test
  `SegmentOutputFollowerTest` already pins this separation.
- **Recommendation.** No consolidation. The two ports remain
  independent.

### C.5 `OperationJournal.listForRun(runId)` and the new `OutputPinPort.pinsOf(stream, range?)`

- **Surfaces.** Journal = operations of a run in execution order;
  pins = byte-range holds on streams of a run.
- **Same authority?** No.
- **Recommendation.** Keep them apart.

### C.6 `RuntimeRecoverPort.recover(runId)` and the existing `OutputRecoveryPort.recover()` (private)

- **Surfaces.** `RuntimeRecoverPort.recover(runId)` is the M2 public
  recover verb; `OutputRecoveryPort.recover()` is the non-published
  private write-side authority.
- **Same authority.** "Bring this run back to a known state without
  re-executing effects."
- **Recommendation.** Keep the split: `RuntimeRecoverPort.recover` is
  the public verb (M2); `OutputRecoveryPort.recover` is the internal
  primitive the M2 verb composes (M2 design §5.7). The M3 audit
  flags that **recover MUST NOT invalidate pinned ranges** (the
  contract §12 invariant): a recover that drops pinned bytes would
  violate I.5 below. The design phase MUST pin this in the recover
  port's KDoc, mirroring the M2 design §5.6 composition rule.

## D. Invariants the contract pins on these verbs

The contract §2 ("`Open`, `Sealed`, `Unavailable`, `RetentionGap`,
`Corrupt` se distinguen"), §8 (idempotency laws), and §12 (retention
under pin) pin six invariants on the ranges + retention surface. The
audit checks each one against the surveyed ports.

### I.1 — exact byte offset

- **What it asserts.** A read at `[a, b)` returns exactly the bytes that
  were committed at offset `a..b-1`, no more, no less, even across a
  stream seal and a recover cycle.
- **Verdict per port.** **PASS, with a typed refusal gap.** The
  contractual property is documented at `OutputReadPort.kt:38-43`:
  "`readRange(s, o, o + n)` must equal the first `n` bytes of
  `readRange(s, o, committed)`". The `OutputReadResult` envelope is
  closed: `Page(bytes, stream, from, next?, committedEnd)` with the
  page invariant at `OutputCursor.kt:128-141` ("the page may not run
  past what is committed"). A read past the committed extent surfaces
  as `OutputRefusal.OffsetBeyondCommitted(requested, committed)`. **The
  gap is that a recovered+re-sealed+pruned range returns
  `OffsetBeyondCommitted` today, conflating three named conditions
  (§B.2).** The contract §2 names `RetentionGap` as the distinct
  answer, and PK does not provide it. **I.1 holds for committed bytes;
  I.1 fails as a typed refusal on lost bytes.**

### I.2 — frame sequence stability

- **What it asserts.** The frame ordinal a client observed before a
  restart is the same ordinal the client sees after the restart; the
  ordinal is not re-assigned.
- **Verdict per port.** **PASS-by-design, with one nuance.** `OutputFrame.ordinal`
  is "`monotonic, strictly increasing, assigned at publication time`"
  (`OutputFrameIndex.kt:45-47`) and "`Ordinal is assigned here, inside
  this call, under the index's own ordering`"
  (`OutputFrameIndex.kt:133-136`). The resume-via-`afterOrdinal` of
  the M1 follow contract (`OutputFollowOptions.afterOrdinal` at
  `follow/OutputFollower.kt:153`) is the cross-restart continuation
  primitive; the index's `framesOfRun(runId, afterOrdinal, limit)` is
  the underlying read. **The nuance:** `recoverUnframedBytes()`
  (`OutputFrameIndex.kt:182-199`) assigns a LATER ordinal to a
  recovered frame than the ordinal the interrupted write would have
  had — but it does NOT re-assign any ordinal that was already
  committed. An ordinal the client observed before the crash (e.g.
  `41`) is unchanged after the crash; a newly-recovered frame for
  bytes that the writer committed but never framed gets a new ordinal
  (e.g. `52`) placed AFTER every ordinal observed before the crash.
  The M1 contract test `SegmentOutputFollowerTest` pins the invariant.
  **I.2 holds as stated: ordinals are monotonic, strictly increasing,
  and not re-assigned.**

### I.3 — concurrent observer independence

- **What it asserts.** Two observers on the same run can read
  independently; neither's cursor affects the other.
- **Verdict per port.** **PASS-by-design.** `OutputCursor` is a value
  class — a cursor is OWNED BY THE CONSUMER, not held by the store
  (`OutputCursor.kt:57-65`); `OutputReadPort` is pull-by-call, no
  per-connection state; `OutputFrameIndex` is read-shared (the index
  is process-local concurrency-safe per `SegmentFrameIndex`); the
  follow handle is single-observer by construction (`OutputFollower.kt:73-78`,
  "the handle is single-observer … Two consumers that want to observe
  the same run independently each call `OutputFollower.open` and each
  get their own handle"). The M1-D cross-JVM e2e test is what proves
  it. **I.3 holds.**

### I.4 — no destructive recover

- **What it asserts.** A recover that brings a run back to a known state
  does NOT overwrite or invalidate already-committed bytes.
- **Verdict per port.** **PARTIALLY VERIFIABLE.** `OutputRecoveryPort.recover()`
  is non-published and runs only inside the JVM that owns the writer
  (`OutputWritePorts.kt:166-176`); the public-facing recover verb is
  `RuntimeRecoverPort.recover(runId)` (M2 design §5), whose composition
  rule explicitly does NOT modify committed bytes — it closes gaps
  (`recoverUnframedBytes`) and seals terminal markers. The
  `OutputRecoveryReport.bytesUnbacked` field
  (`OutputWritePorts.kt:316`) is the store's own honest count of
  bytes it cannot serve; the M2 audit pins it as the **non-zero-stops-
  the-certification** condition (M2 audit §A.6: "the store is damaged
  and is NOT repaired automatically"). **The M3 audit adds: a recover
  on a run that has PINNED ranges MUST NOT touch those ranges.**
  `RuntimeRecoverPort.recover` is currently silent on pins; the M3
  design MUST pin this in the port's KDoc and a new
  `RecoverRefusal.PinnedBytesOutsideRecoveredRegion` (or analog)
  refusal case. **I.4 holds for committed bytes; I.4 fails for pinned
  bytes that are unbacked (today they would be released by
  `OutputRecoveryPort.recover()` silently).**

### I.5 — retention honours pin

- **What it asserts.** A range with an active pin survives any
  retention prune; a range without a pin may be pruned.
- **Verdict per port.** **CANNOT BE TESTED.** No public pin primitive
  exists today (§B.3); `OutputRetentionPort.prune(intent)` has no
  consult-before-prune API; `OutputPruneIntent` has no pin case. The
  audit flags this invariant as **UNVERIFIED** and recommends that
  the M3 implementation pin it with a contract test
  (`PinSurvivesPruneTest`): a pin on range `[a, b)` of stream `S` of
  run `R`; a `prune(RunReachedTerminalState(R))` call returns
  `PruneAuthorisation.RefusedPinned([pinId])`; the pin's bytes are
  still readable via `readRange(S, a, b)` after the refused prune.

### I.6 — retention loss surfaces as a typed refusal, not EOF

- **What it asserts.** If a pinned range is lost (e.g. disk failure),
  the consumer sees a typed refusal (`RetentionGap`, `RetentionLost`,
  `Corrupt`), not an empty page.
- **Verdict per port.** **CANNOT BE TESTED** as a typed refusal today.
  `OutputRefusal.StorageError(cause: String)` is the catch-all on the
  read path; `OutputRefusal.StreamLostRetention(stream, lastCommitted)`
  is the follow-side variant; the read-side `RetentionGap` /
  `Corrupt` / `Unavailable` cases the contract §2 names DO NOT EXIST
  (§B.2). A consumer that asked for `readRange(S, a, b)` and got
  bytes that were pruned would today see either `OffsetBeyondCommitted`
  (wrong — the cursor is fine, the bytes are gone) or
  `StorageError("...")` (wrong — `cause: String` is not a closed ADT).
  The audit flags I.6 as **UNVERIFIED** and recommends the M3 design
  ship the three sealed cases (`RetentionGap`, `Corrupt`, `Unavailable`)
  plus a contract test `LostRangeSurfacesAsTypedRefusalTest`.

### I.summary — invariant table

| Invariant | Verdict today | Why | Closed by |
|---|---|---|---|
| I.1 exact byte offset | PARTIAL — committed bytes PASS; lost bytes ref-conflate | `OutputReadPort.kt:38-43` + `OutputCursor.kt:128-141` PASS; `OutputRefusal.OffsetBeyondCommitted` is wrong answer for GC'd ranges | §B.2 `RetentionGap` case |
| I.2 frame sequence stability | PASS-by-design + tested | `OutputFrameIndex.kt:45-47,133-136`; `OutputFollower.kt:153`; `SegmentOutputFollowerTest` | nothing to close |
| I.3 concurrent observer independence | PASS-by-design + tested | `OutputCursor` is value class; `OutputFollower.kt:73-78`; M1-D cross-JVM e2e | nothing to close |
| I.4 no destructive recover | PARTIAL — committed bytes PASS; pinned bytes UNVERIFIED | `OutputWritePorts.kt:166-176`; M2 design §5.6; missing pin-exclusion refusal | §B.3 pin port + new `RecoverRefusal` case |
| I.5 retention honours pin | UNVERIFIED | no public pin primitive | §B.3 `OutputPinPort` + `canPrune(intent)` |
| I.6 retention loss as typed refusal | UNVERIFIED | `OutputRefusal` has no `RetentionGap`/`Corrupt`/`Unavailable` | §B.2 + §B.5 + §B.6 |

## E. Cross-walk to UAT / AAT / FITNESS / FABRIC evidence

The M3 producer-side UAT/AAT/FITNESS evidence lives in the consumer
`docs/fabric-coordinated-evolution/acceptance/UAT.md` / `AAT.md` /
`FITNESS.md` files (Fabric's repo) and in the producer's own
`:pipeline-output` / `:pipeline-runtime` test batteries.

### E.1 UAT cases that the existing PK ports already cover

| UAT ID | Verb today | Ports touched | M3 status |
|---|---|---|---|
| UAT-PK-M1-005 | UTF-8 split across pages | `OutputReadPort.readRange` + `RedactingOutputIngress` | PASS — but is a byte-cursor test, not a digest test; the contract §8 idempotency law is NOT covered |
| UAT-PK-M1-001 | end-to-end live tail | `OutputFollower` + `EventFollower` + M1-D cross-JVM | PASS — covers I.2/I.3 |
| UAT-PK-M1-004 | retention boundary during follow | `OutputFollower` + `OutputRefusal.StreamLostRetention` | PASS — but follow-side only; the read-side analogue is NOT covered |

### E.2 FABRIC UAT cases (Fabric-side) that depend on PK M3 ports

| UAT ID (Fabric) | What it proves | PK port it depends on | M3 status |
|---|---|---|---|
| UAT-RP-001 | two FactPages same executor no ACK false | idempotency key requires digest | GAP — depends on §B.1 |
| UAT-RP-002 | spool corruption visible | rejection law needs typed refusal | GAP — depends on §B.2 |
| UAT-RP-003 | EventStore increments during `sh` | event cursor continuity (M1 PASS) | PASS-by-design on M1 ports |
| UAT-RP-004 | ConsoleSource exposes byte range while running | `OutputReadPort.readRange` PASS-by-design | PASS — but no digest |
| UAT-RP-005 | netem red drop retains outbox | rejection during outbox is typed refusal | GAP — depends on §B.2 |
| UAT-RP-006 | lost ACK → `AlreadyCommitted` (no double append) | idempotency needs digest | GAP — depends on §B.1 |
| UAT-RP-007 | same range/distinct digest → `Conflict` | idempotency + digest comparison | GAP — depends on §B.1 |
| UAT-RP-008 | out-of-order `[b,c)` before `[a,b)` → `Gap` | range-coverage refusal | GAP — depends on §B.2 (Gap must be a refusal case, not a literal byte-count gap report) |
| UAT-RP-009 | fencing change under partition | `LeaseHeldByAnother` (M2) | PASS-by-design on M2 ports |
| UAT-RP-010 | high concurrency output + cancel | concurrent observer + cancel idempotency (M1 + M2) | PASS-by-design |

### E.3 FABRIC AAT cases (Fabric-side) that depend on PK M3 ports

| AAT ID | What it proves | PK port it depends on | M3 status |
|---|---|---|---|
| AAT-11 | two pages same sender | idempotency needs digest | GAP — §B.1 |
| AAT-13 | retry after lost ACK | idempotency under re-send | GAP — §B.1 |
| AAT-14 | reorder → gap no ACK jump | GAP refusal | GAP — §B.2 |
| AAT-15 | two ranges same key hash distinct → conflict + evidence | digest comparison + Conflict refusal | GAP — §B.1 + §B.2 |
| AAT-16 | net partition + local store active → typed saturation | `Unavailable` refusal | GAP — §B.2 |
| AAT-17 | control loop under flood → P0 progresses without starvation | bytes/second budget | M3 is silent on quotas (existing ports pass) |
| AAT-18 | revoked-epoch worker → no zombie commit | lease fencing + bytes held | GAP — depends on pin NOT being released by revoked-epoch holder (the M3 pin design MUST hold the pin even after the lease crosses) |

### E.4 FABRIC AAT/UAT integration tests

The two Fabric landing procedures
(`PipelinekFabric/docs/landing-m3-control-plane-runs.md` and
`landing-m3-local-mechanism.md`) make the publish-or-fail-closed
discipline law explicit: "the **middle** of M3 … the roadmap's vertical
is four arrows: control plane → local transport → worker → PipelineK";
"the law is read against the PK SDK (`PublishedContractLawTest`,
`publishedContractModules`)". This means the PK M3 audit and the
subsequent design MUST be published in `:pipeline-output`'s `sdk`
artifact (`pipeline-output/build.gradle.kts:54-66`) — the same module
where M1's `output.follow.v1` was published.

### E.5 FITNESS constraints the M3 work must respect

- **byte-read latency budget** under output saturation: a digest-
  computing read MUST NOT block the writer; the audit's M3 design
  proposal is additive (`readRangeDigested`) so the budget is the
  existing-budget + a SHA-256/miB ceiling (a single SHA-256 of a
  64 KiB page is sub-microsecond on any modern CPU; the design phase
  pins the exact cost).
- **out_of_order_advances = 0** — an M3 recover MUST NOT cause the
  store to publish a byte range that the journal has not heard of
  (the existing `OutputRecoveryReport.bytesUnbacked` is the
  counter; M3 design pins `recover(runtime, options)` honouring it).
- **double_side_effect_count = 0** — a pin/unpin cycle MUST NOT
  cause the underlying bytes to be re-written (pins are range holds,
  not writes).
- **pruning cooldown** — a prune that is `RefusedPinned` MUST be
  fail-closed; the consumer that wanted to prune must unpin and retry,
  not see a partial prune.
- **`controller_cpu_silent`** — the new `OutputPinPort` MUST NOT
  introduce a per-pin polling loop; the existing `ObservationWakeup`
  ADT may be composed, not duplicated.

## F. Decision

```
BLOCK 3 PK-CANDIDATE NEEDED.
```

Reasoning:

1. **The contract §2 first-sentence ADTs are not in the published
   `OutputRefusal` hierarchy.** §B.2 names three sealed cases
   (`RetentionGap`, `Corrupt`, `Unavailable`) that the contract names
   explicitly and the audit grep finds zero matches for.

2. **The contract §8 idempotency law cannot be honoured.** §B.1 names
   a digest-on-read primitive (`OutputReadDigestedResult` or an
   additive field on `OutputPage`) that PK does not expose. Without
   the digest, Fabric cannot tell `AlreadyCommitted` from `Conflict`.

3. **The contract §12 retention-under-pin invariant cannot be
   honoured.** §B.3 names a pin/canPrune pair (`OutputPinPort.pin,
   unpin, pinsOf` + `OutputRetentionPort.canPrune(intent)`) that PK
   does not expose. Without the pin, the contract's "no podar rangos
   hasta probar que su garantía activa se transfirió a una copia
   durable, y que no existe pin" is unmeetable.

4. **Three invariants are CANNOT-BE-TESTED today** (I.4 partially,
   I.5 entirely, I.6 entirely — §D). The M3 deliverable MUST pin
   them with contract tests named in §E.

5. **The decision is NOT a Fabric-only compatibility handoff.** Each
   of the three gaps adds a new public surface (a sealed case on
   `OutputRefusal`; a new sibling result type; a new port +
   consult-before-act). Per the release-receipt v2 model
   (`docs/pipelinek-release-evolution/shared/02-release-model-v2.md`),
   that surface is one PK candidate (the next pre-release after
   `v0.50.0-rc1`), the same shape M1 (`v0.49.0-rc1`) used to publish
   `output.follow.v1` / `events.follow.v1` and M2 (`v0.50.0-rc1`)
   used to publish `runtime.inspect.v1` / `runtime.cancel.v1` /
   `runtime.recover.v1`.

6. **No overlap requires PK-side consolidation before M3 ships.**
   §C's six overlaps are demarcated either by existing KDoc or by the
   discipline "additive case on the same closed ADT" the M1 design
   already established. The M3 ports sit ON TOP of the demarcation
   rather than re-doing it.

7. **Authority moves the audit explicitly forbids are NOT proposed.**
   §B.8 confirms the M3 work introduces no new lease, no new fencing
   scheme, no new scheduler. The pin store is a NEW authority
   (per-range retention hold) that is NOT a lease (per-run publishing
   authority); the audit explicitly disambiguates them in §B.3 (f)
   and §B.4 (f). The user's "No implementar ACK remoto, scheduler de
   replicación, S3, gRPC o spools distribuidos dentro del core de
   PipelineK" rule is preserved.

## G. Open issues for the orchestrator

None that block the audit. The following items are flagged for the
design phase that consumes this document; they are not audit failures:

1. **The M3 design must name the home module for `OutputPinPort`.**
   The audit recommends placing it in `:pipeline-output` (alongside
   `OutputRetentionPort`); the design may split it into a sibling
   module if segregation requires. The pin store's IMPLEMENTATION
   lives in `:pipeline-output-store` (mirroring
   `FileBackedRunExecutionLeaseStore`'s internal/non-published split).

2. **The M3 design must pin the `pin holder` identity.** The audit
   recommends `holder: String` (no typed identity at the public
   surface); the design may promote to a typed
   `PinHolder(value)` if the segregation is cleaner. The lease
   store already projects `RunOwnerId` as a `String` at the
   published API; mirroring that convention is the lower-risk
   choice.

3. **The M3 design must decide whether the new `RetentionGap` /
   `Corrupt` / `Unavailable` cases live on the existing `OutputRefusal`
   ADT or on a new sibling hierarchy.** The audit recommends
   additive cases on the existing closed ADT (the M1 design §3
   precedent). The design may split them into a sibling
   `OutputReadM3Refusal` if the segregation rule is in spirit; the
   audit's recommendation is "no, additive".

4. **The M3 design must name the digest algorithm and length.** The
   audit recommends SHA-256 (the existing `Fingerprint` algorithm);
   the design may promote to SHA-3-256 if the threat model
   requires it; the audit pins the choice of algorithm as a
   **configurable baseline**, NOT a contract surface, so a
   future hardened SHA-3-256 may ship without an ABI break.

5. **The M3 design must decide whether pins carry an `expiresAtMs`.**
   The audit's recommendation is YES, optional; the design may pin
   it as REQUIRED for every pin and let the caller pass `null` to
   mean "no expiry". The Fabric M3 spec does not currently require
   an expiry, but the audit pins one as a guard rail for
   forgotten pins that would otherwise grow unbounded.

6. **The M3 design must name the LIMIT on the number of pins per
   stream per run.** The audit recommends a default of `1024` per
   `(stream, runId)`; the design may lower the default to fit a
   specific Fitness budget (the contract §10 implies bounded
   resources). The audit pins the limit as a constant
   `OutputPinPort.DEFAULT_MAX_PINS_PER_STREAM` so the
   implementation can change WITHOUT breaking the contract.

7. **The M3 design must name the contract test IDs.** The audit
   uses `PinSurvivesPruneTest`,
   `LostRangeSurfacesAsTypedRefusalTest`, and a `DigestRoundTripTest`.
   The design may re-number the IDs to match the package's
   `UAT-PK-M3-###` convention (introduced by M1
   `UAT-PK-M1-001..006`; M2 introduced the M2-prefixed names but
   the UAT cross-walk kept `UAT-PK-M1-*` for the original six).

8. **The contract change required is a one-cell update to
   `INTERFACE_CONTRACT.md`'s "Capacidades publicadas" table to
   add rows once M3 ships.** The audit recommends the new
   capabilities be advertised as:
   - `output.read.digested.v1` (the byte read with digest);
   - `output.pin.v1` (the new retention-pin port);
   - `output.refusal.retention.v1` (the new closed-case extension);
   each in the same `Capabilities` companion per-module M1/M2 used.
   This is identical in shape to the M1 promotion and requires the
   same `CONTRACT_SHA256.txt` refresh and `release-receipt`
   evidence.

9. **The M3 work's "node" in the CRIC chain is the next candidate
   after `v0.50.0-rc1`.** The audit does not assign a version
   number; that is a release-harness decision per the release
   model v2 (`02-release-model-v2.md` §3): "main evolves → freeze
   candidate point → build target-versioned artifact V → …
   main continues". The harness chooses the train; the audit names
   what ships.
