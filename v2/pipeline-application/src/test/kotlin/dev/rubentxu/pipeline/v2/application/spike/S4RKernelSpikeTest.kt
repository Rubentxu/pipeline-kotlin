package dev.rubentxu.pipeline.v2.application.spike

import dev.rubentxu.pipeline.v2.application.CoreStepRegistryFactory
import dev.rubentxu.pipeline.v2.application.PwdOutput
import dev.rubentxu.pipeline.v2.application.RegistryStepMetadataResolver
import dev.rubentxu.pipeline.v2.application.StepMetadata
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeCapabilityAccess
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.CommonExecutionBoundary
import dev.rubentxu.pipeline.v2.application.durable.CommonExecutionResult
import dev.rubentxu.pipeline.v2.application.durable.DurableInvocationResolver
import dev.rubentxu.pipeline.v2.application.durable.DurableStepExecutor
import dev.rubentxu.pipeline.v2.application.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.ExternalSubprocessRecovery
import dev.rubentxu.pipeline.v2.application.durable.InvocationReconciliation
import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.PreparedExecution
import dev.rubentxu.pipeline.v2.application.durable.PreparedRegistryExecution
import dev.rubentxu.pipeline.v2.application.durable.RecoveryInterpretationEngine
import dev.rubentxu.pipeline.v2.application.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.application.durable.RunningSubprocessRecovery
import dev.rubentxu.pipeline.v2.application.durable.StepLifecycleContext
import dev.rubentxu.pipeline.v2.application.durable.buildDefaultExecutionBoundary
import dev.rubentxu.pipeline.v2.application.scripted.ScriptedRegistryCall
import dev.rubentxu.pipeline.v2.application.scripted.ScriptedRegistryInvoker
import dev.rubentxu.pipeline.v2.application.scripted.ScriptedRegistryResult
import dev.rubentxu.pipeline.v2.application.support.CoordinatorFixture
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.MemoizedOperation
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepRegistration
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import dev.rubentxu.pipeline.v2.scripting.ScriptedCallSiteId
import dev.rubentxu.pipeline.v2.scripting.ScriptedStepFacade
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path

/**
 * SPIKE S4-R-KERNEL (SPIKE-017B) — "Can SCRIPTED consume the canonical replay authority?".
 * MEASUREMENT ONLY. ZERO production change: not one line of `src/main` is touched.
 *
 * ## The question
 *
 * `S4_R0_REPLAY_IDENTITY_CONFLICT_MEMO.md` §4 named TWO replay authorities, and SPIKE S4-R-POL
 * (`S4RPolReplaySemanticsSpikeTest`, same package) MEASURED that they disagree: for the same
 * `StepDescriptor` and the same journal state, `ScriptedRegistryInvoker.invoke` and the canonical
 * `DurableInvocationResolver` take different decisions and leave different durable states.
 *
 * This spike asks the architectural follow-up, and answers it by BUILDING the candidate rather
 * than by reasoning about it:
 *
 * > If `ScriptedRegistryInvoker` stops interpreting `OperationStatus` and instead routes its call
 * > through the canonical decision + interpretation + execution spine, keeping ONLY its own
 * > addressing and typed-Kotlin adaptation, does the whole K1..K7 matrix hold?
 *
 * The invariant under test, stated literally:
 *
 * > **scripted posee su dirección; no posee su replay protocol.**
 *
 * ## What the candidate is — and what it is NOT
 *
 * [CandidateSpine] is a **TEST-LOCAL composition of PRODUCTION classes**:
 * `ScriptedRegistryCall.operationId()` for the address, `RegistryStepMetadataResolver` for the
 * descriptor truth, `DurableInvocationResolver` for the decision, `RecoveryInterpretationEngine`
 * for the interpretation, `RegistryExecutionPreparation` for the fail-closed admission,
 * `DurableStepExecutor` + `buildDefaultExecutionBoundary` for the execution, and
 * `ScriptedRegistryResult` as the closed outcome.
 *
 * It is deliberately NOT a production class. There is no `ScriptedReplayKernel`, no
 * `ScriptedDurableExecutor` and no new carrier: `CommonExecutionResult` already exists
 * (`CommonExecutionResult.kt:34-37`) and is what the execution boundary already returns. Naming a
 * fourth replay protocol in `src/main` would be the failure mode the spike exists to rule out.
 * What the composition demonstrates is the SHAPE of the real change, so that the real change is a
 * re-wiring of `ScriptedRegistryInvoker.invoke` and nothing else.
 *
 * ## The measured obligations (K1..K7)
 *
 *  - **K1 FRESH** executes exactly once, through the canonical boundary, leaving the canonical
 *    terminal state — and the typed value survives it.
 *  - **K2 REUSE** skips the handler AND the capability plane, and still returns the real typed
 *    Kotlin value. The value is materialised from the `journaled` row the caller ALREADY HOLDS;
 *    the resolver does not return it and no second `journal.get` is issued for the decision
 *    (see `CandidateSpine.materialise`).
 *  - **K3 FAILED prior** re-executes and REPAIRS the row, instead of latching `REPLAY_COMPATIBILITY`.
 *  - **K4 RUNNING prior** enters the canonical recovery port and reaches a TERMINAL state, instead
 *    of sticking on RUNNING forever.
 *  - **K5 NEVER** is expressible at all by the candidate, at the decision level and end to end.
 *  - **K6** the fingerprint is hashed under the DESCRIPTOR policy on every surface, while the
 *    operation ADDRESS is allowed to differ per frontend.
 *  - **K7** the frontend contributes ZERO journal writes: there is no second writer.
 *
 * ## Differential, not address-differential
 *
 * When two surfaces are compared the axes are: StepKey, observed replay policy, reconciliation
 * decision, handler count, recovery probes, journal transition, terminal status, encoded output,
 * typed result, failure kind and capability reads. `operationId` is NEVER one of them — the address
 * namespace is allowed to differ by frontend (RPL-5), and K6 asserts that the canonical and
 * scripted spines really do use different ones rather than hiding it.
 *
 * ## Honesty contract
 *
 * Assertions pin what the code ACTUALLY did. A row that cannot be observed is asserted as NOT
 * observable rather than quietly passed; K4 carries the one such case inline. `Prior` and
 * `seedKernelPrior` reuse the shape proven by `S4RPolReplaySemanticsSpikeTest` (`Prior` is reused
 * from that file: same package, `internal` on purpose).
 */
@Timeout(300)
class S4RKernelSpikeTest {

    // ------------------------------------------------------------------
    // Candidate spine — the route under test
    // ------------------------------------------------------------------

