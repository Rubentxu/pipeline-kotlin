package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.EnvironmentSpec
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageDirective
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.directive.GateEnvironmentPrecedence
import dev.rubentxu.pipeline.v2.domain.directive.WhenPredicate
import dev.rubentxu.pipeline.v2.domain.directive.WhenPredicateEncoder
import dev.rubentxu.pipeline.v2.domain.directivekey.WHEN_DIRECTIVE_KEY
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DivergenceDetector
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceOwnership
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.GateEvaluated
import dev.rubentxu.pipeline.v2.events.StageSkipped
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.InMemorySagaObligationStore
import dev.rubentxu.pipeline.v2.runtime.NoopPipelineTelemetry
import dev.rubentxu.pipeline.v2.runtime.PluginComposition
import dev.rubentxu.pipeline.v2.runtime.RunPolicy
import dev.rubentxu.pipeline.v2.runtime.RunState
import dev.rubentxu.pipeline.v2.runtime.RuntimeWorkspaceTransport
import dev.rubentxu.pipeline.v2.runtime.SequentialIdGenerator
import dev.rubentxu.pipeline.v2.runtime.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.runtime.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.runtime.durable.credentials.CredentialScopePort
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxProfile
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The run that a gate leaves out.
 *
 * ## The guarantee under trial
 *
 * That a gate whose variable nobody declares leaves the stage OUT and lets the
 * run carry on: the run ends in success, and the stage's body never runs.
 *
 * ## Why the RUN is the thing under trial
 *
 * Because the verdict of a gate is not what a reader experiences. The verdict
 * is a decision inside the BEFORE_STAGE seam; what a reader experiences is a
 * pipeline that says "success" for work that never ran. A negative gate that
 * failed the run would be the opposite defect, and a negative gate that
 * failed the run is what a reader migrating a Jenkinsfile fears most: a
 * pipeline that goes red for a condition that was never true.
 *
 * ## Why the body of the skipped stage is a step that cannot run
 *
 * So that the row discriminates. The stage carries a step no registry
 * resolves: if the stage ran, the dispatch would fail and the run would end
 * in failure. The run ends in success, so the body did not run — and the row
 * says so without reading a single event.
 *
 * ## What this instrument does not freeze
 *
 * The wording of the events, and the order of the pair the seam emits. The row
 * reads the two facts it needs — the gate was evaluated and did not hold, and
 * the stage was skipped — and leaves their shape alone.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SkippedGateRunOutcomeTest {

    /**
     * A gate on a variable nobody declares leaves the stage out, and the run
     * carries on.
     *
     * The host carries `BRANCH_NAME=main` — the branch of the agent — and the
     * script declares nothing, so the gate cannot see the variable: the
     * verdict is a decided negative, the stage does not run, and the run ends
     * in success.
     */
    @Test
    @DisplayName("a gate on a variable nobody declares leaves the stage out and does not fail the run")
    fun aGateOnAVariableNobodyDeclaresLeavesTheStageOutAndDoesNotFailTheRun() {
        val events = RecordingEventSink()
        val coordinator = coordinator(events)

        val outcome = runBlocking { coordinator.run(pipeline(), RunId(RUN_ID)) }

        assertEquals(
            RunOutcome.Success,
            outcome,
            "a stage that a negative gate leaves out does not fail the run",
        )
        assertTrue(
            events.skipped().any { skipped -> skipped.stageName == STAGE_NAME },
            "the stage is reported as skipped: ${events.skipped()}",
        )
        assertTrue(
            events.evaluated().any { evaluated ->
                evaluated.stageName == STAGE_NAME && !evaluated.satisfied
            },
            "the gate is reported as evaluated and not satisfied: ${events.evaluated()}",
        )
    }

    /**
     * The coordinator, composed the way the product composes it.
     *
     * The gate's facts come from the same precedence the composition root
     * hands to the coordinator: the row does not rewrite the rule, it
     * exercises it. The host's environment is the agent's, and the stage
     * declares nothing.
     */
    private fun coordinator(events: EventSink): DurableRunCoordinator =
        DurableRunCoordinator(
            state = state(events),
            policy = policy(),
            composition = PluginComposition.resolve(pluginClassLoader = null),
            credentialScopePort = CredentialScopePort { _, _ ->
                CredentialScopeOutcome.Unavailable(CredentialScopeFailure.StoreUnavailable("not used"))
            },
            divergenceDetector = PermissiveDetector(),
            gateContext = { environment ->
                GateEnvironmentPrecedence.DEFAULT.resolve(environment.values, HOST_ENVIRONMENT)
            },
            // Both control journals are absent, so the run keeps the inline retry loop and the
            // inline polling. Their file-backed counterparts create their directory in their
            // constructor, so binding them here would write to disk at composition time.
            retryControlJournal = null,
            waitUntilControlJournal = null,
            // The scripted seam is not bound: this run carries no scripted body.
            scriptedStageExecution = null,
            // The live tracing seam is bound to the disabled observer: this row observes a
            // decision, not a trace, and a disabled observer must cost nothing.
            telemetry = NoopPipelineTelemetry,
        )

    /** The run's durable spine: the journal, the cursor store, the sink and the Saga seam. */
    private fun state(events: EventSink): RunState = RunState(
        journal = InMemoryOperationJournal(FixedClock),
        cursorStore = InMemoryReplayCursorStore(FixedClock),
        eventSink = events,
        outputWakeups = null,
        sagaBinding = SagaCompensationBinding(InMemorySagaObligationStore(), LEASE_EPOCH),
    )

    /** What the run was given: the clock, the replay policy, the control root and the rest. */
    private fun policy(): RunPolicy = RunPolicy(
        clock = FixedClock,
        idGenerator = SequentialIdGenerator(),
        effectReplayPolicy = DefaultEffectReplayPolicy(),
        controlDirRoot = controlRoot,
        sandboxProfile = SandboxProfile.NONE,
        workspace = RuntimeWorkspaceTransport(base = null, ownership = WorkspaceOwnership.PIPELINEK),
        credentialProvider = null,
        withCredentialsExecutor = null,
        allowNetwork = false,
        secretPatternRegistry = SecretPatternRegistry(),
    )

    /**
     * The pipeline of the row: ONE stage, gated on a variable nobody declares,
     * whose body carries a step that no registry resolves.
     */
    private fun pipeline(): CompiledPipeline = CompiledPipeline(
        id = DefinitionId("definition-1"),
        source = SourceDescriptor("gate.pipeline.kts", Digest("digest-1")),
        stages = listOf(
            StageNode(
                id = StageId("stage-0"),
                name = STAGE_NAME,
                environment = EnvironmentSpec.empty(),
                body = StageBody.Steps(listOf(unroutableStep())),
                directives = listOf(
                    StageDirective(
                        key = WHEN_DIRECTIVE_KEY.value,
                        encodedArguments = WhenPredicateEncoder.encode(
                            WhenPredicate.VariableEquals("BRANCH_NAME", "main"),
                        ),
                    ),
                ),
            ),
        ),
        pluginLockDigest = Digest("digest-1"),
    )

    /** A step that no registry resolves: if the stage ran, the run would fail. */
    private fun unroutableStep(): OpaqueStepNode = OpaqueStepNode(
        id = StepId("step-0"),
        pluginStepId = PluginStepId("core.absent"),
        payload = VersionedStepPayload("1", "{}"),
    )

    /** A clock that does not move, so nothing in this row depends on the wall clock. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    /** A detector that reports no divergence: the row is about the gate, not the journal. */
    private class PermissiveDetector : DivergenceDetector {
        override fun check(current: DurableOperation, journaled: DurableOperation?): Result<Unit> =
            Result.success(Unit)
    }

    /**
     * A sink that keeps the events, so the row can read the two it needs.
     */
    private class RecordingEventSink : EventSink {
        private val recorded = mutableListOf<DomainEvent>()

        override fun append(event: DomainEvent) {
            recorded += event
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> = recorded.asSequence()

        /** The recorded skips. */
        fun skipped(): List<StageSkipped> = recorded.filterIsInstance<StageSkipped>()

        /** The recorded gate verdicts. */
        fun evaluated(): List<GateEvaluated> = recorded.filterIsInstance<GateEvaluated>()
    }

    @TempDir
    lateinit var controlRoot: Path

    private companion object {
        /** The name of the stage the row gates. */
        const val STAGE_NAME = "build"

        /** The identifier of the run the row observes. */
        const val RUN_ID = "run-1"

        /** The epoch of the lease the Saga seam takes. */
        const val LEASE_EPOCH = 1L

        /** What the agent has, and what the script declares nothing of. */
        val HOST_ENVIRONMENT = mapOf("BRANCH_NAME" to "main")
    }
}
