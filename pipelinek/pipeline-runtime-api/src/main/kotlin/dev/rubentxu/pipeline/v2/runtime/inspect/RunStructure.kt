package dev.rubentxu.pipeline.v2.runtime.inspect

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.output.OutputStreamId

/**
 * Closed ADT returned by [RuntimeIntrospectionPort.runStructure].
 * Three cases:
 *  - [Stages] — the run has at least one journaled stage; the typed enumeration is
 *    present. The list is bounded by the call's [StructureRunOptions.pageSize].
 *  - [Empty] — the run is known to the runtime but has no journaled stages yet
 *    (a fresh run, or a run whose stages have not been journaled).
 *  - [Unavailable] — the substrate cannot be inspected at all; `refusal` carries
 *    the closed [IntrospectionRefusal] reason.
 * A sealed result ADT with three cases, no
 * `Either`/`Result` reuse. The distinction between [Empty] and [Unavailable] is
 * the same one [RuntimeObservation.LiveButEmpty] pins for the inspect port:
 * "known but empty" vs "substrate unobservable".
*/
sealed interface RunStructure {
    /**
     * The run has journaled stages; `stages` is the bounded enumeration.
     * The list is bounded by the [StructureRunOptions.pageSize] of the call.
     * A consumer that wants the next page reads the [StructureCursor] the
     * adapter attaches (returned in [RunStructureResult.Structure.cursor]).
*/
    data class Stages(val stages: List<StageSummary>) : RunStructure {
        init {
            // The list can be empty (the journal may have a stage row whose
            // `steps` is empty). The bounded check is on the operations, not
            // the stages.
            require(stages.size <= StructureRunOptions.MAX_STRUCTURE_PAGE_SIZE) {
                "stages.size must be <= ${StructureRunOptions.MAX_STRUCTURE_PAGE_SIZE}, got ${stages.size}"
            }
        }
    }

    /** The run is known to the runtime but has no journaled stages yet. */
    data object Empty : RunStructure

    /**
     * The substrate could not be inspected at all. The `refusal` carries the
     * closed [IntrospectionRefusal] reason (UnknownRun / NoControlRoot /
     * StorageError / etc.).
*/
    data class Unavailable(val refusal: IntrospectionRefusal) : RunStructure
}

/** The closed envelope around [RunStructure]; mirrors the [RuntimeIntrospectionResult] shape */
sealed interface RunStructureResult {

    /**
     * The structure call succeeded. `structure` is the typed view; `cursor` is
     * the resume cursor for the next page (null when the result is the
     * last page or the result is [RunStructure.Empty] / [RunStructure.Unavailable]).
*/
    data class Structure(
        val structure: RunStructure,
        val cursor: StructureCursor?,
    ) : RunStructureResult

    /** The call could not be served; `refusal` is the closed [IntrospectionRefusal] reason. */
    data class Refused(val refusal: IntrospectionRefusal) : RunStructureResult
}

/**
 * A bounded view of one stage of a run.
 * `steps` is the list of journaled steps in execution order. `finishedAtMs`
 * is `null` while the stage is in flight.
*/
data class StageSummary(
    val stageOrdinal: Int,
    val stageIndex: String,
    val startedAtMs: Long,
    val finishedAtMs: Long?,
    val steps: List<StepSummary>,
) {
    init {
        require(stageOrdinal >= 0) { "stageOrdinal must be >= 0, got $stageOrdinal" }
        require(stageIndex.isNotBlank()) { "stageIndex must be non-blank" }
        require(startedAtMs >= 0) { "startedAtMs must be >= 0, got $startedAtMs" }
        require(finishedAtMs == null || finishedAtMs >= startedAtMs) {
            "finishedAtMs must be null or >= startedAtMs, got $finishedAtMs vs $startedAtMs"
        }
        require(steps.size <= StructureRunOptions.MAX_STRUCTURE_PAGE_SIZE) {
            "steps.size must be <= ${StructureRunOptions.MAX_STRUCTURE_PAGE_SIZE}, got ${steps.size}"
        }
    }
}