    /**
     * The candidate route. TEST-ONLY composition; see the class KDoc for why it is not a
     * production class.
     *
     * What stays with the scripted frontend (its own responsibility, untouched):
     *  - the durable ADDRESS: `ScriptedRegistryCall.operationId()` and the `"scripted." + stepKey`
     *    namespace rule (`ScriptedRegistryInvoker.kt:369-370`);
     *  - the flat `OperationInput.params` projection of the scripted identity;
     *  - the LAST MILE: materialising the typed value through the Step's DECLARED output codec,
     *    which is what `invokeTyped` does at `ScriptedRegistryInvoker.kt:171-184`.
     *
     * What it delegates to the canonical authority (the point of the spike):
     *  - the fingerprint POLICY: `metadata.replayPolicy`, never a literal;
     *  - the decision: `reconcileInvocation`;
     *  - the interpretation and its effects: `RecoveryInterpretationEngine.interpret`;
     *  - the admission: `RegistryExecutionPreparation.prepare`;
     *  - the execution AND every journal write: `DurableStepExecutor.executeAndJournal`.
     */
    private class CandidateSpine(
        private val runId: String,
        private val stepKey: PluginStepId,
        private val encodedInput: EncodedStepValue,
    ) {
        var handlerInvocations: Int = 0
            private set
        var capabilityReads: Int = 0
            private set
        var recoveryProbes: Int = 0
            private set
        var lastDecision: String = "NOT_RUN"
        var lastSeamOutput: EncodedStepValue? = null
            private set

        private val registry = CountingRegistry(CoreStepRegistryFactory.registry()) { handlerInvocations++ }
        private val journal = CountingJournal(InMemoryOperationJournal(SystemClock()))
        private val events = InMemoryEventStore()
        private val cursors = InMemoryReplayCursorStore(SystemClock())
        private val controlDir: Path = Files.createTempDirectory("s4rkernel-ctrl-")
        private val metadataResolver = RegistryStepMetadataResolver.composite(registry)

        private val resolver = DurableInvocationResolver(
            divergenceDetector = StrictFingerprintDivergenceDetector(),
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            journal = journal,
            runningSubprocessRecovery = CountingRecovery {
                recoveryProbes++
                ExternalSubprocessRecovery(SystemClock(), controlDir)
            },
        )

        private val interpretation = RecoveryInterpretationEngine(events, journal, cursors)

        private val executor = DurableStepExecutor(
            eventSink = events,
            executionBoundary = CapturingBoundary(
                delegate = buildDefaultExecutionBoundary(
                    dispatcher = CanonicalNodeDispatcher(),
                    invocationExecutor = null,
                    stepRegistry = registry,
                ),
                onResult = { lastSeamOutput = it.encodedOutput },
            ),
            journal = journal,
            cursorStore = cursors,
        )

        private fun call(): ScriptedRegistryCall = ScriptedRegistryCall(
            runId = runId,
            entryPointId = ENTRY_POINT_ID,
            callSiteId = CALL_SITE,
            dynamicScopePath = emptyList(),
            invocationOrdinal = 0,
            stepKey = stepKey,
            encodedInput = encodedInput,
            definitionDigest = DEFINITION_DIGEST,
        )

        /** The scripted ADDRESS: the production operationId rule, not a reproduction of it. */
        fun address(): String = call().operationId()

        fun metadata(): StepMetadata =
            metadataResolver.resolve(stepKey) ?: error("no metadata for ${stepKey.value}")

        private fun lifecycle() = StepLifecycleContext(
            runId = runId,
            stageIndex = 0,
            stepIndex = 0,
            stepName = "s4rkernel",
            stepType = stepKey.value,
        )

        suspend fun run(): ScriptedRegistryResult {
            val call = call()
            val stepId = SCRIPTED_NAMESPACE + stepKey.value
            val input = OperationInput(
                stepId = stepId,
                params = buildJsonObject {
                    put("runId", JsonPrimitive(call.runId))
                    put("entryPointId", JsonPrimitive(call.entryPointId))
                    put("callSiteId", JsonPrimitive(call.callSiteId.value))
                    put("dynamicScopePath", JsonPrimitive(call.dynamicScopePath.joinToString("/")))
                    put("invocationOrdinal", JsonPrimitive(call.invocationOrdinal))
                    put("encodedInput", JsonPrimitive(call.encodedInput.value))
                    put("definitionDigest", JsonPrimitive(call.definitionDigest))
                },
                runId = call.runId,
                attempt = ATTEMPT,
            )
            // RPL-3: the policy that really governs reconciliation is the DESCRIPTOR's.
            val metadata = metadata()
            val fingerprint = Fingerprint.compute(input, stepId, metadata.replayPolicy, ATTEMPT)
            val operationId = call.operationId()

            // ADDRESSING read. Permitted, and NOT a replay decision: it locates the row this
            // frontend owns. What is forbidden is interpreting the status it returns.
            val journaled = journal.get(operationId, ATTEMPT)
            val currentOperation = RerunOperation(
                id = operationId,
                fingerprint = fingerprint,
                input = input,
                output = null,
                status = OperationStatus.PENDING,
                attempt = ATTEMPT,
            )

            val reconciliation = resolver.reconcileInvocation(metadata, journaled, currentOperation, operationId)
            lastDecision = reconciliation.label()

            when (
                val effect = interpretation.interpret(
                    reconciliation,
                    RecoveryInterpretationEngine.Request(
                        operationId = operationId,
                        fingerprint = fingerprint,
                        input = input,
                        runIdValue = runId,
                        stageIndex = 0,
                        lifecycleContext = lifecycle(),
                    ),
                )
            ) {
                is RecoveryInterpretationEngine.RecoveryInterpretation.Settled ->
                    return when (reconciliation) {
                        // THE SEAM ANSWER, measured: the caller already holds `journaled`, and the
                        // decision is ReuseCompleted. Nothing is returned by the resolver, no status
                        // is interpreted here, and the typed value is still recoverable.
                        InvocationReconciliation.ReuseCompleted -> materialise(journaled)
                        else -> ScriptedRegistryResult.Failed(effect.outcome.failure())
                    }
                RecoveryInterpretationEngine.RecoveryInterpretation.ProceedToExecution -> Unit
            }

            // Only Execute reaches the runtime context and the capability plane.
            val runtime = CanonicalRuntimeContext(
                opId = OpId(runId, 0, call.invocationOrdinal),
                runId = runId,
                stageName = "scripted",
                stageIndex = 0,
                stepIndex = call.invocationOrdinal,
                shOptions = ShOptions.EMPTY,
                controlDirRoot = controlDir,
                eventSink = events,
            )
            val available = CanonicalRuntimeCapabilityAccess(runtime).available().also { capabilityReads++ }
            val preparation = RegistryExecutionPreparation.prepare(registry, stepKey, encodedInput, available)
            val ready = when (preparation) {
                is ExecutionPreparation.Ready -> preparation.prepared as PreparedRegistryExecution
                is ExecutionPreparation.Rejected -> return ScriptedRegistryResult.Failed(
                    PipelineFailure(FailureKind.SCHEMA, preparation.reason),
                )
            }

            val outcome = executor.executeAndJournal(
                operationId = operationId,
                fingerprint = fingerprint,
                input = input,
                journaled = journaled,
                prepared = ready,
                runtime = runtime,
                lifecycleContext = lifecycle(),
                runIdValue = runId,
                stageIndex = 0,
            )
            return when (outcome) {
                is StepOutcome.Failure -> ScriptedRegistryResult.Failed(outcome.failure)
                // P1: the canonical executor PERSISTED the encoded value, so recovering it needs no
                // second execution. The narrowing that makes this necessary is measured in K1:
                // `DurableStepExecutor.executeAndJournal` returns `StepOutcome`
                // (`DurableStepExecutor.kt:42`), dropping `CommonExecutionResult.encodedOutput`.
                else -> materialise(journal.get(operationId, ATTEMPT))
            }
        }

        /**
         * Typed materialisation AFTER the decision, through the Step's DECLARED output codec — the
         * same last mile `ScriptedRegistryInvoker.invokeTyped` performs
         * (`ScriptedRegistryInvoker.kt:171-184`). TOTAL over the closed `JsonElement` ADT, exactly
         * as `restoredOutput` is (`ScriptedRegistryInvoker.kt:317-332`): a shape this runtime cannot
         * read is a typed `REPLAY_COMPATIBILITY` failure, never a fabricated value.
         */
        private fun materialise(journaled: DurableOperation?): ScriptedRegistryResult {
            val definition = registry.definition(stepKey)
                ?: return ScriptedRegistryResult.Failed(
                    PipelineFailure(FailureKind.ENGINE, "no registry definition for ${stepKey.value}"),
                )
            val encoded = when (val raw = journaled?.output?.result) {
                null -> return ScriptedRegistryResult.Failed(
                    PipelineFailure(
                        FailureKind.REPLAY_COMPATIBILITY,
                        "SUCCEEDED scripted registry step has no persisted output",
                    ),
                )
                is JsonNull -> return ScriptedRegistryResult.Failed(
                    PipelineFailure(
                        FailureKind.REPLAY_COMPATIBILITY,
                        "persisted scripted registry output is a JSON null",
                    ),
                )
                is JsonPrimitive -> EncodedStepValue(raw.content)
                is JsonObject, is JsonArray -> return ScriptedRegistryResult.Failed(
                    PipelineFailure(
                        FailureKind.REPLAY_COMPATIBILITY,
                        "persisted scripted registry output is not a typed Step output",
                    ),
                )
            }
            // The decode itself is the caller's last mile; asserting it is decodable here proves
            // the materialised value really is the Step's declared output wire form.
            return runCatching {
                @Suppress("UNCHECKED_CAST")
                val codec = definition.contract.outputCodec as dev.rubentxu.pipeline.v2.domain.step.StepCodec<Any>
                codec.decode(encoded)
                ScriptedRegistryResult.Success(encoded)
            }.getOrElse {
                ScriptedRegistryResult.Failed(
                    PipelineFailure(
                        FailureKind.REPLAY_COMPATIBILITY,
                        "persisted runtime output is not decodable by ${stepKey.value}'s declared " +
                            "codec: ${it.message}",
                    ),
                )
            }
        }

        fun resetCounters() {
            handlerInvocations = 0
            capabilityReads = 0
            recoveryProbes = 0
            journal.resetWrites()
        }

        fun journalRow(): DurableOperation? = journal.listForRun(runId).lastOrNull()
        fun journalWrites(): Int = journal.writes

        fun seed(prior: Prior) = seedKernelPrior(journal, runId, prior)

        fun rowOf(prior: Prior, returned: String): Row = record(
            Row(
                surface = "CANDIDATE",
                step = stepKey.value,
                prior = prior,
                decision = lastDecision,
                handlerInvocations = handlerInvocations,
                returned = returned,
                journalTerminal = journalRow()?.status?.name ?: "NO_ROW",
                fingerprintPolicy = journalRow()?.let { policyOf(it) } ?: "NO_ROW",
                capabilityReads = capabilityReads,
                recoveryProbes = recoveryProbes,
                journalWrites = journal.writes,
            ),
        )
    }

