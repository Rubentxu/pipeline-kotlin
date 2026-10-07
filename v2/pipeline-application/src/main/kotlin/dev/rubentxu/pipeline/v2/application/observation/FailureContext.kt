package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputReadPort
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputStreamAddress

/**
 * OBS-E3: the failure-context projection — the one view an agent asks for instead of reading a
 * 200 MB build log.
 *
 * ```text
 * OperationJournal (which operations failed)  +  Event Plane (what they said)  +  Output Plane
 *              │                                                        (what they printed)
 *              └──────────────────────┬───────────────────────────────────┘
 *                                     ▼
 *                              FailureContext
 * ```
 *
 * ## Why this is journal-first, and why that was not obvious
 *
 * The obvious implementation reads `StepFailed`, takes `stepName`/`stepIndex`, and looks for the
 * operation's output. That cannot work, and not because of a missing convenience:
 *
 * **`StepFailed` carries no `stageIndex`** ([dev.rubentxu.pipeline.v2.events.StepFailed] declares
 * `runId`, `stepIndex`, `stepName`, `stepType`, `failureKind`, `message` — and that is all). An
 * output stream is named `{runId}/{operationId}/{channel}`, and the operation id is
 * `OpId.format()` = `{runId}-s{stageIndex}-{stepIndex}[-b{branch}][-bp{N}-{childIndex}:{pluginStepId}...]`.
 * Without the stage there is no operation id, so a reader starting from the event would have to
 * **invent** the identity it is about to read by.
 *
 * The other direction works exactly. [OperationJournal.listForRun] returns each
 * [DurableOperation], and its `id` is written by `StepDispatchEngine` as `opId.format()` — the same
 * segment the stream is named by. The journal also knows the terminal status. So the journal
 * locates the failure, and the Output Plane supplies what it printed.
 *
 * ## The message stays at run scope, on purpose
 *
 * The journal does NOT carry the failure message: `DurableStepExecutor` writes `OperationOutput`
 * only when there is an `encodedOutput`, and a failed step's `CommonExecutionResult` has
 * `encodedOutput = null`, so a failed row's output is absent. The message therefore exists only in
 * the Event Plane.
 *
 * And the message cannot be attached to an operation, for the same missing `stageIndex`. So
 * [FailureContext.facts] holds the run's `StepFailed` events **without an operation attached**,
 * even when there is exactly one of each. Attaching them would be a guess that reads as
 * knowledge; when two stages each fail an `sh` step, `stepName` + `stepIndex` match both.
 *
 * A consumer that needs one line of "what went wrong" therefore reads [FailureContext.facts], not a
 * guessed pairing — and it is the only honest pairing available, which is the point.
 *
 * ## A refusal is never an empty tail
 *
 * "This step printed nothing" and "the store would not answer" are different facts. A reader that
 * collapsed them would report a broken store as a silent step, which is precisely the defect
 * `ObservationOutputRead` was sealed to close. So a refusal anywhere fails the whole read as
 * [FailureContextRead.Refused] instead of yielding empty strings.
 *
 * ## `UNSTABLE` is not a failure
 *
 * [FailureStatus.isFailure] excludes `UNSTABLE` deliberately. Its own contract says so: Jenkins has
 * `unstable` as a build result distinct from `failure`, and that is what `warnError` produces — the
 * work ran. Listing it here would make `failure-context` report runs that did not fail. Note that
 * [OperationStatus.isPollFailure] happens to hold the same set but answers a different question (does
 * a retry advance?), so this projection states its own rule rather than borrowing a retry-named
 * predicate and inheriting its future changes.
 *
 * ## Bounded on both axes
 *
 * [FailureContextReader.tailBytes] bounds each channel's tail, and the reader reads from the END of
 * the committed extent rather than draining forward. A failing build is the case where output is
 * largest, and "the last 64 KiB of stderr" is what answers "why did this fail"; the first 64 KiB
 * does not.
 */
object FailureStatus {

