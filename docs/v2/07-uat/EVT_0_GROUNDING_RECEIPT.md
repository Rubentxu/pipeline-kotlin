# EVT-0 — Grounding inventory and contract freeze

Status: **CLOSED (docs-only, no production changes)**  
Baseline: `d0ccf4b5` (CTX-P4-EX closed, 10/10 real examples GREEN via `examples/run.sh`)  
Date: 2026-09-10  
Authority: `docs/v2/05-roadmap/EVENT_SPINE_EVOLUTION.md` (EVT-0), ADR-0077..0079 (PROPOSED), `docs/v2/00-context/EVT_P4_EX_BASELINE.md`

## 1. Producer inventory (who emits DomainEvents)

Single emission primitive: `EventSink.append(event)` (`v2/pipeline-events/.../EventStore.kt`).
45 production `append(...)` call sites across 20 production files:

| Producer group | Files | Events emitted |
|---|---|---|
| Run/stage lifecycle | `durable/CanonicalDurableRunCoordinator.kt` | RunStarted, RunFinished, StageStarted/Finished, CompilationStarted/Finished (via Main), StepStarted/Finished, StepAdmissionObserved |
| Core step dispatchers | `Canonical{EmitEvent,WriteFile,Milestone,Pwd,IsUnix,WaitUntil,DeleteDir,CleanWs,ArchiveArtifacts,Load}NodeDispatcher.kt` | per-step typed events (EchoOutputCaptured, FileWritten/FileRead, MilestoneReached/Aborted, PwdResolved, UnixDetected, WaitUntilPolled/Completed, DirDeleted, WsCleaned, ArtifactArchived/Failed, DirEntered/Exited, WorkflowLoaded, …) |
| Durable shell | `durable/ShExecution.kt` | EchoOutputCaptured, StepFailed, StepStarted/Finished (sh) |
| Step boundary | `durable/StepExecutionBoundary.kt` | StepStarted/StepFinished/StepFailed envelopes |
| SDK runtime | `pipeline-step-sdk/runtime` (ProcessDurableTaskRuntime, StepExecutors), `scm-git/GitCheckoutExecutor.kt` | GitCheckoutStarted/Completed/Failed, GitPollChanged, durable task events |
| Credentials | `pipeline-credentials-api` (CredentialScope), `pipeline-credentials-executor` (WithCredentialsExecutor) | CredentialBound/Used/Unbound |
| Scripting host | `pipeline-scripting-kotlin24/Kotlin24ScriptingHost.kt` | CompilationStarted/Finished |

Not producers (verified `append` sites are non-event, e.g. StringBuilder): `pipeline-step-sdk/files/CleanWsExecutor.kt` (WsCleaned is emitted by `CanonicalCleanWsNodeDispatcher`), `pipeline-binding-factory`, `pipeline-protocol`.

## 2. Sequence authority

Per-`runId` monotonic counter assigned **by the store**, not by producers:

- `InMemoryEventStore`: `ConcurrentHashMap<String, AtomicLong>` per runId; `sequence == 0` → assign next; higher → advance counter (journal-replay tolerance).
- `SqliteEventStore`: same pattern over a `sequence INTEGER` column (`sequenceCounters` map + persisted max).

Producer-supplied `sequence` is a replay-continuation hint, not an authority. **The store is the single sequence authority.** EVT-1/2 must preserve this: `ResourceRef`/envelope work must not move sequence assignment into producers.

## 3. Identity authorities today (pre-ResourceRef)

- `runId`: `RunIdGenerator` (`UuidRunIdGenerator`) for fresh runs; `RunIdDirectory` (`control-root/last-run`) + `DurableRunPolicy` (`ReusePriorRun` default / `ResumePriorRun` / `StartFreshRun`) decides reuse vs fresh. A reused run keeps the SAME runId across CLI invocations (proven by P4-EX 08 contract).
- Stage/step identity: positional strings (`stageName`, `stepName` like `stage/branch/step-N`, `stageIndex`/`stepIndex` fields). Branch children add `branchName`. **No stable typed identity** — this is exactly the EVT-1 gap.
- Operation identity (durable): `OperationJournal` keys (`b0:/b1:` branch journal keys etc.) — journal-side, disjoint from event identity.

## 4. Sink/storage inventory

| Sink | Module | Notes |
|---|---|---|
| `InMemoryEventStore` | pipeline-events | default no-`--db` path |
| `SqliteEventStore` | pipeline-events | `--db` durable path; schema in `OperationJournalSchema`/own tables |
| `NullEventSink` | pipeline-events | discard |
| `RedactingEventSink` | pipeline-credentials-api | decorating sink, secret redaction before delegate |