    // ------------------------------------------------------------------
    // Scripted spine — the CURRENT production invoker, for the differential
    // ------------------------------------------------------------------

    /**
     * The shipped `ScriptedRegistryInvoker` driven directly through its public `invoke`, so the
     * differential compares the two authorities on identical inputs without a DSL harness in the
     * way. This is PRODUCTION code; the only addition is the [CountingRegistry] seam.
     */
    private class ScriptedSpine(
        private val runId: String,
        private val stepKey: PluginStepId,
        private val encodedInput: EncodedStepValue,
    ) {
        var handlerInvocations: Int = 0
            private set
        var capabilityReads: Int = 0
            private set
        var lastDecision: String = "SELF_INTERPRETED"

        private val registry = CountingRegistry(CoreStepRegistryFactory.registry()) { handlerInvocations++ }
        private val journal = CountingJournal(InMemoryOperationJournal(SystemClock()))
        private val events = InMemoryEventStore()
        private val controlDir: Path = Files.createTempDirectory("s4rkernel-script-ctrl-")

        private val invoker = ScriptedRegistryInvoker(
            registry = registry,
            journal = journal,
            clock = SystemClock(),
            runtimeContextFactory = { call ->
                CanonicalRuntimeContext(
                    opId = OpId(call.runId, 0, call.invocationOrdinal),
                    runId = call.runId,
                    stageName = "scripted",
                    stageIndex = 0,
                    stepIndex = call.invocationOrdinal,
                    shOptions = ShOptions.EMPTY,
                    controlDirRoot = controlDir,
                    eventSink = events,
                )
            },
            capabilityAccessFactory = { context ->
                capabilityReads++
                CanonicalRuntimeCapabilityAccess(context)
            },
        )

        suspend fun run(): ScriptedRegistryResult = invoker.invoke(
            ScriptedRegistryCall(
                runId = runId,
                entryPointId = ENTRY_POINT_ID,
                callSiteId = CALL_SITE,
                dynamicScopePath = emptyList(),
                invocationOrdinal = 0,
                stepKey = stepKey,
                encodedInput = encodedInput,
                definitionDigest = DEFINITION_DIGEST,
            ),
        )

        fun resetCounters() {
            handlerInvocations = 0
            capabilityReads = 0
            journal.resetWrites()
        }

        fun journalRow(): DurableOperation? = journal.listForRun(runId).lastOrNull()

        fun seed(prior: Prior) = seedKernelPrior(journal, runId, prior)

        fun rowOf(prior: Prior, returned: String): Row = record(
            Row(
                surface = "SCRIPTED",
                step = stepKey.value,
                prior = prior,
                decision = lastDecision,
                handlerInvocations = handlerInvocations,
                returned = returned,
                journalTerminal = journalRow()?.status?.name ?: "NO_ROW",
                fingerprintPolicy = journalRow()?.let { policyOf(it) } ?: "NO_ROW",
                capabilityReads = capabilityReads,
                recoveryProbes = -1,
                journalWrites = journal.writes,
            ),
        )
    }