    /**
     * Whether [status] means the operation did not deliver its result.
     *
     * Closed on purpose, and exhaustive without an `else`: a new status must be classified here
     * rather than inherited by accident. The exclusions are reasoned, not incidental:
     *
     * - `SUCCEEDED` — it delivered.
     * - `UNSTABLE` — its own contract says the work ran and something in it deserves attention,
     *   which is what `warnError` produces. A run that only went unstable did not fail.
     * - `PENDING`, `RUNNING` — not finished, so not a failure yet. A run still executing has no
     *   failure context to report, and inventing one would make `observe --follow` report a
     *   failure for every step it sees.
     *
     * Note that [OperationStatus.isPollFailure] holds the same five as failures, but it answers a
     * different question (does a retry advance?), so this states its own rule rather than
     * borrowing a retry-named predicate and inheriting whatever it is changed to mean next.
     */
    fun isFailure(status: OperationStatus): Boolean = when (status) {
        OperationStatus.FAILED,
        OperationStatus.FAILED_TIMEOUT,
        OperationStatus.LOST,
        OperationStatus.ABORTED,
        OperationStatus.DIVERGENT,
        -> true

        OperationStatus.SUCCEEDED,
        OperationStatus.UNSTABLE,
        OperationStatus.PENDING,
        OperationStatus.RUNNING,
        -> false
    }
}

/**
 * The identity of an operation that did not succeed, decided without touching any store.
 *
 * This is the decision half of the projection: it answers "which operations failed" from the
 * journal alone, so the interpretation half is only ever about fetching bytes for operations already
 * chosen. Keeping them apart is what makes [selectFailures] testable with no filesystem, no store
 * and no journal.
 */
data class FailedOperationIdentity(
    /** The operation id, which is exactly the middle segment of this operation's stream ids. */
    val operationId: String,

    /**
     * The `StepId` the journal recorded, such as `build/sh-0`.
     *
     * **Not** the operation id, and not interchangeable with it: the two are what
     * `ObservationOperationIdShapeTest` exists to keep apart. This one names the step in the
     * pipeline; [operationId] names the durable operation whose streams hold its bytes.
     */
    val stepId: String,

    val status: OperationStatus,
    val attempt: Int,
)

/**
 * The operations of a run that did not succeed, in journal order.
 *
 * Pure: [journal] is data, not a port. `listForRun` already returns rows ordered by `created_at`
 * ascending, and that order is preserved rather than re-sorted, because "which failed first" is a
 * fact the journal recorded and re-sorting by status would discard it.
 *
 * Control rows are NOT filtered out. Engine-driven rows (retry, wait-until, parallel aggregate) are
 * real operations that can genuinely fail, and excluding them by name would be a `when(stepKey)`
 * branch in a read model — the exact defect the open Step registry exists to remove. A control row
 * simply has no output streams, which [FailureContext] reports as empty tails rather than hiding it.
 */
fun selectFailures(journal: List<DurableOperation>): List<FailedOperationIdentity> =
    journal
        .filter { FailureStatus.isFailure(it.status) }
        .map { operation ->
            FailedOperationIdentity(
                operationId = operation.id,
                stepId = operation.input.stepId,
                status = operation.status,
                attempt = operation.attempt,
            )
        }

/** One failed operation together with the bytes it had committed before it stopped. */
data class FailedOperation(
    val identity: FailedOperationIdentity,

    /**
     * The last [FailureContextReader.tailBytes] bytes of its stdout, decoded for reading.
     *
     * Empty means "this operation committed no stdout", never "reading failed" — a refusal fails the
     * whole read instead, as [FailureContextRead.Refused].
     */
    val stdoutTail: String,

    /** Its stderr, under the same rules as [stdoutTail]. */
    val stderrTail: String,
) {
    /** Whether this operation committed any byte at all. Most steps that fail committed none. */
    val wroteOutput: Boolean get() = stdoutTail.isNotEmpty() || stderrTail.isNotEmpty()
}

/**
 * Why a run went wrong, assembled read-side from the authorities that can each say part of it.
 *
 * Not persisted, for the same reason [ObservationRecord] is not: it is a projection over facts that
 * already exist, and freezing it would create a copy that later corrections could not reach.
 */
data class FailureContext(
    val runId: String,

    /** Operations that did not succeed, with what they printed. Empty when the run did not fail. */
    val operations: List<FailedOperation>,

    /**
     * The run's failure events, at RUN scope.
     *
     * Deliberately not paired with [operations]: [StepFailed] has no `stageIndex`, so no pairing is
     * derivable. See the type KDoc.
     */
    val facts: List<StepFailed>,

    /** The run's own outcome, when the run reached one. `null` while it is still running. */
    val runOutcome: String?,
) {
    /** Whether this run failed at all. A run that only ran `unstable` did not. */
    val didFail: Boolean get() = operations.isNotEmpty()
}

