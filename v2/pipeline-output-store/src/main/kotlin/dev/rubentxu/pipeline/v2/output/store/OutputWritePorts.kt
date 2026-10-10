package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputAdoption
import dev.rubentxu.pipeline.v2.output.OutputReadPort
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import java.io.InputStream

// The write side and the recovery entry point of the Output Plane.
//
// The read side lives in `:pipeline-output` ([OutputReadPort]) and is the whole of the published
// plane. This file is not published: a consumer that can append, commit, recover or prune is a
// second potential authority over the bytes, which is exactly what ADR-M1 D2 removed. Fabric reads
// committed bytes; the runtime owns writing them. The split is enforced by the module graph, and
// the package boundary (`output` published / `output.store` not) is what the fitness checks.

/**
 * The **append** side of the Output Plane: a writer takes a durable reservation, writes bytes into
 * it, and commits.
 *
 * ## The order is the contract
 *
 * ```text
 * reserve  ──▶ write ──▶ commit
 *    O1          O1         O2
 * ```
 *
 * A reservation is durable **before** the first byte is written, and the acknowledgement returned to
 * the caller is the reservation itself. A writer that dies between reserve and commit leaves a range
 * that no reader can see and that recovery releases — it does not leave a hole and it does not leave
 * bytes stranded behind a commit record that never landed.
 *
 * This is the obligation set of [ADR-M1 D4]; it is a conjunction, so a writer that cannot honour all
 * three (O1 reserve-before-write, O2 resume-from-committed, O3 recover-before-serve) is not
 * conformant, and [OutputAdoption] is what makes that checkable.
 *
 * ## Single authority
 *
 * Everything a reader will ever see passes through here. No event, no report and no second renderer
 * re-fabricates these bytes. [ADR-M1 D2].
 *
 * @see ADR-M1 §D2, §D4
 */
interface OutputAppendPort {

    /**
     * Opens a stream for appending, or returns the handle for an already-open stream.
     *
     * Idempotent by stream: two callers opening the same [OutputStreamId] get handles onto the same
     * stream, never two independent byte orders.
     */
    fun open(stream: OutputStreamId): OutputStreamHandle
}

/**
 * A writer's handle on one stream. Every method is a step of the reserve → write → commit order, and
 * the type makes the order unrepresentable out of sequence: there is no way to obtain a
 * [OutputReservation] from a handle whose reservation was not durably taken.
 */
interface OutputStreamHandle {

    val stream: OutputStreamId

    /**
     * Takes a durable reservation of at least [minBytes] and returns it.
     *
     * The reservation is visible to the store's own recovery the moment this returns. It is **not**
     * visible to any reader until [OutputReservation.commit].
     *
     * [minBytes] is a floor, not a cap: the store may reserve more than asked, and
     * [OutputReservation.limit] is the authority on how much this reservation can hold. A caller
     * that must not exceed its own bound has to check [OutputReservation.limit], not [minBytes].
     */
    fun reserve(minBytes: Int): OutputReservation

    /**
     * Appends everything [source] produces, taking as many reservations as it needs, and returns the
     * stream's new committed offset.
     *
     * This is the operation a process transcript actually needs, because its length is not known in
     * advance: a single reservation is bounded, so an unbounded producer cannot be served by one.
     * Each iteration is a full reserve → write → commit cycle, which means a reader tailing the
     * stream sees the transcript in order as it is produced rather than only at the end.
     *
     * The bytes arriving here are expected to be **already redacted**. Redaction is a write-side
     * obligation; a store that redacted on read would have already persisted the secret.
     *
     * @param windowBytes bytes to take per reservation; bounds the resident memory
     */
    fun appendFrom(source: InputStream, windowBytes: Int = DEFAULT_APPEND_WINDOW): Long
}

/** Default bytes taken per reservation by [OutputStreamHandle.appendFrom]. */
const val DEFAULT_APPEND_WINDOW: Int = 64 * 1024

/**
 * A durable, not-yet-visible byte range inside a stream.
 *
 * @property base  stream offset the range starts at
 * @property limit stream offset one past the last byte this reservation may occupy
 */
interface OutputReservation {
    val stream: OutputStreamId
    val base: Long
    val limit: Long