    // ------------------------------------------------------------------
    // Canonical spine — the production coordinator, for the semantic differential
    // ------------------------------------------------------------------

    /**
     * The production canonical surface: [CanonicalDurableRunCoordinator] over
     * [CoreStepRegistryFactory.registry], with a REAL `controlDirRoot` so the `core.sh` recovery
     * hook is armed. Used only for the differential axes, never to justify a decision.
     */
    private class CanonicalSpine(
        private val runId: String,
        private val stepKey: PluginStepId,
        private val payloadJson: String,
    ) {
        var handlerInvocations: Int = 0
            private set

        private val journal = CountingJournal(InMemoryOperationJournal(SystemClock()))
        private val events = InMemoryEventStore()
        private val controlDir: Path = Files.createTempDirectory("s4rkernel-canon-ctrl-")

        private val pipeline = CompiledPipeline(
            id = DefinitionId("s4-r-kernel-$runId"),
            source = SourceDescriptor("s4rkernel.pipeline.kts", Digest("s4rkernel-source")),
            pluginLockDigest = Digest("s4rkernel-lock"),
            stages = listOf(
                StageNode(
                    id = StageId("spike"),
                    name = "spike",
                    body = StageBody.Steps(
                        listOf(
                            OpaqueStepNode(
                                id = StepId("spike/step-0"),
                                pluginStepId = stepKey,
                                payload = VersionedStepPayload("dsl-v1", payloadJson),
                            ),
                        ),
                    ),
                ),
            ),
        )

        suspend fun run(): RunOutcome {
            val countingRegistry = CountingRegistry(CoreStepRegistryFactory.registry()) { handlerInvocations++ }
            val coordinator = CanonicalDurableRunCoordinator(
                dispatcher = CanonicalNodeDispatcher(),
                journal = journal,
                cursorStore = InMemoryReplayCursorStore(SystemClock()),
                clock = SystemClock(),
                effectReplayPolicy = DefaultEffectReplayPolicy(),
                eventSink = events,
                credentialScopePort = CoordinatorFixture.noOpCredentialScopePort(),
                controlDirRoot = controlDir,
                shOptions = ShOptions.EMPTY,
                commonExecutionBoundary = buildDefaultExecutionBoundary(
                    dispatcher = CanonicalNodeDispatcher(),
                    invocationExecutor = null,
                    stepRegistry = countingRegistry,
                ),
                stepRegistry = countingRegistry,
            )
            return coordinator.run(pipeline, RunId(runId))
        }

        fun resetCounters() {
            handlerInvocations = 0
            journal.resetWrites()
        }

        fun journalRow(): DurableOperation? = journal.listForRun(runId).lastOrNull()

        fun seed(prior: Prior) = seedKernelPrior(journal, runId, prior)

        fun rowOf(prior: Prior, returned: String): Row = record(
            Row(
                surface = "CANONICAL",
                step = stepKey.value,
                prior = prior,
                decision = "INTERNAL",
                handlerInvocations = handlerInvocations,
                returned = returned,
                journalTerminal = journalRow()?.status?.name ?: "NO_ROW",
                fingerprintPolicy = journalRow()?.let { policyOf(it) } ?: "NO_ROW",
                capabilityReads = -1,
                recoveryProbes = -1,
                journalWrites = journal.writes,
            ),
        )
    }

    // ------------------------------------------------------------------
    // K1 — FRESH
    // ------------------------------------------------------------------

    @Test
    fun `k1 fresh executes once through the canonical boundary and keeps the typed value`() =
        runBlocking {
            val spine = CandidateSpine("s4rkernel-k1", SH_KEY, shInput("echo s4rkernel-k1"))

            val result = spine.run()
            val row = spine.rowOf(Prior.FRESH, result.describe())
            emit()

            assertTrue(result is ScriptedRegistryResult.Success, "K1: fresh must succeed, got ${result.describe()}")
            assertEquals(1, row.handlerInvocations, "K1 PASS: the handler runs exactly once")
            assertEquals("Execute", row.decision, "K1 PASS: the canonical decision is Execute")
            assertEquals("SUCCEEDED", row.journalTerminal, "K1 PASS: canonical terminal state")
            assertEquals(
                "RERUN",
                row.fingerprintPolicy,
                "K1 PASS: the fingerprint carries the DESCRIPTOR policy for core.sh (RERUN), which is " +
                    "the policy reconciliation actually obeys (RPL-3)",
            )
            assertEquals(1, row.capabilityReads, "K1 PASS: the fresh path reads the capability plane once")
            assertNotNull(
                spine.lastSeamOutput,
                "K1 PASS: the canonical execution seam returned a non-null encodedOutput, so the typed " +
                    "value survives the executor — CommonExecutionResult.kt:34-37 IS the carrier",
            )
            assertEquals(
                spine.lastSeamOutput,
                (result as ScriptedRegistryResult.Success).encodedOutput,
                "K1 PASS: the value the scripted frontend can hand back is byte-identical to the one " +
                    "the canonical boundary produced",
            )
            assertTrue(
                row.journalWrites >= 1,
                "K1 PASS: journal writes are performed by the canonical executor, not by the frontend " +
                    "(writes=${row.journalWrites})",
            )
        }

    // ------------------------------------------------------------------
    // K2 — REUSE, and the seam question
    // ------------------------------------------------------------------