Known merged-stream behavior (INC-021d, characterized in P4-EX): CLI stdout on durable reruns reprints prior journal events with ORIGINAL timestamps; `JsonEventLog.encode` is the stdout serializer (Main.kt:290 validate, :429 run).

## 5. Consumer inventory

All consumers are in-process or CLI-stdout; there are no live/remote consumers today:

- `Main.kt:289/429/646` — read `eventsFor(runId)` and print via `JsonEventLog` (validate/run/resume paths).
- `ShExecution.kt:322` — internal reader wrapper.
- Tests: `RecordingEventSink`/`MockEventSink` fakes across pipeline-application, scripting, scm-git, credentials.
- `examples/run.sh` — post-run contract checks over CLI stdout JSON (the P4-EX oracle).

No EventTail/cursor/live relay exists yet (EVT-2/EVT-4 deliverables).

## 6. Event catalogue (45 kinds, DomainEvent.kt)

Lifecycle: RunStarted, RunFinished, CompilationStarted, CompilationFinished, StageStarted, StageFinished, StageMarkedUnstable, StepStarted, StepFinished, StepFailed, StepAdmissionObserved.
Control flow: ParallelBranchStarted, ParallelBranchFinished, RetryAttemptStarted, RetryAttemptFinished, TimeoutScheduled, TimeoutTriggered, CatchErrorTriggered, MilestoneReached, MilestoneAborted.
Steps/scm/files: EchoOutputCaptured, GitCheckoutStarted/Completed/Failed, GitPollChanged, FileWritten, FileRead, ArtifactArchived, ArtifactArchiveFailed, ArtifactEntry (payload), DirEntered, DirExited, DirDeleted, WsCleaned, WorkflowLoaded, WaitUntilPolled, WaitUntilCompleted, PwdResolved, UnixDetected, TimestampsEntered, TimestampsExited.
Credentials: CredentialBound, CredentialUsed, CredentialUnbound.

Every kind carries `eventId` (UUID, per-event), `runId` (string), `sequence` (store-assigned), `occurredAt` (Instant). **No ResourceRef, no causation/correlation ids, no envelope versioning** — EVT-1 scope.

## 7. Authority statement

> **Journal ≠ Events.** The `OperationJournal` (op keys, fingerprints, replay decisions) is the durable execution authority. The event log is an observable projection of what happened; it is never consulted to decide execution. Sequence numbers are assigned by the event store, not by the journal and not by producers. No current code reads the event log to gate execution (verified: only Main.kt stdout serialization reads `eventsFor`).

This preserves the CTX-P law (context/observability is not durable truth) and unblocks EVT-1/2: adding envelope/identity projection must produce **zero journal/fingerprint diff** (EVT-1 UAT law already frozen).

## 8. Transport/storage assumptions classified

| Assumption | Status |
|---|---|
| SQLite is the architectural contract | REJECTED by ADR-0077; current adapter stays, ports come in EVT-2 |
| stdout JSON is a transport | NO — it is a serialization of history for CLI humans/tests; INC-021d merged-stream debt noted |
| Live observers exist | NO — none today; EVT-4 proves isolation before any is added |
| CloudEvents required now | NO — mapping characterization only (EVT-1/EVT-14); wire format intentionally not frozen |
| NATS/Kafka selection | DEFERRED to EVT-15, evidence-gated |

## 9. P4-EX oracle freeze

`examples/run.sh` at `d0ccf4b5` (exit 0, 10/10) is the immutable behavioral baseline for EVT-1..3.
Any EVT slice that changes a run.sh verdict without an explicit, documented semantic change is a regression.
EVT-3 migrates assertions only via the differential-parity law in `docs/v2/00-context/EVT_P4_EX_BASELINE.md`.

## 10. Gate check (EVT-0 exit)

- [x] no implementation changes required (this slice is docs-only);
- [x] every proposed port maps to a current call site: EventPublisher→45 append sites, EventHistory→`eventsFor` consumers, EventTail→cursor gap (EVT-2), all inventoried above;
- [x] no speculative generic observer SPI proposed;
- [x] producer/reachability inventory complete;
- [x] baseline event catalogue + producers recorded;
- [x] authority statement written (Journal ≠ Events);
- [x] ADR-0077..0079 grounding verified against code (SQLite-as-adapter assumption confirmed; no schema change needed for ports);
- [x] transport/storage assumptions classified.

## Verification

- Grep-based inventory cross-checked against `v2/` production sources (counts above reproduced with the same commands; no tests executed — docs-only slice).
- Full-round gate not required (zero production/test deltas); next Gradle gate belongs to the EVT-1 code cycle.
