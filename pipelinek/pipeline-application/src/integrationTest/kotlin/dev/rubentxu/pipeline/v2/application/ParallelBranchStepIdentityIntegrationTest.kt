package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceOwnership
import dev.rubentxu.pipeline.v2.dsl.pipeline
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.InMemorySagaObligationStore
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
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
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.util.concurrent.TimeUnit

/**
 * The step of a parallel branch says which operation it runs as.
 *
 * ## The guarantee
 *
 * Two branches of one `parallel` run steps with the SAME stage index and the SAME step index: the
 * branch is not a stage and not a step, so the indices of a branch's step are the indices of the
 * step inside its branch. Nothing in the event lane said WHICH branch a step belonged to — the
 * step name spelled it as a slug (`fan/branch-a/sh-0`), and a slug is a text: two branches named
 * `a b` and `a-b` spell the same one.
 *
 * So a consumer that wanted the console of one branch's step had to guess the operation id out of
 * that slug and out of the private encoding of the id. Now the event carries the operation the
 * step runs as, in the very text form the journal row and the Output Plane use, and a consumer
 * reads the bytes of that step by asking for that id.
 *
 * ## What is read, and where
 *
 * The events are read from the DURABLE store, not from a list the test kept: the guarantee is
 * about the event a reader finds in the log, so the row has to travel the wire and come back.
 * The operations are read with the product's own `operations` verb and the console with the
 * product's own `console` verb, because the guarantee is that the id the event carries is the id
 * those verbs answer to.
 *
 * ## What this row does NOT freeze
 *
 * The shape of the operation id. The id is the product's, it is minted by the durable spine and
 * this row does not spell it out: it reads it back from the event and asks the product with it.
 * A change in the spelling of the id is not a change in this guarantee.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ParallelBranchStepIdentityIntegrationTest {

    @TempDir
    lateinit var controlRoot: Path

    @TempDir
    lateinit var storeDir: Path

    /**
     * The two branches publish their operation, and that operation is the one the product's own
     * verbs answer to.
     */
    @Test
    fun `the step of a parallel branch publishes the operation its bytes are read with`() {
        val run = runFan(runId = RUN_ID, failing = false)

        val started = run.events.filterIsInstance<StepStarted>()
        assertEquals(2, started.size, "the two branches ran one step each: ${run.events}")
        val published = started.map { it.opId }
        assertTrue(
            published.none { it == null },
            "every step of a branch publishes the operation it runs as: $published",
        )
        assertEquals(
            2,
            published.toSet().size,
            "the two branches publish DIFFERENT operations: $published",
        )

        val finished = run.events.filterIsInstance<StepFinished>()
        assertEquals(2, finished.size, "both branches reached a terminal: ${run.events}")
        assertEquals(
            published.toSet(),
            finished.map { it.opId }.toSet(),
            "the terminal of a step publishes the operation its start published",
        )

        assertEquals(
            published.toSet(),
            run.operations().toSet(),
            "the operation the event publishes is the operation the product lists",
        )

        val transcripts = published.associateWith { run.console(it!!) }
        assertEquals(
            setOf("rama-a", "rama-b"),
            transcripts.values.map { it.trim() }.toSet(),
            "each published operation reads the bytes of its own branch: $transcripts",
        )
    }

    /**
     * The failure of a branch's step names the operation it happened in.
     */
    @Test
    fun `the failure of a branch step publishes the operation it happened in`() {
        val run = runFan(runId = FAILING_RUN_ID, failing = true)

        val failed = run.events.filterIsInstance<StepFailed>()
        assertEquals(1, failed.size, "one branch failed: ${run.events}")
        val opId = failed.single().opId
        assertNotNull(opId, "the failure of a branch step publishes the operation it happened in")
        assertTrue(
            opId in run.operations(),
            "the published operation is one the product lists: $opId of ${run.operations()}",
        )
        assertTrue(
            run.console(opId!!).contains("rama-falla"),
            "the console of the failing operation reads the bytes of the branch that failed",
        )
    }

    /**
     * A step outside a branch publishes no operation.
     *
     * There the run, the stage and the step already name the step, and the rows a reader already
     * has keep reading the same way: the field is not there, not empty.
     */
    @Test
    fun `a step outside a branch publishes no operation`() {
        val run = runLinear(runId = LINEAR_RUN_ID)

        val started = run.events.filterIsInstance<StepStarted>()
        assertEquals(1, started.size, "the linear stage ran one step: ${run.events}")
        assertNull(
            started.single().opId,
            "a step outside a branch is named by its indices, and publishes no operation",
        )
        assertNull(
            run.events.filterIsInstance<StepFinished>().single().opId,
            "the terminal of a step outside a branch publishes no operation either",
        )
    }

    /** A run and the way to ask the product about it. */
    private class Run(
        val runId: String,
        val events: List<dev.rubentxu.pipeline.v2.events.DomainEvent>,
        private val controlRoot: Path,
    ) {
        /** The operations the product lists, with their complete identity. */
        fun operations(): List<String> {
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            val exit = MainOperationsCli.main(
                arrayOf(runId, "--control-root", controlRoot.toString(), "--format", "text"),
                PrintStream(out, true, Charsets.UTF_8.name()),
                PrintStream(err, true, Charsets.UTF_8.name()),
            )
            assertEquals(0, exit, "the operations verb answered: ${err.toString(Charsets.UTF_8)}")
            return out.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
        }

        /** The console of one operation, read with the product's own verb. */
        fun console(opId: String): String {
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            val exit = MainConsoleCli.main(
                arrayOf(runId, opId, "--control-dir", controlRoot.toString()),
                PrintStream(out, true, Charsets.UTF_8.name()),
                PrintStream(err, true, Charsets.UTF_8.name()),
            )
            assertEquals(0, exit, "the console verb answered: ${err.toString(Charsets.UTF_8)}")
            return out.toString(Charsets.UTF_8)
        }
    }

    /**
     * A `parallel` of two branches, each one a step of its own.
     *
     * With [failing] the first branch writes its bytes and then fails, so the run carries a
     * `StepFailed` that belongs to a branch.
     */
    private fun runFan(runId: String, failing: Boolean): Run {
        val spec = pipeline {
            stages {
                stage("fan") {
                    parallel {
                        if (failing) {
                            branch("a") { sh("echo rama-falla; exit 7") }
                        } else {
                            branch("a") { sh("echo rama-a") }
                        }
                        branch("b") { sh("echo rama-b") }
                    }
                }
            }
        }
        return run(spec, runId, succeeds = !failing)
    }

    /** A linear stage of one step. */
    private fun runLinear(runId: String): Run {
        val spec = pipeline {
            stages {
                stage("build") {
                    sh("echo hola")
                }
            }
        }
        return run(spec, runId, succeeds = true)
    }

    /** Runs a pipeline and reads its events back from the durable store. */
    private fun run(
        spec: dev.rubentxu.pipeline.v2.dsl.PipelineSpec,
        runId: String,
        succeeds: Boolean,
    ): Run {
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = spec,
            sourcePath = "pipeline.kts",
            sourceContent = "pipeline { }",
            pluginLockDigest = Digest("lock"),
        )

        val db = storeDir.resolve("$runId.db")
        val admission = admitEventStore(db.toString(), EventStorePurpose.Produce)
        val store = (admission as dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission.Admitted).store

        val outcome = runBlocking { coordinator(store).run(compiled, RunId(runId)) }
        if (succeeds) {
            assertEquals(RunOutcome.Success, outcome, "the run of the fan did not succeed")
        } else {
            assertTrue(
                outcome is RunOutcome.Failure,
                "the run of the fan failed as it was expected to: $outcome",
            )
        }

        // The writer of the store is asynchronous: a reader that opened it now would find a run
        // whose last event had not landed yet.
        store.flush()
        val events = store.eventsFor(runId).toList()
        store.close()

        return Run(runId, events, controlRoot)
    }

    /**
     * The coordinator, composed the way the product composes it.
     */
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

    /**
     * A clock that does not move.
     *
     * It reads a MOMENT and not the epoch: the durable shell substrate stamps its terminal with
     * the clock it is given and refuses a finished-at of zero.
     */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.ofEpochMilli(FIXED_MILLIS)
    }

    private companion object {
        /** The identifier of the run of the two branches that both succeed. */
        const val RUN_ID = "run-fan-1"

        /** The identifier of the run of the fan whose first branch fails. */
        const val FAILING_RUN_ID = "run-fan-2"

        /** The identifier of the run of the linear stage. */
        const val LINEAR_RUN_ID = "run-linear-1"

        /** The epoch of the lease the Saga seam takes. */
        const val LEASE_EPOCH = 1L

        /** The moment the fixed clock reads. */
        const val FIXED_MILLIS = 1_700_000_000_000L
    }
}