    @Test
    fun `k2 reuse skips handler and capabilities and still returns the typed value`() = runBlocking {
        // core.pwd: READ_ONLY + MEMOIZED and it DECLARES two capabilities, so a reuse that
        // consulted the capability plane would show a non-zero read count. That is the probe.
        val spine = CandidateSpine("s4rkernel-k2", PWD_KEY, pwdInput())

        val fresh = spine.run()
        assertTrue(fresh is ScriptedRegistryResult.Success, "K2 control: fresh pwd succeeds")
        val freshTyped = (fresh as ScriptedRegistryResult.Success).let { decodePwd(it.encodedOutput) }

        spine.resetCounters()
        val reused = spine.run()
        assertTrue(reused is ScriptedRegistryResult.Success, "K2: reuse must return the typed value")
        val reusedTyped = decodePwd((reused as ScriptedRegistryResult.Success).encodedOutput)
        val row = spine.rowOf(Prior.SUCCEEDED, "typed(${reusedTyped.path})")
        emit()

        assertEquals("ReuseCompleted", row.decision, "K2 PASS: the canonical decision is ReuseCompleted")
        assertEquals(0, row.handlerInvocations, "K2 PASS: reuse never re-invokes the handler")
        assertEquals(0, row.capabilityReads, "K2 PASS: reuse never consults the capability plane")
        assertEquals(0, row.journalWrites, "K2 PASS: reuse writes no journal row — the row is terminal")
        assertEquals("SUCCEEDED", row.journalTerminal, "K2 PASS: the row stays terminal SUCCEEDED")
        assertEquals(
            "MEMOIZED",
            row.fingerprintPolicy,
            "K2 PASS: for a MEMOIZED step the hashed policy is MEMOIZED — descriptor truth again",
        )
        assertEquals(
            freshTyped.path,
            reusedTyped.path,
            "K2 PASS: the typed Kotlin value on reuse equals the value the fresh execution produced. " +
                "This is the whole claim of the seam question, measured end to end",
        )
        assertTrue(
            reusedTyped.path.isNotBlank(),
            "K2 PASS: the decoded value is the Step's own typed output (PwdOutput.path), not a " +
                "placeholder",
        )
    }

    // ------------------------------------------------------------------
    // K3 — prior FAILED
    // ------------------------------------------------------------------

    @Test
    fun `k3 a FAILED prior re-executes and repairs the row`() = runBlocking {
        val candidate = CandidateSpine("s4rkernel-k3", SH_KEY, shInput("echo s4rkernel-k3"))
        candidate.run()
        candidate.seed(Prior.FAILED)
        candidate.resetCounters()
        val candidateResult = candidate.run()
        val candidateRow = candidate.rowOf(Prior.FAILED, candidateResult.describe())

        val scripted = ScriptedSpine("s4rkernel-k3", SH_KEY, shInput("echo s4rkernel-k3"))
        scripted.run()
        scripted.seed(Prior.FAILED)
        scripted.resetCounters()
        val scriptedResult = scripted.run()
        val scriptedRow = scripted.rowOf(Prior.FAILED, scriptedResult.describe())
        emit()

        assertEquals("Execute", candidateRow.decision, "K3 PASS: RERUN over a FAILED row is Execute")
        assertEquals(1, candidateRow.handlerInvocations, "K3 PASS: the handler re-runs")
        assertEquals("SUCCEEDED", candidateRow.journalTerminal, "K3 PASS: the row is REPAIRED to SUCCEEDED")

        assertEquals(
            0,
            scriptedRow.handlerInvocations,
            "K3 differential: the shipped invoker does not re-run, so the row is never repaired",
        )
        assertEquals("FAILED", scriptedRow.journalTerminal, "K3 differential: the scripted row stays FAILED")
        assertEquals(
            "REPLAY_COMPATIBILITY",
            (scriptedResult as ScriptedRegistryResult.Failed).failure.kind.name,
            "K3 differential: the shipped invoker latches REPLAY_COMPATIBILITY, confirming the memo " +
                "row-3 divergence against the candidate on the same run",
        )
    }

    // ------------------------------------------------------------------
    // K4 — prior RUNNING
    // ------------------------------------------------------------------

    @Test
    fun `k4 a RUNNING prior reaches a terminal state through the recovery port`() = runBlocking {
        val candidate = CandidateSpine("s4rkernel-k4", SH_KEY, shInput("echo s4rkernel-k4"))
        candidate.run()
        candidate.seed(Prior.RUNNING)
        candidate.resetCounters()
        val candidateResult = candidate.run()
        val row = candidate.rowOf(Prior.RUNNING, candidateResult.describe())
        emit()

        assertEquals(
            "RecoverRunning",
            row.decision,
            "K4 PASS: the RUNNING prior reaches the canonical recovery hook, because core.sh declares " +
                "RecoveryPolicy.ExternalSubprocess",
        )
        assertEquals(1, row.recoveryProbes, "K4 PASS: exactly one recovery probe")
        assertEquals(0, row.handlerInvocations, "K4 PASS: recovery does NOT re-invoke the handler")
        assertEquals(
            "LOST",
            row.journalTerminal,
            "K4 PASS: the row reaches a TERMINAL status (LOST — no control artifact to reattach " +
                "against) instead of sticking on RUNNING forever",
        )
        assertTrue(
            candidateResult is ScriptedRegistryResult.Failed,
            "K4 PASS: the call settles as a typed failure, it does not hang",
        )
        // NOTED, not asserted either way: a SUCCESSFUL reattach of a LIVE process is NOT observable
        // in a spike. It needs a genuinely long-lived subprocess plus a crash between spawn and the
        // terminal journal write. This row is therefore a reserva, not a proof of reattach.
    }

    // ------------------------------------------------------------------
    // K5 — NEVER
    // ------------------------------------------------------------------

