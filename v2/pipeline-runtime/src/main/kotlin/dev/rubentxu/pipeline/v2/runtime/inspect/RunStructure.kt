package dev.rubentxu.pipeline.v2.runtime.inspect

import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.output.OutputStreamId

/**
 * M6 — closed ADT returned by [RuntimeIntrospectionPort.runStructure].
 *
 * Three cases:
 *
 *  - [Stages] — the run has at least one journaled stage; the typed enumeration is
 *    present. The list is bounded by the call's [StructureRunOptions.pageSize].
 *  - [Empty] — the run is known to the runtime but has no journaled stages yet
 *    (a fresh run, or a run whose stages have not been journaled).
 *  - [Unavailable] — the substrate cannot be inspected at all; `refusal` carries
 *    the closed [IntrospectionRefusal] reason.
 *
 * Mirrors the M2 pattern: a sealed result ADT with three cases, no
 * `Either`/`Result` reuse. The distinction between [Empty] and [Unavailable] is
 * the same one [RuntimeObservation.LiveButEmpty] pins for the inspect port:
 * "known but empty" vs "substrate unobservable".
 *
 * @see M6_CONTEXT_PRESSURE_DESIGN.md §B.1.
 */
sealed interface RunStructure {
    /**
     * The run has journaled stages; `stages` is the bounded enumeration.
     *
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

/** The closed envelope around [RunStructure]; mirrors the M2 [RuntimeIntrospectionResult] shape. */
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
 *
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
 *
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
 *
 * `status` is the journal's [OperationStatus] enum, projected verbatim.
 * `fingerprint` is the SHA-256 of the operation's input (M3 sibling) — null
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
        require(fingerprint == null || fingerprint.length == 64) {
            "fingerprint must be null or 64 chars (SHA-256), got ${fingerprint?.length}"
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
 *
 * Both fields are nullable because the operation may declare neither (a
 * pure computation), one, or both. The stream id is the published
 * [OutputStreamId] value class so the consumer can pass it directly to
 * `OutputReadPort.read / readRange / readRangeDigested`.
 */
data class OperationOutputStreams(
    val stdoutStream: OutputStreamId?,
    val stderrStream: OutputStreamId?,
)