/** The result of reading a failure context. A refusal is not an empty context. */
sealed interface FailureContextRead {

    data class Page(val context: FailureContext) : FailureContextRead

    /**
     * The Output Plane would not answer.
     *
     * Never collapsed into an empty [FailureContext]: that would report a broken store as a run
     * that failed silently, which is the opposite of what happened.
     */
    data class Refused(val reason: OutputRefusal) : FailureContextRead
}

/**
 * Reads [FailureContext] by asking the journal which operations failed, then fetching a bounded tail
 * of each one's committed output.
 *
 * Pure in its decisions, effectful in its fetching — [selectFailures] is the whole of the first, and
 * it is separately testable.
 *
 * @param journal the execution authority. Its `DurableOperation.id` is the operation id the streams
 *   are named by; see the type KDoc for why the Event Plane cannot supply that.
 * @param store the byte authority, read only after an operation has been selected.
 * @param tailBytes how much of each channel to keep. A caller's number, not a default: how much of a
 *   failing build is worth carrying into an agent's context is a product decision, and a default
 *   here would hide it behind a call site that appears to say nothing.
 */
class FailureContextReader(
    private val journal: OperationJournal,
    private val store: OutputReadPort,
    private val tailBytes: Int,
) {

    init {
        require(tailBytes > 0) { "tailBytes must be positive, got $tailBytes" }
    }

    /**
     * The failure context of [runId].
     *
     * @param events the run's events, used only for the run-scoped facts and outcome. Defaults to
     *   empty so a caller that has no event lane still gets the operation half rather than a
     *   fabricated one; an absent event half is honest, an invented one is not.
     */
    fun read(runId: String, events: List<DomainEvent> = emptyList()): FailureContextRead {
        val failed = selectFailures(journal.listForRun(runId))

        val operations = ArrayList<FailedOperation>(failed.size)
        for (identity in failed) {
            val stdout = when (val tail = tailOf(runId, identity.operationId, OutputChannel.STDOUT)) {
                is Tail.Bytes -> tail.text
                is Tail.Refused -> return FailureContextRead.Refused(tail.reason)
            }
            val stderr = when (val tail = tailOf(runId, identity.operationId, OutputChannel.STDERR)) {
                is Tail.Bytes -> tail.text
                is Tail.Refused -> return FailureContextRead.Refused(tail.reason)
            }
            operations += FailedOperation(identity, stdout, stderr)
        }

        return FailureContextRead.Page(
            FailureContext(
                runId = runId,
                operations = operations,
                facts = events.filterIsInstance<StepFailed>(),
                runOutcome = events.filterIsInstance<dev.rubentxu.pipeline.v2.events.RunFinished>()
                    .lastOrNull()?.outcome,
            ),
        )
    }

    /**
     * The last [tailBytes] committed bytes of one channel, or a refusal.
     *
     * Reads from the END of the committed extent: for a failing build the tail is the evidence, and
     * draining from the start would cost the same work to reach a less useful window.
     */
    private fun tailOf(runId: String, operationId: String, channel: OutputChannel): Tail {
        val stream = OutputStreamAddress.of(runId, operationId, channel).stream

        // `null` extent means the stream was never opened, which is a fact rather than a refusal:
        // most operations that fail never ran a process, so they have no transcript at all.
        val end = store.committedExtent(stream) ?: return Tail.Bytes("")
        if (end == 0L) return Tail.Bytes("")

        val from = (end - tailBytes).coerceAtLeast(0L)
        return when (val read = store.readRange(stream, from, end)) {
            is OutputReadResult.Page -> Tail.Bytes(String(read.page.bytes, Charsets.UTF_8))
            is OutputReadResult.Refused -> Tail.Refused(read.reason)
        }
    }

    /** Why a tail read cannot be reported, or the text it read. */
    private sealed interface Tail {
        data class Bytes(val text: String) : Tail
        data class Refused(val reason: OutputRefusal) : Tail
    }
}