    @Test
    fun `k5 NEVER is expressible and refuses a prior row`() = runBlocking {
        // 5a: decision level.
        val policy = DefaultEffectReplayPolicy()
        assertEquals(
            "RERUN",
            policy.decide(
                replayPolicy = ReplayPolicy.NEVER,
                effects = setOf(Effect.ABORTS_PIPELINE),
                hasJournalEntry = false,
                journaledOutcome = null,
            ).name,
            "K5 PASS: NEVER over a FRESH operation still executes — NEVER constrains REPLAY, not the " +
                "first execution",
        )
        assertEquals(
            "ABORT",
            policy.decide(
                replayPolicy = ReplayPolicy.NEVER,
                effects = setOf(Effect.ABORTS_PIPELINE),
                hasJournalEntry = true,
                journaledOutcome = OperationStatus.SUCCEEDED,
            ).name,
            "K5 PASS: NEVER over a journaled operation is ABORT — the shipped scripted table had no " +
                "way to express this at all",
        )

        // 5b: end to end on the candidate.
        val candidate = CandidateSpine("s4rkernel-k5", ERROR_KEY, errorInput())
        val fresh = candidate.run()
        assertTrue(fresh is ScriptedRegistryResult.Failed, "K5 control: core.error always fails")
        candidate.seed(Prior.SUCCEEDED)
        candidate.resetCounters()
        val replay = candidate.run()
        val row = candidate.rowOf(Prior.SUCCEEDED, replay.describe())
        emit()

        assertEquals("RejectedAbort", row.decision, "K5 PASS: the NEVER refusal is a first-class resolution")
        assertEquals(0, row.handlerInvocations, "K5 PASS: a refused replay never runs the handler")
        assertEquals(
            "INFRASTRUCTURE",
            (replay as ScriptedRegistryResult.Failed).failure.kind.name,
            "K5 PASS: the refusal settles a typed INFRASTRUCTURE failure, not a reuse",
        )
        assertEquals("NEVER", row.fingerprintPolicy, "K5 PASS: the hashed policy is NEVER")

        // 5c: the shipped invoker cannot be driven to a NEVER Step from its own façade. MEASURED,
        // not assumed: the façade's declared methods are enumerated and every routable key's
        // descriptor policy is read from the PRODUCTION registry.
        val facadeMethods = ScriptedStepFacade::class.java.methods.map { it.name }.toSet()
        assertTrue(
            "error" !in facadeMethods,
            "K5 measured: the façade declares no `error(...)` method. Methods = $facadeMethods",
        )
        val registry = CoreStepRegistryFactory.registry()
        val policiesOfRoutable = ROUTABLE_BY_FACADE.associateWith { key ->
            registry.definition(PluginStepId(key))?.contract?.descriptor?.replayPolicy?.name
        }
        assertNotNull(
            policiesOfRoutable["core.sh"],
            "K5 control: the production registry really exposes the routed Steps",
        )
        assertTrue(
            "NEVER" !in policiesOfRoutable.values,
            "K5 measured: no Step reachable from the scripted façade declares NEVER, so the shipped " +
                "reuse of a NEVER Step is LATENT — a containment hole, not an incident. The candidate " +
                "removes the hole because it can express NEVER at all. Policies = $policiesOfRoutable",
        )
    }

    // ------------------------------------------------------------------
    // K6 — fingerprint semantics vs address
    // ------------------------------------------------------------------

    @Test
    fun `k6 the fingerprint policy is the descriptor's on every surface, the address is not`() =
        runBlocking {
            val canonical = CanonicalSpine("s4rkernel-k6", SH_KEY, shPayload("echo s4rkernel-k6"))
            canonical.run()
            val canonicalRow = canonical.rowOf(Prior.FRESH, "success")

            val candidate = CandidateSpine("s4rkernel-k6", SH_KEY, shInput("echo s4rkernel-k6"))
            candidate.run()
            val candidateRow = candidate.rowOf(Prior.FRESH, "success")

            val scripted = ScriptedSpine("s4rkernel-k6", SH_KEY, shInput("echo s4rkernel-k6"))
            scripted.run()
            val scriptedRow = scripted.rowOf(Prior.FRESH, "success")
            emit()

            assertEquals(
                candidateRow.fingerprintPolicy,
                canonicalRow.fingerprintPolicy,
                "K6 PASS: the candidate and the canonical surface hash under the SAME replay policy. " +
                    "Fingerprint SEMANTICS must not differ by frontend",
            )
            assertNotEquals(
                candidateRow.journalTerminal,
                "NO_ROW",
                "K6 PASS: the candidate really did write a durable row",
            )
            assertEquals(
                "RERUN",
                candidate.metadata().replayPolicy.name,
                "K6 PASS: the policy the candidate hashed IS the descriptor's, read through the " +
                    "production RegistryStepMetadataResolver",
            )
            assertEquals(
                "MEMOIZED",
                scriptedRow.fingerprintPolicy,
                "K6 differential: the shipped invoker hashes under a hardcoded MEMOIZED literal " +
                    "(ScriptedRegistryInvoker.kt:203-205) for a RERUN step — the fingerprint stops " +
                    "encoding the policy that actually governs reconciliation (RPL-3 violation). The " +
                    "candidate fixes it WITHOUT touching the address",
            )

            // The address. Canonical uses OpId.format() (OpId.kt:60-67); the scripted frontend owns
            // its own namespace. They MUST be allowed to differ — that is RPL-5, not a defect.
            val canonicalAddress = canonical.journalRow()!!.id
            val candidateRow0 = candidate.journalRow()!!
            assertNotEquals(
                canonicalAddress,
                candidateRow0.id,
                "K6 PASS: the address DOES differ per frontend, which is legal and expected",
            )
            assertEquals(
                candidate.address(),
                candidateRow0.id,
                "K6 PASS: the candidate's address is the PRODUCTION scripted rule, " +
                    "ScriptedRegistryCall.operationId() (ScriptedRegistryInvoker.kt:70-73)",
            )
            assertTrue(
                candidateRow0.input.stepId.startsWith(SCRIPTED_NAMESPACE),
                "K6 PASS: the scripted step-id namespace is preserved verbatim: ${candidateRow0.input.stepId}",
            )
            assertEquals(
                "core.sh",
                canonical.journalRow()!!.input.stepId,
                "K6: the canonical surface keeps its own step-id spelling; neither is normalised to " +
                    "the other, and neither should be",
            )

            // MEASURED CORRECTION (run 3 of this spike): the FIRST version of this assertion
            // claimed "the address is the only difference" and it FAILED. The fingerprint is a
            // function of the WHOLE OperationInput, and the two frontends legitimately describe the
            // operation differently: canonical params are `{payload}` (StepDispatchEngine.kt:215-221)
            // while the scripted params are the scripted identity tuple
            // (ScriptedRegistryInvoker.kt:191-199). So the full hash MUST differ — and it does.
            // What must NOT differ is the policy axis, which is the only axis reconciliation reads,
            // and that is exactly what `policyOf` isolates above. Measured here, not asserted away.
            val candidateInput = candidateRow0.input
            val canonicalInput = canonical.journalRow()!!.input
            assertEquals(
                candidateRow0.fingerprint,
                Fingerprint.compute(candidateInput, candidateInput.stepId, candidate.metadata().replayPolicy, ATTEMPT),
                "K6 PASS: the candidate hashed EXACTLY its own input under the DESCRIPTOR policy, " +
                    "with no substitution of any kind",
            )
            assertEquals(
                canonical.journalRow()!!.fingerprint,
                Fingerprint.compute(canonicalInput, canonicalInput.stepId, ReplayPolicy.RERUN, ATTEMPT),
                "K6 PASS: the canonical surface likewise hashes under the descriptor policy " +
                    "(StepDispatchEngine.kt:256) — the two surfaces agree on the POLICY axis",
            )
            assertNotEquals(
                canonical.journalRow()!!.fingerprint,
                candidateRow0.fingerprint,
                "K6 MEASURED: the full fingerprint hash DOES differ between the two surfaces, and " +
                    "must: each frontend describes the operation in its own identity space, and the " +
                    "hash covers that description. This is RPL-5 working as intended, not a " +
                    "divergence — reconciliation never reads the hash across frontends, it compares " +
                    "within one",
            )
            assertEquals(
                candidateRow.fingerprintPolicy,
                canonicalRow.fingerprintPolicy,
                "K6 PASS: despite the differing hash, the observed replay policy is identical, so " +
                    "the two surfaces REACH the same decision for the same Step and the same state",
            )
        }

