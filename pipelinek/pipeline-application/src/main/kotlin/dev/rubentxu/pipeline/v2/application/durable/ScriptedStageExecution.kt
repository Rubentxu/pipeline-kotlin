package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.scripted.ScriptedFrontendRunner
import dev.rubentxu.pipeline.v2.application.scripted.ScriptedStructuralAddress
import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.ScriptedStageRef
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Path

/**
 * S4-F2 — executes ONE scripted stage body, and nothing else.
 *
 * ## The scope, stated as a boundary
 *
 * This class does the three things a stage body needs and refuses the rest:
 *
 * 1. **resolve** the [ScriptedStageRef] to a compiled artifact, through [ScriptedStageRegistry];
 * 2. **execute** it by delegating to [ScriptedFrontendRunner] — the same adapter, the same
 *    authorities, the same journal the standalone scripted path already uses;
 * 3. **project** the aggregate into a [StepOutcome], which is the carrier the stage fold consumes.
 *
 * It does NOT decide a stage outcome, emit a bookend, run a `post` finalizer or advance the
 * replay cursor. Those are [StageExecutionEngine]'s, and taking any of them here would create the
 * second authority that ADR-0103 D7 was written to prevent. The cursor is named in that sentence
 * deliberately: a scripted stage's operations do not advance the canonical cursor, so a resumed run
 * re-enters the body and reuses each operation by its own [dev.rubentxu.pipeline.v2.domain.durable.EffectReplayPolicy].
 * That is the whole-program scripted guarantee, unchanged, and it needs no new durable operation
 * kind.
 *
 * ## Why the projection is a projection and not a second precedence
 *
 * [ScriptedFrontendRunner] reduces a whole body to a [RunOutcome] with `RunOutcomeReducer`, and
 * that reduction is the single place precedence is decided. Mapping the result onto the carrier a
 * stage fold reads is not a second precedence: the order was already applied. Re-deriving it here
 * is exactly the S4-D2 defect the runner's own KDoc records — precedence written twice, with the
 * two copies free to disagree about whether an `Unstable` step means an `Unstable` run.
 */
class ScriptedStageExecution(
    private val scriptedArtifacts: ScriptedStageRegistry,
    private val stepRegistry: StepRegistry,
    private val journal: OperationJournal,
    private val eventSink: EventSink,
    private val clock: Clock,
    private val controlDirRoot: Path,
) {

    /** What one scripted stage body produced. Closed: a stage either ran, or said why it did not. */
    sealed interface Verdict {

        /**
         * The body ran to its end. [outcome] is the reduction of every operation it recorded, carried
         * in the shape the stage fold consumes.
         */
        data class Completed(val outcome: StepOutcome) : Verdict

        /**
         * The body could not run. A refusal, not an empty success: a scripted stage that silently
         * became "nothing to do" is the silent no-op the semantic constitution forbids.
         */
        data class Refused(val reason: Refusal) : Verdict
    }

    /**
     * Why a scripted stage did not run, and who has to fix it.
     *
     * Every case is a NAMED condition with an owner. A `null` or an empty message would leave the
     * reader with a run that failed for a reason nobody can name, which is the hardest kind of
     * pipeline failure to act on.
     */
    sealed interface Refusal {

        /** The pipeline names an artifact nobody compiled for this run. The build is incomplete. */
        data class UnknownArtifact(val artifactKey: String) : Refusal

        /** The artifact was compiled, but it has no such entry point. The pipeline is wrong. */
        data class EntryPointMissing(val artifactKey: String, val entryPointId: String) : Refusal

        /**
         * The artifact's own identity is not the one the pipeline was compiled against.
         *
         * Distinct from [UnknownArtifact] on purpose: the object is present, so the diagnosis is
         * "the registry holds something else" and not "nothing was registered".
         */
        data class ArtifactIncompatible(val message: String) : Refusal

        /**
         * The refusal as a sentence a reader can act on.
         *
         * Each case carries its own wording rather than a `when` elsewhere mapping the cases to
         * strings, so a refusal cannot be constructed without saying what it is and a fourth case
         * cannot be added without saying what it means. `StageExecutionEngine` embeds this in the
         * run's failure message, so the reader never sees an enum constant.
         */
        fun describe(): String = when (this) {
            is UnknownArtifact -> "no compiled artifact is registered under '$artifactKey'"
            is EntryPointMissing -> "the artifact '$artifactKey' has no entry point '$entryPointId'"
            is ArtifactIncompatible -> "artifact identity mismatch: $message"
        }
    }

    /**
     * Runs [stage]'s scripted body with the stage's real structural address.
     *
     * Total: resolution and compatibility are refusals, an aborted body is [StepOutcome.Failure],
     * and the only thing that can escape is an invariant violation, which is a defect rather than an
     * operational outcome.
     */
    fun run(
        stage: StageNode,
        stageIndex: Int,
        runId: RunId,
        shOptions: ShOptions,
    ): Verdict {
        val ref = (stage.body as? StageBody.Scripted)?.ref
            ?: return Verdict.Refused(Refusal.UnknownArtifact("<stage '${stage.name}' is not a scripted body>"))
        return run(ref, stage, stageIndex, runId, shOptions)
    }

    /** The same path, addressed by its ref, so the edge is testable without building a stage. */
    fun run(
        ref: ScriptedStageRef,
        stage: StageNode,
        stageIndex: Int,
        runId: RunId,
        shOptions: ShOptions,
    ): Verdict {
        val entryPoint = when (val resolution = scriptedArtifacts.resolve(ref)) {
            is ScriptedStageResolution.Resolved -> resolution.entryPoint
            is ScriptedStageResolution.UnknownArtifact ->
                return Verdict.Refused(Refusal.UnknownArtifact(resolution.artifactKey))
            is ScriptedStageResolution.EntryPointMissing ->
                return Verdict.Refused(Refusal.EntryPointMissing(resolution.artifactKey, resolution.entryPointId))
        }

        val outcome = ScriptedFrontendRunner.run(
            entryPoint = entryPoint,
            runId = runId.value,
            // The artifact IS the expected identity: the registry resolved under a key derived from
            // it. Passing it as `expected` is what turns a stale registry into a refusal rather
            // than a substitution, and it is the same rule the standalone frontend applies.
            expectedArtifact = entryPoint.artifact,
            registry = stepRegistry,
            journal = journal,
            eventSink = eventSink,
            clock = clock,
            shOptions = shOptions,
            controlDirRoot = controlDirRoot,
            structure = ScriptedStructuralAddress.InCanonicalStage(
                stageIndex = stageIndex,
                stageName = stage.name,
            ),
        )

        return when (outcome) {
            is ScriptedFrontendRunner.Outcome.Completed -> Verdict.Completed(outcome.aggregate.asStepOutcome())
            is ScriptedFrontendRunner.Outcome.ArtifactIncompatible ->
                Verdict.Refused(Refusal.ArtifactIncompatible(outcome.message))
        }
    }
}

