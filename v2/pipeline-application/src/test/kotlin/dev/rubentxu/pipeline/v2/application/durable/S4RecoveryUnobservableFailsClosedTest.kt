package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.buildDefaultExecutionBoundary
import dev.rubentxu.pipeline.v2.application.support.CoordinatorFixture
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ParallelFrame
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepRegistryBuilder
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.BranchExecutionResult
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursor
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.StageIndex
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADR-0103 R1-E — the fail-closed law for an OBSERVABLE-BUT-NOT-CONCLUDENT recovery.
 *
 * The S4-R-REC spike measured the gap: `ExternalSubprocess + RUNNING + controlDirRoot == null`
 * reached `Execute`, re-ran a subprocess whose prior external effect was unknown, and then
 * terminalised the row as `SUCCEEDED`. This class is the LAW that must hold afterwards, and it is
 * written against the production spine rather than against the recovery adapter alone, because the
 * adapter is not where the damage happened: the adapter could not tell the caller what it saw, and
 * the caller therefore had no way to fail closed.
 *
 * ## The four counts
 *
 * A fail-closed reconciliation is not one assertion; it is four independent promises, and this
 * harness measures each one with a real collaborator rather than trusting a return value:
 *
 * ```text
 * handler invocations   0   the Step's effect did not run a second time
 * capability reads      0   admission never ran, so no capability was ever derived
 * cursor writes         0   the run did not move past a step it could not resolve
 * journal terminal      —   the RUNNING row is left EXACTLY as it was
 * ```
 *
 * The last one is the subtle one and the reason this test exists. A failure that appends a
 * terminal row is NOT fail-closed: it destroys the only evidence that a later, correctly
 * configured run could still reconcile. `LOST` is equally wrong here — "I could not look" is not
 * "the process is gone". The row must stay `RUNNING`, and the invocation must still fail.
 *
 * ## What this deliberately does NOT assert
 *
 * No timing. The 60 s reattach poll is never reached on this path, and asserting a duration would
 * be asserting a performance property of a machine, not a semantic one.
 */
class S4RecoveryUnobservableFailsClosedTest {

    @Test
    fun `a required recovery with no substrate runs nothing, moves no cursor, and leaves the row RUNNING`() =
        runBlocking {
            val probe = Probe(RecoveryPolicy.ExternalSubprocess, ReplayPolicy.RERUN, Effect.EXECUTES_SUBPROCESS)
            // The recovery substrate is DISARMED: the coordinator has no control root, so the
            // observer has nothing to observe. Recovery is nevertheless REQUIRED by the
            // descriptor, which is the whole point of the case.
            val rig = Rig("s4rec-unobservable", probe, controlDirRoot = null)
            rig.execute()
            rig.flipRowToRunning()
            val handlerBefore = probe.invocations.get()
            val capabilityBefore = rig.capabilityReads.get()
            val cursorBefore = rig.cursorWrites.get()

            val outcome = rig.execute()

            assertAll(
                { ->
                    assertEquals(
                        handlerBefore,
                        probe.invocations.get(),
                        "LAW 1/5 — a required-but-unobservable recovery must not run the handler. " +
                            "The prior external effect of this operation is UNKNOWN; running it " +
                            "again is the at-least-once window this law closes.",
                    )
                },
                { ->
                    assertEquals(
                        capabilityBefore,
                        rig.capabilityReads.get(),
                        "LAW 2/5 — the invocation settles before admission, so no capability is " +
                            "ever derived. A capability read would mean the engine had already " +
                            "committed to executing.",
                    )
                },
                { ->
                    assertEquals(
                        cursorBefore,
                        rig.cursorWrites.get(),
                        "LAW 3/5 — the run must not advance its replay cursor past a step it could " +
                            "not resolve. A cursor write would tell a later resume that this step " +
                            "was settled.",
                    )
                },
                { ->
                    assertEquals(
                        OperationStatus.RUNNING,
                        rig.row()?.status,
                        "LAW 4/5 — the row must stay RUNNING. Appending a terminal row, SUCCEEDED " +
                            "or LOST alike, is not fail-closed: it destroys the evidence a later " +
                            "correctly configured run would need to reconcile.",
                    )
                },
                { ->
                    val runFailed = outcome is dev.rubentxu.pipeline.v2.domain.RunOutcome.Failure
                    assertTrue(
                        runFailed,
                        "LAW 5/5 — the invocation itself still fails. 'I cannot prove what " +
                            "happened' is not success, and reporting it as success is how the " +
                            "unknown effect became indistinguishable from a fresh one. Got $outcome",
                    )
                    if (runFailed) {
                        assertEquals(
                            dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE,
                            (outcome as dev.rubentxu.pipeline.v2.domain.RunOutcome.Failure).failure.kind,
                            "LAW 5/5 — and as INFRASTRUCTURE, not a Step or script failure: nothing " +
                                "about the Step itself is wrong, the runtime simply could not observe " +
                                "the operation's substrate. Naming it accurately is what tells an " +
                                "operator to fix configuration rather than debug their pipeline.",
                        )
                    }
                },
            )
        }