    // ------------------------------------------------------------------
    // K6b — the semantic differential across the matrix
    // ------------------------------------------------------------------

    @Test
    fun `k6b semantic differential candidate equals canonical on every measured axis`() = runBlocking {
        data class Scenario(val runId: String, val key: PluginStepId, val payload: String, val encoded: EncodedStepValue)

        val scenarios = listOf(
            Scenario("s4rkernel-d1", SH_KEY, shPayload("echo s4rkernel-d1"), shInput("echo s4rkernel-d1")),
            Scenario("s4rkernel-d2", PWD_KEY, pwdPayload(), pwdInput()),
        )

        scenarios.forEach { s ->
            val canonical = CanonicalSpine(s.runId, s.key, s.payload)
            canonical.run()
            canonical.seed(Prior.SUCCEEDED)
            canonical.resetCounters()
            val canonicalOutcome = canonical.run()
            val canonicalRow = canonical.rowOf(Prior.SUCCEEDED, canonicalOutcome.describe())

            val candidate = CandidateSpine(s.runId, s.key, s.encoded)
            candidate.run()
            candidate.seed(Prior.SUCCEEDED)
            candidate.resetCounters()
            val candidateResult = candidate.run()
            val candidateRow = candidate.rowOf(Prior.SUCCEEDED, candidateResult.describe())

            // Axes that MUST agree. operationId is deliberately NOT one of them.
            assertEquals(
                canonicalRow.fingerprintPolicy,
                candidateRow.fingerprintPolicy,
                "K6b ${s.key}: observed replay policy must not differ by frontend",
            )
            assertEquals(
                canonicalRow.journalTerminal,
                candidateRow.journalTerminal,
                "K6b ${s.key}: terminal journal state must not differ by frontend",
            )
            assertEquals(
                canonicalRow.handlerInvocations,
                candidateRow.handlerInvocations,
                "K6b ${s.key}: handler count must not differ by frontend on a reuse",
            )
            assertEquals(
                0,
                candidateRow.journalWrites,
                "K6b ${s.key}: a reuse writes no journal row",
            )
            assertTrue(
                candidateResult is ScriptedRegistryResult.Success,
                "K6b ${s.key}: a reuse yields the typed value, got ${candidateResult.describe()}",
            )
            assertEquals(
                "SUCCEEDED",
                candidateRow.journalTerminal,
                "K6b ${s.key}: the durable row survives the reuse untouched",
            )
        }
        emit()
    }

    // ------------------------------------------------------------------
    // K7 — no second writer, no second replay protocol
    // ------------------------------------------------------------------

    @Test
    fun `k7 the candidate adds no second writer and no second replay protocol`() = runBlocking {
        val spine = CandidateSpine("s4rkernel-k7", SH_KEY, shInput("echo s4rkernel-k7"))
        spine.run()
        val freshWrites = spine.journalWrites()

        // FRESH: beginOperation + the canonical terminal row. Both from DurableStepExecutor, never
        // from the frontend (DurableStepExecutor.kt:44 and :53-68).
        assertEquals(2, freshWrites, "K7 PASS: the frontend contributes ZERO journal writes; the " +
            "canonical executor writes beginOperation and the terminal row")

        spine.seed(Prior.SUCCEEDED)
        spine.resetCounters()
        spine.run()
        assertEquals(0, spine.journalWrites(), "K7 PASS: a reuse adds no write at all")

        // The frontend's only status-derived knowledge is the RESOLUTION, never a status match.
        assertEquals(
            "ReuseCompleted",
            spine.lastDecision,
            "K7 PASS: the frontend reads a RESOLUTION, not an OperationStatus",
        )
        emit()
    }
}

// ----------------------------------------------------------------------
// File-level fixtures and helpers.
//
// Top-level, because the nested spine classes above have no outer instance
// to receive them from — the same reason S4RPolReplaySemanticsSpikeTest keeps
// its helpers here.
// ----------------------------------------------------------------------

private const val SCRIPTED_NAMESPACE = "scripted."
private const val ATTEMPT = 1
private const val ENTRY_POINT_ID = "s4rkernel-entry"
private const val CALL_SITE_VALUE = "pipeline.kts:1:step"
private const val DEFINITION_DIGEST = "s4rkernel-digest"

private val CALL_SITE = ScriptedCallSiteId(CALL_SITE_VALUE)
private val SH_KEY = PluginStepId("core.sh")
private val PWD_KEY = PluginStepId("core.pwd")
private val ERROR_KEY = PluginStepId("core.error")

private val ROUTABLE_BY_FACADE = listOf(
    "core.sh",
    "core.pwd",
    "core.pwd.tmp",
    "core.isUnix",
    "core.readFile",
    "core.fileExists",
)

private fun shInput(script: String): EncodedStepValue =
    EncodedStepValue("""{"kind":"sh","command":"$script","isScriptBlock":false,"returnStdout":true}""")

private fun shPayload(script: String): String =
    """{"kind":"sh","command":"$script","isScriptBlock":false,"returnStdout":true}"""

private fun pwdInput(): EncodedStepValue = EncodedStepValue("""{"kind":"pwd","tmp":false}""")

private fun pwdPayload(): String = """{"kind":"pwd","tmp":false}"""

private fun errorInput(): EncodedStepValue =
    EncodedStepValue("""{"kind":"error","message":"s4rkernel","failureKind":"USER"}""")

/** Decodes through the Step's DECLARED output codec — the same last mile as `invokeTyped`. */
@Suppress("UNCHECKED_CAST")
private fun decodePwd(encoded: EncodedStepValue): PwdOutput {
    val definition = CoreStepRegistryFactory.registry().definition(PWD_KEY)
        ?: error("core.pwd is not in the production registry")
    val typed = definition as StepDefinition<Any, PwdOutput>
    return typed.contract.outputCodec.decode(encoded)
}

/** One measured (surface, step, prior) triple. `operationId` is deliberately NOT a column. */
private data class Row(
    val surface: String,
    val step: String,
    val prior: Prior,
    val decision: String,
    val handlerInvocations: Int,
    val returned: String,
    val journalTerminal: String,
    val fingerprintPolicy: String,
    val capabilityReads: Int,
    val recoveryProbes: Int,
    val journalWrites: Int,
) {
    fun render(): String =
        "| $surface | $step | ${prior.label} | decision=$decision" +
            " | invocations=$handlerInvocations" +
            " | returned=$returned | journal=$journalTerminal" +
            " | fingerprintPolicy=$fingerprintPolicy" +
            " | capReads=$capabilityReads" +
            " | recoveryProbes=$recoveryProbes" +
            " | journalWrites=$journalWrites |"
}

