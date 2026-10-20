package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.application.RegistryStepMetadataResolver
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.DurableInvocationResolver
import dev.rubentxu.pipeline.v2.application.durable.DurableStepExecutor
import dev.rubentxu.pipeline.v2.application.durable.ExternalSubprocessRecovery
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.RecoveryInterpretationEngine
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionBoundary
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.RunOutcomeReducer
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.scripting.CompiledScriptedEntryPoint
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions


/**
 * LFC-2R / R4B — scripted frontend execution adapter.
 *
 * One scripted body execution = one canonical stage step (`s{stageIndex}-{stepIndex}`).
 * The adapter owns NO durable authority of its own: the invoker's registry invocation
 * and every `sh` operation write through the SAME [OperationJournal] instance the
 * canonical coordinator uses. Identity is R4A-L1's two-scheme law:
 *
 * - Structural addressing (this adapter): `s{stageIndex}-{stepIndex}` per body.
 * - Frontend addressing (inside the body): the invoker's deterministic
 *   `runId/entryPoint/callSite/scope/ordinal` keys, root-namespaced so they can
 *   never collide with structural keys.
 *
 * Main must never see this class name or any Step key; it selects the
 * [ScriptedFrontendForm] ADT case and delegates composition here.
 */
object ScriptedFrontendRunner {

    /** Compiled lowered source + its compatibility identity (what Main carries). */
    data class EntryPointArtifact(
        val loweredSource: String,
        val identity: dev.rubentxu.pipeline.v2.scripting.ScriptedArtifactIdentity,
    )

    /**
     * Closed result of one scripted frontend run.
     *
     * S4-D2: [Completed.aggregate] is a [RunOutcome], not a [StepOutcome]. It used to be a
     * `StepOutcome` that [dev.rubentxu.pipeline.v2.application.MainScriptedSupport] then mapped to
     * a `RunOutcome` with a second hand-written table — so precedence was written twice, and the
     * copy in the frontend could disagree with the reducer that is supposed to own it. Reducing
     * here and carrying the result makes `RunOutcome.kt`'s claim true: the run's outcome is
     * produced by `RunOutcomeReducer` and by nothing else.
     */
    sealed interface Outcome {
        /** Body completed; the aggregate is the single reduction of every recorded outcome. */
        data class Completed(val aggregate: dev.rubentxu.pipeline.v2.domain.RunOutcome) : Outcome

        /** The compiled artifact is incompatible with the current runtime/facade schema. */
        data class ArtifactIncompatible(val message: String) : Outcome
    }

