# CRIC-M3 — RANGE / RETENTION design (closes B.1–B.8)

**Status:** PROPOSED. This is the design that consumes the M3 audit
(`docs/pipelinek-coordinated-evolution/m3-design/M3_RANGE_RETENTION_AUDIT.md`,
commit `dab35001`, 1214 lines) and produces the contract the next block
(`M3-Impl`) implements against.

**Worktree:** `pk-cric-m3` (branch `audit/cric-m3-range-retention`) at
`dab35001`, based on `4dce18e3` of `release/cric-m2-v0.50.0-rc1`. The
audit branch is remote-only; this design lands on `main` as a docs-only
commit. **Read-only:** this document introduces no code change, no new
authority, and no new publication surface — it formalises the ADT
shapes and composition rules the next block will write code against.

**Anchor:** the Block 3 plan (*"Cerrar las garantías genéricas necesarias
para la replicación de Fabric: offsets byte, lectura exacta de rangos,
frames, secuencia estable, refusals, lector concurrente, recuperación sin
escritura destructiva y retención de bytes bajo garantías activas … No
implementar ACK remoto, scheduler de replicación, S3, gRPC o spools
distribuidos dentro del core de PipelineK"*) and the normative text in
`coordination/INTERFACE_CONTRACT.md` §2 (Plano de salida), §8
(Replicación), and §12 (Retención).

## 0. Re-use, do not duplicate

The audit (§A.1–A.7) confirms that **every primitive the M3 contract
needs already exists**, except three families that the audit names and
this design formalises. The rule this design sharpens:

| M3 requirement                                | Existing primitive (re-use, no new authority) | What changes for M3 |
|---|---|---|
| Byte-range read by exact `[from, to)`        | `OutputReadPort.readRange` (`v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputReadPort.kt:43`); `OutputPage` (`OutputCursor.kt:121-157`); `OutputRefusal.OffsetBeyondCommitted` (`OutputRefusal.kt:37-40`) | **NEW additive method** `OutputReadPort.readRangeDigested(stream, from, to): OutputReadDigestedResult` that surfaces a SHA-256 digest on the existing page. The byte path is unchanged. |
| Frame sequence stability + recovery          | `OutputFrameIndex.declareStream / append / framesOfRun / recoverUnframedBytes` (`OutputFrameIndex.kt:107-200`); `OutputFollower` (`pipeline-output/.../output/follow/`) | **No code change.** The audit's §D.2 verdict is PASS-by-design; the M3 design pins this in §2 ("no code touches the frame index in M3"). |
| Concurrent observer independence             | `OutputCursor` as a value class (`OutputCursor.kt:57-105`); pull-by-call `OutputReadPort` | **No code change.** I.3 PASS-by-design. |
| No destructive recover                       | `RuntimeRecoverPort.recover` (M2 design §5); `OutputRecoveryPort.recover` (`pipeline-output-store/.../store/OutputWritePorts.kt:166-176`); `RecoveredExecutionMaterializer` (`pipeline-application/.../durable/`) | **NEW sealed case** `RecoverRefusal.PinnedBytesOutsideRecoveredRegion` on the M2 `RecoverRefusal` ADT (additive) — a recover on a run with pinned ranges MUST refuse unless the consumer unpins. |
| Retention honouring pin                       | `OutputRetentionPort.prune(intent)` (`OutputRetention.kt:149-162`); `OutputPruneIntent` (`OutputRetention.kt:110-125`); `RetainUntil` (`OutputRetention.kt:74-102`) | **NEW sibling port** `OutputPinPort` in `:pipeline-output` plus a new consult-before-act method `OutputRetentionPort.canPrune(intent): PruneAuthorisation`. The existing `prune` is preserved (additive). |
| Typed refusal for retention / corruption     | `OutputRefusal` (`OutputRefusal.kt:14-99`) | **NEW additive sealed cases** `RetentionGap`, `Corrupt`, `Unavailable`, `RangeLostRetention` on the existing ADT (M1 design §3 precedent: `StreamLostRetention`, `FollowCancelled`, `StorageError` were added as additive cases in M1-B). |
| Content fingerprint on bytes                 | `Fingerprint` (SHA-256 hex of operation input, used by `OperationJournal.beginOperation` at `pipeline-events-store/.../events/durable/OperationJournal.kt:117-123`) | **NEW value class** `OutputDigest` in `:pipeline-output` mirroring the `Fingerprint` shape but computed over committed byte ranges. No new hash algorithm. |
| Pure read / inspect composability            | `OutputRetentionPort`, `OutputTailPort.tailState`, `OutputFrameIndex.streamsOfRun`, `RunExecutionLease.acquire` (pure decider) | **NEW consult port** `OutputPinPort.isPinned(stream, offset): Boolean` and `pinsOf(stream, range?)` — read-only twins, no write authority. |

The audit identified **8 gaps** (B.1–B.8); this design closes every
one of them with additive changes only. No existing port is replaced;
no existing authority is moved; no new fence, lease, scheduler, or
hash algorithm is introduced.

## 1. Context and recap

The M3 audit (`dab35001`) surveyed 30 ports across 6 modules and found
that **the M3 contract surface Fabric needs is missing three
authorities**: a digest on the read path (§B.1), three sealed refusal
cases (`RetentionGap`, `Corrupt`, `Unavailable`) that the contract §2
names verbatim (§B.2), and a retention-pin primitive that the contract
§12 invariant ("no podar rangos hasta probar que su garantía activa se
transfirió a una copia durable, y que no existe pin") requires (§B.3).
Five corollary gaps — `DanglingCommit` on the read side (§B.4),
`StreamLostRetention` on the read side (§B.5), a closed read-side
refusal shape (§B.6), a content-fingerprint sibling for bytes
(§B.7), and a prune-side refusal shape (§B.8) — close the surface
around those three. The user's Block 3 plan approves M3 and limits it
to "offsets byte, lectura exacta de rangos, frames, secuencia estable,
refusals, lector concurrente, recuperación sin escritura destructiva y
retención de bytes bajo garantías activas" — **no ACK remoto, no
scheduler, no S3, no gRPC, no spools distribuidos** in PK. This design
honours that scope.

Three load-bearing gaps in scope (audit §B.1, §B.2, §B.3):

- **B.1** — `OutputReadPort.readRange` returns `OutputPage` with no
  content hash. The contract §8 idempotency key
  `(tenant, run, plane, start, endExclusive, digest, epoch)` cannot be
  computed.
- **B.2** — `OutputRefusal` collapses three named failures
  (`Unavailable`, `RetentionGap`, `Corrupt`) into the catch-all
  `OutputRefusal.StorageError(cause: String)`. A consumer cannot
  pattern-match.
- **B.3** — `OutputRetentionPort.prune` has no consult-before-act and
  `OutputPruneIntent` has no `Pin` case. The contract §12 retention-
  under-pin invariant is unmeetable.

Five corollary gaps (audit §B.4–§B.8):

- **B.4** — `DanglingCommit` is write-side only; the read-side
  analogue is missing.
- **B.5** — `StreamLostRetention` is follow-side only; the read-side
  analogue is missing.
- **B.6** — the read path has no closed outcome that pairs the page
  with a digest AND a typed refusal.
- **B.7** — the journal's `Fingerprint` (SHA-256 of operation input)
  has no output-plane sibling.
- **B.8** — `OutputRetentionPort.prune` has no refusal shape; a
  consumer that wants to pre-flight a release has no typed answer.

Per-invariant verdict the audit pinned (audit §D): I.1 PARTIAL,
I.2 PASS, I.3 PASS, I.4 PARTIAL, I.5 UNVERIFIED, I.6 UNVERIFIED. The
M3 deliverable's job is to close I.4 PARTIAL, I.5 UNVERIFIED, and
I.6 UNVERIFIED with the contract tests this design names in §10.

## 2. Naming and module placement

This design places every new public type in `:pipeline-output` (the
published module, `maven-publish` enabled at
`v2/pipeline-output/build.gradle.kts:54-66`). The implementation lives
in `:pipeline-output-store` (non-published; same split the M1 and M2
designs use). This matches the audit's §G.1 recommendation and the M1
precedent for `OutputReadPort` / `OutputFrameIndex` /
`OutputRetentionPort`.

The audit's §G.3 recommendation — additive sealed cases on the
existing `OutputRefusal`, NOT a parallel `OutputReadM3Refusal` — is
adopted (M1 design §3 precedent: `StreamLostRetention`, `FollowCancelled`,
`StorageError` were additive).

The audit's §G.4 digest-algorithm recommendation — SHA-256 default,
configurable baseline — is adopted. The pin holder is `String` (audit
§G.2), the `expiresAtMs` is optional per pin (audit §G.5), and the
default limit is `1024` per `(stream, runId)` (audit §G.6).

### 2.1 Module placement table

| Public type                       | Module                | File                                                                                    | Status     |
|-----------------------------------|-----------------------|-----------------------------------------------------------------------------------------|------------|
| `OutputRefusal.RetentionGap`      | `:pipeline-output`    | `v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputRefusal.kt`   | NEW case   |
| `OutputRefusal.Corrupt`           | `:pipeline-output`    | same                                                                                    | NEW case   |
| `OutputRefusal.Unavailable`       | `:pipeline-output`    | same                                                                                    | NEW case   |
| `OutputRefusal.RangeLostRetention`| `:pipeline-output`    | same                                                                                    | NEW case   |
| `OutputDigest`                    | `:pipeline-output`    | NEW file `OutputDigest.kt`                                                              | NEW type   |
| `OutputReadDigestedResult`        | `:pipeline-output`    | NEW file `OutputReadDigestedResult.kt`                                                  | NEW type   |
| `OutputReadPort.readRangeDigested`| `:pipeline-output`    | `v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputReadPort.kt`  | NEW method |
| `OutputPinPort`                   | `:pipeline-output`    | NEW file `OutputPinPort.kt`                                                             | NEW port   |
| `OutputPin` / `OutputPinId`       | `:pipeline-output`    | NEW file `OutputPin.kt`                                                                 | NEW type   |
| `OutputPinResult` / `PinRefusal`  | `:pipeline-output`    | NEW file `OutputPin.kt`                                                                 | NEW type   |
| `PinReleaseOutcome`               | `:pipeline-output`    | NEW file `OutputPin.kt`                                                                 | NEW type   |
| `PruneAuthorisation`              | `:pipeline-output`    | NEW file `PruneAuthorisation.kt`                                                        | NEW type   |
| `PruneOutcome` / `PruneRefusal`   | `:pipeline-output`    | NEW file `PruneOutcome.kt`                                                              | NEW type   |
| `OutputRetentionPort.canPrune`    | `:pipeline-output`    | `v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputRetention.kt` | NEW method |
| `RecoverRefusal.PinnedBytesOutsideRecoveredRegion` | `:pipeline-runtime` | M2 file `v2/pipeline-runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/runtime/recover/RecoverRefusal.kt` | NEW case (M2 surface, M3 addition) |
| `Capabilities.OUTPUT_READ_DIGESTED_V1` / `OUTPUT_PIN_V1` / `OUTPUT_REFUSAL_RETENTION_V1` | `:pipeline-output` | NEW `Capabilities.kt` object in `:pipeline-output` | NEW IDs   |

The new `:pipeline-output/Capabilities.kt` mirrors the
`:pipeline-runtime/Capabilities.kt` object from M2 (M2 design §2.2)
and the M1 module convention. Each capability is a static string;
consumers negotiate by inclusion in the artifact's metadata; absence
requires fallback or refusal (contract §6).

### 2.2 Companion constants (design pins)

| Constant | Value | Where it lives | Purpose |
|---|---|---|---|
| `OutputPinPort.DEFAULT_MAX_PINS_PER_STREAM` | `1024` | `OutputPinPort.kt` | Default cap on the number of active pins per `(stream, runId)` (audit §G.6). Configurable baseline, NOT a contract surface. |
| `OutputDigest.DEFAULT_ALGORITHM` | `"SHA-256"` | `OutputDigest.kt` | Hash algorithm used by `OutputDigest.compute(stream, from, to)` (audit §G.4). A future SHA-3-256 may ship without an ABI break. |
| `OutputPin.PIN_REASON_MAX_LEN` | `256` chars | `OutputPin.kt` | Bounded length of the diagnostic `reason` string. |
| `OutputPin.PIN_HOLDER_MAX_LEN` | `256` chars | `OutputPin.kt` | Bounded length of the `holder` string (mirrors `RunOwnerId` discipline; no typed identity at the public surface — audit §G.2). |
| `PruneRefusal.REFUSAL_REASON_MAX_LEN` | `256` chars | `PruneOutcome.kt` | Bounded length of the `cause` string. |
| `OutputRetentionPort.DEFAULT_CAN_PRUNE_TIMEOUT_MS` | `5_000L` | `OutputRetention.kt` | Upper bound on a `canPrune` call's wall time (parallel to M2 `DEFAULT_INSPECT_TIMEOUT_MS` at M2 design §2.3). |

These constants are implementation defaults, not contract surfaces; they
document the baseline the implementation MUST satisfy and the UAT
matrix MUST verify.

## 3. B.1 — Digest on the read path

### 3.1 Decision: additive method, sibling result type

The audit §B.1 proposes two shapes:

- (a) A new sibling port `OutputReadDigestedResult` sealed type.
- (b) An additive `digest: ByteString` field on the existing `OutputPage`.

This design picks **(a)** — a new sibling result type returned by a
new additive method `OutputReadPort.readRangeDigested` — for three
reasons:

- **Closed ADT discipline.** Adding a field to a published data class
  is technically additive in source but it changes the data class's
  shape (a new constructor parameter), which is a quiet ABI extension
  for any consumer that pattern-matches on `OutputPage`. A new
  sibling result type is the precedent M1 used
  (`OutputReadResult` is a sealed envelope; `OutputFollowEvent` is
  sealed for the follow port).
- **Refusal pairing.** A read that crosses a pruned range (the
  `RetentionGap` case from §4) must refuse closed, NOT return a
  `Page` with an empty digest. A sibling result type
  (`Page(page, digest) | Refused(reason)`) makes the refusal the
  same shape as the existing `OutputReadResult`.
- **No-cost path.** The existing `readRange` is unchanged; consumers
  that do not need a digest pay nothing for the new surface.

### 3.2 Port signature

```kotlin
// NEW file:
// v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputDigest.kt

package dev.rubentxu.pipeline.v2.output

/**
 * M3 — a deterministic content hash of the committed bytes in `[from, to)`
 * of a stream.
 *
 * ## Algorithm
 *
 * The default is [DEFAULT_ALGORITHM] ("SHA-256"), the same algorithm
 * the journal's [dev.rubentxu.pipeline.v2.domain.durable.Fingerprint]
 * uses (OperationJournal.beginOperation:117-123). The implementation MAY
 * accept a different algorithm via a constructor knob; the value type
 * itself does NOT carry the algorithm name (the hash is opaque at the
 * wire), and a future hardened SHA-3-256 may ship without an ABI break.
 *
 * ## Idempotency
 *
 * `OutputDigest` is a deterministic function of `(stream, from, to)`:
 * for any two calls that name the same `(from, to)` range of the same
 * committed bytes, the digest is identical. The contract test
 * `DigestDeterminismTest` (§10) pins this.
 *
 * @property bytes the raw digest bytes (length depends on the algorithm;
 *                 SHA-256 yields 32 bytes)
 */
@JvmInline
value class OutputDigest(val bytes: ByteArray) {
    init {
        require(bytes.isNotEmpty()) { "OutputDigest bytes must not be empty" }
    }

    /** Hex encoding (lowercase). Stable for wire use. */
    fun hex(): String = bytes.joinToString("") { "%02x".format(it) }

    override fun toString(): String = "OutputDigest(${hex().take(16)}…)"

    override fun equals(other: Any?): Boolean =
        this === other || (other is OutputDigest && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        /** Default digest algorithm. Matches the journal's `Fingerprint`. */
        const val DEFAULT_ALGORITHM: String = "SHA-256"
    }
}
```

### 3.3 New read method on `OutputReadPort`

```kotlin
// v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputReadPort.kt
//
// ADDITIVE — the existing interface (lines 19-44) is preserved. The new
// method has a default body that delegates to readRange + compute, so a
// custom OutputReadPort may override either or both.

interface OutputReadPort {
    // ... existing methods unchanged ...

    /**
     * M3 — read exactly the committed bytes in `[from, to)` of [stream]
     * AND a deterministic content hash of those bytes.
     *
     * Contractual properties:
     *
     *  - `readRangeDigested(s, a, b).digest` is a deterministic function
     *    of the committed bytes in `[a, b)` — same bytes, same digest;
     *    different bytes, different digest.
     *  - `readRangeDigested(s, a, b).page.bytes` equals the bytes
     *    returned by `readRange(s, a, b)` for the same range (no
     *    redactions, no re-encodings, no padding).
     *  - A read that crosses a pruned range, or that lands in a
     *    corrupted region, refuses closed with the sealed cases from
     *    §4 (`RetentionGap`, `Corrupt`, `Unavailable`, `RangeLostRetention`).
     *    A read NEVER returns a Page with an empty digest.
     *
     * @param stream the stream to read
     * @param from   inclusive start offset
     * @param to     exclusive end offset; `to > from`
     */
    fun readRangeDigested(
        stream: OutputStreamId,
        from: Long,
        to: Long,
    ): OutputReadDigestedResult {
        // Default implementation composes readRange + OutputDigest.compute.
        // The implementation in :pipeline-output-store overrides both for
        // performance (single pass + digest), but the contract is the same.
        val result = readRange(stream, from, to)
        return when (result) {
            is OutputReadResult.Page -> OutputReadDigestedResult.Digested(
                page = result.page,
                digest = OutputDigest.compute(this, stream, from, to),
            )
            is OutputReadResult.Refused -> OutputReadDigestedResult.Refused(
                reason = result.reason,
            )
        }
    }
}
```

### 3.4 The sibling result type

```kotlin
// NEW file:
// v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputReadDigestedResult.kt

package dev.rubentxu.pipeline.v2.output

/**
 * M3 — the result of [OutputReadPort.readRangeDigested].
 *
 * Closed ADT: a read either returns a digested page OR a typed refusal.
 * The refusal case reuses [OutputRefusal] (no new parallel hierarchy).
 *
 * The two-case envelope mirrors the M1 [OutputReadResult] shape: a
 * closed sealed interface with a page case and a refusal case. The
 * `Digested` case is additive on top of the existing `OutputReadResult.Page`
 * — it carries the same [OutputPage] AND the digest of its bytes.
 */
sealed interface OutputReadDigestedResult {

    /**
     * The bytes were read; [digest] is the SHA-256 (or whatever
     * [OutputDigest.DEFAULT_ALGORITHM] names) of the committed bytes
     * in `[from, from + page.bytes.size)`.
     */
    data class Digested(
        val page: OutputPage,
        val digest: OutputDigest,
    ) : OutputReadDigestedResult

    /**
     * The read could not be served; [reason] is a closed [OutputRefusal]
     * case (the cases from §4 — `RetentionGap`, `Corrupt`, `Unavailable`,
     * `RangeLostRetention` — are the M3 additions).
     */
    data class Refused(val reason: OutputRefusal) : OutputReadDigestedResult
}
```

### 3.5 Idempotency key (formalised)

The audit §B.1 names the key
`(tenant, run, plane, start, endExclusive, digest, epoch)`. The design
formalises each field:

| Field | Type | Meaning |
|---|---|---|
| `tenant` | `String` | The tenant id; the same value Fabric carries in its ACL (contract §11). |
| `run` | `String` | The run id; the unit of restartability. |
| `plane` | `OutputChannel` (`STDOUT` / `STDERR`) | The channel; mirrors `OutputChannel` (`v2/pipeline-output/.../output/OutputChannel.kt:23-42`). |
| `start` | `Long` | Inclusive start offset (mirrors `readRange`'s `from`). |
| `endExclusive` | `Long` | Exclusive end offset (mirrors `readRange`'s `to`). |
| `digest` | `OutputDigest` | The SHA-256 of the committed bytes in `[start, endExclusive)`. |
| `epoch` | `Long` | The fencing token of the run's current publishing authority (`FencingToken.value`), per `RunExecutionLease.acquire` (`pipeline-events-store/.../events/durable/RunExecutionLease.kt:230-267`). |

A consumer computes the key by calling
`readRangeDigested(stream, start, endExclusive)` and combining the
result with the run's current epoch (from `RuntimeIntrospectionPort.inspect`).
The contract test `DigestIdempotencyKeyTest` (§10) pins the round-trip.

### 3.6 Coverage

The audit §B.1 asks "all reads? just `readRange`? just frame-bounded
reads?". This design's rule:

- **Byte-range reads (`readRange`)** — always carry a digest via the
  new `readRangeDigested` method. The existing `readRange` is
  preserved unchanged.
- **Cursor reads (`read`)** — unchanged. The audit pins this: a
  paged cursor read is bounded by a consumer-supplied budget and a
  digest across a partial page has lower replicability value than a
  digest across an explicit `[from, to)` range. A consumer that wants
  digests on cursor reads paginates first and calls
  `readRangeDigested` on the assembled range.
- **Frame reads (`framesOfRun`)** — unchanged. Frames are metadata;
  the contract test `SegmentOutputFollowerTest` already pins the
  separation (audit §A.6).

### 3.7 Refusal translation

A read that cannot produce a digest refuses closed, NOT with a
silent `Page` and an empty digest:

- The range crosses a pruned boundary → `OutputRefusal.RetentionGap`
  (§4).
- A row or a frame is unparseable → `OutputRefusal.Corrupt` (§4).
- The store cannot answer right now → `OutputRefusal.Unavailable` (§4).
- The range crosses bytes that were lost to retention (the read-side
  analogue of the M1-B follow-side case) → `OutputRefusal.RangeLostRetention`
  (§4).

The contract test `DigestRefusalTranslation` (§10) pins every cell.

## 4. B.2 — Sealed refusal cases on `OutputRefusal`

The audit §B.2 names three new cases verbatim. The design adopts them
**additive on the existing sealed ADT** (M1 design §3 precedent; audit
§G.3 recommendation), not on a parallel hierarchy.

### 4.1 The three new cases

```kotlin
// v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputRefusal.kt
//
// ADDITIVE — the existing sealed interface (lines 14-99) is preserved.
// The three new cases below close the M3 contract §2 surface.

sealed interface OutputRefusal {

    // ... existing cases preserved: ForeignStream, UnknownStream,
    //     OffsetBeyondCommitted, InvalidRange, RecoveryNotCompleted,
    //     DanglingCommit, StreamLostRetention, FollowCancelled,
    //     StorageError ...

    /**
     * M3 — the read crosses a range that has been pruned by
     * [dev.rubentxu.pipeline.v2.output.OutputRetentionPort.prune].
     *
     * Distinct from [OffsetBeyondCommitted]: that case says "the
     * cursor is past the committed extent, re-anchor". This case
     * says "the bytes you named WERE committed and have since been
     * GC'd". The consumer must escalate (re-fetch from a replica
     * whose pin survived, or fail the read).
     *
     * Surfaced by [OutputReadPort.readRange] and
     * [OutputReadPort.readRangeDigested] when the named range's
     * start or end falls past the stream's pinned-and-unpinned
     * retained extent. The [lastCommitted] is the offset of the last
     * byte that was actually retained at the time of the read; the
     * gap is the region `[lastCommitted, requestedRange.last]` for
     * which no bytes are available.
     *
     * @property stream the stream whose retention gap the read crossed
     * @property requestedRange the half-open range the read asked for
     * @property lastCommitted the last retained byte before the gap;
     *                          the read can resume from
     *                          `lastCommitted + 1` if the consumer
     *                          chooses to re-anchor
     */
    data class RetentionGap(
        val stream: OutputStreamId,
        val requestedRange: LongRange,
        val lastCommitted: Long,
    ) : OutputRefusal

    /**
     * M3 — the bytes in [requestedRange] are unparseable: a row exists
     * but its payload does not back it.
     *
     * Distinct from [DanglingCommit]: that case carries the same fact
     * at the *end-of-stream* granularity (the write-side analogue —
     * [OutputRefusal.kt:56-59]); this case carries it at
     * finer, *per-range* granularity on the read side. A consumer
     * that hits `Corrupt` knows exactly which range is unparseable;
     * a consumer that hits `DanglingCommit` only knows the
     * end-of-stream boundary.
     *
     * Distinct from [StorageError]: that case is a transient I/O
     * fault; this case is a *durable* unparseability. The store can
     * retry `StorageError`; `Corrupt` does not get better.
     *
     * @property stream the stream whose bytes are unparseable
     * @property requestedRange the half-open range the read asked for
     * @property reason a bounded diagnostic; the implementation MUST
     *                   NOT include byte data in the reason (a digest
     *                   of bytes does not survive the constructor)
     */
    data class Corrupt(
        val stream: OutputStreamId,
        val requestedRange: LongRange,
        val reason: String,
    ) : OutputRefusal {
        init {
            require(reason.length <= OutputRefusal.CORRUPT_REASON_MAX_LEN) {
                "Corrupt.reason must be <= ${OutputRefusal.CORRUPT_REASON_MAX_LEN} chars, got ${reason.length}"
            }
            require('\n' !in reason) { "Corrupt.reason must not contain newlines" }
        }
    }

    /**
     * M3 — the store cannot answer right now.
     *
     * Distinct from [StorageError]: that case is a transient I/O
     * fault (the read path's `IOException`); this case is a typed
     * declaration that the substrate is *unreachable*. The
     * distinction matters for retry policy: a consumer retries
     * `StorageError` with backoff; a consumer that hits
     * `Unavailable` waits for a wakeup or escalates.
     */
    data object Unavailable : OutputRefusal

    /**
     * M3 — the read-side analogue of the M1-B follow-side
     * [StreamLostRetention].
     *
     * Surfaced by [OutputReadPort.readRange] and
     * [OutputReadPort.readRangeDigested] when a range the read
     * named has been lost to retention (NOT a follow's tail —
     * [StreamLostRetention] is the follow-side case).
     *
     * The two cases are deliberately distinct types so a consumer
     * can switch on them without parsing free-text reasons. The
     * follow-side case means "I was tailing and the tail is gone";
     * this case means "I asked for a range and that range is gone".
     *
     * @property stream the stream whose retention was lost
     * @property requestedRange the half-open range the read asked for
     * @property lastCommitted the last retained byte before the gap;
     *                          mirrors [RetentionGap.lastCommitted]
     */
    data class RangeLostRetention(
        val stream: OutputStreamId,
        val requestedRange: LongRange,
        val lastCommitted: Long,
    ) : OutputRefusal

    companion object {
        /** Bounded length of [Corrupt.reason]. */
        const val CORRUPT_REASON_MAX_LEN: Int = 256
    }
}
```

### 4.2 Distinction table

The new cases are NOT redundant with the existing ones. The audit
§B.2 + §B.5 + §B.6 require the table:

| Failure mode                              | Existing case                  | New M3 case                              | Why distinct                                |
|-------------------------------------------|--------------------------------|------------------------------------------|---------------------------------------------|
| Cursor past committed extent              | `OffsetBeyondCommitted`        | `RetentionGap`                           | "re-anchor" vs "the bytes were GC'd"        |
| Bytes were committed but payload missing  | `DanglingCommit` (write-side)  | `Corrupt` (read-side, finer granularity) | end-of-stream vs per-range                  |
| Storage I/O fault (transient)                | `StorageError`                 | `Unavailable`                            | transient vs unreachable                    |
| Follow's tail lost retention              | `StreamLostRetention`          | `RangeLostRetention`                     | follow-side vs read-side                    |
| Cursor on wrong stream                    | `ForeignStream`                | (no change)                              | pre-existing                                |
| Stream unknown                            | `UnknownStream`                | (no change)                              | pre-existing                                |
| Range shape invalid                       | `InvalidRange`                 | (no change)                              | pre-existing                                |
| Store not yet recovered                   | `RecoveryNotCompleted`         | (no change)                              | pre-existing                                |
| Follow closed                             | `FollowCancelled`              | (no change)                              | pre-existing                                |

### 4.3 Closed ADT discipline (audit §B.2 (d))

The M1-B precedent test surface is `MainConsoleCli.renderRefusal`
(`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/MainConsoleCli.kt:252-279`).
A sealed ADT extension is a compile error at every `when` site. The
M3 design's contract test (`RenderRefusalCompilationTest`, §10)
asserts:

- The existing `MainConsoleCli.renderRefusal` recompiles after the
  M3 changes WITHOUT modifying its source — i.e. every new case has
  a matching branch added by the M3-Impl block, NOT a parallel
  `when (reason is X) { … } else -> "console-refused: unknown"` fallthrough.
- The new branches render stable, script-branchable text
  (`"console-refused: retention-gap"`, `"console-refused: corrupt"`,
  `"console-refused: unavailable"`, `"console-refused: range-lost-retention"`).

## 5. B.3 — Retention-pin primitive

### 5.1 Decision: new sibling port `OutputPinPort` in `:pipeline-output`

The audit §B.3 proposes two shapes:

- (a) A new sealed case on `OutputPruneIntent` naming a range and a holder.
- (b) A new sibling port `OutputPinPort` with `pin`, `unpin`, `pinsOf`.

This design picks **(b)** for three reasons:

- **Separation of authority.** A pin is a *retention hold*, not a
  *deletion intent*. Mixing them in one ADT conflates "you may not
  delete" with "you have asked to delete" — exactly the conflation
  `ADR-M1 §D2` forbids.
- **Lifecycle.** A pin has its own lifecycle (create, release,
  expire, list, consult) that does not fit the `OutputPruneIntent`
  shape (which is one-shot).
- **Mirror the M1 pattern.** `OutputReadPort`, `OutputRetentionPort`,
  `OutputTailPort`, `OutputFrameIndex` are each their own port with
  their own sealed ADTs. M3 keeps that segregation.

### 5.2 The port

```kotlin
// NEW file:
// v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputPinPort.kt

package dev.rubentxu.pipeline.v2.output

/**
 * M3 — public retention-pin port: a pin attaches to a `(stream, range)`
 * and survives retention prunes until released or expired.
 *
 * ## What a pin is and is NOT
 *
 * A pin is a **retention hold**: "these bytes may not be GC'd until
 * the pin is released". It is NOT a lease: a lease fences a writer
 * (only one publisher per run); a pin holds bytes (no consumer of
 * the bytes may have them GC'd while the pin is active). The two
 * authorities are deliberately separate (audit §B.3 (f), §B.4 (f))
 * and live in different stores (this port's implementation in
 * `:pipeline-output-store`; the lease store in
 * `:pipeline-events-store/.../events/durable/FileBackedRunExecutionLeaseStore.kt`).
 *
 * ## Lifecycle
 *
 * ```text
 * pin()   -> Pinned(pinId)        OR Refused(PinRefusal)
 * release(pinId) -> Released      OR AlreadyReleased OR UnknownPin OR Refused(...)
 * list(stream?) -> List<OutputPin>           (deterministic order: pinId lexicographic)
 * isPinned(stream, offset) -> Boolean        (consult-before-act)
 * pinsOf(stream, range?) -> List<OutputPin>  (consult-before-act)
 * ```
 *
 * ## Authority composition
 *
 * - The pin store lives in `:pipeline-output-store` (non-published).
 *   The implementation overrides this port's methods against the
 *   segment store's directory.
 * - A pin does NOT take a lease. A pin's `holder` is a `String`
 *   (audit §G.2; mirrors the existing `RunOwnerId` discipline).
 * - A pin MAY carry an optional `expiresAtMs` (audit §G.5); the
 *   default is "no expiry". An expired pin is RELEASED, not refused.
 *
 * ## Default limit
 *
 * [DEFAULT_MAX_PINS_PER_STREAM] is the cap on the number of active
 * pins per `(stream, runId)`. The contract test
 * `PinLimitEnforcedTest` (§10) pins this.
 *
 * @see M3_RANGE_RETENTION_AUDIT.md §B.3, §G.6.
 */
interface OutputPinPort {

    /**
     * Pin [range] of [stream] against retention pruning. The pin
     * survives any subsequent `OutputRetentionPort.prune(intent)` UNLESS
     * the pin's `holder` explicitly `release`s it OR `expiresAtMs` passes.
     *
     * The pin id is opaque and assigned by the implementation; consumers
     * carry it through `release(pinId)` and `pinsOf(stream)` filters.
     */
    fun pin(
        stream: OutputStreamId,
        range: LongRange,
        holder: String,
        reason: String,
        expiresAtMs: Long? = null,
    ): OutputPinResult

    /**
     * Release the pin identified by [pinId]. Idempotent: a second
     * call returns [PinReleaseOutcome.AlreadyReleased].
     */
    fun release(pinId: OutputPinId): PinReleaseOutcome

    /**
     * Every active pin for [stream], optionally restricted to
     * [range]. The list is ordered by `pinId` lexicographic; the
     * contract test `PinListDeterministicOrderTest` (§10) pins the
     * order.
     */
    fun pinsOf(
        stream: OutputStreamId,
        range: LongRange? = null,
    ): List<OutputPin>

    /**
     * Whether ANY active pin covers [stream]'s byte at [offset].
     * The consult-before-act primitive for `OutputRetentionPort.prune`:
     * a consumer that wants to pre-flight a release calls `isPinned`
     * for every byte in the range it intends to delete.
     *
     * O(1) lookup against the pin index; the implementation MAY
     * index by stream + offset. The contract test
     * `IsPinnedBoundaryTest` (§10) pins the boundary semantics.
     */
    fun isPinned(stream: OutputStreamId, offset: Long): Boolean

    companion object {
        /**
         * Default cap on the number of active pins per `(stream, runId)`.
         * Configurable baseline (audit §G.6). The implementation MAY
         * allow more; it MUST refuse with [PinRefusal.TooManyPins]
         * above this default.
         */
        const val DEFAULT_MAX_PINS_PER_STREAM: Int = 1024
    }
}
```

### 5.3 The pin ADTs

```kotlin
// NEW file:
// v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputPin.kt

package dev.rubentxu.pipeline.v2.output

/**
 * M3 — opaque identifier for an active pin.
 *
 * The id is assigned by [OutputPinPort.pin] and must be carried
 * through `release(pinId)` and `pinsOf(stream)` filters. The
 * implementation chooses the format (UUID v4 is the recommended
 * baseline — collision-resistant, opaque, no PK-internal encoding).
 */
@JvmInline
value class OutputPinId(val value: String) {
    init {
        require(value.isNotBlank()) { "OutputPinId must not be blank" }
    }
    override fun toString(): String = value
}

/**
 * M3 — the durable record of one active pin.
 *
 * @property pinId       the opaque pin id (assigned at pin time)
 * @property stream      the stream whose bytes are held
 * @property range       the half-open byte range held; `range.last >=
 *                       range.first`
 * @property holder      the durable identity that asked for the pin;
 *                       `String` mirrors the existing `RunOwnerId`
 *                       discipline (audit §G.2)
 * @property reason      a bounded diagnostic (PIN_REASON_MAX_LEN chars)
 * @property createdAtMs epoch-ms when the pin was created
 * @property expiresAtMs epoch-ms when the pin expires, or `null` for
 *                       "no expiry" (audit §G.5)
 */
data class OutputPin(
    val pinId: OutputPinId,
    val stream: OutputStreamId,
    val range: LongRange,
    val holder: String,
    val reason: String,
    val createdAtMs: Long,
    val expiresAtMs: Long?,
) {
    init {
        require(range.first >= 0) { "range.first must be non-negative, got ${range.first}" }
        require(range.last >= range.first) {
            "range.last (${range.last}) must be >= range.first (${range.first})"
        }
        require(holder.length <= OutputPin.PIN_HOLDER_MAX_LEN) {
            "OutputPin.holder must be <= ${OutputPin.PIN_HOLDER_MAX_LEN} chars, got ${holder.length}"
        }
        require(reason.length <= OutputPin.PIN_REASON_MAX_LEN) {
            "OutputPin.reason must be <= ${OutputPin.PIN_REASON_MAX_LEN} chars, got ${reason.length}"
        }
        require('\n' !in reason) { "OutputPin.reason must not contain newlines" }
    }

    companion object {
        const val PIN_HOLDER_MAX_LEN: Int = 256
        const val PIN_REASON_MAX_LEN: Int = 256
    }
}

/**
 * M3 — closed result of [OutputPinPort.pin].
 *
 * Two cases: pinned (with the new pin id) OR refused (with a closed
 * [PinRefusal] reason). Adding a new refusal mode is a compile error
 * at every `when` site.
 */
sealed interface OutputPinResult {

    /** The pin was created; [pinId] is the opaque id, [expiresAtMs] is the effective expiry. */
    data class Pinned(
        val pinId: OutputPinId,
        val expiresAtMs: Long?,
    ) : OutputPinResult

    /** The pin was refused; [reason] names the closed cause. */
    data class Refused(val reason: PinRefusal) : OutputPinResult
}

/**
 * M3 — closed ADT of reasons [OutputPinPort.pin] refused.
 *
 * Mirrors the M1 / M2 convention: sealed interface per port, no
 * `Either`/`Result` re-use, every case named after a real failure mode.
 */
sealed interface PinRefusal {

    /** No stream with this id has ever been opened. */
    data class UnknownStream(val stream: OutputStreamId) : PinRefusal

    /**
     * The range extends past the stream's committed extent. The pin
     * store MUST refuse closed rather than clamping.
     */
    data class RangeBeyondCommitted(
        val stream: OutputStreamId,
        val range: LongRange,
        val committed: Long,
    ) : PinRefusal

    /**
     * The cap on the number of active pins per `(stream, runId)` was
     * exceeded. The default cap is
     * [OutputPinPort.DEFAULT_MAX_PINS_PER_STREAM]; a custom cap is
     * permitted at construction.
     */
    data class TooManyPins(
        val stream: OutputStreamId,
        val limit: Int,
        val active: Int,
    ) : PinRefusal

    /** The underlying storage failed; [cause] is a short diagnostic. */
    data class StorageError(val cause: String) : PinRefusal
}

/**
 * M3 — closed result of [OutputPinPort.release].
 *
 * Idempotent: a second call returns [AlreadyReleased] rather than a
 * refusal. Mirrors the M1-F.3 `SealOutcome.AlreadySealed` discipline.
 */
sealed interface PinReleaseOutcome {

    /** The pin was released by this call. */
    data class Released(val pinId: OutputPinId) : PinReleaseOutcome

    /** The pin had already been released (or expired). */
    data class AlreadyReleased(val pinId: OutputPinId) : PinReleaseOutcome

    /** No pin with this id exists. */
    data class UnknownPin(val pinId: OutputPinId) : PinReleaseOutcome

    /** The underlying storage failed; [cause] is a short diagnostic. */
    data class StorageError(val cause: String) : PinReleaseOutcome
}
```

### 5.4 Integration with the existing prune path

The audit §B.3 (a) names "a consult-before-act API on
`OutputRetentionPort`". This design adds the method to the existing
port (additive; existing `prune` is unchanged):

```kotlin
// v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputRetention.kt
//
// ADDITIVE — the existing interface (lines 149-162) is preserved.

interface OutputRetentionPort {
    // ... existing methods unchanged ...

    /**
     * M3 — consult-before-act: would [intent] succeed RIGHT NOW, given
     * the current pins?
     *
     * The returned [PruneAuthorisation] names the pins the call would
     * refuse on (so the caller can `release` them and retry) WITHOUT
     * mutating any state. The companion [prune] performs the deletion
     * and returns what happened; [canPrune] returns the authorisation
     * WITHOUT performing it. The two methods do NOT share state:
     * [canPrune]'s answer is a point-in-time observation; [prune]'s
     * answer is a record of what happened.
     *
     * A second call to [canPrune] may return a different answer if a
     * pin was released between the two calls. The contract test
     * `CanPrunePointInTimeTest` (§10) pins this.
     *
     * @param intent the deletion the caller is contemplating
     * @return a [PruneAuthorisation] — see §8.
     */
    fun canPrune(intent: OutputPruneIntent): PruneAuthorisation {
        // Default implementation composes OutputPinPort.pinsOf +
        // OutputRetentionPort.hasOutputFor. The store-side adapter
        // overrides for performance.
    }

    companion object {
        /**
         * Upper bound on a single `canPrune` call's wall time.
         * Parallel to M2's `DEFAULT_INSPECT_TIMEOUT_MS` (M2 design §2.3).
         */
        const val DEFAULT_CAN_PRUNE_TIMEOUT_MS: Long = 5_000L
    }
}
```

### 5.5 No new authority for ownership / fencing

This design introduces a pin as a **NEW retention authority**, distinct
from the existing lease authority:

| Authority | Lives in                                          | Owned by              | Fenced? |
|-----------|---------------------------------------------------|-----------------------|---------|
| Lease     | `pipeline-events-store/.../FileBackedRunExecutionLeaseStore.kt:40-298` | OS file lock + `FencingToken` | YES |
| Pin       | `pipeline-output-store/.../OutputPinPortStoreAdapter.kt` (NEW)        | Pin id (UUID v4) + range   | NO  |

The split is deliberate (audit §B.3 (f)): pins are NOT a lease. A
pin does not fence a writer; it holds bytes against GC. Conflating
the two would be a category error.

The pin store does NOT consult `RunExecutionLease.acquire` for
authority (audit §B.8). A pin's `holder` is a `String` the consumer
passes; the store records it and uses it for `list` / `pinsOf` filters.
The audit's recommendation ("any new `OutputPinPort.pin(stream, range, holder)` MUST consult the existing `RunExecutionLease.acquire` to confirm authority before it pins") is **rejected** in this design:

- The holder is a Fabric-side identifier (the replica name, the
  holder's `String`), not a writer-side identifier.
- A pin is a retention hold; the lease decides who may WRITE, not
  who may HOLD.
- Cross-cutting the two authorities would put the M3 pin store behind
  the M2 lease store's internal store, which is exactly the
  publication-bypass the audit §A.5 forbids.

The implementation MUST however record `holder` verbatim so a
subsequent `pinsOf(stream)` filter can name the holder.

## 6. B.4, B.5, B.6 — Read-side analogues

### 6.1 B.4 — `DanglingCommit` read-side analogue

The audit §B.4 asks whether a read-side analogue of `DanglingCommit`
is needed. This design's verdict: **the existing `OutputRefusal.DanglingCommit`
case is BROADENED to surface on the read path**, AND the new
`OutputRefusal.Corrupt` (§4) carries the same fact at finer
granularity. No new port; no new case.

The broadening is mechanical: the implementation of
`OutputReadPort.readRange` / `readRangeDigested` checks whether the
range crosses `readableBytes` (the same field the existing
`DanglingCommit` carries) and refuses with `DanglingCommit` if so.
The contract test `DanglingCommitSurfacesOnReadTest` (§10) pins this.

### 6.2 B.5 — `StreamLostRetention` read-side analogue

The audit §B.5 names a new sealed case `RangeLostRetention`. This
design adopts it as a new case on the existing `OutputRefusal` ADT
(§4.1). Distinct from the follow-side `StreamLostRetention` because:

- `StreamLostRetention` is follow-specific
  (`v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputRefusal.kt:71-74`):
  the consumer was tailing a stream and the tail went away.
- `RangeLostRetention` is read-side: the consumer asked for `[a, b)`
  and the bytes are gone.

The two are deliberately different types so a consumer can switch on
them without parsing free-text reasons.

### 6.3 B.6 — read-side refusal shape

The audit §B.6 asks for a closed `ReadOutcome` sealed type that pairs
the page with the digest AND the refusal. This design's answer:

- The `OutputReadResult` shape (existing, M1) is `Page(page) | Refused(reason)`.
- The `OutputReadDigestedResult` shape (§3.4) is `Digested(page, digest) | Refused(reason)`.

The pair IS the closed read-side outcome the audit names; it is
deliberately split into two result types so the existing read path is
preserved. A consumer that wants both bytes and digest calls
`readRangeDigested` and pattern-matches on `Digested` vs `Refused`. A
consumer that wants bytes only calls `readRange` and pattern-matches
on `Page` vs `Refused`. There is no need for a third "ReadOutcome"
sealed type that combines the two, because the two are
consumption-different (the digest is optional, not always needed).

The contract test `DigestedResultClosed` (§10) asserts the sealed
envelope.

## 7. B.7 — Content-fingerprint shape parity

The audit §B.7 asks whether the journal's `Fingerprint` (SHA-256 hex
of operation input) needs an output-plane sibling. This design's
verdict: **YES, a new `OutputDigest` value class in `:pipeline-output`**.

### 7.1 Decision: new `OutputDigest` value class

The audit §B.7 proposes two shapes:

- (a) A `ByteFingerprint` (or `OutputFingerprint`) value class that
  mirrors the journal's `Fingerprint`.
- (b) A note that the digest in §3 IS the fingerprint, no new type
  needed.

This design picks **(a)** for two reasons:

- **Two distinct authorities.** The journal's `Fingerprint` is a
  content hash of the operation INPUT (used by
  `OperationJournal.beginOperation` at `pipeline-events-store/.../events/durable/OperationJournal.kt:117-123`
  for memoization and idempotency). The output digest is a content
  hash of the committed OUTPUT BYTES in `[from, to)` of a stream.
  The two hashes are computed over different inputs by different
  stores and answer different questions. Conflating them would make
  the journal's `Fingerprint` "be" the byte digest when the two
  facts come from different stores.
- **Modular ownership.** The journal lives in
  `:pipeline-events-store` (not published). The output digest lives
  in `:pipeline-output` (published). A value class in `:pipeline-output`
  cannot depend on `:pipeline-events-store` (the M1 module graph
  forbids it — `v2/pipeline-output/build.gradle.kts:24-25`); a value
  class in `:pipeline-events-store` cannot be part of the published
  Output Plane.

The shape (§3.2) is a `@JvmInline value class OutputDigest(val bytes:
ByteArray)` with `hex(): String` and `companion object { const val
DEFAULT_ALGORITHM = "SHA-256" }`. The wire shape (`hex()`) matches
the journal's `Fingerprint.hex` discipline; the in-memory shape is a
raw `ByteArray` for `MessageDigest` digest equality.

## 8. B.8 — Prune-side refusal shape

The audit §B.8 asks for a typed refusal shape on
`OutputRetentionPort.prune`. This design's answer: a new sealed
`PruneOutcome` type paired with the new consult-before-act
`PruneAuthorisation` (§5.4).

### 8.1 The new outcome type

```kotlin
// NEW file:
// v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/PruneOutcome.kt

package dev.rubentxu.pipeline.v2.output

/**
 * M3 — the result of [OutputRetentionPort.canPrune].
 *
 * Closed ADT: a consult either returns a `Granted` / `Consulted` answer
 * OR a `Refused` answer. Mirrors the M1 refusal-extension pattern: a
 * sealed interface per port, no `Either`/`Result` re-use.
 *
 * Distinct from the existing [OutputPruneReport] (which reports what
 * `prune(intent)` actually did); [PruneAuthorisation] is a
 * point-in-time OBSERVATION, not a report on what happened.
 */
sealed interface PruneAuthorisation {

    /**
     * The intent may proceed; no active pin covers the intent's
     * ranges. [prune] is now safe to call.
     */
    data object Granted : PruneAuthorisation

    /**
     * Active pins cover part of the intent's ranges; the consumer
     * may decide to release them and retry. The pins are named by
     * id so the consumer can act on them.
     */
    data class Consulted(
        val stream: OutputStreamId,
        val range: LongRange,
        val pinsAtConsult: List<OutputPin>,
    ) : PruneAuthorisation

    /**
     * The consult was refused; [reason] names the closed cause.
     */
    data class Refused(val reason: PruneRefusal) : PruneAuthorisation
}

/**
 * M3 — closed ADT of reasons [OutputRetentionPort.canPrune] or
 * [OutputRetentionPort.prune] refused.
 *
 * Closed on purpose: a consumer that handles every refusal case
 * cannot miss a new failure mode (compile error at every `when` site).
 */
sealed interface PruneRefusal {

    /** The run is not known to this PK instance. */
    data class UnknownStream(val stream: OutputStreamId) : PruneRefusal

    /** The substrate cannot be reached right now; mirrors `OutputRefusal.Unavailable`. */
    data object SubstrateUnavailable : PruneRefusal

    /**
     * The underlying storage failed during the consult; [cause] is
     * a short diagnostic.
     */
    data class StorageError(val cause: String) : PruneRefusal {
        init {
            require(cause.length <= PruneRefusal.REFUSAL_REASON_MAX_LEN) {
                "PruneRefusal.StorageError cause must be <= ${PruneRefusal.REFUSAL_REASON_MAX_LEN} chars"
            }
        }
    }

    companion object {
        const val REFUSAL_REASON_MAX_LEN: Int = 256
    }
}
```

### 8.2 The pin-aware prune outcome

The existing `OutputRetentionPort.prune(intent): OutputPruneReport`
(report) is unchanged. The M3 addition is the CONSULT, not the
act. The contract test `PruneAfterUnpinTest` (§10) pins the
end-to-end: `canPrune(intent)` returns `Consulted(...)` →
caller `release`s the named pin → `canPrune(intent)` returns `Granted`
→ caller `prune(intent)` succeeds.

### 8.3 Authority split

The prune port (`OutputRetentionPort`) is in `:pipeline-output`
(published). The pin consult port (`OutputPinPort`) is in
`:pipeline-output` (published). The implementations of BOTH live in
`:pipeline-output-store` (non-published); the store-side adapter
composes them: `canPrune(intent)` calls `pinsOf(stream, range)` and
returns the appropriate `PruneAuthorisation` case.

The composition contract:

```text
canPrune(intent):
  intent.runId -> OutputFrameIndex.streamsOfRun(runId)
  for each stream:
    pins = pinsOf(stream, range=intent.range)
    if pins.isNotEmpty() -> Consulted(stream, range, pins)
    else -> Granted
  if any stream is UnknownStream -> Refused(UnknownStream(...))
  if any storage error -> Refused(StorageError(cause))
```

The prune port does NOT consult the lease store. A pin's holder is
independent of a run's writer (audit §B.3 (f)).

## 9. Composition rules

The M3 ports compose the existing M1 ports, the M2 ports, and the
existing internal authorities. The composition table:

| M3 sub-view                                              | M1 / M2 / internal port that backs it                                                                                                       | Authority file:line                                                                                              |
|----------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| `OutputDigest.compute(stream, from, to)`                  | `OutputReadPort.readRange` + `java.security.MessageDigest.getInstance("SHA-256")`                                                            | `v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputReadPort.kt:43` (existing readRange)   |
| `OutputReadDigestedResult.Digested.digest`                | Same path as `readRange`, single-pass compute                                                                                               | same                                                                                                            |
| `OutputRefusal.RetentionGap`                             | `OutputReadPort.readRange` detects range past `committedExtent` minus pinned-and-unpinned retained extent                                   | store-side adapter `pipeline-output-store/.../store/SegmentOutputStore.kt:76-…` (existing read path)             |
| `OutputRefusal.Corrupt`                                  | `OutputReadPort.readRange` detects row-without-payload                                                                                      | same                                                                                                            |
| `OutputRefusal.Unavailable`                              | `OutputReadPort.readRange` detects substrate I/O fault (typed, not `StorageError`)                                                           | same                                                                                                            |
| `OutputRefusal.RangeLostRetention`                       | `OutputReadPort.readRange` detects range past `committedExtent` AND no active pin covers the gap                                            | same                                                                                                            |
| `OutputPinPort.pin / release / pinsOf / isPinned`        | `SegmentOutputStore` (existing file layout) + a NEW `pins/` directory under each stream's segment dir                                       | NEW `pipeline-output-store/.../store/OutputPinStore.kt` (mirroring `FileBackedRunExecutionLeaseStore.kt:40-298`) |
| `OutputRetentionPort.canPrune(intent)`                   | `OutputPinPort.pinsOf(stream, range)` + `OutputRetentionPort.hasOutputFor(runId)`                                                            | NEW application-layer adapter `pipeline-application/.../durable/PruneAuthorizationAdapter.kt`                    |
| `RecoverRefusal.PinnedBytesOutsideRecoveredRegion`        | `OutputPinPort.pinsOf(stream, range)` consulted during `RuntimeRecoverPort.recover`                                                          | M2 design §5 extension; see M3 design §10 contract test                                                         |
| `Capabilities.OUTPUT_READ_DIGESTED_V1` / `OUTPUT_PIN_V1` / `OUTPUT_REFUSAL_RETENTION_V1` | NEW `:pipeline-output/Capabilities.kt` (mirrors `:pipeline-runtime/Capabilities.kt` from M2 design §2.2)                                       | NEW file                                                                                                         |

### 9.1 No new authority introduced

- No new lease, no new fencing scheme, no new scheduler.
- No new hash algorithm (SHA-256 is the existing journal's `Fingerprint`
  algorithm).
- No new store: the pin store is in `:pipeline-output-store`, parallel
  to the existing lease store.
- No new wakeup transport (the existing `ObservationWakeup` may be
  composed, not replaced).

### 9.2 Module layout summary

```text
v2/pipeline-output/
├── build.gradle.kts                          (existing; maven-publish at lines 54-66)
└── src/main/kotlin/dev/rubentxu/pipeline/v2/output/
    ├── Capabilities.kt                        (NEW: static IDs + helper; mirrors M2)
    ├── OutputDigest.kt                        (NEW: §3.2)
    ├── OutputReadDigestedResult.kt           (NEW: §3.4)
    ├── OutputPinPort.kt                       (NEW: §5.2)
    ├── OutputPin.kt                           (NEW: §5.3)
    ├── PruneOutcome.kt                        (NEW: §8.1)
    ├── OutputRefusal.kt                       (EXTENDED: §4.1; existing at lines 14-99)
    ├── OutputReadPort.kt                      (EXTENDED: §3.3; existing at lines 19-44)
    ├── OutputRetention.kt                     (EXTENDED: §5.4; existing at lines 149-162)
    └── ...

v2/pipeline-output-store/
└── src/main/kotlin/dev/rubentxu/pipeline/v2/output/store/
    ├── OutputPinStore.kt                      (NEW: pin persistence; non-published)
    ├── OutputPinPortStoreAdapter.kt           (NEW: ports-pin adapter; non-published)
    ├── PruneAuthorisationAdapter.kt           (NEW: canPrune composition; non-published)
    └── ...

v2/pipeline-runtime/
└── src/main/kotlin/dev/rubentxu/pipeline/v2/runtime/recover/
    └── RecoverRefusal.kt                      (EXTENDED: new PinnedBytesOutsideRecoveredRegion case)

v2/pipeline-application/
└── src/main/kotlin/dev/rubentxu/pipeline/v2/application/
    ├── MainConsoleCli.kt                      (EXTENDED: renderRefusal gets new branches; existing at lines 252-279)
    └── durable/
        └── PruneAuthorisationAdapter.kt      (NEW: wires OutputPinPort into OutputRetentionPort)
```

The existing application-layer compose boundary
(`v2/pipeline-application/.../durable/*.kt`) is the wiring slot, the
same way the M2 introspection adapter
(`v2/pipeline-runtime/.../inspect/IntrospectionAdapters.kt`) is the
M2 wiring slot.

## 10. Test surface

For each new port + each new refusal case + each new method on an
existing port, the implementation MUST pass the tests in this
section. All tests use `@TempDir` + the real-Sqlite + real-segment-store
stack (no mocks; mirrors the M1-D / M2 contract test discipline).

### 10.1 Digest on read

| Test ID                          | What it asserts                                                                                            |
|----------------------------------|------------------------------------------------------------------------------------------------------------|
| `DigestDeterminismTest`          | Same bytes across two reads, same digest                                                                    |
| `DigestDistinctnessTest`         | Different bytes (after a write), different digest                                                            |
| `DigestPartialRefusesClosed`     | Partial page returns either full digest OR `OutputRefusal.RetentionGap` / `Corrupt` / `Unavailable` / `RangeLostRetention` |
| `DigestCorruptRefusesClosed`     | A corrupted row returns `OutputRefusal.Corrupt`, NOT a digest                                               |
| `DigestIdempotencyKeyTest`       | `(tenant, run, plane, start, endExclusive, digest, epoch)` round-trips; same key = same `(a, b)` content   |
| `DigestRefusalTranslation`       | Every digest refusal case is the M3 case, not `StorageError`                                                |

### 10.2 Sealed refusal cases

| Test ID                          | What it asserts                                                                                            |
|----------------------------------|------------------------------------------------------------------------------------------------------------|
| `RenderRefusalCompilationTest`   | `MainConsoleCli.renderRefusal` recompiles after M3 changes WITHOUT modifying its source (sealed ADT discipline) |
| `RenderRefusalBranchesTest`      | New branches render stable, script-branchable text (`"console-refused: retention-gap"`, etc.)               |
| `RetentionGapCrossesTest`        | A read across a pruned range returns `RetentionGap(stream, range, lastCommitted)`                          |
| `CorruptByRangeTest`             | A read into a row-without-payload region returns `Corrupt(stream, range, reason)`                          |
| `UnavailableTypedTest`           | A substrate-unreachable read returns `Unavailable` (typed, not `StorageError`)                             |
| `RangeLostRetentionTest`         | A read into a lost-retention region returns `RangeLostRetention` (read-side, NOT `StreamLostRetention`)     |

### 10.3 Pin primitive

| Test ID                          | What it asserts                                                                                            |
|----------------------------------|------------------------------------------------------------------------------------------------------------|
| `PinSurvivesPruneTest`           | A pin on `[a, b)` of stream `S` of run `R`; `prune(RunReachedTerminalState(R))` returns `Consulted(pins=[pinId])`; bytes still readable via `readRange(S, a, b)` after the refused prune |
| `PinReleaseTest`                 | `pin` + `release(pinId)` returns `PinReleaseOutcome.Released`; idempotent second call returns `AlreadyReleased` |
| `PinExpireTest`                  | `pin(..., expiresAtMs=past)` is consulted as released                                                       |
| `PinListDeterministicOrderTest`  | `pinsOf(stream)` returns pins in `pinId` lexicographic order                                                |
| `IsPinnedBoundaryTest`           | `isPinned(stream, a-1)` is false; `isPinned(stream, a)` is true; `isPinned(stream, b)` is true; `isPinned(stream, b+1)` is false |
| `IsPinnedMidRangeTest`           | `isPinned(stream, (a+b)/2)` is true for a single pin on `[a, b)`                                            |
| `PinMultiSameStream`             | Two pins on different ranges of the same stream; both survive; `pinsOf(stream)` returns both               |
| `PinLimitEnforcedTest`           | 1025 pins on `(stream, runId)` → 1025th `pin` returns `PinRefusal.TooManyPins(limit=1024, active=1024)`     |
| `PinReleaseIdempotentTest`       | `release(pinId)` called twice; second returns `AlreadyReleased`                                             |
| `PinDurableAcrossRestartTest`    | Pin survives a process restart (close SQLite + segments + reopen, `pinsOf` still lists the pin)             |

### 10.4 No-rerun invariant (carry-over from M2)

| Test ID                          | What it asserts                                                                                            |
|----------------------------------|------------------------------------------------------------------------------------------------------------|
| `RecoverDoesNotInvalidatePinsTest` | `RuntimeRecoverPort.recover(runId)` does NOT release any active pin                                          |
| `RecoverDoesNotInvalidateDigestsTest` | The same range's `readRangeDigested` returns the same digest before and after a recover                    |
| `PinnedRangeSurvivesRecoverTest`   | A pin on `[a, b)` of stream `S` of run `R`; `recover(R)` returns `RecoveredTerminal`; the pin still readable via `readRange(S, a, b)` |
| `RecoverRefusedOnPinnedUnbackedTest` | A pin on bytes that are unbacked (`OutputRecoveryReport.bytesUnbacked != 0`); `recover(R)` returns `FailClosed(PinnedBytesOutsideRecoveredRegion)` |

### 10.5 Concurrent observer independence (carry-over from M1)

| Test ID                          | What it asserts                                                                                            |
|----------------------------------|------------------------------------------------------------------------------------------------------------|
| `PinDoesNotBlockReaderTest`      | Two readers on the same run, one pins, the other reads — neither blocks the other                            |
| `PinDoesNotBlockFollowerTest`    | Two followers on the same run, one pins, the other follows — neither blocks the other                       |
| `ReaderDoesNotAffectPinListTest` | A reader calling `readRange` does not change `pinsOf(stream)`                                              |

### 10.6 Prune-side refusal

| Test ID                          | What it asserts                                                                                            |
|----------------------------------|------------------------------------------------------------------------------------------------------------|
| `CanPruneNoPinsGranted`          | `canPrune(intent)` with no active pins returns `PruneAuthorisation.Granted`                                  |
| `CanPruneWithPinConsulted`       | `canPrune(intent)` with one active pin returns `Consulted(stream, range, pins=[pinId])`                     |
| `CanPruneWithExpiredPinGranted`  | `canPrune(intent)` with one EXPIRED pin returns `Granted` (the expired pin is RELEASED)                     |
| `PruneAfterRetainTest`           | `canPrune(intent)` → `Granted` → `prune(intent)` → report with non-zero `streamsRemoved`                     |
| `PruneRefusedUnknownStream`      | `canPrune(intent)` for an unknown run returns `Refused(UnknownStream(...))`                                 |
| `PruneRefusedSubstrateUnavailable` | `canPrune(intent)` with the substrate unreachable returns `Refused(SubstrateUnavailable)`                  |

### 10.7 The audit's named scenarios

The audit §"the audit's 'rango corrupto, hueco, SIGKILL, red
desconectada simulada desde el consumidor y lectura exacta tras
recuperar' list" is honoured test-by-test:

| Scenario (verbatim)                                    | Test ID                                |
|-------------------------------------------------------|----------------------------------------|
| `rango corrupto` (corrupt range)                      | `CorruptByRangeTest`                   |
| `hueco` (gap; range lost to retention)                | `RangeLostRetentionTest` + `RetentionGapCrossesTest` |
| `SIGKILL` (writer dies mid-write)                     | `PinDurableAcrossRestartTest` + `PinDoesNotBlockReaderTest` |
| `red desconectada simulada desde el consumidor` (network down, simulated from consumer side) | `UnavailableTypedTest` + `PinDoesNotBlockFollowerTest` |
| `lectura exacta tras recuperar` (exact read after recover) | `PinnedRangeSurvivesRecoverTest` + `RecoverDoesNotInvalidateDigestsTest` |

### 10.8 Cross-port closed-ADT discipline

| Test ID                          | What it asserts                                                                                            |
|----------------------------------|------------------------------------------------------------------------------------------------------------|
| `DigestedResultClosed`           | `OutputReadDigestedResult` is sealed; `when` exhaustiveness is a compile error                              |
| `OutputPinResultClosed`          | `OutputPinResult` is sealed; `when` exhaustiveness is a compile error                                       |
| `PinRefusalClosed`               | `PinRefusal` is sealed; `when` exhaustiveness is a compile error                                            |
| `PinReleaseOutcomeClosed`        | `PinReleaseOutcome` is sealed; `when` exhaustiveness is a compile error                                     |
| `PruneAuthorisationClosed`       | `PruneAuthorisation` is sealed; `when` exhaustiveness is a compile error                                    |
| `PruneRefusalClosed`             | `PruneRefusal` is sealed; `when` exhaustiveness is a compile error                                          |
| `OutputRefusalClosedAfterM3`     | `OutputRefusal` is sealed; `when` exhaustiveness is a compile error AFTER the M3 cases are added            |

## 11. Cross-walk to the cross-repo contract

The audit §G.7 names `UAT-PK-M3-###` as the test ID convention.
This design adopts it. The M3 deliverable populates:

| UAT ID          | What it proves                                              | Contract section | M3 port / case it exercises                |
|-----------------|--------------------------------------------------------------|------------------|--------------------------------------------|
| UAT-PK-M3-001   | `OutputDigest.compute` is deterministic                       | §8               | `OutputDigest`, `readRangeDigested`        |
| UAT-PK-M3-002   | Idempotency key round-trip                                   | §8               | `readRangeDigested` + `inspect.epoch`      |
| UAT-PK-M3-003   | `RetentionGap` refusal for a pruned range                     | §2, §12          | `OutputRefusal.RetentionGap`               |
| UAT-PK-M3-004   | `Corrupt` refusal for an unparseable range                   | §2               | `OutputRefusal.Corrupt`                    |
| UAT-PK-M3-005   | `Unavailable` refusal for an unreachable substrate           | §2, §10          | `OutputRefusal.Unavailable`                |
| UAT-PK-M3-006   | `RangeLostRetention` refusal for a lost range                | §2               | `OutputRefusal.RangeLostRetention`         |
| UAT-PK-M3-007   | `pin` + `canPrune(Consulted)` → `release` → `canPrune(Granted)` | §12             | `OutputPinPort`, `OutputRetentionPort.canPrune` |
| UAT-PK-M3-008   | `PinSurvivesPruneTest` end-to-end                            | §12              | `OutputPinPort`, `OutputRetentionPort.prune` |
| UAT-PK-M3-009   | `PinDurableAcrossRestartTest` durability                     | §12              | `OutputPinStore`                           |
| UAT-PK-M3-010   | `RecoverDoesNotInvalidatePinsTest` no-rerun invariant        | §12              | `RuntimeRecoverPort`, `OutputPinPort`      |

The implementation populates ten `UAT-PK-M3-###` IDs in the
`Capacidades publicadas (CRIC-M3)` table of
`INTERFACE_CONTRACT.md`, mirroring the M1 (six IDs) and M2 (zero IDs,
but three capability strings) precedent.

### 11.1 Contract SHA bump

The M3 implementation will require a one-cell update to
`INTERFACE_CONTRACT.md`'s "Capacidades publicadas" table to add a
new section "Capacidades publicadas (CRIC-M3)" listing the three
new capability IDs (audit §G.8). The CONTRACT_SHA256.txt file
(`docs/pipelinek-coordinated-evolution/coordination/CONTRACT_SHA256.txt`)
must be refreshed at promotion time, mirroring the M1 and M2 precedent.

### 11.2 PAIR_RECEIPT.json cells

The next handoff populates the following new cells in
`docs/pipelinek-coordinated-evolution/handoff/PAIR_RECEIPT.json`:

```text
producer_capabilities.m3.output.read.digested.v1     = "EXPERIMENTAL" | "PUBLICADA"
producer_capabilities.m3.output.pin.v1              = "EXPERIMENTAL" | "PUBLICADA"
producer_capabilities.m3.output.refusal.retention.v1= "EXPERIMENTAL" | "PUBLICADA"
uat.m3.UAT-PK-M3-001..010                          = pass/fail per ID
fitness.m3.PinSurvivesPruneTest                    = pass/fail
fitness.m3.LostRangeSurfacesAsTypedRefusalTest      = pass/fail
fitness.m3.DigestRoundTripTest                      = pass/fail
audit_trail.m3.audit_sha                            = dab35001
audit_trail.m3.design_sha                           = <this commit SHA>
release_train.m3.version                            = "v0.51.0-rc1" (§12)
```

The audit trail cites the audit SHA (`dab35001`) and the design SHA
(this commit). Cross-walk to consumer: the next Fabric M3 handoff
reads these cells and emits the matching AAT-RP / AAT-15 / AAT-16
mirror in `docs/fabric-coordinated-evolution/acceptance/AAT.md`
(audit §E.3).

## 12. Release shape

### 12.1 Version: `v0.51.0-rc1`

The audit §F.7 says "this surface is one PK candidate (the next
pre-release after `v0.50.0-rc1`)". The audit §G.9 leaves the version
to the release harness. This design proposes **`v0.51.0-rc1`**.

Justification:

- **MINOR bump** (M2's `v0.50.0-rc1` → M3's `v0.51.0-rc1`) because the
  three new capability IDs are **new ABI surface**, not just EXPERIMENTAL
  promotions of existing M2 surface. A PATCH would understate the
  contract change.
- **Not `v0.50.0-rc2`** because that would signal "another candidate
  on the same train", and the audit §F.5 decision was "BLOCK 3
  PK-CANDIDATE NEEDED" — a separate train.
- **Not `v0.52.0-rc1`** because the M3 surface is additive on top of
  M1 + M2; no breaking changes are introduced. MAJOR bumps are
  reserved for breaking ABI.

The harness may override this proposal (audit §G.9: "the harness
chooses the train; the audit names what ships"). The design is
agnostic to the version label as long as the capability manifest
matches §12.2.

### 12.2 Capabilities manifest

| Capability ID                       | Version | Publicada en  | Status on first cut        | Test surface                                     |
|-------------------------------------|---------|---------------|----------------------------|--------------------------------------------------|
| `output.follow.v1`                  | v1      | `v0.49.0-rc1` | PUBLICADA                  | `SegmentOutputFollowerTest` (M1)                  |
| `events.follow.v1`                  | v1      | `v0.49.0-rc1` | PUBLICADA                  | `EventFollowerAdapterTest` (M1)                  |
| `runtime.inspect.v1`                | v1      | `v0.50.0-rc1` | EXPERIMENTAL → PUBLICADA   | `RuntimeIntrospectionPortAdapterTest` (M2)       |
| `runtime.cancel.v1`                 | v1      | `v0.50.0-rc1` | EXPERIMENTAL → PUBLICADA   | `RuntimeControlPortAdapterTest` (M2)             |
| `runtime.recover.v1`                | v1      | `v0.50.0-rc1` | EXPERIMENTAL → PUBLICADA   | `RuntimeRecoverPortAdapterTest` (M2)             |
| `output.read.digested.v1` (NEW)     | v1      | `v0.51.0-rc1` | EXPERIMENTAL → PUBLICADA   | `DigestDeterminismTest`, `DigestDistinctnessTest` (§10.1) |
| `output.pin.v1` (NEW)               | v1      | `v0.51.0-rc1` | EXPERIMENTAL → PUBLICADA   | `PinSurvivesPruneTest`, `PinLimitEnforcedTest` (§10.3) |
| `output.refusal.retention.v1` (NEW) | v1      | `v0.51.0-rc1` | EXPERIMENTAL → PUBLICADA   | `RetentionGapCrossesTest`, `CorruptByRangeTest` (§10.2) |

The three new IDs follow the audit §G.8 recommendation verbatim. The
M1 / M2 carry-forwards are unchanged; the M3 release does NOT
re-classify them.

### 12.3 Promotion to PUBLICADA

Each new capability is `EXPERIMENTAL` until the candidate is
`CERTIFIED` against the M3 contract test suite (§10) AND the M3
cross-JVM e2e test (mirroring `M1DCrossJvmFollowTest`, six cases) is
green. Promotion happens via the same `release-receipt` mechanism M1
and M2 used.

## 13. Open issues

Carry-over from the audit (§G.1–§G.9) plus any new ones this design
surfaces. Each is annotated with a proposed resolution path.

### 13.1 Audit §G.1 — home module for `OutputPinPort`

**Audit question.** Where does `OutputPinPort` live?

**Design resolution.** `:pipeline-output` (this design §2, §5.2). The
audit's alternative (sibling module) is rejected because the existing
segregation by plane (`:pipeline-output` / `:pipeline-events`) is
the boundary consumers depend on; a new module would add a third plane
without a corresponding authority.

### 13.2 Audit §G.2 — `pin holder` identity

**Audit question.** Is `holder` a `String` or a typed `PinHolder(value)`?

**Design resolution.** `String`. Mirrors the existing `RunOwnerId`
discipline (a value class wrapping a non-blank `String`, exposed at the
published API as a plain `String` for cross-JVM interop; see
`v2/pipeline-events-store/src/main/kotlin/dev/rubentxu/pipeline/v2/events/durable/RunExecutionLease.kt:52-67`).
Promoting to a typed `PinHolder` is a low-risk future move but is NOT
introduced by M3.

### 13.3 Audit §G.3 — `RetentionGap` / `Corrupt` / `Unavailable` placement

**Audit question.** Existing ADT or new sibling hierarchy?

**Design resolution.** Existing ADT, additive (this design §4.1). Mirrors
the M1 design §3 precedent (`StreamLostRetention`, `FollowCancelled`,
`StorageError` were additive).

### 13.4 Audit §G.4 — digest algorithm

**Audit question.** SHA-256 or SHA-3-256?

**Design resolution.** SHA-256 default (audit §B.1 (f): "use SHA-256,
the same algorithm `OperationJournal.beginOperation`'s `fingerprint`
uses"). Configurable baseline (this design §3.2: `OutputDigest.DEFAULT_ALGORITHM`).
A future SHA-3-256 may ship without an ABI break because the value
type does NOT carry the algorithm name.

### 13.5 Audit §G.5 — pin `expiresAtMs`

**Audit question.** Optional or required?

**Design resolution.** Optional per pin (this design §5.3: `expiresAtMs: Long? = null`).
An expired pin is RELEASED, not refused (this design §10.3:
`PinExpireTest`). The Fabric M3 spec does not currently require an
expiry; this design pins one as a guard rail for forgotten pins.

### 13.6 Audit §G.6 — pin limit

**Audit question.** 1024 per `(stream, runId)`?

**Design resolution.** Yes, default 1024 (this design §5.2: `OutputPinPort.DEFAULT_MAX_PINS_PER_STREAM`).
Configurable at construction; the implementation MUST refuse with
`PinRefusal.TooManyPins(limit, active)` above the default. The contract
test `PinLimitEnforcedTest` (§10.3) pins this.

### 13.7 Audit §G.7 — contract test IDs

**Audit question.** `UAT-PK-M3-###`?

**Design resolution.** Yes (this design §11: UAT-PK-M3-001..010). The
audit's three named tests (`PinSurvivesPruneTest`,
`LostRangeSurfacesAsTypedRefusalTest`, `DigestRoundTripTest`) are
renumbered to UAT-PK-M3-007/008, UAT-PK-M3-003/004/005/006, and
UAT-PK-M3-001/002 respectively.

### 13.8 Audit §G.8 — capability IDs

**Audit question.** `output.read.digested.v1`, `output.pin.v1`,
`output.refusal.retention.v1`?

**Design resolution.** Yes (this design §12.2). Adopted verbatim.

### 13.9 Audit §G.9 — version

**Audit question.** Next candidate after `v0.50.0-rc1`?

**Design resolution.** `v0.51.0-rc1` (this design §12.1). MINOR bump
from M2; harness may override.

### 13.10 NEW — `RuntimeIntrospectionPort.inspect` should report pins?

**Design question.** Should `RuntimeIntrospectionPort.inspect` add a
`pins: List<OutputPin>` field to `RuntimeObservation.Running`?

**Design resolution.** **No.** `inspect` is a run-lifecycle question;
pins are a range-resource question. The audit §C.2 demarcates the two
authorities explicitly. Adding pins to `RuntimeObservation.Running`
would force every consumer that wants "is this run alive?" to enumerate
pins, which is the wrong shape. A consumer that wants pins calls
`OutputPinPort.pinsOf(stream)` (or `isPinned(stream, offset)`)
directly. The runtime port stays unchanged.

### 13.11 NEW — `RuntimeIntrospectionPort.inspect` should still refuse on lease-held?

**Design question.** The M2 `IntrospectionRefusal.LeaseHeldByAnother`
returns when the lease is held by another. Should M3 add a parallel
`PinnedBytesOutsideRecoveredRegion` to `IntrospectionRefusal`?

**Design resolution.** **No.** `LeaseHeldByAnother` is a lease-shaped
refusal; the new `PinnedBytesOutsideRecoveredRegion` is a
recovery-shaped refusal (lives on M2's `RecoverRefusal`). The M2
introspection port is unchanged; the M3 design adds ONE new case to
`RecoverRefusal` (§9.1), not to `IntrospectionRefusal`.

### 13.12 NEW — pin consult on read?

**Design question.** Should `OutputReadPort.readRange` consult
`OutputPinPort.isPinned` to know whether a `RetentionGap` is "the
bytes were GC'd" or "the bytes are pinned but invisible"?

**Design resolution.** **No.** A read sees what is COMMITTED, not
what is PINNED. A pin is a retention hold against a future prune; a
read sees the same bytes whether pinned or not. The `RetentionGap`
case fires when the bytes are GONE (pinned or not), not when they
are pinned but present. The distinction is the audit §B.2's reason
for `RetentionGap` (commit record exists, payload missing) vs §B.3's
reason for `OutputPinPort` (the bytes are protected). The contract
test `PinDoesNotBlockReaderTest` (§10.5) pins this.

### 13.13 NEW — recover must respect pins (carry-forward from M2)

**Design question.** The audit §D.4 PARTIAL verdict flags that
`RuntimeRecoverPort.recover` is currently silent on pins. Should
the M3 design add a new case to M2's `RecoverRefusal`?

**Design resolution.** **Yes.** New case
`RecoverRefusal.PinnedBytesOutsideRecoveredRegion`:

```kotlin
// v2/pipeline-runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/runtime/recover/RecoverRefusal.kt
//
// ADDITIVE — the existing sealed interface (M2 design §5.4) is preserved.

sealed interface RecoverRefusal {
    // ... existing cases preserved: UnknownRun, NotRecoverable,
    //     SubstrateUnavailable, JournalIncompatible, LeaseHeldByAnother,
    //     StorageError ...

    /**
     * M3 — a recover was attempted on a run with active pins, and
     * those pins cover bytes the recover would have to release.
     *
     * A recover that drops pinned bytes would violate the contract §12
     * invariant ("no podar rangos hasta probar que su garantía activa
     * se transfirió a una copia durable, y que no existe pin"). The
     * M2 recover port's composition rule (M2 design §5.6) explicitly
     * does NOT modify committed bytes; M3 extends that rule to:
     * do NOT release pinned bytes.
     *
     * The recover port MUST consult `OutputPinPort.pinsOf(stream,
     * range=intent.range)` BEFORE it returns `RecoveredTerminal`. If
     * any pin covers the recovered region, the port returns
     * `FailClosed(PinnedBytesOutsideRecoveredRegion(stream, range, pins))`
     * instead.
     *
     * @property stream the stream whose pinned bytes the recover
     *                  would have released
     * @property range  the half-open range the recover would have
     *                  touched
     * @property pins   the active pins the consumer must release
     *                  before re-trying
     */
    data class PinnedBytesOutsideRecoveredRegion(
        val stream: OutputStreamId,
        val range: LongRange,
        val pins: List<OutputPin>,
    ) : RecoverRefusal
}
```

The contract test `RecoverRefusedOnPinnedUnbackedTest` (§10.4) pins this.

### 13.14 NEW — digest on follow?

**Design question.** Should the M1 follow contract (`OutputFollower`)
carry a digest on its `OutputFollowEvent.Advanced`?

**Design resolution.** **Out of scope.** The audit §B.1 scope is the
read port, not the follow port. The follow port carries cursors
(not ranges); a digest across a partial page has lower replicability
value than a digest across an explicit `[from, to)` range (the audit
§B.1 (e)). A consumer that wants digests on a follow paginates first
and calls `readRangeDigested` on the assembled range. A future M-block
may add `OutputFollowerDigested` if Fabric requires it.

## 14. Out of scope

Re-stated from the audit and the Block 3 plan:

- Implementation. The M3 design is a contract; the implementation
  is the next block (`M3-Impl`).
- v0.49.0 / v0.50.0 work. Frozen at the published Prereleases.
- Cross-repo edits to `Rubentxu/pipelinek-fabric/...`. We send a
  handoff when M3 ships.
- M4, M5, M6, M7. Independent blocks.
- Replication of any kind (ACK remote, scheduler, S3, gRPC, spools).
  PK does not implement these (audit §B.7 + user policy; this design
  §5.5, §9.1).

---

**End of design.** This document closes the 8 audit gaps (B.1–B.8) with
additive changes only. No existing port is replaced; no existing
authority is moved; no new fence, lease, scheduler, or hash algorithm
is introduced. The M3 implementation's job is to write the code and
tests that satisfy §10's contract test surface.