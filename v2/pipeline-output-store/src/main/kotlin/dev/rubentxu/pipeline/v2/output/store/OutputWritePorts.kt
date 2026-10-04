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
 * What a recovery pass found and repaired.
 *
 * [committedBytes] is the **stable** part: it is the same however many times recovery runs, so a
 * caller can assert idempotence on it. The other two are *work this pass did* and are zero on a
 * second call, which is correct — a recovery that has nothing left to repair must say so rather
 * than repeat its first-pass numbers. The first version had no stable field at all, so the only
 * way to check idempotence was to compare two reports that are *supposed* to differ.
 */
data class OutputRecoveryReport(
    val streamsReconciled: Int,
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