    fun run(
        entryPoint: dev.rubentxu.pipeline.v2.scripting.CompiledScriptedEntryPoint,
        runId: String,
        expectedArtifact: dev.rubentxu.pipeline.v2.scripting.ScriptedArtifactIdentity,
        registry: StepRegistry,
        journal: OperationJournal,
        eventSink: dev.rubentxu.pipeline.v2.events.EventSink,
        clock: Clock,
        shOptions: ShOptions,
        controlDirRoot: java.nio.file.Path,
        structure: ScriptedStructuralAddress = ScriptedStructuralAddress.WholeProgram,
    ): Outcome {
        // Fail-closed artifact compatibility: never silently replay an old artifact.
        if (entryPoint.artifact.fingerprintMaterial() != expectedArtifact.fingerprintMaterial()) {
            return Outcome.ArtifactIncompatible(
                "scripted artifact identity mismatch: artifact ${entryPoint.artifact.fingerprintMaterial()} " +
                    "!= source ${expectedArtifact.fingerprintMaterial()}",
            )
        }

        // ADR-0103 RPL-4 + D7 — the scripted frontend COMPOSES the canonical authorities
        // instead of owning a second replay protocol. Everything below is existing
        // production wiring: the same resolver, the same executor, the same recovery
        // interpretation and the same registry boundary the canonical coordinator uses.
        //
        // `ExternalSubprocessRecovery(clock, controlDirRoot)` is the real port, so a scripted
        // `sh` that was RUNNING when the process died is genuinely recoverable rather than
        // refused — which is what the previous hand-written `when (status)` table made
        // impossible.
        val resolver = DurableInvocationResolver(
            divergenceDetector = StrictFingerprintDivergenceDetector(),
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            journal = journal,
            runningSubprocessRecovery = ExternalSubprocessRecovery(clock, controlDirRoot),
        )
        val executor = DurableStepExecutor(
            eventSink = eventSink,
            executionBoundary = RegistryExecutionBoundary.adapt(milestoneStateStore = null),
            journal = journal,
        )
        // S4-F1-C2: the scripted surface runs the SAME authorities as the canonical coordinator,
        // with the materialiser supplied by the engine's own default — so a recovered `core.sh`
        // returns the same value on both surfaces, and neither composition site can forget it.
        val interpretation = RecoveryInterpretationEngine(eventSink, journal)

        val invoker = ScriptedRegistryInvoker(
            registry = registry,
            journal = journal,
            eventSink = eventSink,
            invocationResolver = resolver,
            stepExecutor = executor,
            recoveryInterpretation = interpretation,
            metadataResolver = RegistryStepMetadataResolver.composite(registry),
            runtimeContextFactory = { call ->
                CanonicalRuntimeContext(
                    // S4-F2: the STRUCTURAL address of an operation depends on WHERE the body
                    // runs, and the two places are not the same. A whole-program scripted entry
                    // has no canonical stage, so it keeps stage 0 and the invocation ordinal as
                    // its step. A scripted STAGE inside a canonical run must carry its real stage
                    // index, or its operations would answer to the same keys as a sibling stage
                    // and two bodies would fight over one journal row.
                    opId = OpId(runId, structure.stageIndex, structure.stepIndexFor(call.invocationOrdinal)),
                    runId = call.runId,
                    stageName = structure.stageName,
                    stageIndex = structure.stageIndex,
                    stepIndex = structure.stepIndexFor(call.invocationOrdinal),
                    shOptions = shOptions,
                    controlDirRoot = controlDirRoot,
                    eventSink = eventSink,
                )
            },
        )
        // S4-A1 — the scripted shell reaches the durable engine through the SAME
        // registry spine as every other scripted step. There is deliberately no
        // second implementation of ScriptedOperationRuntime wired here: the only
        // path to a subprocess is one that admits SHELL_OPERATIONS_CAPABILITY.
        val shell = RegistryScriptedShellRuntime(invoker)

        val aggregate: RunOutcome = kotlinx.coroutines.runBlocking {
            runBody(entryPoint, runId, invoker, shell)
        }
        return Outcome.Completed(aggregate)
    }

    /**
     * S4-D2 — the ONE place a scripted run's outcome is decided.
     *
     * The collector is created HERE, above the `try`, and handed to the runtime. That placement
     * is the whole point: if the runtime owned it, a body that threw would abort the scope that
     * holds it and the `catch` below could not reach a snapshot, so the failure would never
     * reach the reducer. Owning it outside is what lets one reduction see every outcome,
     * including the one that ended the body.
     *
     * Precedence is NOT decided here. `RunOutcomeReducer` owns it, once, for the whole run. This
     * function used to return a hardcoded `StepOutcome.Success` after discarding the runtime's
     * result, which is how an `Unstable` step reported a successful run; and the frontend then
     * re-mapped that into a `RunOutcome` with a second table.
     */
    private suspend fun runBody(
        entryPoint: dev.rubentxu.pipeline.v2.scripting.CompiledScriptedEntryPoint,
        runId: String,
        invoker: ScriptedRegistryInvoker,
        shell: ScriptedOperationRuntime,
    ): RunOutcome {
        val outcomes = ScriptedOutcomeCollector()
        try {
            ScriptedRuntime(
                operationRuntime = shell,
                callSites = ScriptedCallSiteProvider {
                    error("Compiled scripted entry points must supply explicit call-site ids")
                },
            ).run(
                definitionDigest = entryPoint.artifact.fingerprintMaterial(),
                entryPointId = entryPoint.entryPointId,
                runId = runId,
                outcomes = outcomes,
            ) {
                entryPoint.execute(RuntimeScriptedStepFacade(this, invoker))
            }
        } catch (e: dev.rubentxu.pipeline.v2.domain.PipelineStepException) {
            // Recorded EXACTLY ONCE, here. `invokeTyped` throws rather than returning a failed
            // result, so nothing upstream already recorded this failure. It joins the list
            // instead of short-circuiting the reduction, so `Failure > Unstable > Success` keeps
            // a single authority.
            outcomes.record(StepOutcome.Failure(e.failure))
        }
        return RunOutcomeReducer.reduce(outcomes.snapshot())
    }
}

