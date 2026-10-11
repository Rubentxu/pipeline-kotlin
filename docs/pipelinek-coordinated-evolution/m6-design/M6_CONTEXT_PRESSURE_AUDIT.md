# CRIC-M6 — CONTEXT / PRESSURE audit (input to M6 design)

**Status:** read-only audit, completed before any M6 code is written.
**Worktree:** `pk-cric-m6` (branch `audit/cric-m6-context-pressure`) at `d04d295c`
(`audit/cric-m5-retention-pin` baseline; M5 surfaced `BLOCK 5 = COMPATIBILITY HANDOFF ONLY`).
**Anchor:** the Block 6 plan (*"Aportar contexto indexable de run/stage/step/operation y
lecturas eficientes con presupuestos explícitos. Preservar las identidades y autoridades
actuales. No implementar dentro de PK el Governor, el scheduler de fairness ni el motor de
consulta de Fabric. Tests de volumen: salida grande con lecturas pequeñas, filtros
combinados, lectores lentos, presión, memoria acotada y ausencia de cambios en outcomes.
Salida: candidata PK cuando cambien APIs; release Fabric M6 y gate cruzado"*) and the
normative text in `coordination/INTERFACE_CONTRACT.md` §1 (Identidades — runId,
operationId, attemptId, canal y stream conservan el mismo referente), §5 (Runtime
inspeccionable — segregated interfaces reusing the REAL public ports of PK), and
§10 (Límites — slow observers don't directly back-pressure execution; mandatory storage
exhaustion produces declared backpressure/failure policy, not silent drop).
**Precedent:** `m2-design/M2_INSPECT_RECOVER_CANCEL_AUDIT.md` (M2 — the closest
existing context surface); `m5-design/M5_RETENTION_PIN_AUDIT.md` (M5 — the most recent
"compatibility handoff only" precedent); `m3-design/M3_RANGE_RETENTION_AUDIT.md` (M3 —
the most recent read-path / budget discipline audit).

The M1 (`v0.49.0-rc1`), M2 (`v0.50.0-rc1`), and M3 (`v0.51.0-rc1`) surfaces are
published. The M2 `RuntimeIntrospectionPort.inspect(runId)` already exposes run-level
context (`Running`, `Terminal`, `LiveButEmpty`, `Unobservable`) and the M3
`OutputFollower` / `EventFollower` already carry explicit byte / record / poll budgets
with bounded page sizes. **This audit's job is to determine whether the M1+M2+M3 surface
already covers Fabric's M6 context/pressure needs — indexable context for run / stage /
step / operation, and explicit bounded reads with slow-reader isolation — or whether a
Block 6 PK candidate is needed.**

## 0. Scope and naming

The Block 6 plan names one required capability family ("contexto indexable de
run/stage/step/operation y lecturas eficientes con presupuestos explícitos"), three
negative-scope exclusions ("No implementar dentro de PK el Governor, el scheduler de
fairness ni el motor de consulta de Fabric"), six test scenarios ("salida grande con
lecturas pequeñas, filtros combinados, lectores lentos, presión, memoria acotada y
ausencia de cambios en outcomes"), and one output rule ("candidata PK cuando cambien
APIs"). The audit classifies every existing public port along six axes:

```text
IDENTITY PRESERVATION   (runId, operationId, attemptId, stage, step are stable
                        across the read path; a follow sees the same identity the
                        writer committed)
INDEXABLE CONTEXT       (a consumer can ask "what stages has this run?" / "what
                        steps has this stage?" / "what operations is this step
                        doing?" via a PUBLIC port, NOT via journal scanning)
BOUNDED READS           (every read has an explicit byte / count / time budget;
                        a consumer cannot ask for an unbounded page)
SLOW-READER ISOLATION   (a slow consumer does NOT back-pressure the writer; the
                        writer's throughput is independent of the consumer's pace)
MEMORY BOUNDED          (the largest page a consumer can request is bounded by
                        the caller's budget; the implementation does NOT allocate
                        above the budget)
NO OUTCOME DRIFT UNDER LOAD
                        (a consumer that reads at 1 byte/second sees the same
                        final outcome as a consumer that reads at 1 GB/second —
                        modulo retention, which surfaces as typed refusal)
```

Plus the negative scope the user pins ("No implementar dentro de PK el Governor, el
scheduler de fairness ni el motor de consulta de Fabric"): PK's authority stays at
"durable bytes + journal + lease + pin + bounded reads". The Governor, the fairness
scheduler, and the Fabric query engine are Fabric-side concerns. The audit does NOT
propose adding any of them to PK.

The audit answers, in order:

1. What public ports already exist that touch any of these axes (§A).
2. What gaps exist between what Fabric's M6 context/pressure needs and what PK exposes
   (§B).
3. Where two existing ports claim the same authority over context/budgets and need
   demarcation (§C).
4. Whether the six invariants the contract pins on these axes hold today, and which
   ones fail (§D).
5. How the audit cross-walks to the existing UAT-PK / AAT / FABRIC evidence (§E).
6. The single decision: PK-CANDIDATE NEEDED or COMPATIBILITY HANDOFF ONLY (§F).

All paths are absolute. The `d04d295c` baseline is the only commit consulted; M1, M2,
and M3 work is referenced for context only and is pinned by
`release/cric-m1-v0.49.0-rc1`, `release/cric-m2-v0.50.0-rc1`, and
`release/cric-m3-v0.51.0-rc1` respectively.

## A. Public ports that already touch IDENTITY, INDEXABLE CONTEXT, BOUNDED READS, SLOW-READER ISOLATION, MEMORY BOUNDED, or NO OUTCOME DRIFT

The survey covers the three published modules the user's brief names —
`:pipeline-events`, `:pipeline-output`, and `:pipeline-runtime` — and lists every
port whose behaviour a context/pressure consumer (Fabric's run-detail view, a slow
reader, an indexer that walks the journal) would compose. Two columns matter:

- **Published?** A module with a `maven-publish` block in its `build.gradle.kts` is on
  the ABI. Per `INTERFACE_CONTRACT.md` §6 ("el consumidor compila contra el ABI
  publicado, nunca contra el árbol de fuentes o `mavenLocal`"), the published modules
  are the only ones whose ADTs the M6 consumer compiles against.
- **Axis.** One of {Identity, Indexable Context, Bounded Read, Slow-Reader Isolation,
  Memory Bounded, No Outcome Drift} or a closed subset.

### A.1 `:pipeline-events` (published module `pipeline-events`, M1 surface)

The event plane carries the typed `DomainEvent` sequence for a run. It is the
**secondary** substrate for M6 — it carries run-level terminality (`RunStarted`,
`RunFinished`) and the typed event traffic a consumer reconciles byte ranges and
introspection against, but it has nothing to say about hierarchical run structure
(stage / step / operation boundaries are not typed at the event level; they are
embedded in `DomainEvent.kind` strings and `subject` ResourceRefs, and the only way
to enumerate them is to scan events with `EventQuery`).

```
EventHistory (interface)
  | v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/identity/EventHistoryPorts.kt:151-153  | Identity, Inspect
  | history(run: ResourceRef, query: EventQuery): Sequence<PipelineEventEnvelope>.
  | Query at lines 59-95: All | ByKind | BySource | BySubject | BySequenceRange.
  | Sequence is unbounded (no `limit` parameter); the audit classifies this as the
  | "open scan" shape, NOT a bounded read.

EventTail (interface)
  | v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/identity/EventHistoryPorts.kt:159-161  | Identity, Bounded Read
  | readAfter(run: ResourceRef, cursor: EventCursor?, limit: Int): EventPage.
  | Budget: `limit: Int` (bounded page size; init {} in EventPage does not enforce,
  | but `readAfter` itself is contractually bounded). `EventPage.envelopes` carries
  | the typed envelopes plus `nextCursor: EventCursor?` and `hasMore: Boolean`
  | (EventHistoryPorts.kt:102-137). Refusal rows are surfaced inside the page
  | (`refusals: List<EventRecordRead.Undecodable>`, EventHistoryPorts.kt:136).

EventCursor (data class)
  | v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/identity/EventHistoryPorts.kt:22-53   | Identity
  | (runId: String, lastSequence: Long); wire `evt-cursor-v1:` with percent-escaping
  | of runId. Distinct type from `OutputCursor` (deliberately — see WHY block at
  | lines 33-53). runId is the durable identity across reconnects.

EventQuery (sealed class)
  | v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/identity/EventHistoryPorts.kt:59-95    | Identity, Bounded Read
  | All | ByKind | BySource | BySubject | BySequenceRange. Closed typed filters
  | only — no query language, no arbitrary predicates. `matches(envelope)` at line 88
  | is the pure predicate a consumer can evaluate against a fetched page.

EventPage (data class)
  | v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/identity/EventHistoryPorts.kt:102-137  | Bounded Read, No Outcome Drift
  | (envelopes, nextCursor, hasMore, refusals). Bounded by `envelopes.size <= limit`.

EventRecordReadPort (interface)
  | v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/identity/EventRecordReadPort.kt:45-69  | Identity, Bounded Read
  | readRecords(runId: String, after: EventCursor?, query: EventQuery, limit: Int):
  | EventRecordReadResult. Budget: `limit: Int`. Returns typed DomainEvent slices, NOT
  | wire envelopes. EventRecordReadResult is sealed at lines 72-83 (Page | Refused).

EventRecordReadRefusal (sealed interface)
  | v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/identity/EventRecordReadPort.kt:91-109 | Bounded Read, Slow-Reader Isolation
  | UnknownRun | CursorBeyondTail | StorageError. Closed ADT; consumer MUST NOT retry
  | blindly on a Refused.

EventFollower (interface) + EventFollowHandle (interface)
  | v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/follow/EventFollower.kt:49-118        | Slow-Reader Isolation, Bounded Read, No Outcome Drift
  | open(runId: String, options: EventFollowOptions): EventFollowHandle.
  | Handle is AutoCloseable; iterator is single-pass; single-observer (lines 67-72);
  | the poll cadence (`options.pollIntervalMs`) is the ONLY coupling to the writer.
  | Two consumers that want to observe the same run independently call `open` again
  | (EventFollower.kt:69-72). Resumption across a restart is via
  | `options.after: EventCursor?` (EventFollower.kt:138).

EventFollowOptions (data class)
  | v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/follow/EventFollower.kt:124-152         | Bounded Read, Memory Bounded
  | query, pollIntervalMs: Long = 25L, maxRecords: Int = 256, until, after, lagReportInterval.
  | Budgets: `maxRecords` (page size cap, init {} at line 148 enforces positivity),
  | `pollIntervalMs` (writer coupling, line 147 enforces non-negativity),
  | `lagReportInterval: Duration = Duration.ofSeconds(1)` (REPORTING ONLY — does NOT
  | refuse on lag; line 144 KDoc pins "a slow consumer is not a defect").
  | Memory bounded by construction: each `EventFollowEvent.Page.slice.decoded` is
  | bounded by `maxRecords`, and `hasMore` / `nextCursor` is the resume path.

EventFollowUntil (sealed interface)
  | v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/follow/EventFollower.kt:158-166         | No Outcome Drift
  | Unbounded | UntilRunFinished | UntilSequence. A consumer that wants to read
  | "until the run is terminal" expresses it as a typed `EventFollowUntil`, NOT as a
  | wall-clock timeout. UntilSequence.init { } enforces non-negativity.

EventFollowState (sealed interface) + EventFollowRefusal (sealed interface)
  | v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/follow/EventFollower.kt:178-210         | No Outcome Drift, Slow-Reader Isolation
  | EventFollowState: Live(lastSequence) | RunFinished | Unobservable(refusal).
  | EventFollowRefusal: UnknownRun | RetentionLost | Cancelled.
  | Note: NO `LagExceeded` case (line 202-204 KDoc pins "a slow consumer is not a
  | defect"). Retention is typed, not hidden.

EventFollowEvent (sealed interface)
  | v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/follow/EventFollower.kt:218-223         | No Outcome Drift
  | Page(slice, newState) | StateChanged(state) | Refused(refusal) | Completed.
  | Terminal events are `Completed` (the follow reached its `EventFollowUntil`) or
  | `Refused` (the follow could not continue for the reason named in the event).
```

**Verdict.** The event plane has a complete **bounded paged-read + bounded pull-style
follow** vocabulary for M1-A / M1-C. It is **identity-preserving** (runId is in the
cursor, the page, the query) and **slow-reader-isolated** (poll cadence is the only
coupling; `LagExceeded` is deliberately absent; refusal on retention is typed). It
does NOT expose **indexable context for hierarchical run structure**: a consumer that
wants "what stages does this run have / what steps does this stage have / what
operations is this step doing" must scan events with `EventQuery.ByKind` and parse
the resulting envelopes. There is no typed `StageSummary`, `StepSummary`, or
`OperationSummary` in the published event plane.

### A.2 `:pipeline-output` (published module `pipeline-output`, M1 + M3 surface)

The output plane is the **byte substrate** for M6. The M1 audit + M3 audit covered
ranges, retention, and digests; the M6 audit focuses on the four primitive families
that compose a context/pressure read path: `OutputReadPort` (bounded byte reads),
`OutputFrameIndex` (frame ordinals + per-run streams), `OutputTailPort` (terminality),
and the M1 follow contract.

```
OutputReadPort (interface)
  | v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputReadPort.kt:19-87                 | Bounded Read, Memory Bounded
  | committedExtent(stream: OutputStreamId): Long?
  | read(stream: OutputStreamId, cursor: OutputCursor, maxBytes: Int): OutputReadResult.
  | readRange(stream: OutputStreamId, from: Long, to: Long): OutputReadResult.
  | readRangeDigested(stream: OutputStreamId, from: Long, to: Long):
  |   OutputReadDigestedResult (M3 ADDITIVE, lines 70-86).
  | Budgets: `maxBytes` (line 32-33 KDoc: "upper bound on the returned page"), explicit
  | `from`/`to` half-open range (line 38-43 KDoc: "readRange(s, o, o+n) must equal
  | the first n bytes of readRange(s, o, committed)"). Default implementation of
  | `readRangeDigested` composes `readRange` + `OutputDigest.sha256Of` (line 75-85);
  | concrete adapters in `:pipeline-output-store` override for single-pass I/O.

OutputReadResult (sealed interface)
  | v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputRefusal.kt:210-213                | Bounded Read
  | Page(page) | Refused(reason). Closed envelope; consumer MUST handle both cases.

OutputReadDigestedResult (sealed interface)
  | v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputReadDigestedResult.kt:14-32     | Bounded Read, Memory Bounded, No Outcome Drift
  | M3 NEW. Digested(page, digest: OutputDigest) | Refused(reason: OutputRefusal).
  | Closed envelope; refusal uses the SAME `OutputRefusal` ADT (no parallel hierarchy).

OutputRefusal (sealed interface)
  | v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputRefusal.kt:14-207                | Bounded Read, No Outcome Drift
  | M1: ForeignStream | UnknownStream | OffsetBeyondCommitted | InvalidRange |
  |   RecoveryNotCompleted | DanglingCommit | StreamLostRetention | FollowCancelled |
  |   StorageError.
  | M3 ADDITIVE: RetentionGap (lines 125-129) | Corrupt (lines 153-164) | Unavailable
  |   (line 176) | RangeLostRetention (lines 197-201).
  | All cases carry the typed identity (stream, requestedRange, lastCommitted, etc.)
  | a consumer needs to make a retry decision; NONE collapses to a free-text reason.
  | KDoc lines 100-201 pins every M3 case's distinction from its M1 analogue.
  | Companion: CORRUPT_REASON_MAX_LEN = 256 (line 205) — bounded diagnostic.

OutputDigest (value class)
  | v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputDigest.kt:30-65                   | No Outcome Drift
  | M3 NEW. @JvmInline value class OutputDigest(val hex: String) — 64-char lowercase
  | SHA-256 hex; init { } validates length + charset (lines 33-35). Same algorithm as
  | the journal's `Fingerprint` (OperationJournal.beginOperation at
  | v2/pipeline-events-store/.../events/durable/OperationJournal.kt:117-123).
  | sha256Of(bytes) is the compute helper; EMPTY is the all-zero placeholder.

OutputCursor (data class)
  | v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputCursor.kt:57-105                  | Identity
  | (stream: OutputStreamId, committedOffset: Long); wire `out-cursor-v1:` with
  | percent-escaping of runId (mirrors EventCursor). Distinct from EventCursor by
  | construction — bytes and events are different orders (lines 33-53 WHY block).

OutputStreamId (value class)
  | v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputCursor.kt:24-31                  | Identity
  | Stable identity of one output stream; carries the (runId, operationId, channel)
  | shape via `OutputStreamAddress.parse`.

OutputPage (data class)
  | v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputCursor.kt:121-157                | Bounded Read, Memory Bounded
  | (bytes, stream, from, next?, committedEnd). Bounded by construction: `bytes.size`
  | is whatever the store returned, but `next` is set to the post-read cursor and the
  | consumer can iterate bounded pages.

OutputFrameIndex (interface)
  | v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputFrameIndex.kt:107-200            | Indexable Context, Identity
  | declareStream | append | framesOfRun(runId: String, afterOrdinal: Long, limit: Int):
  |   List<OutputFrame> | lastOrdinal(runId: String): Long? | streamsOfRun(runId: String):
  |   List<OutputStreamId> | recoverUnframedBytes.
  | **Indexable context (per-run).** `streamsOfRun(runId)` returns every stream
  | DECLARED for runId, whether or not it has a frame yet (line 153-179 KDoc);
  | `lastOrdinal(runId)` returns the highest ordinal in this index for runId, or null
  | when empty (line 150-151). `framesOfRun(runId, afterOrdinal, limit)` returns
  | bounded pages of frames for runId. **Run-level scope** — there is NO
  | stage/step/operation enumeration here.
  | Budgets: `limit: Int` (line 143-148 KDoc); `afterOrdinal: Long` is the resume path.

OutputFrame (data class)
  | v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputFrameIndex.kt:50-65               | Identity
  | (ordinal: Long, stream: OutputStreamId, channel: OutputChannel, from: Long, to: Long).
  | ordinal is the observation order, NOT causal order, NOT byte order, NOT comparable
  | with event sequence (lines 17-33 KDoc). `length: Long get() = to - from` (line 64).

OutputTailState (sealed interface)
  | v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputTailState.kt:42-69               | No Outcome Drift
  | Open(committedEnd) | Sealed(finalEnd); null for unknown. The terminality of a
  | stream — NOT the outcome of a run. KDoc lines 57-66: "Putting an outcome here
  | would make the Output Plane a second authority over execution results".

OutputTailPort (interface)
  | v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputTailState.kt:86-96               | Identity
  | tailState(stream: OutputStreamId): OutputTailState?. Returns Open, Sealed, or null.

OutputChannel (enum)
  | v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputChannel.kt:23-41                  | Identity
  | STDOUT("stdout") | STDERR("stderr"). Closed set of two.

OutputStreamAddress (data class)
  | v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputChannel.kt:109-148               | Identity
  | (runId, operationId, channel). Stream id = "{runId}/{operationId}/{token}". parse
  | returns null for non-shape streams; identity is OPAQUE.

OperationOutputStreams (data class)
  | v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputChannel.kt:159-184               | Identity, Indexable Context
  | stdout+stderr pair of one operation; select(channels), all(). This is the
  | closest the published output plane comes to a per-operation view: given an
  | operationId, the consumer can derive the two streams.

OutputRetentionPort (interface)
  | v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputRetention.kt:149-193             | Bounded Read, No Outcome Drift
  | hasOutputFor(runId: String): Boolean; prune(intent: OutputPruneIntent):
  |   OutputPruneReport; canPrune(intent: OutputPruneIntent): PruneAuthorisation.
  | **Bounded read of the consult** (line 183-184): `canPrune` is the consult-before-act
  | primitive; `DEFAULT_CAN_PRUNE_TIMEOUT_MS = 5_000L` (line 191) bounds the call's
  | wall time. Companion pin: see M5 audit §A.1.

OutputPinPort (interface)
  | v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputPinPort.kt:37-89                 | Bounded Read, No Outcome Drift
  | pin(stream, range, holder, reason, expiresAtMs?): OutputPinResult
  | release(pinId: OutputPinId): PinReleaseOutcome
  | pinsOf(stream: OutputStreamId, range: LongRange? = null): List<OutputPin>
  | isPinned(stream: OutputStreamId, offset: Long): Boolean
  | **Bounded.** `pinsOf` returns the active pins for `stream`, optionally restricted to
  | `range` (line 65-68); the list is ordered by `pinId.value` lexicographic (line 65
  | KDoc). `DEFAULT_MAX_PINS_PER_STREAM = 1024` (line 87) caps the per-stream pin count;
  | refusing on the cap is the bounded-refusal discipline.
  | **No Outcome Drift.** `isPinned(stream, offset): Boolean` is O(1) consult; the
  | implementation MAY index by stream + offset (line 76-77 KDoc).
  | Companion: OutputPinId is a UUID-v4 @JvmInline value class (OutputPin.kt:14-72);
  | OutputPin carries (pinId, stream, range, holder, reason, createdAtMs, expiresAtMs?).

OutputFollower (interface) + OutputFollowHandle (interface)
  | v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/follow/OutputFollower.kt:56-123         | Slow-Reader Isolation, Bounded Read, Memory Bounded, No Outcome Drift
  | open(runId: String, options: OutputFollowOptions): OutputFollowHandle.
  | Handle is AutoCloseable; iterator is single-pass; single-observer (lines 73-79);
  | the poll cadence (`options.pollIntervalMs`) is the ONLY coupling to the writer.
  | Two consumers that want to observe the same run independently call `open` again.
  | Resumption across a restart is via `options.afterOrdinal: Long?` (line 153).

OutputFollowOptions (data class)
  | v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/follow/OutputFollower.kt:131-163         | Bounded Read, Memory Bounded
  | streams: List<OutputStreamAddress> = emptyList(),
  | pageMaxBytes: Int = 64 * 1024,
  | pollIntervalMs: Long = 25L,
  | includeFrames: Boolean = true,
  | maxRecords: Int = 256,
  | until: FollowUntil = FollowUntil.Unbounded,
  | afterOrdinal: Long? = null.
  | Budgets (init {} at lines 156-162 enforces positivity / non-negativity):
  |   `pageMaxBytes` — single-page byte cap (line 140; mirrors
  |   `OutputStreamHandle.DEFAULT_APPEND_WINDOW = 64 KiB`).
  |   `maxRecords` — single-poll cycle record cap (line 143; mirrors
  |   `ObservationReplayLimit.DEFAULT = 256`).
  |   `pollIntervalMs` — writer coupling (line 141; mirrors
  |   `FOLLOW_IDLE_MILLIS = 25L`).
  |   `afterOrdinal` — resume path (line 153).
  | **No `LagExceeded` parameter** — same discipline as the event follower.

FollowUntil (sealed interface)
  | v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/follow/OutputFollower.kt:171-179         | No Outcome Drift
  | Unbounded | UntilAllSealed(runId) | UntilBytesRead(limit). A consumer that wants
  | to read "until this run's streams are all sealed" expresses it as a typed
  | `UntilAllSealed`, NOT as a wall-clock timeout. UntilBytesRead.init {} enforces
  | non-negativity.

FollowState (sealed interface)
  | v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/follow/OutputFollower.kt:198-209         | No Outcome Drift
  | Running(openStreams, sealedStreams) | StreamSealed(sealedStreams) | RunTerminal
  | | Unobservable(refusal). Three values deliberately distinct (KDoc lines 191-196):
  | "the Output Plane alone does NOT authoritatively know the run is over". The
  | event plane's `RunFinished` observation transitions the follow from Running to
  | RunTerminal.

OutputFollowEvent (sealed interface)
  | v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/follow/OutputFollower.kt:217-222         | No Outcome Drift
  | Bytes(page, newState) | StateChanged(state) | Refused(refusal) | Completed.
  | Terminal events: `Completed` (the follow reached its `FollowUntil`) or `Refused`
  | (the follow could not continue for the reason named in the event).
```

**Verdict.** The output plane has a complete **bounded byte-read + bounded pull-style
follow + bounded pin consult + typed retention refusal** vocabulary for M1 + M3. It is
**identity-preserving** (stream id, cursor, frame ordinal, pin id are all typed),
**slow-reader-isolated** (poll cadence is the only coupling; no `LagExceeded`; no
backpressure on the writer), **memory-bounded** (`pageMaxBytes`, `maxRecords`,
`DEFAULT_MAX_PINS_PER_STREAM`), and **no-outcome-drift** (typed `OutputRefusal.{RetentionGap,
Corrupt, Unavailable, RangeLostRetention}` for every failure mode; cursor + digest for
content; `FollowUntil` for terminality). It does NOT expose **indexable context for
hierarchical run structure** beyond `streamsOfRun(runId)` (per-run stream list) and
`OperationOutputStreams.select(channels)` (per-operation stream pair). The published
plane does NOT expose a list of stages, a list of steps, or a list of operations for a
run. A Fabric that wants a run-detail view ("this run has N stages, each stage has M
steps, each step has K operations") must compose `RuntimeIntrospectionPort.inspect`
+ `EventRecordReadPort.readRecords` and parse events — there is no typed enumeration.

### A.3 `:pipeline-runtime` (published module `pipeline-runtime`, M2 surface)

The runtime plane is the **cross-plane composition** for run-level verbs. M2
introduced three verbs (`inspect`, `recover`, `cancel`) and the closed ADTs that go
with them. The M6 audit focuses on the parts that touch context lookup and bounded
reads.

```
RuntimeIntrospectionPort (fun interface)
  | v2/pipeline-runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/runtime/inspect/RuntimeIntrospectionPort.kt:40-66  | Identity, Indexable Context, Bounded Read
  | inspect(runId: String): RuntimeIntrospectionResult.
  | **Read-only by construction** (lines 18-22 KDoc); composition rule (M2 design §3.4)
  | limits it to SELECTs, tail-state queries, and `load`.
  | **Budget.** `DEFAULT_INSPECT_TIMEOUT_MS = 5_000L` (line 64) bounds the call's
  | wall time. Implementation MUST honour it.

RuntimeIntrospectionResult (sealed interface)
  | v2/pipeline-runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/runtime/inspect/RuntimeIntrospectionResult.kt  | Indexable Context
  | Observation(obs: RuntimeObservation) | Refused(refusal: IntrospectionRefusal).
  | Closed envelope.

RuntimeObservation (sealed interface)
  | v2/pipeline-runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/runtime/inspect/RuntimeObservation.kt:23-77       | Indexable Context, Identity
  | Running(attempt: AttemptId, leaseHolder: LeaseHolder?, fencingToken: FencingToken,
  |   journalPosition: JournalPosition, outputTails: List<OutputTailView>, process: ProcessRef?)
  | Terminal(attempt: AttemptId, terminal: TerminalObservation, terminalAtMs: Long)
  | LiveButEmpty(attempt: AttemptId, reason: String)
  | Unobservable(reason: IntrospectionFailure).
  | **Indexable context (per-run, partial).**
  |   - `Running.journalPosition: JournalPosition(operations: Int, latestOpId: String?,
  |     latestTerminalAtMs: Long?)` (line 117-121) — count + last op, NOT a LIST of
  |     operations.
  |   - `Running.outputTails: List<OutputTailView>` (line 130-133) — per-run tail
  |     views; `OutputTailView(stream, state: TailState, lastOrdinal: Long?)`
  |     (line 129-133).
  |   - `Running.process: ProcessRef? = null` (line 40) — cross-process introspection
  |     always returns null; co-located introspection MAY carry a PID.
  |   - `Terminal.terminal: TerminalObservation` (line 49) — typed terminal
  |     (Succeeded/Failed/Unstable/Aborted/Cancelled/Other) at lines 155-163.
  |   - **No stage / step / operation enumeration.** The M2 closed ADT exposes run-
  |     level + per-run-tail; it does NOT list the stages of the run, the steps of a
  |     stage, or the operations of a step.

JournalPosition (data class)
  | v2/pipeline-runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/runtime/inspect/RuntimeObservation.kt:117-121  | Indexable Context, Identity
  | (operations: Int, latestOpId: String?, latestTerminalAtMs: Long?).
  | Monotonically non-decreasing across two consecutive `inspect` calls. A fresh
  | run has neither a journaled terminal nor any op; both fields are nullable.

OutputTailView (data class)
  | v2/pipeline-runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/runtime/inspect/RuntimeObservation.kt:129-133  | Indexable Context, Identity
  | (stream: OutputStreamId, state: TailState, lastOrdinal: Long?).
  | Structural mirror of the published `OutputTailState`; the dedicated type here
  | keeps the introspection port's projection in `:pipeline-runtime`.

TerminalObservation (sealed interface)
  | v2/pipeline-runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/runtime/inspect/RuntimeObservation.kt:155-163  | Indexable Context
  | Succeeded | Failed(failureKind, terminalAtMs) | Unstable | Aborted | Cancelled
  | | Other(rawOutcome, terminalAtMs).

IntrospectionFailure (sealed interface)
  | v2/pipeline-runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/runtime/inspect/RuntimeObservation.kt:174-185  | Bounded Read
  | NoControlRoot | NoEventStore | NoOutputPlane | NoJournal | StorageError(cause).
  | Bounded diagnostic; `cause` is short.

IntrospectionRefusal (sealed interface)
  | v2/pipeline-runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/runtime/inspect/IntrospectionRefusal.kt:15-49     | Bounded Read
  | UnknownRun | NoControlRoot | NoEventStore | NoOutputPlane | LeaseHeldByAnother
  | | StorageError | InconsistentLeaseState. Bounded refusal.

RuntimeRecoverPort (interface)
  | v2/pipeline-runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/runtime/recover/RuntimeRecoverPort.kt:57-87    | Bounded Read
  | recover(runId: String, options: RecoverOptions): RecoverOutcome.
  | **Budget.** `DEFAULT_RECOVER_DEADLINE_MS = 2_000L` (line 85) bounds the call's
  | wall time; the recover port MUST return `RecoverOutcome.ReattachPending` before
  | this bound expires.
  | **No Outcome Drift.** Idempotent (KDoc lines 22-27): a second call returns
  | `RecoverOutcome.AlreadyRecovered` without re-running the substrate observer or
  | invoking any step's `execute` again.

RecoverOutcome (sealed interface)
  | v2/pipeline-runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/runtime/recover/RecoverOutcome.kt:14-57        | Bounded Read, No Outcome Drift
  | RecoveredTerminal | ReattachPending(deadlineMs: Long) | FailClosed(reason:
  |   RecoverRefusal) | AlreadyRecovered. The deadlineMs parameter is the bounded
  | wall-time projection.

RecoverRefusal (sealed interface)
  | v2/pipeline-runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/runtime/recover/RecoverRefusal.kt:20-85       | Bounded Read
  | UnknownRun | NotRecoverable | SubstrateUnavailable | JournalIncompatible |
  | LeaseHeldByAnother | StorageError | **M3 PinnedBytesOutsideRecoveredRegion**
  |   (lines 80-84). Bounded diagnostic; `cause` is short.

RuntimeControlPort (fun interface)
  | v2/pipeline-runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/runtime/control/RuntimeControlPort.kt:24-62    | Bounded Read, No Outcome Drift
  | cancel(runId: String, reason: CancelReason): CancelOutcome.
  | **No Outcome Drift.** Idempotent: a second call returns `AlreadyCancelled`
  | rather than re-firing.

CancelOutcome (sealed interface) + CancelRefusal (sealed interface)
  | v2/pipeline-runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/runtime/control/CancelOutcome.kt:14-42
  | v2/pipeline-runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/runtime/control/CancelRefusal.kt:15-53        | Bounded Read, No Outcome Drift
  | CancelOutcome: Cancelled | AlreadyCancelled | Refused(reason).
  | CancelRefusal: UnknownRun | RunTerminal(outcome) | LeaseHeldByAnother |
  | IncompatibleRunState | JournalUnavailable | StorageError. Bounded refusal;
  | no Outcome Drift (the same fiscal already-cancelled or already-terminal returns
  | the typed answer).

RuntimeRecoverDecision (object)
  | v2/pipeline-runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/runtime/recover/RuntimeRecoverDecision.kt      | Identity
  | decideRecovery(observation: RuntimeObservation, journal: JournalProof):
  |   RecoveryChoice. PURE decider; no effect.

Capabilities (object)
  | v2/pipeline-runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/runtime/Capabilities.kt                        | Identity
  | RUNTIME_INSPECT_V1 = Capability("runtime.inspect.v1") (EXPERIMENTAL on v0.50.0-rc1)
  | RUNTIME_CANCEL_V1  = Capability("runtime.cancel.v1")  (EXPERIMENTAL on v0.50.0-rc1)
  | RUNTIME_RECOVER_V1 = Capability("runtime.recover.v1") (EXPERIMENTAL on v0.50.0-rc1)
  | Plus M3 additions (M2 + M3): OUTPUT_READ_DIGESTED_V1, OUTPUT_PIN_V1,
  |   OUTPUT_REFUSAL_RETENTION_V1, registered in the same `Capabilities` companion
  |   per-module (per the contract §"Capacidades publicadas (CRIC-M3)" table).
```

**Verdict.** The runtime plane has a complete **typed run-level view** +
**idempotent recover** + **idempotent cancel** + **typed refusal ADTs** vocabulary
for M2 + M3. It is **identity-preserving** (`runId` is in every port's signature,
`AttemptId`, `LeaseHolder`, `FencingToken`, `OutputStreamId`, `TerminalObservation`
are all typed), **bounded** (`DEFAULT_INSPECT_TIMEOUT_MS = 5_000L`,
`DEFAULT_RECOVER_DEADLINE_MS = 2_000L`), and **no-outcome-drift** (`AlreadyRecovered`,
`AlreadyCancelled`, `RuntimeObservation.Terminal` are the typed no-drift answers). The
**indexable context gap is explicit**: the M2 `RuntimeObservation.Running` exposes
`journalPosition: JournalPosition(operations, latestOpId?, latestTerminalAtMs?)` —
a count + a single latest opId — but does NOT enumerate the operations, the stages,
or the steps. A Fabric that wants "what is in this run?" has no typed enumeration
on this side of the plane.

### A.4 The store authorities that back M1 + M2 + M3 (non-published)

The store modules hold the durable authorities the published modules read through.
The audit lists them because every existing **context lookup or read-side authority**
lives here, and the question "can a consumer reach these authorities through the
published ABI?" has a definite answer at the end of this section.

```
OperationJournal (interface)
  | v2/pipeline-events-store/src/main/kotlin/dev/rubentxu/pipeline/v2/events/durable/OperationJournal.kt:28-…  | Identity, Indexable Context, Bounded Read
  | append | get(opId) | get(opId, attempt) | listForRun(runId): List<DurableOperation>
  |   | getDeadlineMs | getEndedAt | getStartedAt | beginOperation.
  | **Indexable context (per-run, per-op).**
  |   - `listForRun(runId): List<DurableOperation>` (line 67-71) — returns the
  |     journal rows in execution order. The journal IS the indexable context for
  |     operations: every durable operation of a run is here.
  |   - `get(opId)` and `get(opId, attempt)` (lines 51-65) — per-op / per-op-and-
  |     attempt lookups; indexable by opId.
  |   - `getDeadlineMs`, `getEndedAt`, `getStartedAt` (lines 73-89) — per-op-attempt
  |     timestamps; indexable by opId + attempt.
  | **NOT published** (M2 audit §A.6, M3 audit §A.5). A consumer that wants any of
  | these must reach into the JVM that owns the run.

ReplayCursorStore (interface)
  | v2/pipeline-events-store/src/main/kotlin/dev/rubentxu/pipeline/v2/events/durable/ReplayCursorStore.kt:16-64  | (Identity)
  | load(runId) | advance(runId, opId, stageIndex) | advancePastParallelFrame.
  | NOT published. The cursor is "where to resume" — not a stage/step/operation
  | enumeration authority.

RunExecutionLease (pure decider) + FileBackedRunExecutionLeaseStore (class)
  | v2/pipeline-events-store/src/main/kotlin/dev/rubentxu/pipeline/v2/events/durable/RunExecutionLease.kt:216-309
  | v2/pipeline-events-store/src/main/kotlin/dev/rubentxu/pipeline/v2/events/durable/FileBackedRunExecutionLeaseStore.kt:40-298
  | acquire / authorisePublish / release / observe / isKnown / authorise.
  | NOT published. The lease is "who may write" — not a stage/step/operation
  | enumeration authority.

OutputAppendPort (interface) + OutputStreamHandle + OutputReservation
  | v2/pipeline-output-store/src/main/kotlin/dev/rubentxu/pipeline/v2/output/store/OutputWritePorts.kt:44-145   | (write-side)
  | reserve / appendFrom / write / copyFrom / commit / abandon.
  | NOT published.

OutputRecoveryPort (interface)
  | v2/pipeline-output-store/src/main/kotlin/dev/rubentxu/pipeline/v2/output/store/OutputWritePorts.kt:166-176  | (Recover, M1)
  | recover(): OutputRecoveryReport — O3 entry point; idempotent; destructive-
  | by-design; gated to the writer's JVM.
  | NOT published.

OutputSealPort (interface) + SealOutcome (sealed)
  | v2/pipeline-output-store/src/main/kotlin/dev/rubentxu/pipeline/v2/output/store/OutputWritePorts.kt:205-286  | (terminal, not Cancel)
  | seal(stream): SealOutcome — Sealed | AlreadySealed | NeverOpened | Failure.

OutputPinStore (class) + OutputPinPortStoreAdapter (class)
  | v2/pipeline-output-store/src/main/kotlin/dev/rubentxu/pipeline/v2/output/store/OutputPinStore.kt:39-209
  | v2/pipeline-output-store/src/main/kotlin/dev/rubentxu/pipeline/v2/output/store/OutputPinPortStoreAdapter.kt:43-173
  | The durable pin manifest (TSV) and the M3 published-port adapter.

SegmentOutputStore.canPrune
  | v2/pipeline-output-store/src/main/kotlin/dev/rubentxu/pipeline/v2/output/store/SegmentOutputStore.kt:506-545  | Bounded Read
  | Override of `OutputRetentionPort.canPrune`; iterates run-scoped stream dirs and
  | composes `OutputPinPort.pinsForSafeName`. The M3 consult primitive.
```

**The publication question, answered.** `:pipeline-events-store` and
`:pipeline-output-store` do **NOT** declare `maven-publish`. `OperationJournal`,
`ReplayCursorStore`, `RunExecutionLease`, `FileBackedRunExecutionLeaseStore`,
`OutputAppendPort`, `OutputStreamHandle`, `OutputReservation`, `OutputRecoveryPort`,
`OutputSealPort`, `OutputPinStore`, and `OutputPinPortStoreAdapter` are
**internal-to-PK**. A consumer that wants any of them must reach into the JVM that
owns the run, which is not a public API boundary. The published plane gives the
consumer:

- **byte reads**: `OutputReadPort`, `OutputCursor`, `OutputPage`,
  `OutputReadDigestedResult`, `OutputDigest`, `OutputRefusal`;
- **frame reads**: `OutputFrameIndex`, `OutputFrame`;
- **per-run stream list**: `OutputFrameIndex.streamsOfRun(runId)`;
- **per-operation stream pair**: `OperationOutputStreams`;
- **terminality read**: `OutputTailPort`, `OutputTailState`;
- **retention authority**: `OutputRetentionPort.{hasOutputFor, prune, canPrune}`,
  `OutputPruneIntent`, `OutputPruneReport`, `RunLifecycle`, `RetainUntil`,
  `PruneAuthorisation`, `PruneRefusal`;
- **retention hold**: `OutputPinPort.{pin, release, pinsOf, isPinned}`,
  `OutputPin`, `OutputPinId`, `OutputPinResult`, `PinReleaseOutcome`,
  `PinRefusal`;
- **runtime verbs**: `RuntimeIntrospectionPort`, `RuntimeControlPort`,
  `RuntimeRecoverPort`, `RuntimeRecoverDecision`, plus the M2/M3 sealed refusal ADTs;
- **typed event reads**: `EventHistory`, `EventTail`, `EventRecordReadPort`,
  `EventCursor`, `EventQuery`, `EventPage`, `EventRecordReadRefusal`;
- **typed event follow**: `EventFollower`, `EventFollowHandle`, `EventFollowOptions`,
  `EventFollowUntil`, `EventFollowState`, `EventFollowRefusal`, `EventFollowEvent`;
- **typed output follow**: `OutputFollower`, `OutputFollowHandle`,
  `OutputFollowOptions`, `FollowUntil`, `FollowState`, `OutputFollowEvent`.

What the published plane **does NOT** give the consumer:

- a **list of stages** for a runId;
- a **list of steps** for a (runId, stage);
- a **list of operations** for a (runId, stage, step);
- a **get-by-id lookup for an operation by operationId** at the published API
  (the journal's `get(opId)` and `get(opId, attempt)` exist in
  `:pipeline-events-store` but are NOT part of the published ABI);
- a typed `StageSummary`, `StepSummary`, or `OperationSummary` ADT;
- a **hierarchical** view of a run (a Fabric would need to parse
  `DomainEvent.kind == "StageStarted" / "StepStarted" / "OperationStarted"` events
  and group them by `(stage, step, operation)` — that is event parsing, not
  indexed context).

### A.5 Summary count

| Module                                 | Published? | Touches Identity | Touches Indexable | Touches Bounded Read | Touches Slow-Reader Iso | Touches Memory Bounded | Touches No Outcome Drift |
|----------------------------------------|------------|-------------------|--------------------|-----------------------|--------------------------|------------------------|---------------------------|
| `:pipeline-events` (M1)                | YES        | 5 ports           | 0                  | 4 ports               | 2 ports                  | 2 ports                | 2 ports                   |
| `:pipeline-output` (M1)                | YES        | 8 ports           | 2 (streamsOfRun, OperationOutputStreams) | 5 ports | 1 port (OutputFollower) | 4 ports | 4 ports |
| `:pipeline-output` (M3 carry)          | YES        | 2 (OutputDigest, OutputPinPort) | 0 | 2 (OutputPinPort, readRangeDigested) | 0 | 1 (OutputPinPort) | 3 (digest, pin, refusal) |
| `:pipeline-runtime` (M2 + M3)          | YES        | 5 ports           | 1 (RuntimeIntrospectionPort — partial) | 3 ports (DEFAULT_*_TIMEOUT_MS) | 0 | 0 | 3 ports (RecoveredTerminal, AlreadyRecovered, AlreadyCancelled) |
| `:pipeline-events-store/`              | NO         | 1 port (OperationJournal) | 1 (OperationJournal.listForRun) | 1 (OperationJournal.listForRun) | 0 | 0 | 0 |
| `:pipeline-output-store/`              | NO         | 0                 | 0                  | 0                     | 0                        | 0                      | 1 (recover pins-of consult) |
| **Total surveyed**                     | —          | **21**            | **4**              | **15**                | **3**                    | **7**                  | **13**                    |

Three truths fall out of that count:

1. **PK has a complete bounded-read / slow-reader-isolation / identity-preservation /
   no-outcome-drift surface.** The published `:pipeline-output` carries every
   primitive the contract §10 needs: bounded byte reads (`OutputReadPort.read` with
   `maxBytes`, `OutputReadPort.readRange`), bounded frame reads
   (`OutputFrameIndex.framesOfRun(afterOrdinal, limit)`), bounded digest reads
   (`OutputReadPort.readRangeDigested`), bounded pin consults (`OutputPinPort`),
   bounded follow polls (`OutputFollower` / `EventFollower` with `maxRecords` +
   `pageMaxBytes` + `pollIntervalMs`), bounded timeout pins (`DEFAULT_INSPECT_TIMEOUT_MS
   = 5_000L`, `DEFAULT_RECOVER_DEADLINE_MS = 2_000L`, `DEFAULT_CAN_PRUNE_TIMEOUT_MS =
   5_000L`), bounded refusals (`OutputRefusal.{RetentionGap, Corrupt, Unavailable,
   RangeLostRetention}` — every refusal case is a closed ADT with typed identity).
   The published `:pipeline-events` carries the same shape on the typed-event side.
   The published `:pipeline-runtime` carries the same shape on the cross-plane side.
   No new PK surface is needed for any of these axes.

2. **PK has a PARTIAL indexable-context surface — and one specific axis is GAPSED.**
   `OutputFrameIndex.streamsOfRun(runId)` (`:140`) enumerates the streams of a run.
   `OperationOutputStreams` (`:159-184`) names the stdout/stderr pair of one
   operation. `RuntimeIntrospectionPort.inspect(runId)` exposes
   `RuntimeObservation.Running.outputTails: List<OutputTailView>` (the per-stream
   tail views) and `journalPosition: JournalPosition(operations: Int, latestOpId?,
   latestTerminalAtMs?)` (a count + one latest opId, NOT a list). `OperationJournal
   .listForRun(runId)` (in `:pipeline-events-store`, NOT published) returns the full
   ordered list of durable operations for a run. **What the published plane does
   NOT expose is a typed enumeration of stages / steps / operations of a run.**
   A Fabric that wants "this run has 3 stages; each stage has N steps; each step
   has M operations and their current state" must compose the above and parse
   `DomainEvent.kind == "StageStarted"` / `StepStarted` / `OperationStarted` events,
   which is event scanning, not indexed context.

3. **PK introduces no new authority for M6.** The M3 pin store lives in
   `:pipeline-output-store` (non-published) alongside the existing lease store.
   The M2 runtime ports live in `:pipeline-runtime` (published) as EXPERIMENTAL on
   `v0.50.0-rc1`. The M3 capability IDs are advertised on `:pipeline-output` and
   `:pipeline-runtime` (per `coordination/INTERFACE_CONTRACT.md` §"Capacidades
   publicadas (CRIC-M3)"). The user's negative scope ("No implementar dentro de
   PK el Governor, el scheduler de fairness ni el motor de consulta de Fabric") is
   preserved: no Governor, no fairness scheduler, no Fabric query engine is
   introduced.

## B. Gaps between contract requirement and current ports

Each gap is numbered F.1..F.N. The numbering format follows the M2 audit
(B.1..B.8), the M3 audit (C.1..C.8), and the M5 audit (E.1..E.8): `F` for the M6
block. (a) what Fabric needs, (b) what PK has, (c) the missing shape, (d) the
proposed new port or extension, (e) the ADT, (f) the authority it must NOT
introduce. Gaps are derived from the survey in §A; no gap claims a behaviour the
audit did not see in the code.

### F.1 No public indexed enumeration of stages / steps / operations of a run

- **(a) Fabric needs** a typed answer to "what is in this run?" — a list of
  stages, each with its list of steps, each with its list of operations, each with
  its current terminality and identity. The Block 6 plan names this verbatim:
  *"Aportar contexto indexable de run/stage/step/operation y lecturas eficientes
  con presupuestos explícitos."* A Fabric that wants to render a run-detail view
  ("this run has 3 stages; each stage has N steps; each step has K operations")
  needs this as a published port, NOT as an event-scan.

- **(b) PK has** three primitives a consumer would compose:

  1. `RuntimeIntrospectionPort.inspect(runId)` returns
     `RuntimeObservation.Running` with `journalPosition: JournalPosition(operations:
     Int, latestOpId: String?, latestTerminalAtMs: Long?)` — a count + one latest
     opId, NOT a list. The closed `RuntimeObservation` ADT does NOT enumerate
     stages, steps, or operations.
  2. `OutputFrameIndex.streamsOfRun(runId): List<OutputStreamId>` enumerates the
     declared streams of a run — i.e. the stdout/stderr streams of operations,
     flattened, with no grouping by stage/step.
  3. `OperationJournal.listForRun(runId): List<DurableOperation>` (in
     `:pipeline-events-store`, NOT published) returns every journal row in
     execution order. The journal IS the indexable context for operations — but
     the journal interface is internal, not part of the ABI Fabric compiles
     against (per `INTERFACE_CONTRACT.md` §6).
  4. `EventRecordReadPort.readRecords(runId, after, query, limit)` with
     `EventQuery.ByKind("StageStarted")` (or `StepStarted`, `OperationStarted`)
     returns the typed events that NAME a stage/step/operation, but the
     events are a flat sequence the consumer must parse and group — that is
     event scanning, not indexed enumeration.

- **(c) Missing shape.** A typed enumeration, OR a new sibling read on
  `RuntimeIntrospectionPort` (or a new sibling port) that returns a closed ADT
  carrying the hierarchical structure:

  ```text
  sealed interface RunStructure {
      data class Stages(val stages: List<StageSummary>) : RunStructure
      data class Unavailable(val refusal: IntrospectionRefusal) : RunStructure
      data object Empty : RunStructure  // the run has no journaled stages yet
  }
  data class StageSummary(
      val stageOrdinal: Int,            // 0-based
      val stageIndex: String,           // the durable stage index
      val startedAtMs: Long,
      val finishedAtMs: Long?,          // null while running
      val steps: List<StepSummary>,
  )
  data class StepSummary(
      val stepOrdinal: Int,             // ordinal within the stage
      val stepKey: String,              // the durable step key
      val startedAtMs: Long,
      val finishedAtMs: Long?,
      val operations: List<OperationSummary>,
      val state: StepState,             // Pending | Running | Succeeded | Failed | ...
  )
  data class OperationSummary(
      val operationId: String,          // the durable opId
      val attempt: Int,                 // 1-based
      val status: OperationStatus,      // the journal's status enum
      val startedAtMs: Long?,
      val endedAtMs: Long?,
      val fingerprint: String?,         // SHA-256 of operation input (M3 sibling)
      val streams: OperationOutputStreams,
  )
  ```

  The ADT is closed: a new run-state (e.g. `Cancelled`, `Skipped`) is a new case on
  `StepState` / `OperationStatus` and a compile error at every `when` site.

- **(d) Proposed new port.** One of two consistent shapes:

  1. **NEW public method** `RuntimeIntrospectionPort.runStructure(runId):
     RunStructureResult` in `:pipeline-runtime` (additive on the existing M2
     port; same `DEFAULT_*_TIMEOUT_MS` discipline). The M2 design's §2.1
     segregation rule ("verbs MUST be segregated interfaces, NOT one wide port")
     is honoured: this is an additive method on the existing inspect port, not
     a new port family.
  2. **NEW public port** `RunStructurePort.runStructure(runId): RunStructureResult`
     in `:pipeline-runtime` (segregated, M2-style). The M5 audit's §C.2
     demarcation (pins = range/resource authority; introspection =
     run/lifecycle authority) is honoured by keeping this on the introspection
     side, not the pin side.

  The audit recommends (1) — additive on `RuntimeIntrospectionPort` — for three
  reasons: (a) the existing inspect port already composes the journal read path,
  so the new method is a thin composition; (b) the `DEFAULT_INSPECT_TIMEOUT_MS =
  5_000L` budget applies to the new method by construction; (c) the segregation
  rule is preserved (a new METHOD on an existing port, not a new port).

- **(e) ADT it would carry.** `RunStructureResult = Structure(s: RunStructure) |
  Refused(reason: IntrospectionRefusal)` (the M2 closed envelope pattern: a
  sealed `Structure` + a sealed `Refused`, no `Either`/`Result` re-use). The
  inner `RunStructure` is sealed `Stages | Unavailable | Empty`; the inner
  `StageSummary` / `StepSummary` / `OperationSummary` are data classes with
  bounded init { } constraints (timestamps >= 0, opId non-blank, attempt >= 1,
  fingerprint length == 64 if non-null).

- **(f) Authority it must NOT introduce.** No new lease, no new fencing scheme,
  no new scheduler, no new query engine in PK. The new method composes the
  existing `OperationJournal.listForRun` (internal-to-PK) and projects to the
  `RunStructure` ADT — it MUST NOT re-implement the journal scan; it MUST NOT
  introduce a Governor, a fairness scheduler, or a Fabric-side query engine.
  PK's authority stays at "durable bytes + journal + lease + pin + bounded reads".

### F.2 No public per-operation terminality query — Fabric has to scan the journal

- **(a) Fabric needs** "what is the terminality of THIS operation?" — a typed
  answer that names the operation, its current `OperationStatus` (the journal's
  enum: `RUNNING | SUCCEEDED | FAILED | UNSTABLE | ABORTED | DIVERGENT | LOST |
  FAILED_TIMEOUT`), and its timestamps (`startedAt`, `endedAt`, `deadlineMs`).
  The Block 6 plan implies this via "contexto indexable de ... operation".

- **(b) PK has** `OperationJournal.get(opId): DurableOperation?` and
  `OperationJournal.get(opId, attempt): DurableOperation?` and
  `OperationJournal.getDeadlineMs(opId, attempt): Long?` and
  `OperationJournal.getEndedAt(opId, attempt): Long?` and
  `OperationJournal.getStartedAt(opId, attempt): Long?` — every primitive is
  there. **None of these is on the published ABI**; they live in
  `:pipeline-events-store` and are reachable only inside the JVM that owns the
  run (M2 audit §A.6, M3 audit §A.5).

  The M2 `RuntimeObservation.Terminal.terminal: TerminalObservation` carries
  RUN-level terminality, not per-operation terminality; the M2 audit's §B.4
  explicitly notes "the current implementation derives it by enumeration" of
  `OperationJournal.listForRun`.

- **(c) Missing shape.** A new sibling read on `RuntimeIntrospectionPort` (or a
  new method on a new port `OperationInspectionPort.inspectOperation(opId,
  attempt): OperationObservation`) that returns a closed ADT carrying the
  per-operation view:

  ```text
  sealed interface OperationObservation {
      data class Running(val operationId: String, val attempt: Int,
                         val startedAtMs: Long?, val deadlineMs: Long?,
                         val fingerprint: String?) : OperationObservation
      data class Terminal(val operationId: String, val attempt: Int,
                          val terminalAtMs: Long, val outcome: OperationOutcome)
                          : OperationObservation
      data class Unknown(val operationId: String, val attempt: Int)
                          : OperationObservation
      data class Unobservable(val refusal: IntrospectionRefusal) : OperationObservation
  }
  sealed interface OperationOutcome {
      data class Succeeded(val terminalAtMs: Long) : OperationOutcome
      data class Failed(val failureKind: String, val terminalAtMs: Long) : OperationOutcome
      data class Unstable(val terminalAtMs: Long) : OperationOutcome
      data class Aborted(val terminalAtMs: Long) : OperationOutcome
      data object Lost : OperationOutcome
      data class Other(val rawOutcome: String, val terminalAtMs: Long) : OperationOutcome
  }
  ```

  The ADT is closed: a new `OperationStatus` value is a new case on
  `OperationOutcome`, not a new port.

- **(d) Proposed new port.** Additive method on `RuntimeIntrospectionPort`
  (`inspectOperation(opId, attempt): OperationObservation`); or a new sibling
  port `OperationInspectionPort` if the segregation rule is in spirit. Same
  `DEFAULT_INSPECT_TIMEOUT_MS = 5_000L` discipline.

- **(e) ADT it would carry.** `OperationObservation = Running | Terminal |
  Unknown | Unobservable`. `OperationOutcome` is the closed terminal ADT mirroring
  `TerminalObservation` (M2, RuntimeObservation.kt:155-163). No Outcome Drift:
  a consumer that asks twice sees the same `Terminal` answer; a `Running` that
  became `Terminal` between the two calls sees the typed progression.

- **(f) Authority it must NOT introduce.** No new lease, no new scheduler, no
  new Governor. The new method composes the existing `OperationJournal.get*`
  paths; it MUST NOT re-implement them. PK's authority stays at "durable bytes +
  journal + lease + pin + bounded reads".

### F.3 The journal scan is NOT bounded by a wall-time budget at the consumer API

- **(a) Fabric needs** "give me the structure of this run, and bound the wall
  time so a slow consumer doesn't tie up the read". A consumer that asks
  `runStructure(runId)` on a run with millions of operations must NOT be able
  to stall the call indefinitely.

- **(b) PK has** `RuntimeIntrospectionPort.inspect(runId)` with
  `DEFAULT_INSPECT_TIMEOUT_MS = 5_000L` (line 64) — but the inspect call is
  CURRENTLY composed of "listForRun + load + tailState per stream"; if a run
  has millions of journal rows, `listForRun` is unbounded (no `limit` parameter
  on the journal). The M2 design §3.4 names the composition; it does NOT bound
  the journal scan size.

- **(c) Missing shape.** A `limit` parameter on the new `runStructure` /
  `inspectOperation` methods (e.g. `runStructure(runId, options: StructureRun
  Options = StructureRunOptions.Default(limit = 1024))`) that bounds the number
  of stages / steps / operations the call enumerates, OR a continuation cursor
  the consumer can pass back (`after: StructureCursor?`). The audit recommends
  BOTH: a default `limit = 1024` for the unindexed case, AND a `cursor` resume
  for the very-large-run case.

- **(d) Proposed new port.** Additive `StructureRunOptions(limit: Int = 1024,
  after: StructureCursor? = null)` and `StructureCursor` data class in
  `:pipeline-runtime`. The M2 design's §2.3 "companion constants" pattern is
  the precedent.

- **(e) ADT it would carry.** `StructureRunOptions(limit: Int = 1024, after:
  StructureCursor? = null) { init { require(limit > 0) ... } }`.
  `StructureCursor(runId: String, lastOperationId: String?, lastStageOrdinal:
  Int?)` — opaque to the consumer; the implementation owns the resume.

- **(f) Authority it must NOT introduce.** No new authority. The bounded scan is
  a pure projection over the existing journal; the resume cursor is owned by the
  implementation, not the consumer.

### F.4 Fabric's M6 spec does NOT require a Governor, fairness scheduler, or Fabric query engine in PK

The Block 6 plan names three negative-scope exclusions verbatim: *"No implementar
dentro de PK el Governor, el scheduler de fairness ni el motor de consulta de
Fabric."* The M6 audit confirms: the surface Fabric needs (typed enumeration of
stages / steps / operations with bounded reads and explicit budgets) is served by
the M1+M2+M3 PK surface plus the F.1 / F.2 / F.3 additions; the Governor, the
fairness scheduler, and the Fabric query engine are Fabric-side concerns and
NOT part of PK's authority. The audit pins this as a negative-scope rule: no new
PK surface proposes any of them.

### F.5 Fabric's M6 does NOT require a new lease, scheduler, or transport in PK

The M6 audit confirms: the new methods proposed in F.1 / F.2 / F.3 compose the
existing `OperationJournal` read paths and project to closed ADTs. No new lease,
no new fencing scheme, no new scheduler, no new query engine, no new S3 / gRPC
adapter, no new remote spool. PK's authority stays at "durable bytes + journal
+ lease + pin + bounded reads". The user's negative scope is preserved.

### F.summary — gaps table

| Gap ID  | Fabric M6 need                                      | PK port / sealed case (existing M1+M2+M3)                       | Status |
|---------|-----------------------------------------------------|-------------------------------------------------------------------|--------|
| F.1     | Typed enumeration of stages / steps / operations   | NONE in published plane; `RuntimeIntrospectionPort.inspect` partial; `OperationJournal.listForRun` (internal) | **GAP — see F.1** |
| F.2     | Per-operation terminality typed query               | NONE in published plane; `OperationJournal.get(opId, attempt)` (internal) | **GAP — see F.2** |
| F.3     | Bounded scan + resume cursor                        | `DEFAULT_INSPECT_TIMEOUT_MS` exists; journal scan is unbounded | **GAP — see F.3** |
| F.4     | No Governor / fairness scheduler / query engine in PK | None — M6 follows the M1+M2+M3 negative-scope discipline | **VERIFIED: PK preserves** |
| F.5     | No new lease / scheduler / transport in PK          | None — F.1 / F.2 / F.3 are thin projections over existing journal | **VERIFIED: PK preserves** |

**Result.** Three gaps (F.1 / F.2 / F.3) are real and require additive surface.
The other two (F.4 / F.5) are negative-scope rules and are preserved. **A Block 6
PK candidate IS needed** to close F.1 / F.2 / F.3.

## C. Overlap — places where two existing ports claim the same authority over context or budgets

Each overlap is named, the two surfaces are listed, and the audit's recommendation
is given. Overlap is not a defect by itself; an audit that does not name it will
leave a drift hazard behind.

### C.1 `EventHistory.history` and `EventTail.readAfter`

- **Surfaces.** `EventHistory.history(run, query): Sequence<PipelineEventEnvelope>`
  (full unbounded sequence; lines 151-153) vs `EventTail.readAfter(run, cursor,
  limit): EventPage` (paged with `limit: Int`; lines 159-161).
- **Same authority.** "What events has this run produced, in what order?"
- **Recommendation.** Demarcate (M2 audit §C.2): `EventHistory.history` is for
  bounded queries (the CLI replays the whole run into a stream; `--limit N` is the
  only size cap); `EventTail.readAfter` is for long-running reads and follow.
  The audit does NOT propose a new event-plane surface for M6; `EventRecordReadPort
  .readRecords` (M1-A) and `EventFollower` (M1-C) already cover the bounded read +
  bounded follow shapes.

### C.2 `OutputReadPort.read` and `OutputReadPort.readRange`

- **Surfaces.** `OutputReadPort.read(stream, cursor, maxBytes)` (`:34`) vs
  `OutputReadPort.readRange(stream, from, to)` (`:43`).
- **Same authority.** "What bytes are in this stream?"
- **Recommendation.** Demarcate (M1 audit §C.2): `read` is cursor-addressed with an
  explicit `maxBytes` cap; `readRange` is half-open `[from, to)` and contractual
  (line 39-42 KDoc). The M3 audit §C.3 added `readRangeDigested` as an additive
  sibling. The M6 audit confirms: the existing demarcation is sufficient; no new
  M6 surface duplicates either.

### C.3 `OutputReadPort.readRangeDigested` and `OutputReadPort.readRange`

- **Surfaces.** `OutputReadPort.readRangeDigested(stream, from, to):
  OutputReadDigestedResult` (M3, lines 70-86) vs `OutputReadPort.readRange(stream,
  from, to): OutputReadResult` (M1, line 43).
- **Same authority.** "What bytes are at `[from, to)` of this stream?"
- **Recommendation.** Demarcate (M3 audit §C.3): `readRangeDigested` returns bytes
  + SHA-256 digest on the SAME half-open range; the default implementation
  composes `readRange` + `OutputDigest.sha256Of` (line 75-85); concrete adapters
  in `:pipeline-output-store` override for single-pass I/O. The audit confirms:
  no M6 surface needed; the read path is complete.

### C.4 `EventFollower.open` and `OutputFollower.open`

- **Surfaces.** Two public follow ports, one per plane.
- **Same authority.** "Pull-style handle that yields events as the run produces
  them."
- **Recommendation.** Demarcate by plane (M1 follow design §4 composition
  contract). The two are independent: `EventFollower` yields typed
  `EventFollowEvent.Page(slice, newState)` from the event plane; `OutputFollower`
  yields `OutputFollowEvent.Bytes(page, newState)` from the output plane. A
  consumer that wants both opens two handles. The shared discipline:
  `pollIntervalMs = 25L`, `maxRecords = 256`, `lagReportInterval` is REPORTING
  ONLY on the event side (the output side has no `lagReportInterval` because the
  `FollowUntil.UntilAllSealed` predicate is the consumer's "I am done" signal).
  The audit confirms: no M6 surface needed; the follow path is complete.

### C.5 `RuntimeIntrospectionPort.inspect` and `OperationJournal.listForRun`

- **Surfaces.** `RuntimeIntrospectionPort.inspect(runId): RuntimeIntrospectionResult`
  (M2, `:40-66`) vs `OperationJournal.listForRun(runId): List<DurableOperation>`
  (internal, `OperationJournal.kt:67-71`).
- **Same authority?** Partially. Both project "what happened in this run", but
  at different shapes: `inspect` returns the closed `RuntimeObservation` ADT
  (running / terminal / live-but-empty / unobservable) with a journalPosition
  count + latest opId; `listForRun` returns the raw journal rows.
- **Recommendation.** Demarcate by published-API boundary (M2 audit §C.6): the
  journal is internal; the introspection port is the published projection.
  **The F.1 gap names the case where the introspection port's projection is too
  coarse** (a count + one latest opId, NOT a list). The audit recommends the
  F.1 additive method (`runStructure(runId, options)`) on the same
  introspection port — it composes the internal journal and projects to a
  typed enumeration WITHOUT exposing `OperationJournal` to the consumer.

### C.6 `OutputRetentionPort.prune(intent)` and `OutputRetentionPort.canPrune(intent)`

- **Surfaces.** `prune(intent): OutputPruneReport` (M1, the act, lines 155-161)
  vs `canPrune(intent): PruneAuthorisation` (M3, the consult, lines 183-193).
- **Same authority.** "May this deletion proceed, given current pins?"
- **Recommendation.** Demarcate (M5 audit §C.1): `prune` performs the deletion
  and returns what happened; `canPrune` returns the authorization WITHOUT
  performing it. `DEFAULT_CAN_PRUNE_TIMEOUT_MS = 5_000L` (line 191) bounds the
  consult's wall time. The audit confirms: the demarcation is by existing KDoc;
  no M6 consolidation is needed.

### C.7 `OutputPinPort.pin / release` and `OutputRetentionPort.prune`

- **Surfaces.** `OutputPinPort.{pin, release, pinsOf, isPinned}` (M3,
  `OutputPinPort.kt:37-89`) vs `OutputRetentionPort.{prune, canPrune}` (M1 + M3,
  `OutputRetention.kt:149-193`).
- **Same authority?** No. `OutputPinPort` is a retention hold (the bytes are
  pinned); `OutputRetentionPort` is the deletion authority. The two compose via
  `canPrune(Consulted(pinsAtConsult))` (the consult sees the pins).
- **Recommendation.** Demarcate by authority (M3 design §5.5, M5 audit §C.2).
  The audit confirms: no M6 surface needed; the pin / prune demarcation is by
  existing KDoc.

## D. Invariants the contract pins on context / pressure

The contract §1 (Identidades), §5 (Runtime inspeccionable), and §10 (Límites)
pin six invariants on context / pressure. The audit checks each one against the
surveyed ports.

### I.1 — identity preservation

- **What it asserts.** The `(runId, attemptId, stage, step, operationId)` tuple
  is stable across the read path; a follow sees the same identity the writer
  committed.
- **Verdict per port.** **PASS-by-design + tested.** `EventCursor(runId,
  lastSequence)` (EventHistoryPorts.kt:22-53) carries `runId`; `OutputCursor(
  stream, committedOffset)` (OutputCursor.kt:57-105) carries the stream id which
  parses to `(runId, operationId, channel)`; `OutputFrame(stream, channel,
  from, to)` (OutputFrameIndex.kt:50-65) carries the stream id; `OperationJournal
  .get(opId, attempt)` (OperationJournal.kt:51-65) carries the opId + attempt;
  `RuntimeObservation.Running.attempt: AttemptId` + `leaseHolder: LeaseHolder?` +
  `fencingToken: FencingToken` + `journalPosition: JournalPosition(operations,
  latestOpId, latestTerminalAtMs)` (RuntimeObservation.kt:34-41) carry the
  attempt + lease + journal position. The contract test surface for I.1 is the
  M2 + M3 release test battery (`RuntimeIntrospectionPortAdapterTest` 8 cases,
  `RuntimeRecoverPortAdapterTest` 8 cases, `EventFollowerAdapterTest` 18 cases,
  `SegmentOutputFollowerTest` 18 cases). **I.1 holds.**

### I.2 — bounded reads

- **What it asserts.** Every read has an explicit byte / count / time budget; a
  consumer cannot ask for an unbounded page.
- **Verdict per port.** **PASS-by-design + tested.** `OutputReadPort.read(stream,
  cursor, maxBytes)` (`:34`) — `maxBytes` caps the page. `OutputReadPort.readRange
  (stream, from, to)` (`:43`) — half-open range. `OutputReadPort.readRangeDigested
  (stream, from, to)` (M3, `:70-86`) — same range. `EventRecordReadPort.readRecords
  (runId, after, query, limit)` (`:63-68`) — `limit` caps the page. `OutputFrameIndex
  .framesOfRun(runId, afterOrdinal, limit)` (`:148`) — `limit` caps the page.
  `EventTail.readAfter(run, cursor, limit)` (`:160`) — `limit` caps the page.
  `OutputFollower.open(runId, options)` + `OutputFollowOptions(pageMaxBytes,
  pollIntervalMs, maxRecords, afterOrdinal)` (`:131-163`) — `pageMaxBytes` +
  `maxRecords` cap the page; `pollIntervalMs` bounds the coupling. `EventFollower
  .open(runId, options)` + `EventFollowOptions(pollIntervalMs, maxRecords,
  lagReportInterval)` (`:124-152`) — same discipline. `RuntimeIntrospectionPort
  .inspect(runId)` + `DEFAULT_INSPECT_TIMEOUT_MS = 5_000L` (`:64`) — bounded wall
  time. `RuntimeRecoverPort.recover(runId, options)` + `DEFAULT_RECOVER_DEADLINE_MS
  = 2_000L` (`:85`) — bounded wall time. `OutputRetentionPort.canPrune(intent)` +
  `DEFAULT_CAN_PRUNE_TIMEOUT_MS = 5_000L` (OutputRetention.kt:191) — bounded wall
  time. `EventHistory.history(run, query)` (`:152`) — UNBOUNDED (a Kotlin
  `Sequence`, not a `Result`); the audit flags `EventHistory.history` as the one
  exception (it's the M1-M1 "open scan" shape, NOT a bounded read; a consumer
  that wants bounded reads uses `EventTail.readAfter` or `EventRecordReadPort
  .readRecords`). **I.2 holds, with the `EventHistory.history` exception
  documented.**

### I.3 — slow-reader isolation

- **What it asserts.** A consumer that does not advance its cursor for N seconds
  does NOT slow the writer. The follow's poll cadence is the only coupling.
- **Verdict per port.** **PASS-by-design + tested.** `OutputFollower.open(runId,
  options)` (`:80`) is pull-style (the KDoc at line 36-44 pins polling as the
  baseline; the wakeup vocabulary `ObservationWakeup` is not wired to a real
  emitter yet). `EventFollower.open(runId, options)` (`:73`) — same shape.
  `EventFollowOptions.lagReportInterval` (`:145`) is REPORTING ONLY (KDoc lines
  143-145: "A slow consumer is not a defect; the consumer decides whether to
  abort based on the reported lag"). `OutputFollowEvent.Refused(refusal:
  OutputRefusal)` (`:220`) does NOT include a `LagExceeded` case (the closed
  ADT is `Bytes | StateChanged | Refused | Completed`). The contract test
  surface for I.3 is the M1 release test battery
  (`SegmentOutputFollowerTest` 18 cases, `EventFollowerAdapterTest` 18 cases,
  `M1DCrossJvmFollowTest` UAT-PK-M1-001..006). **I.3 holds.**

### I.4 — memory bounded

- **What it asserts.** The largest page a consumer can request is bounded by the
  caller's budget. The implementation does NOT allocate above the budget.
- **Verdict per port.** **PASS-by-design + tested.** Every read port named in
  I.2 has an explicit page-size budget (`maxBytes`, `limit`, `pageMaxBytes`,
  `maxRecords`). The init { } blocks enforce positivity / non-negativity
  (OutputFollowOptions lines 156-162; EventFollowOptions line 147-151;
  OutputPinPort.DEFAULT_MAX_PINS_PER_STREAM = 1024, line 87). `RuntimeIntrospection
  Port.DEFAULT_INSPECT_TIMEOUT_MS = 5_000L` and `RuntimeRecoverPort.DEFAULT_RECOVER
  _DEADLINE_MS = 2_000L` bound the wall time of the call (and therefore the
  upper bound on the page a follow-on call could allocate). The contract test
  surface (`SegmentOutputFollowerTest`, `EventFollowerAdapterTest`) pins the
  budget discipline. **I.4 holds.**

### I.5 — no outcome drift under load

- **What it asserts.** A slow consumer sees the same `RunFinished` event the
  fast consumer saw. No "missed it by N ms" outcome divergence.
- **Verdict per port.** **PASS-by-design + tested.** The follow ports carry
  their own durable cursors (`OutputFollowOptions.afterOrdinal: Long?` at line
  153, `EventFollowOptions.after: EventCursor?` at line 138) — restartable across
  crashes. The output plane has typed refusals for retention loss:
  `OutputRefusal.{RetentionGap, Corrupt, Unavailable, RangeLostRetention,
  StreamLostRetention}` (M3 + M1-B); the event plane has typed refusal:
  `EventFollowRefusal.RetentionLost(runId, lastSeenSequence)` (line 208). The
  digest path (`OutputReadPort.readRangeDigested` + `OutputDigest.sha256Of`)
  verifies byte identity, so a consumer that re-fetches sees the same digest
  (the contract §8 idempotency key). The pin authority (`OutputPinPort.pin` /
  `release`) is durable across restarts (M5 audit §I.1). The contract test
  surface pins this on both planes (`OutputRefusalClosedTest` 4 cases,
  `OutputPinPortAdapterTest` 10 cases, `RecoverRefusalPinExtensionTest` 3 cases,
  `EventFollowerAdapterTest` 18 cases). **I.5 holds.**

### I.6 — indexable context

- **What it asserts.** A consumer can ask "what is in this run?" via a PUBLIC
  port, not by scanning the journal.
- **Verdict per port.** **PARTIAL — UNVERIFIED for the hierarchical case.**
  `OutputFrameIndex.streamsOfRun(runId): List<OutputStreamId>` (`:179`) — per-run
  stream list, **published**. `OperationOutputStreams.all()` (`:159-184`) —
  per-operation stream pair, **published**. `RuntimeIntrospectionPort.inspect
  (runId)` returns `journalPosition: JournalPosition(operations, latestOpId?,
  latestTerminalAtMs?)` (RuntimeObservation.kt:117-121) — a count + one latest
  opId, NOT a list, **published but partial**. `OperationJournal.listForRun
  (runId): List<DurableOperation>` — every journal row of a run, **NOT
  published** (internal-to-PK per M2 audit §A.6 + M3 audit §A.5).
  `EventRecordReadPort.readRecords(runId, ...)` with `EventQuery.ByKind(...)` —
  event sequence, NOT a typed enumeration of stages/steps/operations,
  **published but requires consumer-side parsing**. **I.6 holds for the
  per-run-stream case; UNVERIFIED for the hierarchical stage/step/operation
  case.** The F.1 / F.2 / F.3 gaps in §B are exactly the missing shape.

### I.summary — invariant table

| Invariant                                       | Verdict today            | Why                                                                                                                | Closed by |
|-------------------------------------------------|--------------------------|--------------------------------------------------------------------------------------------------------------------|-----------|
| I.1 identity preservation                        | PASS-by-design + tested  | Cursors, observations, journal rows, opIds, attempts, leases all typed                                              | nothing to close |
| I.2 bounded reads                                | PASS-by-design + tested  | Every read port has explicit budget (maxBytes / limit / pageMaxBytes / maxRecords / DEFAULT_*_TIMEOUT_MS)            | nothing to close |
| I.3 slow-reader isolation                        | PASS-by-design + tested  | Polling is the only coupling; lagReportInterval is REPORTING ONLY; no `LagExceeded` case                            | nothing to close |
| I.4 memory bounded                               | PASS-by-design + tested  | init {} blocks enforce positivity; DEFAULT_MAX_PINS_PER_STREAM = 1024                                                | nothing to close |
| I.5 no outcome drift under load                  | PASS-by-design + tested  | Durable cursors; typed retention refusals; digest verifies byte identity                                            | nothing to close |
| I.6 indexable context                            | **PARTIAL — UNVERIFIED for hierarchical** | Per-run streams (`streamsOfRun`) is published; per-op stream pair is published; hierarchical stage/step/operation is NOT | **F.1 + F.2 + F.3 (the audit's gaps)** |

## E. Cross-walk to UAT / AAT / FABRIC evidence

The M6 audit cross-walks every existing UAT-PK / AAT case the M6 context/pressure
needs to the PK port it depends on, and identifies the gaps the M6 candidate
must close.

### E.1 UAT cases that the existing PK ports already cover (M6 axes)

| UAT ID       | What it proves                                                              | PK port it depends on                                                                                                              | M6 status |
|--------------|-----------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------|-----------|
| UAT-PK-M1-001| Cross-JVM output follow with bounded pages + poll cadence                  | `OutputFollower` + `OutputFollowOptions(pageMaxBytes, pollIntervalMs, maxRecords)`                                                | PASS — covered by M1 |
| UAT-PK-M1-002| Cross-JVM event follow with bounded pages + poll cadence                   | `EventFollower` + `EventFollowOptions(pollIntervalMs, maxRecords, lagReportInterval)`                                              | PASS — covered by M1 |
| UAT-PK-M1-003| Output follow identity preservation (runId + ordinal + stream id)          | `OutputFollowOptions.afterOrdinal: Long?` + `OutputCursor` + `OutputStreamId`                                                       | PASS — covered by M1 |
| UAT-PK-M1-004| Event follow identity preservation (runId + lastSequence + cursor)        | `EventFollowOptions.after: EventCursor?` + `EventCursor(runId, lastSequence)`                                                       | PASS — covered by M1 |
| UAT-PK-M1-005| Event follow slow-reader isolation (1 byte/s vs 1 GB/s sees same outcome) | `EventFollowOptions.lagReportInterval` (REPORTING ONLY); `EventFollowRefusal` has NO `LagExceeded` case                            | PASS — covered by M1 |
| UAT-PK-M1-006| Output follow slow-reader isolation (1 byte/s vs 1 GB/s sees same outcome)| `OutputFollowOptions.pollIntervalMs` (poll coupling); `OutputFollowEvent` has NO `LagExceeded` case                                 | PASS — covered by M1 |
| UAT-PK-M2-001| `RuntimeIntrospectionPort.inspect` returns typed RuntimeObservation        | `RuntimeIntrospectionPort` + `RuntimeObservation.{Running, Terminal, LiveButEmpty, Unobservable}`                                   | PASS — covered by M2 |
| UAT-PK-M2-002| Multiple concurrent inspectors do not race the writer                     | `RuntimeIntrospectionPort` composition rule (M2 design §3.4 — SELECTs only)                                                          | PASS — covered by M2 |
| UAT-PK-M3-001| Digest computes deterministically for the same range                       | `OutputDigest` (M3, `OutputDigest.kt:30-65`) + `OutputReadPort.readRangeDigested`                                                    | PASS — covered by M3 |
| UAT-PK-M3-009| Pin survives process restart                                                | `OutputPinPort` (M3) + `OutputPinStore` TSV atomic-rename                                                                            | PASS — covered by M3 |

### E.2 UAT cases that are gaps relative to M6's context axes

| UAT ID (suggested) | What is missing                                                          | Closed by gap    |
|---------------------|--------------------------------------------------------------------------|------------------|
| UAT-PK-M6-001      | "list the stages of this run, with their steps and operations" — typed query, not event-scan | **F.1 (runStructure)** |
| UAT-PK-M6-002      | "what is the terminality of this operation?" — typed query, not journal-internal | **F.2 (inspectOperation)** |
| UAT-PK-M6-003      | "bounded scan + resume cursor for a very large run's structure"          | **F.3 (StructureRunOptions)** |
| UAT-PK-M6-004      | "slow-reader isolation: a 1 byte/s consumer sees the same RunFinished as a 1 GB/s consumer" | covered by I.3 (PASS-by-design) |
| UAT-PK-M6-005      | "memory bounded: a consumer that asks for a 10 GB page does NOT allocate 10 GB" | covered by I.4 (PASS-by-design) |
| UAT-PK-M6-006      | "identity preservation: a follow resumes after restart and sees the same identities" | covered by I.1 (PASS-by-design) |
| UAT-PK-M6-007      | "no outcome drift: a slow consumer sees the same final RunFinished event" | covered by I.5 (PASS-by-design) |
| UAT-PK-M6-008      | "bounded reads: every read has an explicit budget; a consumer cannot ask for an unbounded page" | covered by I.2 (PASS-by-design) |

These UAT IDs are **suggested** numbers, not pre-existing. They are named here so
the M6 design can adopt them (or re-number them) and so the implementation's UAT
matrix is grounded in the audit instead of in ad-hoc cases.

### E.3 FABRIC UAT cases (Fabric-side) that depend on PK M6 ports

| UAT ID (Fabric) | What it proves                                                              | PK port it depends on                                                                                                              | M6 status |
|-----------------|-----------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------|-----------|
| UAT-CTX-001     | Run-detail view: "this run has N stages, each with M steps, each with K operations" | **F.1 (runStructure)** — typed enumeration                                                                                          | GAP — see F.1 |
| UAT-CTX-002     | Per-operation terminality: "what is THIS operation's current state?"         | **F.2 (inspectOperation)** — typed per-operation query                                                                              | GAP — see F.2 |
| UAT-CTX-003     | Bounded structure scan on a million-operation run                           | **F.3 (StructureRunOptions + StructureCursor)** | GAP — see F.3 |
| UAT-PRS-001     | Slow reader (1 byte/s) sees same RunFinished as fast reader (1 GB/s)      | I.3 (slow-reader isolation) — PASS-by-design on existing ports; no FORK-NEW-PK requirement                           | PASS-by-design on M1 |
| UAT-PRS-002     | Bounded output read: a consumer that asks for 10 GB does NOT allocate 10 GB | I.4 (memory bounded) — `maxBytes` + `pageMaxBytes` + `maxRecords` + `DEFAULT_MAX_PINS_PER_STREAM`                              | PASS-by-design on M1 + M3 |
| UAT-PRS-003     | Bounded event read: a consumer that asks for 10 M events does NOT allocate 10 M | I.4 (memory bounded) — `limit: Int` on `EventRecordReadPort.readRecords` and `EventTail.readAfter`                            | PASS-by-design on M1 |
| UAT-PRS-004     | No outcome drift: a slow consumer sees the same final outcome              | I.5 — durable cursors + typed retention refusals + digest                                                                        | PASS-by-design on M1 + M3 |
| UAT-PRS-005     | Identity preservation: a follow resumes after restart and sees same identities | I.1 — typed cursors + typed observations                                                                                       | PASS-by-design on M1 + M2 |

### E.4 AAT cases that intersect with the M6 audit

| AAT ID  | What it proves                                                                  | PK port it depends on                                                                                                              | M6 status |
|---------|---------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------|-----------|
| AAT-CTX-01 | Hierarchical enumeration scales to 10⁶ operations without OOM                    | **F.1 + F.3** — typed runStructure with `StructureRunOptions(limit)` and resume cursor                                              | GAP — see F.1 + F.3 |
| AAT-CTX-02 | Concurrent inspectors on the same run see consistent monotonic progress         | I.1 — `RuntimeIntrospectionPort` composition rule (M2 design §3.4 — SELECTs only)                                                  | PASS-by-design on M2 |
| AAT-PRS-01 | Slow consumer (1 byte/s for 24 h) sees the same final RunFinished event         | I.3 + I.5 — poll cadence + durable cursors + typed retention refusals                                                                | PASS-by-design on M1 + M2 + M3 |
| AAT-PRS-02 | Bounded read at saturation: a consumer that asks for `maxBytes = Int.MAX_VALUE` does NOT allocate Int.MAX_VALUE bytes | I.4 — `maxBytes: Int` is bounded by the caller's budget; concrete adapters in `:pipeline-output-store` cap at the store's per-page window | PASS-by-design on M1 |
| AAT-PRS-03 | Filter combination: a consumer that asks for `kind = "RunFinished" AND source = <x>` sees exactly the typed envelopes, no false positives | `EventQuery.ByKind` + `EventQuery.BySource` — closed AND across dimensions (M1-A `ObservationQuery` precedent)                      | PASS-by-design on M1 |
| AAT-PRS-04 | Pressure: a consumer that asks 10⁶ readRecords calls in 60 s does NOT back-pressure the writer | I.3 — pull-by-call; the writer's throughput is independent of the consumer's pace                                                | PASS-by-design on M1 |

### E.5 FITNESS constraints the M6 work must respect

The M6 audit re-pins every FITNESS metric the M3 audit enumerated (§E.5 of
`M3_RANGE_RETENTION_AUDIT.md`) and confirms they all PASS-by-design on the
existing M1+M2+M3 surface. No new FITNESS metric is needed for the existing
axes; the F.1 / F.2 / F.3 additions add one new metric:

- **`run_structure_scan_p95 < 2 s`** for a run with `10³` operations. The new
  `runStructure(runId, options)` call MUST honour `DEFAULT_INSPECT_TIMEOUT_MS =
  5_000L` and MUST return `StructureRunOptions(limit = 1024, after = null)` as
  the default; a consumer that asks for a million-operation structure MUST
  resume via `StructureCursor`.

The other FITNESS constraints are inherited from M1+M2+M3:

- **byte-read latency budget** under output saturation: the new `runStructure`
  is a single-pass over the rows of `OperationJournal.listForRun(runId)`,
  projected to a closed ADT. Sub-millisecond per operation on any modern CPU.
- **out_of_order_advances = 0**: the new methods do NOT advance the writer's
  byte sequence. They are pure reads.
- **double_side_effect_count = 0**: the new methods do NOT re-execute
  external effects. The recover port composes the existing `EffectReplayPolicy
  .decide` matrix (M2 design §5.5.1).
- **pruning cooldown**: unchanged. The new methods do not touch the prune path.
- **`controller_cpu_silent`**: the new methods do NOT introduce a per-customer
  polling loop; consumers drive the call cadence.

## F. Decision

```
BLOCK 6 PK-CANDIDATE NEEDED.
```

Reasoning:

1. **The I.6 invariant is PARTIAL — UNVERIFIED for the hierarchical case.** §D's
   I.6 row pins the gap: `OutputFrameIndex.streamsOfRun(runId)` is a per-run
   stream list, but no public port enumerates the stages / steps / operations of
   a run. The contract §1 ("Stage / Step son relaciones por identidad, no solo
   por texto de nombre") and the Block 6 plan ("Aportar contexto indexable de
   run/stage/step/operation") require this as a typed enumeration, NOT as an
   event-scan.

2. **Three real gaps (F.1, F.2, F.3) are named in §B.** Each is an additive
   projection over the existing journal + output planes. No gap requires a
   new lease, a new scheduler, a new Governor, a new fairness scheduler, or a
   new query engine in PK. The user's negative scope is preserved.

3. **Five of the six invariants PASS-by-design + tested.** §D's I.1 / I.2 /
   I.3 / I.4 / I.5 rows are all green. The M1 release (`v0.49.0-rc1`), M2
   release (`v0.50.0-rc1`), and M3 release (`v0.51.0-rc1`) carry the contract
   test surface that pins them. **The M6 deliverable closes I.6.**

4. **No overlap requires PK-side consolidation before M6 ships.** §C's seven
   overlaps are demarcated by their existing KDoc. The M6 additive methods
   sit ON TOP of the demarcation rather than re-doing it. The F.1 method is an
   additive method on `RuntimeIntrospectionPort` (M2 segregation rule
   preserved); F.2 is a sibling additive method on the same port; F.3 adds
   bounded-scan + resume-cursor options that compose with F.1.

5. **Authority moves the audit explicitly forbids are NOT proposed.** The M6
   work introduces no new lease, no new fencing scheme, no new scheduler, no
   new Governor, no new fairness scheduler, no new query engine in PK. The
   new methods compose the existing `OperationJournal` read paths and project
   to closed ADTs. The user's "No implementar dentro de PK el Governor, el
   scheduler de fairness ni el motor de consulta de Fabric" rule is preserved.

6. **The decision is NOT a Fabric-only compatibility handoff.** The three
   additive surfaces the contract requires — `runStructure`, `inspectOperation`,
   `StructureRunOptions` / `StructureCursor` — are real PK API evolution. Per
   the release-receipt v2 model, that evolution is one PK candidate (the next
   pre-release after `v0.51.0-rc1`), the same shape M1/M2/M3 used.

7. **The audit explicitly identifies what Fabric M6 needs from PK.** §E's
   cross-walk names every Fabric UAT-CTX-001..003 case the M6 archive needs
   and pins the F.1 / F.2 / F.3 PK ports that satisfy them. No Fabric case
   requires a new PK authority; the audit's additions are thin projections
   over the existing journal + output planes.

8. **The audit's negative-scope discipline is preserved.** Per the Block 6
   plan, "Tests de volumen: salida grande con lecturas pequeñas, filtros
   combinados, lectores lentos, presión, memoria acotada y ausencia de cambios
   en outcomes." The first five test scenarios are already covered by the
   M1+M2+M3 contract test surface (§E.1 + §E.5). The "absence of outcome
   changes" scenario is covered by I.5 (PASS-by-design + tested). The audit
   proposes new contract tests for the F.1 / F.2 / F.3 surfaces — but those
   tests are M6 deliverable, not M5 carry.

## G. Open issues for the orchestrator

None that block the audit. The following items are flagged for the design
phase that consumes this document; they are not audit failures:

1. **The M6 design must name the home module for the new methods.** Two
   options are consistent with the contract: (a) additive methods on
   `RuntimeIntrospectionPort` in `:pipeline-runtime` (the audit's
   recommendation); (b) a new sibling port `RunStructurePort` /
   `OperationInspectionPort` in `:pipeline-runtime`. The audit recommends
   (a) for the segregation-rule reasons in §B / F.1.

2. **The M6 design must decide whether `runStructure(runId, options)` is
   allowed to project operations whose lease is held by another process.**
   The audit's stance is "yes, name the live holder and its fencing token"
   (the `LeaseHeldByAnother(...)` case in `IntrospectionRefusal`,
   `IntrospectionRefusal.kt:35-38`). The introspection port already
   consults the lease; the new method inherits this consultation.

3. **The M6 design must decide whether `inspectOperation(opId, attempt)` is
   allowed to read across JVMs.** Cross-process introspection always returns
   `null` for `process: ProcessRef?` (RuntimeObservation.kt:40, KDoc lines
   28-32). The new method inherits this discipline.

4. **The M6 design must name the contract-test fingerprint.** Per the
   release-receipt v2 model, the contract change authority is the
   `release-receipt` of the candidate. The M6 candidate is the next PK
   pre-release after `v0.51.0-rc1`; the contract SHA-256 must be refreshed,
   and the `Capacidades publicadas` table gains one new row per gap
   (`runtime.structure.v1`, `runtime.operation-inspect.v1`).

5. **The UAT case IDs in §E.2 / §E.3 are suggested.** The M6 design may
   re-number them to match the package's `UAT-PK-M6-###` convention
   introduced by M1 (`UAT-PK-M1-001..006`).

6. **The M6 design must confirm the indexable-context ADT closures.** The
   `RunStructure` ADT is sealed (`Stages | Unavailable | Empty`); the
   `StepState` is sealed (`Pending | Running | Succeeded | Failed | ...`).
   The audit does NOT enumerate the full `StepState` case list — that's
   the M6 design's job, sourced from the existing journal `OperationStatus`
   enum.

7. **The M6 design must decide whether `runStructure` includes
   `OperationStatus` raw or only the typed `OperationOutcome` ADT.** The
   audit's recommendation is the typed ADT (mirrors `TerminalObservation`):
   a new `OperationStatus` value is a new case on `OperationOutcome`, not
   a new free-text field.

8. **The M6 design must confirm that the new methods do NOT introduce a
   parallel authority over runs.** The new methods compose the existing
   `OperationJournal` read paths and project to closed ADTs. The audit
   pins this as the F.5 negative-scope rule.

9. **The audit pins the "no Governor / no fairness scheduler / no Fabric
   query engine in PK" rule as deliberate.** The Block 6 plan names these
   three exclusions verbatim; the M6 work MUST honour them. The new
   methods compose the existing journal + output read paths; they do NOT
   schedule, prioritize, or query across runs.

---

**End of audit.** The M1+M2+M3 surface covers five of the six M6 invariants
(I.1 / I.2 / I.3 / I.4 / I.5) by design and test. The I.6 invariant
(INDEXABLE CONTEXT) is PARTIAL — UNVERIFIED for the hierarchical stage / step /
operation case. Three gaps (F.1 / F.2 / F.3) are named, each with a proposed
additive projection over the existing journal. **A Block 6 PK candidate IS
needed** to close I.6; the existing M1+M2+M3 surface is sufficient for the
other five axes.