    /** Bytes already written into this reservation. */
    val written: Long

    /**
     * Writes [bytes] at the reservation's current position.
     *
     * @throws OutputReservationExceeded if the write would pass [limit]. A reservation is a bound,
     *   not a hint: exceeding it would make the committed extent ambiguous.
     */
    fun write(bytes: ByteArray)

    /**
     * Copies [source] into this reservation in bounded windows, so an unbounded producer never has
     * to be materialised.
     *
     * Refuses with [OutputReservationExceeded] if the source has more bytes than this reservation
     * can hold. Use [OutputStreamHandle.appendFrom] for a producer of unknown length — this method
     * is the single-reservation operation underneath it, and truncating silently would lose bytes.
     *
     * The bytes are expected to be already redacted: redaction is a write-side obligation, and a
     * store that redacted on read would have already persisted the secret.
     */
    fun copyFrom(source: InputStream)

    /**
     * Publishes everything written so far and returns the stream's new committed offset.
     *
     * Safe to call on an empty reservation: it commits zero bytes, which is how a stream ends.
     */
    fun commit(): Long

    /**
     * Releases whatever was written here without publishing it, and returns the base offset for
     * reuse. The base is returned rather than discarded because a released range is the mechanism
     * that keeps the recovered order **dense** — without it, a reserved-and-unused range would be a
     * permanent hole.
     */
    fun abandon(): Long
}

/** Thrown when a write would pass its reservation's [OutputReservation.limit]. */
class OutputReservationExceeded(
    val stream: OutputStreamId,
    val attemptedAt: Long,
    val limit: Long,
) : IllegalStateException(
    "reservation on ${stream.value} would write at $attemptedAt, past its limit $limit",
)

/**
 * The recovery entry point. **Distinct from construction on purpose** (O3).
 *
 * A store that reconciles lazily, on the first read, has already served a reader from an
 * unreconciled state. Recovery is therefore an explicit call that must complete before any
 * [OutputReadPort] call is honoured, and a store that has not completed it refuses reads with
 * [OutputRefusal.RecoveryNotCompleted] rather than guessing.
 *
 * @see ADR-M1 §D4 O3
 */
interface OutputRecoveryPort {

    /**
     * Reconciles durable state and returns what survived.
     *
     * Must be callable more than once, and must be idempotent: recovery is itself a crash-prone
     * operation and a store that cannot be recovered twice is a store that cannot be trusted after
     * its own recovery crashes.
     */
    fun recover(): OutputRecoveryReport
}

/**
 * Recording that a stream will receive no further bytes.
 *
 * ## Why sealing is a separate concern from writing
 *
 * `OutputAppendPort` answers "what did the producer just write". This one answers "is there
 * anything left to write", which is a different question with a different owner: the writer knows
 * when it is done, and only the writer does. Keeping them apart means the byte path never has to
 * carry a flag that only matters at the very end.
 *
 * ## Why the execution layer decides, and not the pump
 *
 * A pump ending is **not** the same fact as a stream being finished. When a JVM dies mid-`sh`, the
 * pump ends while the operation continues: a resumed run re-attaches to the same operation id and
 * appends more bytes to the very same stream. Sealing on pump close would therefore seal a stream
 * that is legitimately about to grow again, and the re-attached bytes would be refused by a stream
 * that had already declared itself finished.
 *
 * So the seal is recorded where the terminal outcome is known — the execution layer, which has
 * decided the operation is over — and only then. A killed operation is never sealed, which is
 * exactly what leaves room for recovery.
 *
 * ## Idempotence
 *
 * [seal] must be callable more than once and must not move a sealed stream's end. A resumed run that
 * re-observes the same terminal must produce the same durable state, not a second, later end.
 */
interface OutputSealPort {