private val report = mutableListOf<Row>()

private fun record(row: Row): Row {
    report += row
    return row
}

private fun emit() {
    println("")
    println("=== S4-R-KERNEL SPIKE — MEASURED MATRIX (this run) ===")
    report.forEach { println(it.render()) }
    println("=== end S4-R-KERNEL SPIKE ===")
    println("")
}

/**
 * Recovers WHICH [ReplayPolicy] produced the stored fingerprint by recomputing the hash under each
 * policy. A measurement of the hash, NOT a restatement of the literal that was passed in — which is
 * the whole point, because one surface hardcodes `ReplayPolicy.MEMOIZED`
 * (`ScriptedRegistryInvoker.kt:203-205`).
 */
private fun policyOf(row: DurableOperation): String =
    ReplayPolicy.entries.firstOrNull { candidate ->
        Fingerprint.compute(row.input, row.input.stepId, candidate, row.attempt) == row.fingerprint
    }?.name ?: "UNRECOGNISED"

private fun ScriptedRegistryResult.describe(): String = when (this) {
    is ScriptedRegistryResult.Success -> "Success(encoded=${encodedOutput.value.length} chars)"
    is ScriptedRegistryResult.Failed -> "Failed(${failure.kind})"
}

private fun RunOutcome.describe(): String = when (this) {
    is RunOutcome.Success -> "success"
    is RunOutcome.Unstable -> "unstable"
    is RunOutcome.Aborted -> "aborted"
    is RunOutcome.Failure -> "Failure(${failure.kind})"
}

private fun StepOutcome.failure(): PipelineFailure = (this as StepOutcome.Failure).failure

private fun InvocationReconciliation.label(): String = when (this) {
    is InvocationReconciliation.Diverged -> "Diverged"
    is InvocationReconciliation.RecoverRunning -> "RecoverRunning"
    InvocationReconciliation.ReuseCompleted -> "ReuseCompleted"
    is InvocationReconciliation.RejectedAbort -> "RejectedAbort"
    InvocationReconciliation.Execute -> "Execute"
}

/**
 * Counts handler invocations AT THE SEAM, not inferred from the event plane: wrapping the registry
 * observes the real handler call while leaving the codec, the descriptor, the required capabilities
 * and the provider metadata untouched. The definitions are the PRODUCTION ones.
 */
private class CountingRegistry(
    private val delegate: StepRegistry,
    private val onHandlerInvoke: () -> Unit,
) : StepRegistry {
    override fun register(definition: StepDefinition<*, *>) = delegate.register(definition)

    override fun register(registration: StepRegistration<*, *>) = delegate.register(registration)

    override fun providerOf(key: PluginStepId) = delegate.providerOf(key)

    override fun contains(key: PluginStepId) = delegate.contains(key)

    override fun keys(): Set<PluginStepId> = delegate.keys()

    @Suppress("UNCHECKED_CAST")
    override fun definition(key: PluginStepId): StepDefinition<*, *>? {
        val original = delegate.definition(key) ?: return null
        val typed = original as StepDefinition<Any, Any>
        return object : StepDefinition<Any, Any> {
            override val contract: dev.rubentxu.pipeline.v2.domain.step.StepContract<Any, Any> = typed.contract
            override val handler: StepHandler<Any, Any> = StepHandler { input, context ->
                onHandlerInvoke()
                typed.handler.execute(input, context)
            }
        }
    }
}

/**
 * Pass-through recorder around the PRODUCTION default boundary. It MUST delegate: this is the
 * single execution seam (`CommonExecutionBoundary.kt:24-29`), so counting it without delegating
 * would execute nothing and measure a fiction. Capturing the returned carrier is what measures
 * whether the typed value survives the canonical executor.
 */
private class CapturingBoundary(
    private val delegate: CommonExecutionBoundary,
    private val onResult: (CommonExecutionResult) -> Unit,
) : CommonExecutionBoundary {
    override suspend fun execute(
        prepared: PreparedExecution,
        context: CanonicalRuntimeContext,
    ): CommonExecutionResult = delegate.execute(prepared, context).also(onResult)
}

/**
 * Counts durable WRITES (beginOperation + append) so "the frontend is not a second writer" is
 * measured rather than asserted. Reads are deliberately not counted: `journal.get` is an
 * addressing concern, not a replay decision.
 */
private class CountingJournal(
    private val delegate: InMemoryOperationJournal,
) : OperationJournal by delegate {
    var writes: Int = 0
        private set

    override fun append(op: DurableOperation, deadlineMs: Long?) {
        writes++
        delegate.append(op, deadlineMs)
    }

    override fun beginOperation(
        opId: String,
        attempt: Int,
        fingerprint: String,
        inputJson: String,
        deadlineMs: Long?,
    ) {
        writes++
        delegate.beginOperation(opId, attempt, fingerprint, inputJson, deadlineMs)
    }

    fun resetWrites() {
        writes = 0
    }
}

/** Counts probes of the canonical recovery port without replacing its adapter. */
private class CountingRecovery(
    private val onProbe: () -> RunningSubprocessRecovery,
) : RunningSubprocessRecovery {
    override fun recover(
        recoveryPolicy: RecoveryPolicy,
        journaled: DurableOperation?,
        operationId: String,
    ) = onProbe().recover(recoveryPolicy, journaled, operationId)
}

/**
 * Flips the journal's row for [runId] to [prior], preserving the fingerprint and input the
 * production surface wrote. This can change only the STATUS being reconciled — never the identity
 * — so it cannot smuggle a different variable into the experiment.
 */
private fun seedKernelPrior(journal: OperationJournal, runId: String, prior: Prior) {
    val status = when (prior) {
        Prior.FRESH -> return
        Prior.SUCCEEDED -> OperationStatus.SUCCEEDED
        Prior.FAILED -> OperationStatus.FAILED
        Prior.RUNNING -> OperationStatus.RUNNING
    }
    val source = journal.listForRun(runId).lastOrNull()
        ?: error("S4-R-KERNEL SPIKE: no production row to clone for $runId; the fresh run wrote none")
    val output = source.output.takeIf { status == OperationStatus.SUCCEEDED }
    journal.append(
        MemoizedOperation(
            id = source.id,
            fingerprint = source.fingerprint,
            input = source.input,
            output = output,
            status = status,
            attempt = source.attempt,
            cachedOutput = output,
        ),
    )
}
