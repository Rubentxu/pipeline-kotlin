package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.identity.ResourceKind
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceOwnership
import dev.rubentxu.pipeline.v2.dsl.pipeline
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.InMemorySagaObligationStore
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.events.identity.EnvelopeProjector
import dev.rubentxu.pipeline.v2.runtime.NoopPipelineTelemetry
import dev.rubentxu.pipeline.v2.runtime.PluginComposition
import dev.rubentxu.pipeline.v2.runtime.RunPolicy
import dev.rubentxu.pipeline.v2.runtime.RunState
import dev.rubentxu.pipeline.v2.runtime.RuntimeWorkspaceTransport
import dev.rubentxu.pipeline.v2.runtime.SequentialIdGenerator
import dev.rubentxu.pipeline.v2.runtime.durable.DurableRunCoordinator
import dev.rubentxu.pipeline.v2.runtime.durable.SagaCompensationBinding
import dev.rubentxu.pipeline.v2.runtime.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.runtime.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.runtime.durable.credentials.CredentialScopePort
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxProfile
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The envelope subject of a real run's branch steps, contrasted against the plane the
 * product actually produces.
 *
 * The subject is what a consumer resolves events with: two branches of one stage run steps
 * with the same stage index and the same step index, so a subject derived from the indices
 * alone folds the two branches into one resource and attributes one branch's rows to the
 * other. The step's events carry the durable identity of the operation the step runs as,
 * and that identity is what the subject now carries: the envelopes of a real two-branch run
 * name two distinct resources, one per branch.
 */
class BranchStepEnvelopeSubjectTest {

    @TempDir
    lateinit var controlRoot: Path

    @TempDir
    lateinit var storeDir: Path

    @Test
    @DisplayName("los envelopes de un run real de dos ramas llevan sujetos distintos")
    fun `the envelopes of a real two-branch run carry distinct subjects`() {
        val spec = pipeline {
            stages {
                stage("fan") {
                    parallel {
                        branch("left") { sh("echo one") }
                        branch("right") { sh("echo two") }
                    }
                }
            }
        }

        val started = runAndCollectStepStarts(spec, "run-branch-subjects")

        assertEquals(2, started.size) { "the run did not start two branch steps: $started" }
        assertNotEquals(
            started[0].subject.canonicalText(),
            started[1].subject.canonicalText(),
            "two sibling branches produced the same envelope subject in a real run",
        )
        assertEquals(ResourceKind.OPERATION, started[0].subject.kind)
        assertEquals(ResourceKind.OPERATION, started[1].subject.kind)
    }

    @Test
    @DisplayName("los pasos fuera de rama del mismo run conservan el sujeto de coordenadas")
    fun `steps outside a branch in the same run keep the coordinate subject`() {
        val spec = pipeline {
            stages {
                stage("serial") {
                    sh("echo plain")
                }
            }
        }

        val started = runAndCollectStepStarts(spec, "run-serial-subject")

        assertEquals(1, started.size) { "the run did not start one step: $started" }
        assertEquals(ResourceKind.STEP, started[0].subject.kind)
    }

    // --- the fixture ----------------------------------------------------------------

    private fun runAndCollectStepStarts(
        spec: dev.rubentxu.pipeline.v2.dsl.PipelineSpec,
        runId: String,
    ): List<dev.rubentxu.pipeline.v2.events.identity.PipelineEventEnvelope> {
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = spec,
            sourcePath = "pipeline.kts",
            sourceContent = "pipeline { }",
            pluginLockDigest = Digest("lock"),
        )
        val db = storeDir.resolve("$runId.db")
        val admission = admitEventStore(db.toString(), EventStorePurpose.Produce)
        val store = (admission as EventStoreAdmission.Admitted).store
        runBlocking { coordinator(store).run(compiled, RunId(runId)) }
        store.flush()
        val events = store.eventsFor(runId).toList()
        store.close()
        return events.filterIsInstance<StepStarted>()
            .map { EnvelopeProjector.project(it) }
    }

    private fun coordinator(
        eventSink: dev.rubentxu.pipeline.v2.events.EventSink,
    ): DurableRunCoordinator = DurableRunCoordinator(
        state = RunState(
            journal = InMemoryOperationJournal(FixedClock),
            cursorStore = InMemoryReplayCursorStore(FixedClock),
            eventSink = eventSink,
            outputWakeups = null,
            sagaBinding = SagaCompensationBinding(InMemorySagaObligationStore(), LEASE_EPOCH),
        ),
        policy = RunPolicy(
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
        ),
        composition = PluginComposition.resolve(pluginClassLoader = javaClass.classLoader),
        credentialScopePort = CredentialScopePort { _, _ ->
            CredentialScopeOutcome.Unavailable(CredentialScopeFailure.StoreUnavailable("not used"))
        },
        divergenceDetector = dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector(),
        gateContext = { dev.rubentxu.pipeline.v2.domain.directive.GateContext.EMPTY },
        retryControlJournal = null,
        waitUntilControlJournal = null,
        scriptedStageExecution = null,
        telemetry = NoopPipelineTelemetry,
    )

    private object FixedClock : dev.rubentxu.pipeline.v2.domain.durable.Clock {
        override fun now(): Instant = Instant.ofEpochMilli(1_700_000_000_000L)
    }

    private companion object {
        const val LEASE_EPOCH = 1L
    }
}