    /**
     * Records that no further bytes will be written to [stream], and returns the resulting
     * [SealOutcome].
     *
     * ## Closed return type, by design (M1-F.3)
     *
     * A channel that was never opened is NOT a failure: `sh("echo hi")` writes
     * to stdout and never opens stderr, so the stderr seal must be a silent
     * no-op. A real I/O failure (disk full, FS permission) IS a failure, and
     * must surface as a typed [SealOutcome.Failure] rather than an exception
     * that the caller cannot distinguish from "unknown stream".
     *
     * The return type makes all four outcomes explicit:
     *   - [SealOutcome.Sealed] — newly sealed; `end` is the committed extent
     *   - [SealOutcome.AlreadySealed] — was sealed before; `end` is the
     *     previously-recorded end. Idempotent: re-sealing is a no-op.
     *   - [SealOutcome.NeverOpened] — legitimate absence; `end` is null.
     *     This is the per-channel "no bytes were ever produced" case the
     *     durable shell substrate observes for the silent channel of a
     *     stdout-only (or stderr-only) script.
     *   - [SealOutcome.Failure] — real I/O failure; `cause` is the throwable
     *     and `end` may be null or a partial extent (the failure might have
     *     happened before the marker could be written).
     *
     * Refuses an append after a seal rather than accepting it, because a
     * sealed stream that grows would make [dev.rubentxu.pipeline.v2.output.OutputTailState.Sealed]
     * a promise the store had already broken.
     */
    fun seal(stream: OutputStreamId): SealOutcome
}

/**
 * The closed result of a single [OutputSealPort.seal] call.
 *
 * M1-F.3 — every channel's seal is a total function whose result is one
 * of these cases. Exceptions were the wrong shape for the "legitimate
 * absence" path: a `sh("echo hi")` invocation only opens stdout, so
 * sealing stderr must be silent and a non-exceptional no-op. Real I/O
 * failures still surface, but as data a caller can route on rather than
 * a try/catch that conflates "unknown stream" with "disk full".
 */
sealed interface SealOutcome {

    /**
     * The committed extent of the stream, recorded at the moment of sealing.
     *
     * `null` for [NeverOpened] (there are no bytes to record) and for
     * [Failure] when the failure happened before the marker could be
     * written. A failure that DID write the marker carries the recorded
     * end so the caller can decide whether to keep the partial seal.
     */
    val end: Long?

    /** Newly sealed. `end` is the committed extent at the moment of sealing. */
    data class Sealed(override val end: Long) : SealOutcome

    /**
     * Was already sealed. `end` is the previously-recorded end. Idempotent:
     * re-sealing is a no-op and the recorded end does not move.
     */
    data class AlreadySealed(override val end: Long) : SealOutcome

    /**
     * Stream was never opened. Legitimate absence — the durable shell
     * substrate sees this when a stdout-only script has no stderr bytes
     * to seal. A caller does NOT need to surface this as a warning.
     */
    data object NeverOpened : SealOutcome {
        override val end: Long? = null
    }

    /**
     * Real I/O failure (disk full, FS permission denied, ...).
     *
     * `cause` is the throwable. `end` is null if the failure happened
     * before the marker could be written, or the partial extent if the
     * marker DID land but a subsequent step failed.
     */
    data class Failure(val cause: Throwable, override val end: Long? = null) : SealOutcome
}

/**
 * What a recovery pass found and repaired.
 *
 * [committedBytes] is the **stable** part: it is the same however many times recovery runs, so a
 * caller can assert idempotence on it. The other two are *work this pass did* and are zero on a
 * second call, which is correct — a recovery that has nothing left to repair must say so rather
 * than repeat its first-pass numbers. The first version had no stable field at all, so the only
 * way to check idempotence was to compare two reports that are *supposed* to differ.
 */
data class OutputRecoveryReport(
    /** Streams this pass actually reconciled. A stream held by a live writer is NOT one of them. */
    val streamsReconciled: Int,
    /**
     * Streams skipped because a live writer holds their ownership, per ADR-OBS-002.
     *
     * Non-zero is a normal answer, not a failure: it is the store saying "these are being written
     * right now, so I did not touch them". It is reported rather than hidden because a recovery that
     * silently skipped a stream would look identical to one that found nothing to do.
     */
    val streamsOwned: Int,
    val committedBytes: Long,
    val bytesReleased: Long,
    val reservationsReleased: Int,
    /**
     * Bytes a commit record claims that the payload does not hold. **Non-zero means the store is
     * damaged and is NOT repaired automatically** — see [OutputNotEstablished.CORRUPT_COMMIT_RECORD].
     * A read that would need those bytes fails loudly rather than returning a plausible short page.
     */
    val bytesUnbacked: Long,
)