/**
 * Where a scripted body structurally RUNS, which is what decides the address of its operations.
 *
 * ## Why this is a value and not a flag
 *
 * A `Boolean inCanonicalRun` would have left the reader to infer the two real cases from a `when`
 * elsewhere, and the third case — a body that is neither — would have had to be asserted rather
 * than expressed. Two cases, each carrying its own address, and the compiler refuses the shapes
 * that do not exist.
 *
 * ## Why the stage index is not optional detail
 *
 * A scripted stage inside a canonical run is not a program; it is a stage. Its operations live in
 * the SAME journal the declarative steps use, and `OpId` is `runId-s{stageIndex}-{stepIndex}`. A
 * hardcoded `stageIndex = 0` would therefore address a scripted stage at index 2 as though it were
 * the stage at index 0 — two bodies, one row, and the second one to run would read the first one's
 * durable facts as its own replay evidence. That is the failure the two-scheme identity law
 * (R4A-L1) exists to make impossible, so the address is derived, never assumed.
 */
sealed interface ScriptedStructuralAddress {

    /** The stage position and the name a body is addressed by, as an `OpId` needs them. */
    val stageIndex: Int
    val stageName: String

    /**
     * The CLI's whole-program entry point: no canonical stage exists, so the body is stage 0 and
     * the invocation ordinal is the step. This is the historical behaviour, kept verbatim so the
     * standalone frontend's durable identities do not move.
     */
    data object WholeProgram : ScriptedStructuralAddress {
        override val stageIndex: Int = 0
        override val stageName: String = "scripted"
    }

    /**
     * A stage body inside a canonical run: it carries the stage's real position and name, so its
     * operations cannot collide with a sibling stage's.
     */
    data class InCanonicalStage(
        override val stageIndex: Int,
        override val stageName: String,
    ) : ScriptedStructuralAddress {
        init {
            require(stageIndex >= 0) { "a canonical stage index is not negative: $stageIndex" }
            require(stageName.isNotBlank()) { "a canonical stage name is not blank" }
        }
    }

    /**
     * The step index an operation gets inside the body.
     *
     * Both cases use the invocation ordinal, and that is deliberate: within one body the ordinals
     * are the body's own sequence, and the two-scheme law keeps them root-namespaced so they never
     * collide with structural keys. What differs between the cases is the STAGE, not the step.
     */
    fun stepIndexFor(invocationOrdinal: Int): Int = invocationOrdinal
}

/**
 * Frontend FORM selection for the CLI. Main selects the representation; the
 * durable authority is composed once and shared. Never an execution-algorithm
 * switch (R4A: SECOND_RUNNER = REJECTED).
 */
sealed interface ScriptedFrontendForm {
    /** Declarative PipelineSpec artifact (today's default). */
    data class PipelineSpecForm(val spec: dev.rubentxu.pipeline.v2.dsl.PipelineSpec) : ScriptedFrontendForm

    /** Compiled scripted entry point artifact (runtime-returned control flow). */
    data class ScriptedEntryPointForm(
        val entryPoint: dev.rubentxu.pipeline.v2.scripting.CompiledScriptedEntryPoint,
    ) : ScriptedFrontendForm
}
