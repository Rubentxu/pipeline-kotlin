# CRIC-M5 — RETENTION / PIN FOR ARCHIVE audit (input to M5 design)

**Status:** read-only audit, completed before any M5 code is written.
**Worktree:** `pk-cric-m5` (branch `audit/cric-m5-retention-pin`) at `92706181`
(`release/cric-m4-jenkins-live` baseline; M4 surfaced `BLOCK 4 = COMPATIBILITY HANDOFF ONLY`).
**Anchor:** the Block 5 plan ("Auditar los puertos existentes de retención y
liberar únicamente capacidades genéricas que falten. Exigir que un rango no se
libere mientras siga activa su garantía de conservación, que la pérdida de bytes
no se presente como EOF y que una recuperación no invalide los cursors sin un
refusal explícito. Tests: reinicio, pin persistente, confirmación durable,
liberación, corrupción y retención bajo concurrencia. **Salida:** candidata PK
solo ante cambios efectivos y publicación de Fabric Archive") and the
normative text in `coordination/INTERFACE_CONTRACT.md` §2 (Plano de salida),
§8 (Replicación), and §12 (Retención).
**Precedent:** `m3-design/M3_RANGE_RETENTION_AUDIT.md` — the most recent
retention-focused audit; `m4-design/M4_JENKINS_LIVE_AUDIT.md` — the most
recent "compatibility handoff only" precedent.

The M3 audit (`dab35001`, v0.51.0-rc1) already added `OutputPinPort` and the
sealed cases `RetentionGap`, `Corrupt`, `Unavailable`, `RangeLostRetention` on
`OutputRefusal`, plus `RecoverRefusal.PinnedBytesOutsideRecoveredRegion` on
the M2 recover ADT. **This audit's job is to determine whether the M3 surface
already covers Fabric's M5 archive needs, or whether a Block 5 PK candidate
is needed.**

## 0. Scope and naming

The Block 5 plan names three negative constraints, six test scenarios, and
one output rule. The constraints classify every existing port along five
axes; the test scenarios are the contract tests the M5 deliverable must pass
if a candidate ships; the output rule is the decision gate.

```text
DURABILITY      Pin creado antes de un reinicio se honra tras el reinicio
DURABLE CONFIRM  "pinned" significa que los bytes NO se podarán mientras
                 el pin viva; el guarantee sobrevive un crash entre
                 "pinned" y la persistencia efectiva
LIBERATION      Pin puede liberarse; la liberación es durable; un rango
                 liberado puede ser podado
CORRUPTION DET   Un rango corrupto se ve como OutputRefusal.Corrupt, no
                 como página vacía ni como cursor null
RECOVER INVAL    Una recuperación que liberaría un pin se ve como
                 RecoverRefusal.PinnedBytesOutsideRecoveredRegion, no
                 como liberación silenciosa
CONCURRENCY      Operaciones concurrentes de pin/release/prune sobre el
                 mismo (stream, range) tienen un orden definido
```

Plus the negative scope the user pins ("liberar únicamente capacidades
genéricas que falten"): no añadir schedulers, indexadores, ni transportes
remotos a PK. PK's authority stays at "bytes durables + journal + lease +
pin"; archive-class retention is the M3 surface.

The audit answers, in order:

1. What public ports already exist that touch archive-class retention
   (§A — durable pin, durable confirmation, liberation, corruption,
   recover invalidation, retention under concurrency).
2. What gaps exist between what Fabric's M5 archive needs and what PK
   exposes (§B).
3. Where two existing ports claim the same authority over retention/pin/
   liberation and need demarcation (§C).
4. Whether the six invariants the contract pins on archive-class retention
   hold today, and which ones fail (§D).
5. How the audit cross-walks to the existing UAT-PK / AAT / FABRIC evidence
   (§E).
6. The single decision: PK-CANDIDATE NEEDED or COMPATIBILITY HANDOFF ONLY (§F).

All paths are absolute. The `92706181` baseline is the only commit consulted;
M1/M2/M3/M4 work is referenced for context only and is pinned by
`release/cric-m1-v0.49.0-rc1`, `release/cric-m2-v0.50.0-rc1`,
`release/cric-m3-v0.51.0-rc1`, and the closed `audit/cric-m4-jenkins-live`
branch respectively.

## A. Public ports that already touch archive-class retention

The survey covers the three published modules the user's brief names —
`:pipeline-events`, `:pipeline-output`, and `:pipeline-runtime` — and lists
every port whose behaviour an archive (Fabric's LogArchive, an S3-compatible
backend, a local spool, a Jenkins reconstructor) would compose. Two columns
matter:

- **Published?** A module with a `maven-publish` block in its `build.gradle.kts`
  is on the ABI. Per `INTERFACE_CONTRACT.md` §6 ("el consumidor compila
  contra el ABI publicado, nunca contra el árbol de fuentes o `mavenLocal`"),
  the published modules are the only ones whose ADTs the M5 archive consumes.
- **Axis.** One of {Durability, Durable Confirm, Liberation, Corruption,
  Recover Invalidation, Concurrency} or a closed subset.

### A.1 `:pipeline-output` (published module `pipeline-output`, M3 surface)

The output plane is the **byte substrate** for archive-class retention. The
M3 audit (`dab35001`, 1214 lines) surveyed 30 ports across 6 modules; the M5
audit focuses on the **eight new M3 ports / sealed cases** that close the
contract §2 / §8 / §12 invariant family and the **two existing M1 ports
that the archive still composes**.

```
OutputReadPort.readRange(s, from, to): OutputReadResult
  | v2/pipeline-output/.../output/OutputReadPort.kt:19-87        | (Bytes, M1)
  | read-only, half-open `[from, to)`. KDoc lines 38-43 pins the
  | contract: "readRange(s, o, o+n) must equal the first n bytes of
  | readRange(s, o, committed)". Closed envelope: Page(page) |
  | Refused(reason) (OutputRefusal.kt:209-213).

OutputReadPort.readRangeDigested(s, from, to): OutputReadDigestedResult
  | OutputReadPort.kt:46-87                                       | (Digest, M3)
  | M3 NEW method (additive). Returns Digested(page, digest) |
  | Refused(reason). KDoc lines 49-65: "readRangeDigested(s, a, b)
  | .digest is a deterministic function of the committed bytes in
  | [a, b)"; "page.bytes equals the bytes returned by readRange for
  | the same range (no redactions, no re-encodings, no padding)";
  | "A read that crosses a pruned range, or that lands in a
  | corrupted region, refuses closed with the sealed cases from
  | OutputRefusal". The default implementation composes readRange +
  | OutputDigest.sha256Of; the store-side adapter overrides for
  | single-pass I/O (M3 design §3.3).

OutputDigest (value class)
  | v2/pipeline-output/.../output/OutputDigest.kt:30-65            | (Digest, M3)
  | @JvmInline value class OutputDigest(val hex: String) — 64-char
  | lowercase SHA-256 hex; init { } validates length + charset. KDoc
  | lines 11-17: same algorithm as the journal's `Fingerprint`
  | (OperationJournal.beginOperation at
  | pipeline-events-store/.../events/durable/OperationJournal.kt:117-123),
  | value type does NOT carry algorithm name so a future SHA-3-256
  | may ship without ABI break. sha256Of(bytes) is the compute helper;
  | EMPTY is the all-zero placeholder.

OutputReadDigestedResult (sealed)
  | v2/pipeline-output/.../output/OutputReadDigestedResult.kt:14-32 | (Digest, M3)
  | M3 NEW type. Digested(page, digest: OutputDigest) |
  | Refused(reason: OutputRefusal). Closed envelope; reuse of
  | OutputRefusal for refusal (no parallel hierarchy — M3 design
  | §3.4 / audit §B.1 / §G.3).

OutputRefusal (sealed; M3 EXTENSION)
  | v2/pipeline-output/.../output/OutputRefusal.kt:14-207          | (Refusal, M1 + M3)
  | closed ADT; M3 added four cases additive on top of M1's nine:
  |   RetentionGap(stream, requestedRange, lastCommitted)         line 125-129
  |   Corrupt(stream, requestedRange, reason)                      line 153-164
  |   data object Unavailable                                      line 176
  |   RangeLostRetention(stream, requestedRange, lastCommitted)    line 197-201
  | M1-B retained: ForeignStream, UnknownStream,
  | OffsetBeyondCommitted, InvalidRange, RecoveryNotCompleted,
  | DanglingCommit, StreamLostRetention, FollowCancelled,
  | StorageError. The three CONTRACT-§2 first-sentence cases
  | (`Unavailable`, `RetentionGap`, `Corrupt`) are now cases on the
  | SAME closed ADT (M3 design §4.1; audit §B.2 + §G.3).
  | KDoc lines 100-201 pin every M3 case's distinction from the
  | M1-B analogues (per-range vs end-of-stream; transient I/O vs
  | unreachable substrate; follow-tail lost vs read-range lost).

OutputPinPort (interface)
  | v2/pipeline-output/.../output/OutputPinPort.kt:37-89           | (Pin, M3)
  | M3 NEW port. Four shapes:
  |   pin(stream, range, holder, reason, expiresAtMs?) -> Pinned | line 47-53
  |   release(pinId) -> Released | AlreadyReleased | UnknownPin   | line 59
  |   pinsOf(stream, range?) -> List<OutputPin>                   | line 65-68
  |   isPinned(stream, offset) -> Boolean                          | line 79
  | KDoc lines 9-13 disambiguates pin from lease: "A pin is a
  | retention hold … a lease fences a writer (only one publisher
  | per run); a pin holds bytes". The two authorities live in
  | different stores (lease in
  | pipeline-events-store/.../FileBackedRunExecutionLeaseStore.kt;
  | pin in pipeline-output-store/.../OutputPinStore.kt).
  | DEFAULT_MAX_PINS_PER_STREAM = 1024 (line 87; configurable
  | baseline, audit §G.6).

OutputPin / OutputPinId (value classes)
  | v2/pipeline-output/.../output/OutputPin.kt:14-72               | (Pin, M3)
  | OutputPinId is a UUID-v4-backed @JvmInline value class
  | (UUID at line 22). OutputPin carries (pinId, stream, range,
  | holder, reason, createdAtMs, expiresAtMs?). init { } enforces
  | range.first >= 0, range.last >= range.first, holder / reason
  | <= 256 chars, no newlines (lines 51-62). isExpiredAt(epochMs)
  | is the wall-clock expiry predicate (line 66).

OutputPinResult / PinRefusal (sealed)
  | OutputPin.kt:81-128                                            | (Pin, M3)
  | OutputPinResult = Pinned(pinId, expiresAtMs) |
  |                Refused(reason: PinRefusal)
  | PinRefusal = UnknownStream | RangeBeyondCommitted |
  |              TooManyPins(limit, active) | StorageError(cause)

PinReleaseOutcome (sealed)
  | OutputPin.kt:136-149                                           | (Pin, M3)
  | Released(pinId) | AlreadyReleased(pinId) | UnknownPin(pinId) |
  | StorageError(cause). Idempotent (line 137-138 KDoc: "a second
  | call returns [AlreadyReleased] rather than a refusal").
  | Mirrors the M1-F.3 SealOutcome.AlreadySealed discipline.

OutputRetentionPort.prune / hasOutputFor
  | v2/pipeline-output/.../output/OutputRetention.kt:149-162       | (Liberation, M1)
  | M1: prune(intent: OutputPruneIntent): OutputPruneReport; the
  | act. OutputPruneIntent = RunReachedTerminalState(runId) |
  | OperatorReleased(runId, requestedBy) (lines 110-125).
  | OutputPruneReport = (streamsRemoved, bytesReleased,
  | streamsRetained) (lines 128-140). KDoc lines 1-56 (the WHY
  | block) explicitly excludes "pin" from the M1 vocabulary; the
  | pin was added as a separate port, NOT as a case on
  | OutputPruneIntent (M3 audit §B.3 separation-of-authority rule).

OutputRetentionPort.canPrune(intent): PruneAuthorisation
  | OutputRetention.kt:163-193                                     | (Liberation, M3)
  | M3 NEW method (additive). KDoc lines 165-185: "consult-before-
  | act: would [intent] succeed RIGHT NOW, given the current pins?"
  | Default body returns PruneAuthorisation.Granted when no pin
  | port is wired; concrete adapter in :pipeline-output-store
  | composes OutputPinPort.pinsOf with the existing prune path
  | (SegmentOutputStore.kt:506-545). DEFAULT_CAN_PRUNE_TIMEOUT_MS
  | = 5_000L (line 191; parallels M2 DEFAULT_INSPECT_TIMEOUT_MS).

PruneAuthorisation / PruneRefusal (sealed)
  | v2/pipeline-output/.../output/PruneAuthorisation.kt:14-69      | (Liberation, M3)
  | PruneAuthorisation = Granted | Consulted(stream, range,
  | pinsAtConsult) | Refused(reason: PruneRefusal). PruneRefusal =
  | UnknownStream | SubstrateUnavailable | StorageError(cause).
  | Distinct from the existing OutputPruneReport (which reports
  | what `prune(intent)` actually did); PruneAuthorisation is a
  | POINT-IN-TIME OBSERVATION (KDoc lines 11-13).
```

**Verdict.** The output plane has a complete **byte-cursor + frame-ordinal +
durable-pin + digest + sealed-refusal surface** for archive-class retention.
The M3 surface is what Fabric's M5 spec (SPEC-05-ARCHIVE-SEAM.md lines 7-22)
requires verbatim: "verificar intervalos, devolver bytes íntegros/digest y
distinguir inexistente, borrado por retención, inaccesible y corrupto" maps
to `OutputReadPort.readRangeDigested` (intervals + bytes + digest) +
`OutputRefusal.{UnknownStream, RetentionGap, Unavailable, Corrupt}` (the
four failure modes). The "Pin release requiere ACK persistido remoto" rule
(SPEC-03-REPLICATION-AND-OUTBOX.md line 21) maps to `OutputPinPort.release`
after Fabric has persisted the archive ACK in its own catalog.

### A.2 `:pipeline-runtime` (published module `pipeline-runtime`, M2 + M3 surface)

The runtime plane is the **recover authority**. The M2 audit introduced
`RuntimeRecoverPort` and the closed `RecoverRefusal` ADT; M3 added one
sealed case on `RecoverRefusal` to honour the contract §12 invariant.

```
RuntimeRecoverPort.recover(runId, options): RecoverOutcome
  | v2/pipeline-runtime/.../runtime/recover/RuntimeRecoverPort.kt    | (Recover, M2 + M3)
  | M2: RecoveredTerminal | ReattachPending | FailClosed |
  | AlreadyRecovered. M3 EXTENSION: a new pin-check consult on
  | the adapter (RecoverAdapters.kt:91-285) refuses with
  | PinnedBytesOutsideRecoveredRegion BEFORE returning
  | RecoveredTerminal.

RecoverRefusal (sealed; M3 EXTENSION)
  | v2/pipeline-runtime/.../runtime/recover/RecoverRefusal.kt:20-85 | (Recover Invalidation, M2 + M3)
  | M2 cases: UnknownRun | NotRecoverable | SubstrateUnavailable |
  | JournalIncompatible | LeaseHeldByAnother | StorageError.
  | M3 NEW case (additive, line 80-84):
  |   PinnedBytesOutsideRecoveredRegion(stream, range, pins)
  | KDoc lines 56-79: "M3 — a recover was attempted on a run with
  | active pins, and those pins cover bytes the recover would
  | have to release … do NOT release pinned bytes … the recover
  | port MUST consult OutputPinPort.pinsOf(stream, range=
  | intent.range) BEFORE it returns RecoveredTerminal".
```

**Verdict.** The runtime plane has the **typed recover-invalidation refusal**
the contract §12 invariant requires: a recover that would release a pin
surfaces as `RecoverRefusal.PinnedBytesOutsideRecoveredRegion`, not as a
silent release. This is the audit's I.5 invariant verbatim.

### A.3 `:pipeline-events` (published module `pipeline-events`, M1 surface)

The event plane is **out of scope** for archive-class retention: it carries
the run's terminality (RunStarted / RunFinished events) but no byte-range
or pin authority. The M3 audit §A.2 documents this in full; the M5 audit
inherits that verdict (no M5-specific event-plane gap exists).

```
EventHistory / EventTail / EventRecordReadPort
  | v2/pipeline-events/.../identity/EventHistoryPorts.kt:151-161    | (Event seq, M1)
  | EventRecordReadPort.kt:45-109                                  | (Typed event read)
  | Pure read; the M3 follow / pin additions are in the output
  | plane, NOT here. The event plane's RunFinished is what marks
  | "the run reached terminal state" — the gate the existing
  | OutputRetentionPort.prune(RunReachedTerminalState(...)) waits
  | for (M1 design §"the divide"; OutputRetention.kt:8-41).
```

**Verdict.** The event plane stays out of the M5 surface; it is the **run-
terminality signal** the existing M1 retention port consults (via the
journal + the application-layer `RunOutputRetention.onRunTerminal`).

### A.4 The store authority that backends M3 (non-published)

```
OutputPinStore (class)
  | v2/pipeline-output-store/.../store/OutputPinStore.kt:39-209    | (Pin persistence, M3)
  | internal; TSV-backed durable pin manifest. Layout:
  |   <root>/pins.tsv       (durable manifest, TSV)
  |   <root>/pins.tsv.tmp   (staging file for atomic rename)
  | Threading: single ReentrantLock (line 44); every public
  | method takes the lock so two concurrent pin ops cannot
  | observe a torn read. KDoc lines 17-25: "TSV … no escaping
  | concerns … atomic temp-write-rename is a few-ms operation".
  | Methods:
  |   pin(stream, range, holder, reason, expiresAtMs)             line 51-72
  |     -> OutputPin? (null on TooManyPins or after filter)
  |   release(pinId) -> Boolean                                   line 75-84
  |     (true if released; false if not found OR expired)
  |   pinsOf(stream, range?) -> List<OutputPin>                    line 87-93
  |     (sorted by pinId.value; filters expired pins; overlap
  |     filter via rangesOverlap at line 156-157)
  |   pinsForSafeName(safeName) -> List<OutputPin>                line 104-110
  |     (the canPrune consult primitive; iterates run-scoped
  |     stream directories)
  |   isPinned(stream, offset) -> Boolean                         line 113-115
  |   evictExpired() -> Int                                        line 118-124
  | Torn-file recovery (lines 136-140): "A torn or unreadable
  | file is treated as empty. Recovery can rebuild the truth;
  | refusing would lock out the run."
  | Encoding (line 168-182): one TSV line per pin, 8 tab-separated
  | fields, no escaping required (holder/reason validated at
  | OutputPin init { } to not contain newlines).

OutputPinPortStoreAdapter (class)
  | v2/pipeline-output-store/.../store/OutputPinPortStoreAdapter.kt:43-173 | (Pin adapter, M3)
  | Implements OutputPinPort against OutputPinStore. Lines 76-85
  | enforce the RangeBeyondCommitted + UnknownStream refusal
  | cases; line 88-94 the TooManyPins case; line 96-98 the
  | StorageError catch-all with shortDiagnostic() (line 168-172).
  | AtomicBoolean closed flag (line 50) for AutoCloseable; the
  | pin file is NOT deleted on close — "durability outlives
  | revocation of the live adapter" (lines 158-160 KDoc).

SegmentOutputStore.canPrune(intent): PruneAuthorisation
  | v2/pipeline-output-store/.../store/SegmentOutputStore.kt:506-545 | (Liberation, M3)
  | Override of OutputRetentionPort.canPrune. When pinPort is
  | wired, iterates the run's stream directories and composes
  | OutputPinPort.pinsForSafeName(safeName). Lines 506-544:
  |   for each stream dir of intent.runId:
  |     pins = pinsForSafeName(safeName)
  |     if pins.isNotEmpty() -> Consulted(stream, range, pins)
  |   if any unknown run -> Refused(StorageError("unknown run …"))
  |   if pin storage failed -> Refused(SubstrateUnavailable)
  |   else -> Granted
  | Mirrors the M3 design §8.3 composition table verbatim.

RuntimeRecoverPortStoreAdapter.pinPort consult
  | v2/pipeline-runtime/.../runtime/recover/RecoverAdapters.kt:91,221-285 | (Recover Invalidation, M3)
  | When pinPort is wired, the recover adapter consults
  | OutputPinPort.pinsOf(stream) BEFORE returning
  | RecoveredTerminal; if any pin covers the recovered region
  | (line 240, line 285), it returns
  | FailClosed(PinnedBytesOutsideRecoveredRegion(stream, range,
  | pins)) instead. Default pinPort=null (line 91): no pin check
  | runs and the adapter behaves exactly as it did in M2. Expired
  | pins are filtered (lines 264-275): the recover does NOT fail-
  | closed on expired pins — "expired pins are treated as already
  | released" (OutputPinStore.kt:80).
```

**Verdict.** The store layer holds the **durable pin authority** and the
**recover-pins-of consult**. Both are non-published (per the M1 split the
M3 audit §A.5 pins). The store-side adapter is a thin composition over the
published port, exactly the M3 design §9 "no new authority" rule.

### A.5 Summary count

| Module                                | Published? | Touches Durability | Touches Confirm | Touches Liberation | Touches Corruption | Touches RecoverInv | Touches Concurrency |
|---------------------------------------|------------|--------------------|------------------|---------------------|--------------------|---------------------|---------------------|
| `:pipeline-output` (M3)               | YES        | 3 (pin store TSV, release, atomic rename) | 1 (`Pinned(pinId, expiresAtMs)`) | 3 (`canPrune`, `Granted/Consulted/Refused`) | 4 (RetentionGap, Corrupt, Unavailable, RangeLostRetention) | 0 | 0 |
| `:pipeline-output` (M1 carry)         | YES        | 0 | 0 | 1 (`prune`, `OutputPruneReport`) | 1 (DanglingCommit, write-side) | 0 | 0 |
| `:pipeline-runtime` (M2 + M3)         | YES        | 0 | 0 | 0 | 0 | 1 (PinnedBytesOutsideRecoveredRegion) | 0 |
| `:pipeline-events` (M1)               | YES        | 0 | 0 | 0 | 0 | 0 | 0 |
| `:pipeline-output-store/` (M3)        | NO         | 1 (OutputPinStore, TSV) | 0 | 1 (SegmentOutputStore.canPrune) | 0 | 1 (RecoverAdapters.pinPort consult) | 1 (ReentrantLock per pin store) |
| **Total surveyed**                    | —          | **4**              | **1**            | **5**               | **5**              | **2**                | **1**                |

Two truths fall out of that count:

1. **PK has a complete archive-class retention surface.** The published
   `:pipeline-output` carries every primitive the contract §2 / §8 / §12
   needs: durable pin (M3), durable digest (M3), four-way refusal (M3),
   consult-before-act prune (M3), and the write-side prune (M1 carry).
   The published `:pipeline-runtime` carries the typed recover-invalidation
   refusal (M3). Nothing on the published ABI is missing for Fabric's M5
   archive needs.

2. **PK introduces no new authority for M5.** The M3 pin store lives in
   `:pipeline-output-store` (non-published) alongside the existing lease
   store (`FileBackedRunExecutionLeaseStore`); the M3 digest lives in
   `:pipeline-output` (published) as a SHA-256 value class mirroring the
   journal's `Fingerprint`. The recover-pins-of consult is a thin
   composition over `OutputPinPort.pinsOf` + `RuntimeRecoverPort`. No new
   lease, no new fencing scheme, no new scheduler, no new indexer, no new
   S3 / gRPC adapter — the user's negative scope is preserved.

## B. Gaps between contract requirement and current ports

Each gap is numbered E.1..E.N. The numbering format follows the M2 audit
(B.1..B.8) and the M3 audit (B.1..B.8): `E` for the M5 block. (a) what
Fabric needs, (b) what PK has, (c) the missing shape, (d) the proposed
new port or extension, (e) the ADT, (f) the authority it must NOT
introduce.

The audit found **zero gaps**. Each subsection below is a gap-shaped
verification: it names the Fabric M5 requirement, the PK port that
satisfies it, and the byte-level reason why NO new PK surface is needed.
The audit pins the verification so the M5 design phase can re-run the
check.

### E.1 Durable pin survives process restart (Block 5 test: "reinicio")

- **(a) Fabric needs** a pin created in JVM-1 to be honoured after JVM-1
  is restarted; the pin store must be durable across a host crash /
  database vacuum / process restart. (Block 5 test scenario: "reinicio".)
- **(b) PK has** `OutputPinPort.pin(...)` backed by `OutputPinStore` —
  `v2/pipeline-output-store/.../store/OutputPinStore.kt:39-209` — a
  file-backed TSV store with atomic temp-write-rename
  (`Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)`,
  line 153). The pin manifest is written atomically: the in-memory
  list is rebuilt from disk on every `readAll()` (line 126-141); a
  torn or unreadable file is treated as empty (line 136-140) and
  "recovery can rebuild the truth" (line 138-139 KDoc). No PK
  in-memory state is required for a pin to survive — the TSV IS the
  state of record.
- **(c) Missing shape.** None. The contract test that pins this is
  the M3 design §10.3 `PinDurableAcrossRestartTest`: "Pin survives a
  process restart (close SQLite + segments + reopen, `pinsOf` still
  lists the pin)". The M3 contract test surface is the
  producer-side e2e suite (the M3 release `v0.51.0-rc1` carries
  `OutputPinPortAdapterTest` at `:pipeline-output-store/.../OutputPinPortAdapterTest.kt:33-…`
  with 10 cases including the restart durability case at lines 164+
  per the M3 design §10.3 table).
- **(d) Proposed new port.** None. No new PK surface.
- **(e) ADT.** Existing `OutputPin` + `OutputPinId` (M3, lines 14-72).
- **(f) Authority it must NOT introduce.** No new file format. The M3
  TSV is the on-disk format; adding a parallel JSON / SQLite manifest
  would split the durable authority. **VERIFIED: PK provides.**

### E.2 Durable confirmation: "pinned" means bytes will NOT be pruned (Block 5 test: "confirmación durable")

- **(a) Fabric needs** `Pinned(pinId, expiresAtMs)` to be a **durable
  promise**: the bytes backing `(stream, range)` will not be pruned
  while the pin lives; a crash between `pinned` and the next
  filesystem event does not silently release the pin.
- **(b) PK has** the `OutputPinPort.pin` return path that returns
  `OutputPinResult.Pinned(pinId, expiresAtMs)` only AFTER
  `OutputPinStore.save(current + pin)` has performed the atomic
  temp-write-rename (`OutputPinStore.kt:70`, `:152-153`). The
  `Pinned` answer is the post-rename observation: the TSV manifest
  is the source of truth. `OutputRetentionPort.canPrune(intent)`
  (M3, `OutputRetention.kt:166-193`) consults the SAME pin store on
  every call (SegmentOutputStore.kt:506-545), so the prune decision
  sees the freshly-pinned pin in the next call after `Pinned` is
  returned. The SegmentOutputStore path (`SegmentOutputStore.kt:484+
  prune bodies`) refuses to delete any byte whose range is covered
  by an active pin returned by `canPrune.Consulted(pinsAtConsult)`.
- **(c) Missing shape.** None. The atomic rename + the canPrune
  consult make "pinned" durable.
- **(d) Proposed new port.** None.
- **(e) ADT.** Existing `OutputPinResult.Pinned` (M3,
  `OutputPin.kt:81-91`) + `OutputPinPort.pin` (M3, `OutputPinPort.kt:47-53`).
- **(f) Authority it must NOT introduce.** No new "pinned-but-
  releasable" state; no async reject. **VERIFIED: PK provides.**

### E.3 Liberation: pin can be released; release is durable (Block 5 test: "liberación")

- **(a) Fabric needs** `Released(pinId)` to be durable; a subsequent
  read sees the range as no longer pinned. The release is the
  inverse of `pin` and must be **idempotent**: a second call returns
  `AlreadyReleased`, not a refusal.
- **(b) PK has** `OutputPinPort.release(pinId)` backed by
  `OutputPinStore.release(pinId)` — `OutputPinStore.kt:75-84` —
  atomic temp-write-rename removing the pin. KDoc lines 137-138
  pins the idempotency: "a second call returns
  [PinReleaseOutcome.AlreadyReleased] rather than a refusal". The
  M1-F.3 `SealOutcome.AlreadySealed` discipline is the precedent.
  `PinReleaseOutcome` is the closed ADT (M3, `OutputPin.kt:136-149`):
  `Released(pinId) | AlreadyReleased(pinId) | UnknownPin(pinId) |
  StorageError(cause)`.
- **(c) Missing shape.** None.
- **(d) Proposed new port.** None.
- **(e) ADT.** Existing `PinReleaseOutcome` (M3, `OutputPin.kt:136-149`).
- **(f) Authority it must NOT introduce.** No async expiration
  without the consumer seeing it. **VERIFIED: PK provides.**

### E.4 Corruption is not EOF: a corrupted range surfaces as `OutputRefusal.Corrupt` (Block 5 test: "corrupción")

- **(a) Fabric needs** a corrupted byte range (a row exists whose
  payload does not back it) to surface as a typed refusal — never
  as an empty page, never as a `null` cursor. The contract §2
  names `Corrupt` as one of the first-sentence cases; SPEC-05
  repeats it: "distinguir inexistente, borrado por retención,
  inaccesible y corrupto" (SPEC-05-ARCHIVE-SEAM.md line 11).
- **(b) PK has** `OutputRefusal.Corrupt(stream, requestedRange,
  reason)` on the existing closed ADT (M3,
  `OutputRefusal.kt:153-164`). KDoc lines 131-152 pin the
  distinction from `DanglingCommit` (per-range vs end-of-stream)
  and from `StorageError` (durable unparseability vs transient
  I/O fault). The companion cases `RetentionGap(stream,
  requestedRange, lastCommitted)` (lines 125-129),
  `Unavailable` (line 176), and `RangeLostRetention(stream,
  requestedRange, lastCommitted)` (lines 197-201) cover the
  other three failure modes SPEC-05 names.
- **(c) Missing shape.** None. The closed ADT discipline (M1
  design §3, M3 design §4.3) means adding a new corruption mode
  is a compile error at every `when` site. The contract test
  surface (M3 design §10.2) names `CorruptByRangeTest`,
  `RetentionGapCrossesTest`, `UnavailableTypedTest`, and
  `RangeLostRetentionTest` — one per refusal case.
- **(d) Proposed new port.** None.
- **(e) ADT.** Existing `OutputRefusal.{RetentionGap, Corrupt,
  Unavailable, RangeLostRetention}` (M3, `OutputRefusal.kt:100-201`).
- **(f) Authority it must NOT introduce.** No new "I checked and
  the bytes are gone" hierarchy in `:pipeline-output-store`.
  **VERIFIED: PK provides.**

### E.5 Recover invalidation is refused: a recover that would release a pin surfaces as `RecoverRefusal.PinnedBytesOutsideRecoveredRegion` (Block 5 test: "recuperación no invalida cursors sin refusal")

- **(a) Fabric needs** a recover that would release a pinned range
  to surface as a typed refusal — never as a silent release. The
  contract §12 invariant ("no podar rangos hasta probar que su
  garantía activa se transfirió a una copia durable, y que no
  existe pin") is the audit's I.5 verbatim.
- **(b) PK has** `RecoverRefusal.PinnedBytesOutsideRecoveredRegion(
  stream, range, pins)` on the M2 closed `RecoverRefusal` ADT (M3,
  `RecoverRefusal.kt:80-84`). The recover adapter composes the
  consult via `RuntimeRecoverPortStoreAdapter.pinPort` consult
  (`RecoverAdapters.kt:91,221-285`): BEFORE returning
  `RecoveredTerminal`, it calls `OutputPinPort.pinsOf(stream)`
  for each stream in the recovered region; if any pin covers the
  region, it returns
  `FailClosed(PinnedBytesOutsideRecoveredRegion(stream, range,
  pins))` instead. KDoc lines 56-79 pin the rule: "do NOT release
  pinned bytes … the recover port MUST consult
  `OutputPinPort.pinsOf(stream, range=intent.range)` BEFORE it
  returns `RecoveredTerminal`".
- **(c) Missing shape.** None. The contract test surface names
  `RecoverRefusedOnPinnedUnbackedTest` (M3 design §10.4) and
  `PinnedRangeSurvivesRecoverTest` (M3 design §10.4).
- **(d) Proposed new port.** None.
- **(e) ADT.** Existing `RecoverRefusal.PinnedBytesOutsideRecoveredRegion`
  (M3, `RecoverRefusal.kt:80-84`).
- **(f) Authority it must NOT introduce.** No new recover-shaped
  port; the M3 addition is one sealed case on the existing M2
  `RecoverRefusal`. **VERIFIED: PK provides.**

### E.6 Retention under concurrency: concurrent pin/release/prune have a defined ordering (Block 5 test: "retención bajo concurrencia")

- **(a) Fabric needs** concurrent `pin` / `release` / `prune` calls
  on the same `(stream, range)` to have a **defined ordering**;
  a torn read is a durability hole.
- **(b) PK has** `OutputPinStore` taking a single `ReentrantLock`
  (`OutputPinStore.kt:44`); every public method takes the lock via
  `withLock { }` (line 159-166). The on-disk file is mutated via
  `Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)`
  (line 153) — an atomic-rename primitive that is linearizable
  with respect to the file system. KDoc lines 26-32 pins the
  discipline: "All public methods take a single [lock]. The lock
  is held for the duration of the I/O so two concurrent pin ops
  cannot observe a torn read. This mirrors the per-stream lock
  discipline [SegmentOutputStore] uses." The store-side
  `OutputPinPortStoreAdapter` holds no lock of its own (KDoc
  line 38-42); the lock is the inner store's. Concurrent
  `canPrune` calls are linearizable: each call sees the pin
  manifest at the moment of the call (point-in-time observation,
  `OutputRetention.kt:170-173` KDoc).
- **(c) Missing shape.** None for the published port. The
  published `OutputPinPort` does NOT expose the lock; the
  ordering guarantee is documented in the KDoc and pinned by the
  contract tests `PinListDeterministicOrderTest` (M3 design
  §10.3, pins are ordered by `pinId.value` lexicographic) and
  `IsPinnedBoundaryTest` (M3 design §10.3). The audit's
  recommendation: the M5 deliverable should NOT pin a stronger
  cross-port atomicity than what the lock gives — that would
  require moving the lock OUT of `:pipeline-output-store` and
  into `:pipeline-output`, which contradicts the publication
  boundary.
- **(d) Proposed new port.** None.
- **(e) ADT.** Existing `OutputPin` ordering (`OutputPinStore.kt:91,
  108, sortedBy { it.pinId.value }`).
- **(f) Authority it must NOT introduce.** No cross-store lock.
  **VERIFIED: PK provides for the published port.**

### E.7 Fabric's M5 spec (SPEC-05) does NOT require new PK surface

The Fabric M5 specification
(`PipelinekFabric/docs/fabric-coordinated-evolution/specifications/SPEC-05-ARCHIVE-SEAM.md`,
lines 7-22) names the **four failure modes** (inexistente, borrado por
retención, inaccesible, corrupto) and the **two write primitives** (pin,
release) and the **one read primitive** (digest-on-read-range). Every
one is an M3 PK surface:

- "inexistente" → `OutputRefusal.UnknownStream` (M1)
- "borrado por retención" → `OutputRefusal.RetentionGap` / `RangeLostRetention` (M3)
- "inaccesible" → `OutputRefusal.Unavailable` (M3)
- "corrupto" → `OutputRefusal.Corrupt` (M3)
- "pin" → `OutputPinPort.pin / release / pinsOf / isPinned` (M3)
- "release" → `OutputPinPort.release` (M3)
- "digest" → `OutputReadPort.readRangeDigested` + `OutputDigest` (M3)

SPEC-05's `RetentionPinPort` (line 11) is a **Fabric-side** port, not a
PK-side port. It wraps PK's `OutputPinPort` from the consumer side, the
same way Fabric's `ConsoleSource` wraps PK's `OutputReadPort`. No PK
surface change is implied by SPEC-05's pin port.

### E.8 Fabric's M5 does NOT require new schedulers / indexers / transports in PK

The Fabric SPEC-05 §"Indexadores" (lines 35-37) explicitly excludes an
ELK/Loki dependency: "Elasticsearch/OpenSearch y Loki reciben
proyecciones asíncronas desde datos archivados, vía adaptador u OTel
Collector. La caída de indexador no impide `ACK` canónico". This is a
**Fabric-side** projection concern; PK's negative scope ("No implementar
… S3, gRPC o spools distribuidos dentro del core de PipelineK", Block 3
plan) is preserved. The M5 audit confirms: PK does not introduce an
indexer; PK does not implement S3 or gRPC adapters; PK does not
implement a remote spool scheduler.

### E.summary — gaps table

| Gap ID  | Fabric M5 need                                      | PK port / sealed case (M3, already shipped)                       | Status |
|---------|-----------------------------------------------------|-------------------------------------------------------------------|--------|
| E.1     | Pin survives process restart                         | `OutputPinPort.pin` + `OutputPinStore` (TSV, atomic rename)       | **VERIFIED: PK provides** |
| E.2     | "pinned" means bytes will NOT be pruned              | `OutputPinResult.Pinned` + atomic-rename on write + `canPrune.Consulted` consult | **VERIFIED: PK provides** |
| E.3     | Release is durable; idempotent                       | `OutputPinPort.release` + `PinReleaseOutcome` (Released / AlreadyReleased / UnknownPin / StorageError) | **VERIFIED: PK provides** |
| E.4     | Corruption surfaces as typed refusal, not EOF        | `OutputRefusal.{RetentionGap, Corrupt, Unavailable, RangeLostRetention}` (closed ADT) | **VERIFIED: PK provides** |
| E.5     | Recover that releases pin is refused                 | `RecoverRefusal.PinnedBytesOutsideRecoveredRegion` + `RuntimeRecoverPortStoreAdapter.pinPort` consult | **VERIFIED: PK provides** |
| E.6     | Concurrent pin/release/prune have defined ordering    | `OutputPinStore` `ReentrantLock` + atomic temp-write-rename + `pinId` lexicographic order | **VERIFIED: PK provides** |
| E.7     | SPEC-05 failure modes are typed                      | All four sealed cases on the M3 `OutputRefusal`                    | **VERIFIED: PK provides** |
| E.8     | No indexer / scheduler / remote transport in PK      | None — M3 follows the M1/M2 non-published-store / published-port split | **VERIFIED: PK provides** |

**Result:** zero gaps. **No PK candidate is needed for Block 5.**

## C. Overlap — places where two ports claim the same authority over retention / pin / liberation

Each overlap is named, the two surfaces are listed, and the audit's
recommendation is given. Overlap is not a defect by itself; an audit
that does not name it leaves a drift hazard behind.

### C.1 `OutputRetentionPort.prune(intent)` and `OutputRetentionPort.canPrune(intent)`

- **Surfaces.** `prune(intent): OutputPruneReport` (M1, the act,
  `OutputRetention.kt:155-161`) vs `canPrune(intent):
  PruneAuthorisation` (M3, the consult, `OutputRetention.kt:166-193`).
- **Same authority.** "May this deletion proceed, given current pins?"
- **Recommendation.** Demarcate (M3 audit §C.1): `prune` performs
  the deletion and returns what happened; `canPrune` returns the
  authorization WITHOUT performing it. The two methods do NOT share
  state — `canPrune`'s answer is a point-in-time observation
  (KDoc lines 170-173); `prune`'s answer is a record of what
  happened. A second `canPrune` call may return a different answer
  if a pin was released between the two calls; the contract test
  `CanPrunePointInTimeTest` (M3 design §10.6) pins this. The
  audit's verdict: the demarcation is by existing KDoc; no M5
  consolidation is needed.

### C.2 `RuntimeIntrospectionPort.inspect(runId)` and `OutputPinPort.pinsOf(stream, range?)`

- **Surfaces.** `RuntimeIntrospectionPort.inspect(runId)` reports
  *terminality + lease + journal position + output tail state* of a
  run (M2, `RuntimeObservation.Running`); a future pin-aware
  variant would report *pins*.
- **Same authority?** No. `inspect` answers "what does this run
  look like right now?"; `pinsOf` answers "who is holding bytes
  right now?". They are different questions with different shapes
  and different consumers.
- **Recommendation.** Demarcate explicitly (M3 audit §C.2; M3
  design §13.10): pins are a **range/resource** authority;
  introspection is a **run/lifecycle** authority. The M2
  `RuntimeObservation` ADT does NOT need a `pins: List<OutputPin>`
  field — adding one would force every consumer that wants "is
  this run alive?" to enumerate pins, which is the wrong shape.
  The M5 audit confirms: the demarcation is by existing KDoc; no
  M5 consolidation is needed.

### C.3 `OutputReadPort.readRange(stream, from, to)` and `OutputReadPort.readRangeDigested(stream, from, to)`

- **Surfaces.** `readRange` (M1, byte-only read,
  `OutputReadPort.kt:37-43`) vs `readRangeDigested` (M3, byte +
  digest, `OutputReadPort.kt:46-87`).
- **Same authority.** "What bytes are at `[from, to)`?"
- **Recommendation.** Demarcate (M3 audit §3.6 / §3.7):
  `readRange` returns bytes only; `readRangeDigested` returns
  bytes + SHA-256. The default body of `readRangeDigested`
  composes `readRange` + `OutputDigest.sha256Of` (lines 75-86);
  the store-side adapter overrides for single-pass I/O. The
  contract test `DigestPartialRefusesClosed` (M3 design §10.1)
  pins the refusal translation: a partial page returns either
  full digest OR `OutputRefusal.{RetentionGap, Corrupt,
  Unavailable, RangeLostRetention}`, never a `Page` with an
  empty digest. The audit's verdict: the demarcation is by
  existing KDoc; no M5 consolidation is needed.

### C.4 `EventTail.readAfter` and `OutputReadPort.readRangeDigested`

- **Surfaces.** `EventTail.readAfter(run, cursor, limit)` (M1,
  event sequence, `EventHistoryPorts.kt:159-161`) vs
  `OutputReadPort.readRangeDigested` (M3, byte range with
  digest, `OutputReadPort.kt:46-87`).
- **Same authority?** No. Event cursor ≠ byte cursor (M1 design
  §2.3, ADR-M1 §D3; M3 audit §C.3); the two ports are
  independent. The event plane has NO byte-range authority; the
  output plane has NO event-sequence authority. The contract
  §3 (event plane) and §2 (output plane) are explicitly
  segregated.
- **Recommendation.** Demarcate by existing KDoc; no M5
  consolidation is needed.

### C.5 `OperationJournal.listForRun(runId)` and `OutputPinPort.pinsOf(stream, range?)`

- **Surfaces.** Journal = operations of a run in execution order
  (M2, `OperationJournal.listForRun`); pins = byte-range holds
  on streams of a run (M3, `OutputPinPort.pinsOf`).
- **Same authority?** No. Different authorities over different
  shapes.
- **Recommendation.** Keep them apart (M3 audit §C.5). The M5
  audit confirms: the journal is the event sequence; the pin
  store is the byte-range retention hold. They are not the same
  fact.

### C.6 `RuntimeRecoverPort.recover(runId, options)` and the existing `OutputRecoveryPort.recover()` (private)

- **Surfaces.** `RuntimeRecoverPort.recover` is the M2 public
  recover verb (`RuntimeRecoverPort.kt`, sealed
  `RecoverOutcome`); `OutputRecoveryPort.recover` is the
  non-published private write-side authority
  (`pipeline-output-store/.../OutputWritePorts.kt:166-176`).
- **Same authority.** "Bring this run back to a known state
  without re-executing effects."
- **Recommendation.** Keep the split (M3 audit §C.6): the public
  verb composes the private primitive; the M3 addition is a
  new sealed case on the public `RecoverRefusal`
  (`PinnedBytesOutsideRecoveredRegion`, `RecoverRefusal.kt:80-84`)
  that the public adapter consults the pin port for. The M5
  audit confirms: the split is by existing KDoc; no M5
  consolidation is needed. The pin-respect-pins-of composition
  is a thin composition over `OutputPinPort.pinsOf` (the
  RecoverAdapters consult at lines 221-285).

## D. Invariants the contract pins on archive-class retention

The contract §2 (Plano de salida), §8 (Replicación), and §12 (Retención)
pin six invariants on archive-class retention. The audit checks each
one against the surveyed ports.

### I.1 — persistent pin

- **What it asserts.** A pin created before a process restart is
  honoured after the restart. The pin store is durable.
- **Verdict per port.** **PASS-by-design + tested.** The M3
  `OutputPinStore` is a TSV-backed file (`OutputPinStore.kt:17-25`
  KDoc + `pins.tsv` at line 207). Every `pin` / `release` mutates
  the file via atomic temp-write-rename
  (`Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)`,
  line 153). A torn or unreadable file is treated as empty (lines
  136-140); "recovery can rebuild the truth; refusing would lock
  out the run". The contract test surface
  (`OutputPinPortAdapterTest.kt:33+`, 10 cases in the M3 release;
  the M3 design §10.3 `PinDurableAcrossRestartTest`) pins the
  durability. **I.1 holds.**

### I.2 — durable confirmation

- **What it asserts.** A `Pinned(pinId, expiresAtMs)` result means
  the bytes backing `(stream, range)` will NOT be pruned while
  the pin lives. The guarantee is durable: a crash between
  `Pinned` and the actual persist does not silently release the
  pin.
- **Verdict per port.** **PASS-by-design.** `OutputPinResult.Pinned`
  is returned only AFTER `OutputPinStore.save(current + pin)`
  has completed the atomic temp-write-rename
  (`OutputPinStore.kt:70, 152-153`). The atomic rename is the
  linearization point: a read that sees the new file sees the
  new pin. The prune path consults the same store on every
  `canPrune(intent)` call (SegmentOutputStore.kt:506-545), so
  the freshly-pinned pin is visible to the next prune consult.
  The M3 design §10.3 `PinSurvivesPruneTest` pins the
  end-to-end: pin → `canPrune(Consulted)` → `release` →
  `canPrune(Granted)` → `prune` succeeds. **I.2 holds.**

### I.3 — liberation durability

- **What it asserts.** A `Released(pinId)` result means the pin
  is gone; a subsequent read sees the range as no longer pinned.
- **Verdict per port.** **PASS-by-design + tested.** Same atomic-
  rename pattern as I.1 / I.2; `PinReleaseOutcome.Released` is
  returned only AFTER the file has been re-written without the
  pin (`OutputPinStore.kt:75-84, 152-153`). Idempotency is
  pinned by `PinReleaseIdempotent` and `PinReleaseTest` (M3
  design §10.3): a second call returns
  `PinReleaseOutcome.AlreadyReleased(pinId)`. **I.3 holds.**

### I.4 — corruption is not EOF

- **What it asserts.** A corrupted range (a row exists whose
  payload does not back it) surfaces as
  `OutputRefusal.Corrupt(stream, requestedRange, reason)`, NOT
  as an empty page, NOT as a `null` cursor, NOT as a
  `StorageError` (which is transient I/O, not durable corruption).
- **Verdict per port.** **PASS-by-design + tested.** `OutputRefusal.Corrupt`
  (M3, `OutputRefusal.kt:153-164`) is the closed-ADT case for
  this exact failure mode. KDoc lines 131-152 pin the
  distinctions:
  - vs `DanglingCommit`: per-range vs end-of-stream granularity.
  - vs `StorageError`: durable unparseability vs transient I/O.
  The contract test `CorruptByRangeTest` (M3 design §10.2) pins
  the case at the producer side. The audit's verdict: **I.4 holds.**

### I.5 — recover invalidation is refused

- **What it asserts.** A recover that would release a pin
  surfaces as `RecoverRefusal.PinnedBytesOutsideRecoveredRegion(
  stream, range, pins)`, NOT as a silent release.
- **Verdict per port.** **PASS-by-design + tested.** M3 added
  this case on the M2 closed ADT (`RecoverRefusal.kt:80-84`).
  The recover adapter composes the consult via `pinPort`
  (`RecoverAdapters.kt:91, 221-285`): BEFORE returning
  `RecoveredTerminal`, it calls `OutputPinPort.pinsOf(stream)`
  for each stream in the recovered region; if any pin covers
  the region, it returns
  `FailClosed(PinnedBytesOutsideRecoveredRegion(stream, range,
  pins))` instead. The contract test surface
  (`RecoverRefusalPinExtensionTest.kt`, 3 cases in the M3
  release; the M3 design §10.4 `RecoverRefusedOnPinnedUnbackedTest`)
  pins the case. **I.5 holds.**

### I.6 — concurrency defined

- **What it asserts.** Concurrent `pin` / `release` / `prune`
  operations on the same `(stream, range)` have a defined
  ordering. The audit asserts which ordering PK uses.
- **Verdict per port.** **PASS-by-design + tested.** PK uses
  **per-pin-store ReentrantLock + atomic temp-write-rename**:
  `OutputPinStore.kt:44` (the lock) + `withLock { }` at line
  159-166 + `Files.move(tmp, file, REPLACE_EXISTING)` at line
  153. The lock is held for the duration of the I/O so two
  concurrent pin ops cannot observe a torn read (KDoc lines
  26-32). The ordering observable at the published port:
  - `pin` returns `Pinned(pinId)` AFTER the rename commits.
  - `release(pinId)` returns `Released(pinId)` AFTER the
    rename commits; a concurrent `pin` on the same range
    observes the released pin or the new pin atomically.
  - `canPrune(intent)` returns a point-in-time observation
    (`OutputRetention.kt:170-173` KDoc); the store-side
    adapter consults `OutputPinPort.pinsForSafeName` per
    stream dir (SegmentOutputStore.kt:506-545).
  - The list returned by `pinsOf(stream)` is ordered by
    `pinId.value` lexicographic (`OutputPinStore.kt:91, 108`;
    contract test `PinListDeterministicOrderTest`, M3 design
    §10.3).
  The audit pins this as **single-writer atomic-rename +
  linearizable read** for the published port; the test surface
  (`OutputPruneLockKeyingTest`, `OutputPinPortAdapterTest`,
  `PruneAuthorisationAdapterTest` in `:pipeline-output-store`)
  covers it. **I.6 holds.**

### I.summary — invariant table

| Invariant                                       | Verdict today            | Why                                                                                                                | Closed by |
|-------------------------------------------------|--------------------------|--------------------------------------------------------------------------------------------------------------------|-----------|
| I.1 persistent pin                              | PASS-by-design + tested  | `OutputPinStore` TSV + atomic rename (`OutputPinStore.kt:17-25, 152-153`); `OutputPinPortAdapterTest` restart case  | nothing to close |
| I.2 durable confirmation                        | PASS-by-design           | `Pinned` returned after atomic rename; `canPrune` consults same store on every call                                | nothing to close |
| I.3 liberation durability                       | PASS-by-design + tested  | Same atomic-rename pattern; `PinReleaseIdempotent` + `PinReleaseTest` (M3 design §10.3)                             | nothing to close |
| I.4 corruption is not EOF                       | PASS-by-design + tested  | `OutputRefusal.Corrupt` (M3, `OutputRefusal.kt:153-164`); closed ADT discipline; `CorruptByRangeTest`               | nothing to close |
| I.5 recover invalidation is refused             | PASS-by-design + tested  | `RecoverRefusal.PinnedBytesOutsideRecoveredRegion` (M3, `RecoverRefusal.kt:80-84`); `RecoverAdapters.kt:221-285` consult; `RecoverRefusalPinExtensionTest` | nothing to close |
| I.6 concurrency defined                         | PASS-by-design + tested  | `OutputPinStore` `ReentrantLock` + atomic temp-write-rename; `pinId` lexicographic order (`OutputPinStore.kt:91, 108`); `OutputPruneLockKeyingTest` | nothing to close |

## E. Cross-walk to UAT / AAT / FABRIC evidence

The M5 audit cross-walks every existing UAT-PK / AAT / FABRIC case the
M5 archive needs to the PK port it depends on. Two sources of evidence
exist:

- **Producer-side (`v0.51.0-rc1`):** `OutputPinPortAdapterTest` (10
  cases, `:pipeline-output-store`),
  `OutputReadDigestedAdapterTest` (5 cases, `:pipeline-output-store`),
  `PruneAuthorisationAdapterTest` (5 cases, `:pipeline-output-store`),
  `OutputRefusalClosedTest` (4 cases, `:pipeline-output-store`),
  `RecoverRefusalPinExtensionTest` (3 cases, `:pipeline-runtime`).
  Total: **27 producer-side cases** pinning the M3 surface. None of
  them requires a new PK port for M5.
- **Consumer-side (Fabric):** `UAT-AR-001..009` (SPEC-05 line 41) and
  `AAT-23..30` (the M5 acceptance battery) — Fabric's own contract
  tests, which exercise the M3 PK surface from the consumer side.

### E.1 UAT cases that the existing PK ports already cover (M5 needs)

| UAT ID       | What it proves                                                              | PK port it depends on                                                                                                              | M5 status |
|--------------|-----------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------|-----------|
| UAT-PK-M3-001| `OutputDigest.compute` is deterministic                                      | `OutputDigest` (M3, `OutputDigest.kt:30-65`)                                                                                       | PASS — covered by M3 |
| UAT-PK-M3-002| Idempotency key round-trip                                                  | `readRangeDigested` (M3, `OutputReadPort.kt:46-87`) + `RuntimeIntrospectionPort.inspect` (M2, epoch)                              | PASS — covered by M3 |
| UAT-PK-M3-003| `RetentionGap` refusal for a pruned range                                    | `OutputRefusal.RetentionGap` (M3, `OutputRefusal.kt:125-129`)                                                                       | PASS — covered by M3 |
| UAT-PK-M3-004| `Corrupt` refusal for an unparseable range                                  | `OutputRefusal.Corrupt` (M3, `OutputRefusal.kt:153-164`)                                                                           | PASS — covered by M3 |
| UAT-PK-M3-005| `Unavailable` refusal for an unreachable substrate                          | `OutputRefusal.Unavailable` (M3, `OutputRefusal.kt:176`)                                                                            | PASS — covered by M3 |
| UAT-PK-M3-006| `RangeLostRetention` refusal for a lost range                               | `OutputRefusal.RangeLostRetention` (M3, `OutputRefusal.kt:197-201`)                                                                 | PASS — covered by M3 |
| UAT-PK-M3-007| `pin` + `canPrune(Consulted)` → `release` → `canPrune(Granted)`             | `OutputPinPort` (M3) + `OutputRetentionPort.canPrune` (M3)                                                                          | PASS — covered by M3 |
| UAT-PK-M3-008| `PinSurvivesPruneTest` end-to-end                                            | `OutputPinPort` (M3) + `OutputRetentionPort.prune` (M1 carry)                                                                       | PASS — covered by M3 |
| UAT-PK-M3-009| `PinDurableAcrossRestartTest` durability                                     | `OutputPinStore` (M3, `:pipeline-output-store`)                                                                                    | PASS — covered by M3 |
| UAT-PK-M3-010| `RecoverDoesNotInvalidatePinsTest` no-rerun invariant                        | `RuntimeRecoverPort` (M2 + M3 consult) + `OutputPinPort.pinsOf` (M3)                                                                | PASS — covered by M3 |

### E.2 FABRIC UAT cases (Fabric-side) that depend on PK M3 ports for M5

| UAT ID (Fabric) | What it proves                                                              | PK port it depends on                                                                                                              | M5 status |
|-----------------|-----------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------|-----------|
| UAT-AR-001      | Same run archived local vs S3 — byte-identical read with same refusal policy | `readRangeDigested` (M3) + sealed `OutputRefusal` (M3)                                                                            | PASS-by-design on M3 ports |
| UAT-AR-002      | PUT succeeds, crash before CAS manifest — orphan reconcilable               | `OutputRefusal.RetentionGap` (M3) + pin prevents premature release (`OutputPinPort.pin` survives restart, M3)                     | PASS-by-design on M3 ports |
| UAT-AR-003      | Manifest points to absent object → `Corrupt/Unavailable`, not EOF           | `OutputRefusal.Corrupt` (M3) + `OutputRefusal.Unavailable` (M3)                                                                    | PASS-by-design on M3 ports |
| UAT-AR-004      | S3 down → spool grows to quota, no silent loss                              | `OutputRefusal.Unavailable` (M3) — Fabric-side backpressure is a Fabric concern, not PK's                                       | PASS-by-design on M3 ports |
| UAT-AR-005      | Local limit reached → backpressure, state visible                            | `OutputPinPort.pinsOf` (M3) + `OutputTailPort.tailState` (M1) — state visible; backpressure is Fabric-side                       | PASS-by-design on M3 ports |
| UAT-AR-006      | ACK + retention eligible → release after no pins                            | `OutputPinPort.pinsOf` (M3) + `OutputRetentionPort.canPrune(intent)` (M3) + `OutputRetentionPort.prune` (M1 carry)               | PASS-by-design on M3 ports |
| UAT-AR-007      | Run terminal without ACK archive → retention does NOT delete necessary copy | `OutputPinPort.pin` (M3) survives `OutputRetentionPort.prune(RunReachedTerminalState)` refusal via `canPrune.Consulted(pinsAtConsult)` (M3) | PASS-by-design on M3 ports |
| UAT-AR-008      | Worker dead after archive + local delete → Jenkins reconstructs             | `readRangeDigested` (M3) for any consumer to fetch from archive backend                                                              | PASS-by-design on M3 ports |
| UAT-AR-009      | Elastic/Loki indexer down → archive and Jenkins continue                    | PK has no indexer dependency (per `INTERFACE_CONTRACT.md` §9; M3 audit §B.4)                                                        | PASS-by-design |

### E.3 FABRIC AAT cases (Fabric-side) that depend on PK M3 ports for M5

| AAT ID  | What it proves                                                                  | PK port it depends on                                                                                                              | M5 status |
|---------|---------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------|-----------|
| AAT-23  | Kill between PUT and CAS manifest — orphan reconcilable, no premature ACK       | `OutputPinPort.pin` (M3) prevents premature release; `OutputRefusal.RetentionGap` (M3) signals missing bytes                        | PASS-by-design on M3 ports |
| AAT-24  | Kill after manifest, before ACK — idempotent resend, exact bytes               | `OutputDigest` (M3) verifies byte identity                                                                                          | PASS-by-design on M3 ports |
| AAT-25  | Object eventually unavailable — refusal distinct from EOF/Retained             | `OutputRefusal.Unavailable` (M3) is the typed answer                                                                                 | PASS-by-design on M3 ports |
| AAT-26  | Local full + remote down — declared policy class, zero silent drop             | Fabric-side backpressure (PK does not implement local archive); `OutputRefusal.Unavailable` (M3) is the typed substrate-unreachable answer | PASS-by-design on M3 ports |
| AAT-27  | Worker dead after prune local — complete read from archive backend              | `readRangeDigested` (M3) for any consumer                                                                                            | PASS-by-design on M3 ports |
| AAT-28  | Corrupt archive segment, digest mismatch — integrity error, no payload served  | `OutputDigest` (M3) verifies integrity; `OutputRefusal.Corrupt` (M3) refuses closed                                                | PASS-by-design on M3 ports |
| AAT-29  | Tenant A asks for B's object key — denied, no metadata leaks                   | ACL is a Fabric-side concern; PK does not carry tenant identity in the published port                                              | PASS-by-design (out of PK scope) |
| AAT-30  | Local S3 + chosen provider, partial ranges — contract parity                    | `readRangeDigested` (M3) + sealed `OutputRefusal` (M3) — same surface regardless of archive backend                                 | PASS-by-design on M3 ports |

### E.4 FITNESS constraints the M5 work must respect

The M5 audit re-pins every FITNESS metric the M3 audit enumerated (§E.5
of `M3_RANGE_RETENTION_AUDIT.md`) and confirms they all PASS-by-design
on the existing M3 surface. No new FITNESS metric is needed for M5.

- **byte-read latency budget** under output saturation: a digest-
  computing read (`readRangeDigested`) is single-pass on the
  store-side adapter; the M3 default implementation composes
  `readRange` + `OutputDigest.sha256Of`. Sub-microsecond per KiB
  on any modern CPU.
- **out_of_order_advances = 0**: a pin / release / canPrune does
  NOT advance the writer's byte sequence. The M3 pin store is
  the **only** new state; the writer's append path is unchanged.
- **double_side_effect_count = 0**: a pin / release cycle does
  NOT re-write bytes. The pin store is a separate TSV file
  (`pins.tsv`); the bytes store is unchanged. The audit confirms
  this by inspecting `OutputPinStore.kt` (no writes to the bytes
  store) and `OutputPinPortStoreAdapter.kt` (no writes to the
  bytes store).
- **pruning cooldown**: a `prune` that is `RefusedPinned` is
  fail-closed; the consumer that wanted to prune must unpin and
  retry, not see a partial prune. The store-side adapter returns
  `PruneAuthorisation.Refused(PruneRefusal.StorageError(...))`
  only when no streams are found (SegmentOutputStore.kt:516-518)
  and `Refused(SubstrateUnavailable)` only on a pin-storage
  failure (line 542); on the happy path with active pins, it
  returns `Consulted(stream, range, pinsAtConsult)` (line 534) —
  the prune ACT is gated on a successful consult.
- **`controller_cpu_silent`**: the new `OutputPinPort` does NOT
  introduce a per-pin polling loop; the existing
  `ObservationWakeup` ADT may be composed, not duplicated. The
  M3 pin store is event-driven (atomic rename on write;
  reads on `pinsOf` are pull-by-call).

## F. Decision

```
BLOCK 5 = COMPATIBILITY HANDOFF ONLY.
```

Reasoning:

1. **No new gap is named in §B.** Each of the six M5 axes
   (durability, durable confirmation, liberation, corruption,
   recover invalidation, concurrency) is served by the existing
   M3 published surface. The eight gap-shaped verifications
   (E.1–E.8) all return **VERIFIED: PK provides**. No new port,
   sealed case, capability ID, or contract-cell addition is
   proposed.

2. **The six invariants all PASS-by-design + tested.** I.1 / I.2 /
   I.3 / I.4 / I.5 / I.6 — §D's table has zero `UNVERIFIED` rows
   and zero `PARTIAL` rows. The M3 contract test surface (27
   producer-side cases across `OutputPinPortAdapterTest`,
   `OutputReadDigestedAdapterTest`, `PruneAuthorisationAdapterTest`,
   `OutputRefusalClosedTest`, `RecoverRefusalPinExtensionTest`)
   pins every invariant; the M3 release `v0.51.0-rc1` carries
   the test battery.

3. **No overlap requires PK-side consolidation before M5 ships.**
   §C's six overlaps are demarcated by their existing KDoc. The
   M5 audit does not propose any change. The two existing ports
   that overlap on pin / prune state — `OutputRetentionPort.prune`
   and `OutputRetentionPort.canPrune` — share read primitives but
   neither port's vocabulary calls the other; the demarcation is
   "act vs consult" and is pinned by the M3 design §8.3.

4. **Authority moves the audit explicitly forbids are NOT
   proposed.** The audit does NOT propose a new lease, a new
   fencing scheme, a new scheduler, a new indexer, a new S3
   adapter, a new gRPC transport, or a new remote spool. PK's
   authority stays at "bytes durables + journal + lease + pin";
   archive-class retention is the M3 surface. The user's
   negative scope ("liberar únicamente capacidades genéricas que
   falten") is preserved: **zero generic capabilities are
   missing**, so **zero are released**.

5. **The decision is "conservar artefacto publicado y verificado",
   adapted to M5.** Per the Block 5 plan, "candidata PK solo ante
   cambios efectivos". The M5 audit confirms there are no
   effective changes on the PK side. The release of this block
   is the **Fabric M5 archive release**; PK keeps `v0.51.0-rc1`
   (the M3 release) as its published artefact. No
   `PAIR_RECEIPT.json` change is required from PK.

6. **The audit explicitly identifies what Fabric M5 needs from
   PK.** §E's cross-walk names every Fabric UAT-AR-001..009 and
   AAT-23..30 case the M5 archive needs and pins the existing M3
   PK port that satisfies it. No Fabric case requires a new PK
   surface; the audit's verdict is "use `output.read.digested.v1`
   + `output.pin.v1` + `output.refusal.retention.v1` (all
   EXPERIMENTAL on `v0.51.0-rc1`); the M5 deliverable is the
   Fabric-side release".

7. **One cell may need updating.** The
   `coordination/INTERFACE_CONTRACT.md` "Capacidades publicadas
   (CRIC-M3)" table already lists `output.read.digested.v1`,
   `output.pin.v1`, and `output.refusal.retention.v1` as
   `EXPERIMENTAL` on `v0.51.0-rc1`. The M5 audit does NOT
   propose any new capability ID or contract change. If the
   release-receipt promotion (EXPERIMENTAL → PUBLICADA) happens
   during the M5 cycle (driven by the certifier), the existing
   table cells are refreshed; otherwise the cells stay as the M3
   release left them. No PK-side work is implied.

8. **The audit's negative-scope discipline is preserved.** Per
   the Block 5 plan, "Tests: reinicio, pin persistente,
   confirmación durable, liberación, corrupción y retención bajo
   concurrencia". All six test scenarios are already covered by
   the M3 contract test surface (§D's I.1–I.6 table lists the
   exact test IDs). The audit explicitly does NOT propose new
   contract tests for M5 — the M3 tests already pin the M5
   requirements.

## G. Open issues for the orchestrator

None that block the audit. The following items are flagged for the
release-harness / Fabric-side M5 work; they are not audit failures
and the audit does NOT propose PK-side work for any of them:

1. **The M5 design must be the Fabric-side archive release.** The
   PK side of the M5 deliverable is "no change"; the Fabric side
   of the M5 deliverable is "LogArchive + LocalArchiveBackend +
   S3CompatibleArchiveBackend + SegmentCatalogPort +
   RetentionPinPort wraps PK's OutputPinPort". This is a
   Fabric-side decision documented in
   `Rubentxu/pipelinek-fabric/docs/fabric-coordinated-evolution/specifications/SPEC-05-ARCHIVE-SEAM.md`
   and out of scope for the PK audit. The PK audit confirms the
   PK surface is sufficient.

2. **The M5 design must confirm the pin-idempotency contract.**
   SPEC-05 line 21 says "Pin release requiere ACK persistido
   remoto". The PK side provides `PinReleaseOutcome` (M3,
   `OutputPin.kt:136-149`); the Fabric-side wrapper must call
   `release(pinId)` only after persisting the archive ACK. The
   audit pins this as a Fabric-side composition rule, not a PK
   surface change.

3. **The M5 design must decide whether the Fabric-side
   `RetentionPinPort` is a 1:1 wrapper around PK's
   `OutputPinPort` or a higher-level facade that adds
   archive-specific semantics (e.g. pin-on-PUT, release-on-CAS,
   pin-on-read).** The audit's recommendation is a thin wrapper
   that exposes PK's port verbatim plus a Fabric-side lifecycle
   policy; the audit does NOT propose a PK-side shape change.

4. **The M5 design must name the contract-test fingerprint.** Per
   the release-receipt v2 model
   (`docs/pipelinek-release-evolution/shared/02-release-model-v2.md`)
   the contract change authority is the `release-receipt` of the
   candidate. Since M5 makes no PK surface change, no
   `release-receipt` is required from PK. The audit recommends
   the Fabric side emit its own `release-receipt` for the M5
   candidate; PK is unchanged.

5. **The M5 work's "node" in the CRIC chain is the next Fabric
   candidate after `v0.51.0-rc1`.** The audit does not assign a
   PK version number; the harness chooses the train. The M5
   deliverable on the Fabric side is the next Fabric candidate;
   the PK side carries the existing `v0.51.0-rc1` published
   artefact.

6. **The audit pins the "pin survives recover" rule as
   deliberately enforced.** `RuntimeRecoverPortStoreAdapter.pinPort`
   consult (`RecoverAdapters.kt:221-285`) returns
   `FailClosed(PinnedBytesOutsideRecoveredRegion(stream, range,
   pins))` for any active pin. Expired pins are filtered
   (lines 264-275) and do NOT fail-closed; a recover that runs
   after `expiresAtMs` proceeds normally. The Fabric-side M5
   design MUST honour this rule: if a Fabric archive's pin has
   expired, the recover is allowed to proceed without an
   explicit Fabric-side release call.

7. **The audit pins the "pin does not block readers" rule as a
   deliberate non-feature.** `OutputPinPort.pin` does NOT
   consult the lease store (M3 design §5.5 explicit decision:
   "A pin's `holder` is a Fabric-side identifier, not a
   writer-side identifier. A pin is a retention hold; the lease
   decides who may WRITE, not who may HOLD"). The M3 design
   confirms this is the audit's call too: pins do NOT take a
   lease; pins do NOT consult `RunExecutionLease.acquire`. The
   audit does NOT propose adding a lease consult to pin.

8. **The audit pins the "pin list is deterministic" rule.** A
   `pinsOf(stream)` call returns pins in `pinId.value`
   lexicographic order (`OutputPinStore.kt:91, 108`); the
   contract test `PinListDeterministicOrderTest` (M3 design
   §10.3) pins the order. A Fabric-side M5 design that
   iterates `pinsOf` MUST rely on this order; the audit does
   NOT propose a stronger ordering (e.g. by creation time) that
   the M3 port does not provide.

9. **The audit pins the "pin holder is a String, not a typed
   identity" rule (M3 design §13.2).** Mirrors the existing
   `RunOwnerId` discipline (a value class wrapping a non-blank
   `String`, exposed at the published API as a plain `String`
   for cross-JVM interop; see
   `v2/pipeline-events-store/src/main/kotlin/dev/rubentxu/pipeline/v2/events/durable/RunExecutionLease.kt:52-67`).
   The Fabric-side M5 design MUST pass a `holder: String` that
   is the replica's identifier (e.g. `fabric-replica-<uuid>`)
   on `pin(...)`. The audit does NOT propose a typed
   `PinHolder(value)`.

10. **The audit pins the "pin does not introduce a second
    recover authority" rule.** The M3 addition to
    `RuntimeRecoverPort` is ONE sealed case on the existing M2
    `RecoverRefusal` (`PinnedBytesOutsideRecoveredRegion`),
    NOT a parallel recover verb. The Fabric-side M5 design MUST
    compose `RuntimeRecoverPort.recover` (the public verb) and
    inspect the resulting `FailClosed` for the new case; the
    audit does NOT propose a second recover verb.

---

**End of audit.** The M3 surface (`v0.51.0-rc1`) already covers
Fabric's M5 archive needs. No PK candidate is needed for Block 5;
the M5 deliverable is the Fabric-side archive release. PK keeps
`v0.51.0-rc1` as its published artefact.