    // ------------------------------------------------------------------ rig

    private class Rig(
        private val runId: String,
        probe: Probe,
        controlDirRoot: java.nio.file.Path?,
    ) {
        private val journal = InMemoryOperationJournal(SystemClock())
        private val registry = StepRegistryBuilder().also { it.add(probe) }.build()
        val capabilityReads = AtomicInteger(0)
        val cursorWrites = AtomicInteger(0)

        private val cursorStore = object : ReplayCursorStore {
            private val delegate = InMemoryReplayCursorStore(SystemClock())
            override fun load(runId: String): ReplayCursor? = delegate.load(runId)
            override fun advance(runId: String, opId: String, stageIndex: Int) {
                cursorWrites.incrementAndGet()
                delegate.advance(runId, opId, stageIndex)
            }

            override fun advancePastParallelFrame(
                runId: String,
                frame: ParallelFrame,
                branchResults: List<BranchExecutionResult>,
                explicitMaxStageIndex: Int?,
            ): StageIndex = delegate.advancePastParallelFrame(runId, frame, branchResults, explicitMaxStageIndex)
        }

        private val coordinator = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = cursorStore,
            clock = SystemClock(),
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = InMemoryEventStore(),
            credentialScopePort = CoordinatorFixture.noOpCredentialScopePort(),
            controlDirRoot = controlDirRoot,
            shOptions = ShOptions.EMPTY,
            capabilityContributor = RuntimeCapabilityContributor {
                capabilityReads.incrementAndGet()
                emptyMap<StepCapability, Any>()
            },
            divergenceDetector = StrictFingerprintDivergenceDetector(),
            commonExecutionBoundary = buildDefaultExecutionBoundary(
                dispatcher = CanonicalNodeDispatcher(),
                invocationExecutor = null,
                stepRegistry = registry,
            ),
            stepRegistry = registry,
        )

        suspend fun execute() = coordinator.run(pipelineFor(runId), RunId(runId))

        fun row(): DurableOperation? = journal.listForRun(runId).lastOrNull()

        fun flipRowToRunning(): String {
            val source = row() ?: error("S4-REC: no production row to clone for $runId")
            journal.append(
                RerunOperation(
                    id = source.id,
                    fingerprint = source.fingerprint,
                    input = source.input,
                    output = null,
                    status = OperationStatus.RUNNING,
                    attempt = source.attempt,
                ),
            )
            return source.id
        }
    }

    private class Probe(
        recovery: RecoveryPolicy,
        replay: ReplayPolicy,
        effect: Effect,
    ) : StepDefinition<String, String> {

        val invocations = AtomicInteger(0)

        private val codec = object : StepCodec<String> {
            override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)
            override fun decode(encoded: EncodedStepValue): String = encoded.value
        }

        override val contract: StepContract<String, String> = StepContract(
            key = PROBE_KEY,
            descriptor = StepDescriptor(
                stepId = PROBE_KEY.value,
                name = "recovery-probe",
                configRef = "",
                executionLocation = ExecutionLocation.CONTROLLER,
                effects = listOf(effect),
                replayPolicy = replay,
                recoveryPolicy = recovery,
            ),
            inputCodec = codec,
            outputCodec = codec,
        )

        override val handler: StepHandler<String, String> = StepHandler { input, _ ->
            invocations.incrementAndGet()
            input
        }
    }

    private companion object {
        val PROBE_KEY = PluginStepId("law.recovery.unobservable")

        fun assertTrue(condition: Boolean, message: String) =
            org.junit.jupiter.api.Assertions.assertTrue(condition, message)

        fun pipelineFor(runId: String): CompiledPipeline = CompiledPipeline(
            id = DefinitionId("s4rec-$runId"),
            source = SourceDescriptor("s4rec.pipeline.kts", Digest("s4rec-source")),
            pluginLockDigest = Digest("s4rec-lock"),
            stages = listOf(
                StageNode(
                    id = StageId("s4rec"),
                    name = "s4rec",
                    body = StageBody.Steps(
                        listOf(
                            OpaqueStepNode(
                                id = StepId("s4rec/step-0"),
                                pluginStepId = PROBE_KEY,
                                payload = VersionedStepPayload("dsl-v1", """{"kind":"probe"}"""),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }
}