/**
 * Carries a reduced [RunOutcome] into the carrier a stage fold reads.
 *
 * ## Why this is a function and not a second precedence table
 *
 * The precedence `Failure > Unstable > Success` was already applied by `RunOutcomeReducer` before
 * this runs. Re-deciding it here is what S4-D2 removed from this codebase, and a stage is the
 * worst place to reintroduce it: an `Unstable` body and an `Unstable` step are different facts, and
 * a table that "knows" how to bridge them is a table that will be edited by someone who thinks
 * they are fixing a symptom.
 *
 * ## Why `Aborted` is an invariant violation here, and not a case
 *
 * `RunOutcomeReducer` states the law in its own KDoc: `Aborted` is **never derived from steps**, and
 * `RunCoordinator` adds that it is set explicitly by the orchestrator when a run is cancelled or
 * interrupted. A scripted body is steps, so it cannot produce `Aborted` — the case is unreachable by
 * construction.
 *
 * That is why it is not mapped to a `FailureKind`. Inventing a kind for it (there is no `CANCELLED`
 * in [dev.rubentxu.pipeline.v2.domain.FailureKind], and no `CANCELLED` was added) would have turned
 * a broken contract into an ordinary pipeline failure that a reader would go looking for in the
 * wrong place. Throwing [EngineInvariantViolation] says the true thing: the reducer's law is
 * broken, and the run's outcome must not be derived from a value that should not exist.
 *
 * It is a `throw` and not a `when` branch precisely because an exhaustive `when` must still name
 * the case; naming it by asserting it is unreachable is how the law stays visible in the code that
 * depends on it.
 */
private fun RunOutcome.asStepOutcome(): StepOutcome = when (this) {
    is RunOutcome.Success -> StepOutcome.Success
    is RunOutcome.Unstable -> StepOutcome.Unstable
    is RunOutcome.Failure -> StepOutcome.Failure(failure)
    is RunOutcome.Aborted -> throw EngineInvariantViolation(
        "a scripted body reduced to RunOutcome.Aborted, which RunOutcomeReducer forbids deriving " +
            "from steps. If this fired, the reducer's contract is broken and the run's outcome is " +
            "being derived from a value that cannot exist.",
    )
}
