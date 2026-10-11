package dev.rubentxu.pipeline.v2.runtime.inspect

import dev.rubentxu.pipeline.v2.output.OutputStreamId

/**
 * M2 — closed ADT the audit's B.1/B.4 names "what does this run look like right
 * now?". Each case names a substrate condition; new cases are compile errors at
 * every `when` site that exhausts this ADT.
 *
 * The shape mirrors `PK-SPEC-02-RUNTIME-OBSERVATION.md` lines 22-28, with the
 * M2 design's corrections:
 *
 *  - `Running.process` is OPTIONAL (the runtime does not always have a durable
 *    reference to the live writer process — e.g. when Fabric is on a different
 *    host). The contract does not depend on it.
 *  - `Terminal.terminal` carries a typed [TerminalObservation] (NOT a raw
 *    `(StepOutcome, OperationStatus)` pair), which is what §D.4 of the audit
 *    names as the fix for the category error between "what the journal recorded"
 *    and "what the run semantically is".
 *
 * @see M2_INSPECT_RECOVER_CANCEL_DESIGN.md §3.2.
 */
sealed interface RuntimeObservation {

    /**
     * The run is in flight and observed.
     *
     * `leaseHolder` is the durable, cross-process fact a Fabric can act on (the
     * holder's owner id + fencing token + liveness). `process` is OPTIONAL: cross-
     * process introspection always returns `null` for it; co-located introspection
     * may carry a [ProcessRef]. The contract never depends on `process` being non-
     * null.
     */
    data class Running(
        val attempt: AttemptId,
        val leaseHolder: LeaseHolder?,
        val fencingToken: FencingToken,
        val journalPosition: JournalPosition,
        val outputTails: List<OutputTailView>,
        val process: ProcessRef? = null,
    ) : RuntimeObservation

    /**
     * The run reached a terminal state and that state was observed by the journal.
     * The `terminal` carries the typed [TerminalObservation].
     */
    data class Terminal(
        val attempt: AttemptId,
        val terminal: TerminalObservation,
        val terminalAtMs: Long,
    ) : RuntimeObservation

    /**
     * The run is in flight and the substrate was successfully inspected, but the
     * inspection found no live evidence. This is a FACT about the substrate, not
     * a refusal — the runtime knows the run exists but the journal / lease record
     * / event plane is empty.
     *
     * The M1-A correction (see M1 design revision note 1) made this the same
     * distinction on the introspection port: "unknown run" vs "empty-but-known".
     * The contract test `IntrospectionUnknownVsEmptyTest` (§7.1 of the design)
     * pins the distinction.
     */
    data class LiveButEmpty(
        val attempt: AttemptId,
        val reason: String,
    ) : RuntimeObservation

    /**
     * The substrate could not be inspected. The `reason` is a closed
     * [IntrospectionFailure] so a `when` over the typed reasons fails to compile
     * when a new substrate is added.
     */
    data class Unobservable(
        val reason: IntrospectionFailure,
    ) : RuntimeObservation
}

/** The current attempt a run is on. */
@JvmInline
value class AttemptId(val value: Int) {
    init { require(value >= 1) { "attempt must be >= 1, got $value" } }
}

/**
 * The fence token a lease carries, projected as a primitive `Long`.
 *
 * The design uses `FencingToken` (a value class in `:pipeline-events-store`).
 * Projecting to `Long` here keeps the public ABI free of any
 * `:pipeline-events-store` reference: a consumer of `:pipeline-runtime` does
 * not need JDBC, SQLite, or the file-backed lease on its classpath to handle
 * the answer.
 */
typealias FencingToken = Long

/**
 * The lease holder as observed by the pure decider. `null` when the run is
 * unowned at inspection time (the lease was released, or no acquisition has
 * happened yet).
 *
 * `ownerId` is a `String` — the existing M1 read-side surface uses `String`
 * for owner ids, and the public ABI preserves that convention.
 */
data class LeaseHolder(
    val ownerId: String,
    val fencingToken: FencingToken,
    val alive: Boolean?,
)

/**
 * The position of the journal relative to the live run.
 *
 * Monotonically non-decreasing across two consecutive `inspect` calls on the
 * same run. `latestOpId` and `latestTerminalAtMs` are nullable because a fresh
 * run has neither a journaled terminal nor any op at all.
 */
data class JournalPosition(
    val operations: Int,
    val latestOpId: String?,
    val latestTerminalAtMs: Long?,
)

/**
 * A bounded view of one output stream's tail state.
 *
 * Mirrors the published `OutputTailState` discriminated union without dragging
 * the type along: `state` is one of [TailState.Open] / [TailState.Sealed].
 */
data class OutputTailView(
    val stream: OutputStreamId,
    val state: TailState,
    val lastOrdinal: Long?,
)

/**
 * Mirrors the published `OutputTailState` discriminated union.
 *
 * Either the stream can still grow (`Open(committedEnd)`) or it has been sealed
 * (`Sealed(finalEnd)`). The shape is the same one M1's `OutputTailState`
 * publishes; the dedicated type here is a structural mirror so the
 * introspection port's projection can stay in `:pipeline-runtime`.
 */
sealed interface TailState {
    data class Open(val committedEnd: Long) : TailState
    data class Sealed(val finalEnd: Long) : TailState
}

/**
 * A typed terminal observation, projected from the journal's terminal row.
 *
 * Closed ADT: every mapping the M2 design names is here, plus `Other` for the
 * schema-extension safety valve (a journaled terminal that does not map to the
 * canonical outcomes is surfaced verbatim rather than collapsed to `Failed`).
 */
sealed interface TerminalObservation {
    data class Succeeded(val terminalAtMs: Long) : TerminalObservation
    data class Failed(val failureKind: String, val terminalAtMs: Long) : TerminalObservation
    data class Unstable(val terminalAtMs: Long) : TerminalObservation
    data class Aborted(val terminalAtMs: Long) : TerminalObservation
    data class Cancelled(val terminalAtMs: Long) : TerminalObservation
    /** The journal recorded a terminal that does not map to the canonical outcomes. */
    data class Other(val rawOutcome: String, val terminalAtMs: Long) : TerminalObservation
}

/**
 * Closed ADT of reasons the substrate could not be inspected at all.
 *
 * Mirrors the audit's B.1 distinction: "substrate cannot be inspected" (this
 * refusal) is a different type from "substrate was inspected and yields
 * nothing" ([RuntimeObservation.LiveButEmpty]). The two cases are deliberately
 * different types so a consumer can `when` on them without parsing a free-text
 * reason.
 */
sealed interface IntrospectionFailure {
    /** No `--control-root` was given at startup; there is no journal or lease to consult. */
    data object NoControlRoot : IntrospectionFailure
    /** The event plane is not configured for this instance. */
    data object NoEventStore : IntrospectionFailure
    /** The output plane is not configured for this instance. */
    data object NoOutputPlane : IntrospectionFailure
    /** The journal could not be loaded. */
    data object NoJournal : IntrospectionFailure
    /** The underlying storage failed; `cause` is a short diagnostic. */
    data class StorageError(val cause: String) : IntrospectionFailure
}

/**
 * A process-scoped handle to the live writer (PID + host). Optional; cross-process
 * introspection always returns `null`. The contract does not depend on it.
 */
data class ProcessRef(val host: String, val pid: Long)
