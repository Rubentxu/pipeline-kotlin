package dev.rubentxu.pipeline.v2.runtime.inspect

import dev.rubentxu.pipeline.v2.domain.AttemptId
import dev.rubentxu.pipeline.v2.output.OutputStreamId

/**
 * Closed ADT that names "what does this run look like right
 * now?". Each case names a substrate condition; new cases are compile errors at
 * every `when` site that exhausts this ADT.
 *  - `Running.process` is OPTIONAL (the runtime does not always have a durable
 *    reference to the live writer process — e.g. when an external consumer is on
 *    a different host). The contract does not depend on it.
 *  - `Terminal.terminal` carries a typed [TerminalObservation] (NOT a raw
 *    `(StepOutcome, OperationStatus)` pair), which names the fix for the
 *    category error between "what the journal recorded"
 *    and "what the run semantically is".
 *
 * What a consumer outside this process may do with these facts, and what it
 * may not do, is the contract the root README states under "Reading a run
 * from outside".
*/
sealed interface RuntimeObservation {

    /**
     * The run is in flight and observed.
     * `leaseHolder` is the durable, cross-process fact an external consumer can act on (the
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
     * The introspection port draws the same distinction: "unknown run" and
     * "empty but known" are two different answers, and a consumer that cannot
     * tell them apart cannot decide whether to wait for the run or to give it
     * up.
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

/**
 * The fence token a lease carries, projected as a primitive `Long`.
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
 * `ownerId` is a `String` — the existing read-side surface uses `String`
 * for owner ids, and the public ABI preserves that convention.
*/
data class LeaseHolder(
    val ownerId: String,
    val fencingToken: FencingToken,
    val alive: Boolean?,
)

/**
 * The position of the journal relative to the live run.
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
 * Either the stream can still grow (`Open(committedEnd)`) or it has been sealed
 * (`Sealed(finalEnd)`). The shape is the same one 's `OutputTailState`
 * publishes; the dedicated type here is a structural mirror so the
 * introspection port's projection can stay in `:pipeline-runtime`.
*/
sealed interface TailState {
    /**
     * The stream can still grow.
     *
     * [committedEnd] is the extent committed when the state was observed. It
     * says nothing about whether a writer is attached: a stream whose
     * process died is still open until something records its end.
     */
    data class Open(val committedEnd: Long) : TailState

    /**
     * The stream will not grow again.
     *
     * [finalEnd] is the extent the stream had when it was sealed, which is
     * its committed extent afterwards: a sealed stream cannot grow, so the
     * two are the same number.
     */
    data class Sealed(val finalEnd: Long) : TailState
}

/**
 * A typed terminal observation, projected from the journal's terminal row.
 * Closed ADT: every mapping the design names is here, plus `Other` for the
 * schema-extension safety valve (a journaled terminal that does not map to the
 * canonical outcomes is surfaced verbatim rather than collapsed to `Failed`).
 *
 * ## The vocabulary of what was OBSERVED, and where each word is pronounced
 *
 * This type owns the vocabulary of the terminal state a runtime OBSERVED: the
 * six forms [Succeeded], [Failed], [Unstable], [Aborted], [Cancelled] and
 * [Other]. It is not persisted, and it is not a synonym of the run's RESULT
 * (`RunOutcome` in the domain): it has a `Cancelled` that the result does not
 * have, and it calls `Succeeded` what the result calls success. The two answer
 * different questions — what the run ENDED as, and what was SEEN — so a reader
 * that swaps them reads a cancelled run as a failed one.
 *
 * - **To the operator**: `MainRunsCli` renders it as succeeded, failed,
 *   unstable, aborted, cancelled, or the raw word when there is no other.
 * - **Only inside**: the recovery journal writes the same words beside the
 *   operation's state.
 *
 * The run's RESULT crosses the wire as a word and is recovered by
 * `RunOutcome.parse`; this projection is what the runtime saw of it.
*/
sealed interface TerminalObservation {
    /**
     * The run ended with its work done.
     *
     * Every step of the run succeeded, and the runtime observed that
     * terminal. [terminalAtMs] is the moment the run's own terminal carries,
     * not the moment the observation was taken.
     */
    data class Succeeded(val terminalAtMs: Long) : TerminalObservation

    /**
     * The run ended in failure.
     *
     * [failureKind] is the run's own word for the failure, and a bare one
     * names the outcome without naming the cause: the cause lives in the
     * run's failure fact. [terminalAtMs] is the moment the run's own
     * terminal carries.
     */
    data class Failed(val failureKind: String, val terminalAtMs: Long) : TerminalObservation

    /**
     * The run ended degraded: some step asked for attention and none
     * failed.
     *
     * It is not a failure, and the runtime keeps it apart from one, because
     * a step that reports a degraded run as a failed one says the opposite
     * of what the run said. [terminalAtMs] is the moment the run's own
     * terminal carries.
     */
    data class Unstable(val terminalAtMs: Long) : TerminalObservation

    /**
     * The run ended with the `aborted` word: it was stopped before it
     * reached a normal terminal state.
     *
     * The word is the run's own, read back from the outcome the run
     * recorded. [terminalAtMs] is the moment the run's own terminal carries.
     */
    data class Aborted(val terminalAtMs: Long) : TerminalObservation

    /**
     * The run was cancelled.
     *
     * The word belongs to what was observed, and the run's own result
     * vocabulary has no word for it: that vocabulary says `aborted` for a
     * run that was cancelled or interrupted, so a consumer that swaps the
     * two reads a cancelled run as an aborted one. [terminalAtMs] is the
     * moment the run's own terminal carries.
     */
    data class Cancelled(val terminalAtMs: Long) : TerminalObservation
    /** The journal recorded a terminal that does not map to the canonical outcomes. */
    data class Other(val rawOutcome: String, val terminalAtMs: Long) : TerminalObservation
}

/**
 * Closed ADT of reasons the substrate could not be inspected at all.
 * Mirrors the distinction: "substrate cannot be inspected" (this
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
