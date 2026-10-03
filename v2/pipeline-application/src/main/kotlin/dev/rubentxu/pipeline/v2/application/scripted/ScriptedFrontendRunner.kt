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

    /** Closed result of one scripted frontend run. */
    sealed interface Outcome {
        /** Body completed; aggregate outcome is the terminal failure if any step failed. */
        data class Completed(val aggregate: StepOutcome) : Outcome

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
                    opId = OpId(runId, 0, call.invocationOrdinal),
                    runId = call.runId,
                    stageName = "scripted",
                    stageIndex = 0,
                    stepIndex = call.invocationOrdinal,
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

        val aggregate: StepOutcome = kotlinx.coroutines.runBlocking {
            runBody(entryPoint, runId, invoker, shell)
        }
        return Outcome.Completed(aggregate)
    }

    private suspend fun runBody(
        entryPoint: dev.rubentxu.pipeline.v2.scripting.CompiledScriptedEntryPoint,
        runId: String,
        invoker: ScriptedRegistryInvoker,
        shell: ScriptedOperationRuntime,
    ): StepOutcome = try {
        ScriptedRuntime(
            operationRuntime = shell,
            callSites = ScriptedCallSiteProvider {
                error("Compiled scripted entry points must supply explicit call-site ids")
            },
        ).run(
            definitionDigest = entryPoint.artifact.fingerprintMaterial(),
            entryPointId = entryPoint.entryPointId,
            runId = runId,
        ) {
            entryPoint.execute(RuntimeScriptedStepFacade(this, invoker))
        }
        StepOutcome.Success
    } catch (e: dev.rubentxu.pipeline.v2.domain.PipelineStepException) {
        StepOutcome.Failure(e.failure)
    }
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