/**
 * A bounded view of one step of a stage.
 * `state` is the typed [StepState]; `operations` is the list of journaled
 * operations in execution order. `finishedAtMs` is `null` while the step is
 * in flight.
*/
data class StepSummary(
    val stepOrdinal: Int,
    val stepKey: String,
    val startedAtMs: Long,
    val finishedAtMs: Long?,
    val state: StepState,
    val operations: List<OperationSummary>,
) {
    init {
        require(stepOrdinal >= 0) { "stepOrdinal must be >= 0, got $stepOrdinal" }
        require(stepKey.isNotBlank()) { "stepKey must be non-blank" }
        require(startedAtMs >= 0) { "startedAtMs must be >= 0, got $startedAtMs" }
        require(finishedAtMs == null || finishedAtMs >= startedAtMs) {
            "finishedAtMs must be null or >= startedAtMs"
        }
        require(operations.size <= StructureRunOptions.MAX_STRUCTURE_PAGE_SIZE) {
            "operations.size must be <= ${StructureRunOptions.MAX_STRUCTURE_PAGE_SIZE}, got ${operations.size}"
        }
    }
}

/**
 * A bounded view of one operation of a step.
 * `status` is the journal's [OperationStatus] enum, projected verbatim.
 * `fingerprint` is the SHA-256 of the operation's input— null
 * when the journal did not record one. `streams` names the declared stdout
 * / stderr streams of the operation.
*/
data class OperationSummary(
    val operationId: String,
    val attempt: Int,
    val status: OperationStatus,
    val startedAtMs: Long?,
    val endedAtMs: Long?,
    val fingerprint: String?,
    val streams: OperationOutputStreams,
) {
    init {
        require(operationId.isNotBlank()) { "operationId must be non-blank" }
        require(attempt >= 1) { "attempt must be >= 1, got $attempt" }
        require(endedAtMs == null || startedAtMs == null || endedAtMs >= startedAtMs) {
            "endedAtMs must be null or >= startedAtMs, got $endedAtMs vs $startedAtMs"
        }
        require(fingerprint == null || Sha256.isSha256Hex(fingerprint)) {
            "fingerprint must be null or a 64-char lowercase hex SHA-256; got '$fingerprint'"
        }
    }
}

/**
 * Closed ADT of step states. Mirrors the journal's [OperationStatus] at the
 * step level (a step is "Succeeded" when its last operation is
 * [OperationStatus.SUCCEEDED], etc.). New step states are compile errors at
 * every `when` site that exhausts this ADT.
*/
sealed interface StepState {
    data object Pending : StepState
    data object Running : StepState
    data object Succeeded : StepState
    data class Failed(val failureKind: String) : StepState
    data object Cancelled : StepState
    data object Skipped : StepState
}

/**
 * The declared stdout / stderr streams of one operation.
 * Both fields are nullable because the operation may declare neither (a
 * pure computation), one, or both. The stream id is the published
 * [OutputStreamId] value class so the consumer can pass it directly to
 * `OutputReadPort.read / readRange / readRangeDigested`.
 * ## Why the two must differ when both are present
 * None/one/both are the three real states of "what did this operation declare",
 * so the nullability stays — but "both" means exactly **two** streams. A value
 * naming one stream twice tells a consumer reading `stdoutStream` and
 * `stderrStream` that it will see the same bytes twice and never the other
 * channel.
 * The guard is therefore **not** the `stdoutStream != stderrStream` it looks
 * like it should be: that form refuses `null != null`, which is `false`, which
 * rejects the *none* state — the one an operation declaring no stream produces.
 * Measured in an earlier build; the row that caught it is
 * `the three real states are all constructible`. A guard that reads correctly
 * and rejects a real state is worse than none, because the type then promises
 * something it cannot keep.
 * **Latent, not shipped.** The only construction,
 * `RuntimeIntrospectionPortStoreAdapter.streamsForOperation`, matches `::stdout`
 * and `::stderr` as disjoint suffixes, so it cannot produce a duplicate — but
 * that is a property of one caller being correct, not of the type. Its sibling
 * in `dev.rubentxu.pipeline.v2.output` had the same hole with the same cause,
 * and closing only that one would have left the shape broken twice.
*/
data class OperationOutputStreams(
    val stdoutStream: OutputStreamId?,
    val stderrStream: OutputStreamId?,
) {
    init {
        require(stdoutStream == null || stderrStream == null || stdoutStream != stderrStream) {
            "the two fields must name two different streams, and both name $stdoutStream. " +
                "An operation that declared stdout and stderr has two streams; a reader given one " +
                "twice sees the same bytes twice and never sees the other channel."
        }
    }
}
