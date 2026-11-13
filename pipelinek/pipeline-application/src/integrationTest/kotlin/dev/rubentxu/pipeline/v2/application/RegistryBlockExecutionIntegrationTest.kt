package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.BlockStepNode
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import dev.rubentxu.pipeline.v2.domain.directive.GateContext
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceOwnership
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.InMemorySagaObligationStore
import dev.rubentxu.pipeline.v2.dsl.pipeline
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
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * A WRAPPER and a PLUGIN block, executed.
 *
 * ## The guarantee under trial
 *
 * That the two shapes a body can take really RUN: the core scope the engine
 * drives (`withEnv { }`) and the PLUGIN's block (`registryBlock { }`), whose
 * body is invoked by the plugin's OWN handler through the bound continuation.
 *
 * ## Why this row is an INTEGRATION row
 *
 * Because the two shapes live in different places and the claim is that they
 * work TOGETHER. The wrapper projects a scope; the plugin's block is invoked by
 * a handler that lives in a JAR outside this repository; and the body of that
 * block has to run INSIDE the scope the wrapper projected. A row that only
 * read the IR would say the plan is well formed; a row that only read the
 * wrapper would say the scope is applied; only a run says that the plugin's
 * body ran inside the scope.
 *
 * ## The plugin under trial
 *
 * The block plugin of the examples (`example.repeat`), which is bundled into
 * the distribution and is therefore on this module's runtime classpath. Its
 * contract is the interesting one: the plugin decides HOW MANY times the body
 * runs, and each iteration carries its own attempt segment, so each one has
 * its own durable identity and its own journal rows. The body is written
 * through the generic `registryBlock { }` form — the form a plugin's own
 * façade uses — with the key the plugin declared.
 *
 * ## What this row does NOT freeze
 *
 * The plugin's own semantics (how many times a body runs is the plugin's
 * contract, and the plugin's own rows pin it), and the shell's own behaviour.
 * This row reads what the RUN did: the body ran, it ran inside the scope the
 * wrapper projected, and the run says so.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class RegistryBlockExecutionIntegrationTest {

    /**
     * The wrapper and the plugin block both run, and the plugin's body runs
     * inside the scope the wrapper projected.
     *
     * The marker is the witness: it holds one line per iteration of the
     * plugin's body, and every line carries the variable the WRAPPER declared.
     */
    @Test
    @DisplayName("the wrapper and the plugin block both run, and the plugin's body runs inside the scope")
    fun theWrapperAndThePluginBlockBothRunAndThePluginsBodyRunsInsideTheScope() {
        val marker = markerFile()
        val spec = pipeline {
            stages {
                stage("build") {
                    withEnv(listOf("TARGET=from-the-block")) {
                        registryBlock(
                            PluginStepId(REPEAT_KEY),
                            EncodedStepValue("{\"times\":2}"),
                            schemaVersion = "dsl-v1",
                        ) {
                            sh("printf '%s\\n' \"\$TARGET\" >> '$marker'")
                        }
                    }
                }
            }
        }

        val compiled = DslCompiledPipelineCompiler.compile(
            spec = spec,
            sourcePath = "pipeline.kts",
            sourceContent = "pipeline { }",
            pluginLockDigest = Digest("lock"),
        )
        val wrapper = (compiled.stages.single().body as StageBody.Steps).steps.single()
        assertTrue(
            wrapper is BlockStepNode,
            "the stage does not carry the wrapper as a block: it carries $wrapper",
        )
        val pluginBlock = (wrapper as BlockStepNode).body.single()
        assertEquals(
            PluginStepId(REPEAT_KEY),
            pluginBlock.pluginStepId,
            "the plugin block kept the key the plugin declared",
        )

        val outcome = runBlocking { coordinator().run(compiled, RunId(RUN_ID)) }

        assertEquals(
            RunOutcome.Success,
            outcome,
            "the run of the wrapper and the plugin block did not succeed",
        )
        val lines = Files.readAllLines(marker)
        assertEquals(
            listOf("from-the-block", "from-the-block"),
            lines,
            "the body of the plugin block did not run inside the scope the wrapper projected",
        )
    }

    /**
     * The coordinator, composed the way the product composes it.
     *
     * The composition is resolved over the classloader of THIS test, which is
     * the classloader the distribution's plugins are bundled into: the block
     * plugin of the examples is on it, and the plugin's own step is what the
     * row exercises.
     */
    private fun coordinator(): DurableRunCoordinator = DurableRunCoordinator(
        state = RunState(
            journal = InMemoryOperationJournal(FixedClock),
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
        composition = PluginComposition.resolve(pluginClassLoader = javaClass.classLoader),
        credentialScopePort = CredentialScopePort { _, _ ->
            CredentialScopeOutcome.Unavailable(CredentialScopeFailure.StoreUnavailable("not used"))
        },
        divergenceDetector = StrictFingerprintDivergenceDetector(),
        gateContext = { GateContext.EMPTY },
        retryControlJournal = null,
        waitUntilControlJournal = null,
        scriptedStageExecution = null,
        telemetry = NoopPipelineTelemetry,
    )

    /** The marker file, in the directory this row owns. */
    private fun markerFile(): Path = Files.createDirectories(temp.resolve("marker")).resolve("iterations.txt")

    /**
     * A clock that does not move.
     *
     * It reads a MOMENT and not the epoch: the durable shell substrate stamps
     * its terminal with the clock it is given and refuses a finished-at of
     * zero.
     */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.ofEpochMilli(FIXED_MILLIS)
    }

    /** A sink that keeps the events. */
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
        /** The identifier of the run. */
        const val RUN_ID = "run-1"

        /** The epoch of the lease the Saga seam takes. */
        const val LEASE_EPOCH = 1L

        /** The moment the fixed clock reads. */
        const val FIXED_MILLIS = 1_700_000_000_000L

        /** The key the block plugin of the examples declares. */
        const val REPEAT_KEY = "example.repeat"
    }
}
