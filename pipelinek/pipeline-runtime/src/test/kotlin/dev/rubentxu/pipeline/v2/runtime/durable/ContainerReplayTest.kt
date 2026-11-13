package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
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
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import dev.rubentxu.pipeline.v2.domain.directive.GateContext
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceOwnership
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
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
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * The durable REPLAY of a plan that carries containers.
 *
 * ## The guarantee under trial
 *
 * That a plan whose steps live inside a CONTAINER — a `dir { }`, a `withEnv { }`
 * — survives its own replay: the replay does not repeat the effect the
 * container's child already had, and it does not lose the operation the
 * container declared.
 *
 * ## What the replay IS
 *
 * The SAME plan, run AGAIN under the SAME run id, over the SAME journal. That
 * is what the product does when a run is resumed: the journal is the only
 * thing that survives the process, and the reconciliation of every operation
 * against that journal is what decides whether the effect happens again. The
 * published example of the catalogue (`durable-replay`) promises exactly this
 * for a plan of DIRECT steps; this row asks the same question of a plan whose
 * steps live inside a container, because a container changes the identity of
 * the operation that carries the effect.
 *
 * ## Why the container is the interesting case
 *
 * A container is NOT a step: the engine runs its body directly and the
 * container itself carries no operation row. The row that carries the effect
 * belongs to the CHILD, and the child's identity is the path that reaches it
 * — the body path, which names the container and the position of the child
 * inside it. So the container is where the identity of an operation is
 * assembled, and a replay is where that identity is read back.
 *
 * ## What the rows measure
 *
 * - the EFFECT: the child appends a line to a marker file, and the marker is
 *   the witness. One line after two runs means the effect happened once.
 * - the OPERATIONS: the journal is read after the replay, and every operation
 *   the plan declares is there.
 * - the CONTROL: a plan whose child declares something DIFFERENT diverges on
 *   the replay, and the replay says so instead of running the new work in
 *   silence.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ContainerReplayTest {

    /**
     * The replay of a plan with a container does not repeat the effect.
     *
     * The child appends a line to the marker. The first run writes the line;
     * the replay finds the journaled completion and serves it, so the marker
     * keeps ONE line.
     */
    @Test
    @DisplayName("the replay of a plan with a container does not repeat the effect")
    fun theReplayOfAPlanWithAContainerDoesNotRepeatTheEffect() {
        val marker = markerFile()
        val journal = InMemoryOperationJournal(FixedClock)
        val plan = planWithContainer(command = appendTo(marker))

        val first = runBlocking { coordinator(journal).run(plan, RunId(RUN_ID)) }
        assertEquals(
            RunOutcome.Success,
            first,
            "the first run of the plan did not succeed: the replay would be measured over a " +
                "run that never happened. The outcome was $first",
        )
        assertEquals(
            1,
            linesOf(marker),
            "the first run did not perform the effect: the marker holds ${linesOf(marker)} lines",
        )

        val second = runBlocking { coordinator(journal).run(plan, RunId(RUN_ID)) }

        assertEquals(
            RunOutcome.Success,
            second,
            "the replay of the plan did not succeed: a resumed run that fails is a different " +
                "guarantee from the one under trial",
        )
        assertEquals(
            1,
            linesOf(marker),
            "the replay repeated the effect of the container's child: the marker holds " +
                "${linesOf(marker)} lines, and the effect had already happened",
        )
    }

    /**
     * The replay of a plan with a container does not lose its operations.
     *
     * The journal is read after the replay: the operation of the child is
     * there, with its terminal status. A replay that dropped the row would
     * leave the next run with nothing to resume from — and the plan's work
     * would be done by nobody.
     */
    @Test
    @DisplayName("the replay of a plan with a container does not lose its operations")
    fun theReplayOfAPlanWithAContainerDoesNotLoseItsOperations() {
        val marker = markerFile()
        val journal = InMemoryOperationJournal(FixedClock)
        val plan = planWithContainer(command = appendTo(marker))

        runBlocking { coordinator(journal).run(plan, RunId(RUN_ID)) }
        runBlocking { coordinator(journal).run(plan, RunId(RUN_ID)) }

        val rows = journal.listForRun(RUN_ID)
        val child = rows.singleOrNull { it.id == CHILD_OPERATION_ID }
        assertNotNull(
            child,
            "the journal does not hold the operation of the container's child: the rows are " +
                "${rows.map { it.id }}",
        )
        assertEquals(
            1,
            rows.size,
            "the replay wrote a row of its own for an operation the plan declares once: the " +
                "rows are ${rows.map { it.id }}",
        )
    }

    /**
     * The replay of a plan whose child DECLARED SOMETHING ELSE diverges.
     *
     * This is the control of the two rows above, and it is the row that says
     * the replay is a RECONCILIATION and not a blind re-run: the journal holds
     * the row of a DIFFERENT program, the fingerprints differ, and the run
     * stops saying so. The effect is not repeated either — the divergence is
     * declared before the child runs.
     */
    @Test
    @DisplayName("the replay of a plan whose child declared something else diverges")
    fun theReplayOfAPlanWhoseChildDeclaredSomethingElseDiverges() {
        val marker = markerFile()
        val journal = InMemoryOperationJournal(FixedClock)

        runBlocking { coordinator(journal).run(planWithContainer(command = appendTo(marker)), RunId(RUN_ID)) }
        val changed = planWithContainer(command = appendTo(marker, text = "other"))

        val outcome = runBlocking { coordinator(journal).run(changed, RunId(RUN_ID)) }

        assertTrue(
            outcome is RunOutcome.Failure,
            "the replay of a plan whose child changed did not fail: the journal holds the row " +
                "of another program, and the run said nothing about it",
        )
        assertEquals(
            1,
            linesOf(marker),
            "the diverged replay performed the effect of the new program: the marker holds " +
                "${linesOf(marker)} lines",
        )
    }

    // --- the composition -----------------------------------------------------

    /**
     * The coordinator, composed the way the product composes it.
     *
     * The journal is the ONE thing the two runs share: it is the durable state
     * that outlives the process, and the replay is the second run over it.
     * Everything else is fresh, which is what a resumed run is — a new
     * instance over an old journal.
     */
    private fun coordinator(journal: InMemoryOperationJournal): DurableRunCoordinator =
        DurableRunCoordinator(
            state = RunState(
                journal = journal,
                cursorStore = InMemoryReplayCursorStore(FixedClock),
                eventSink = RecordingEventSink(),
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
            composition = PluginComposition.resolve(pluginClassLoader = null),
            credentialScopePort = CredentialScopePort { _, _ ->
                CredentialScopeOutcome.Unavailable(CredentialScopeFailure.StoreUnavailable("not used"))
            },
            // The detector the product ships: a fingerprint that moved is a
            // divergence, and the run says so.
            divergenceDetector = StrictFingerprintDivergenceDetector(),
            gateContext = { GateContext.EMPTY },
            retryControlJournal = null,
            waitUntilControlJournal = null,
            scriptedStageExecution = null,
            telemetry = NoopPipelineTelemetry,
        )

    /**
     * The plan under trial: ONE stage whose body is a CONTAINER (`core.dir`)
     * whose body is ONE child (`core.sh`).
     */
    private fun planWithContainer(command: String): CompiledPipeline = CompiledPipeline(
        id = DefinitionId("definition-1"),
        source = SourceDescriptor("replay.pipeline.kts", Digest("digest-1")),
        stages = listOf(
            StageNode(
                id = StageId("stage-0"),
                name = STAGE_NAME,
                environment = EnvironmentSpec.empty(),
                body = StageBody.Steps(listOf(container(command))),
                directives = emptyList(),
            ),
        ),
        pluginLockDigest = Digest("digest-1"),
    )

    /** The container: a `dir` scope, which the canonical engine runs. */
    private fun container(command: String): BlockStepNode = BlockStepNode(
        id = StepId("step-0"),
        pluginStepId = PluginStepId("core.dir"),
        payload = VersionedStepPayload(
            SCHEMA_VERSION,
            buildJsonObject { put("path", JsonPrimitive("sub")) }.toString(),
        ),
        body = listOf(child(command)),
    )

    /** The child of the container: the step whose effect is under trial. */
    private fun child(command: String): OpaqueStepNode = OpaqueStepNode(
        id = StepId("step-0-0"),
        pluginStepId = PluginStepId("core.sh"),
        payload = VersionedStepPayload(
            SCHEMA_VERSION,
            buildJsonObject {
                put("kind", JsonPrimitive("sh"))
                put("command", JsonPrimitive(command))
                put("isScriptBlock", JsonPrimitive(false))
                put("returnStdout", JsonPrimitive(false))
            }.toString(),
        ),
    )

    /** The command that appends a line to the marker: the effect under trial. */
    private fun appendTo(marker: Path, text: String = "effect"): String =
        "printf '%s\\n' '$text' >> '$marker'"

    /** The marker file, in the directory this row owns. */
    private fun markerFile(): Path = Files.createDirectories(temp.resolve("marker")).resolve("effect.log")

    /** The lines the marker holds: the number of times the effect happened. */
    private fun linesOf(marker: Path): Int =
        if (!Files.exists(marker)) 0 else Files.readAllLines(marker).size

    /**
     * A clock that does not move, so nothing in this row depends on the wall
     * clock.
     *
     * It reads a MOMENT and not the epoch: the durable shell substrate stamps
     * its terminal with the clock it is given and refuses a finished-at of
     * zero, so the epoch would fail the row over a fact that has nothing to do
     * with the replay.
     */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.ofEpochMilli(FIXED_MILLIS)
    }

    /** A sink that keeps the events, so the row can read them if it needs to. */
    private class RecordingEventSink : EventSink {
        private val recorded = mutableListOf<DomainEvent>()

        override fun append(event: DomainEvent) {
            recorded += event
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> = recorded.asSequence()
    }

    @TempDir
    lateinit var temp: Path

    @TempDir
    lateinit var controlRoot: Path

    private companion object {
        /** The name of the stage the plan declares. */
        const val STAGE_NAME = "build"

        /** The identifier of the run both invocations carry: the run is RESUMED. */
        const val RUN_ID = "run-1"

        /** The epoch of the lease the Saga seam takes. */
        const val LEASE_EPOCH = 1L

        /** The schema version the compiler writes for the steps of a stage. */
        const val SCHEMA_VERSION = "dsl-v1"

        /**
         * The moment the fixed clock reads.
         *
         * A moment and not the epoch: the durable shell substrate stamps its
         * terminal with the clock it is given and refuses a finished-at of
         * zero.
         */
        const val FIXED_MILLIS = 1_700_000_000_000L

        /**
         * The identity of the child's operation: the run, the stage, the step and
         * the body path that reaches the child — the container and the position
         * of the child inside it.
         */
        const val CHILD_OPERATION_ID = "run-1-s0-0-bp1-0:core.sh"
    }
}
