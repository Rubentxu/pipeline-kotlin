package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepRegistryBuilder
import dev.rubentxu.pipeline.v2.events.NullEventSink
import dev.rubentxu.pipeline.v2.runtime.durable.CapabilityBindings
import dev.rubentxu.pipeline.v2.runtime.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.runtime.durable.OpId
import dev.rubentxu.pipeline.v2.runtime.durable.RegistryExecutionBoundary
import dev.rubentxu.pipeline.v2.runtime.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.runtime.durable.RuntimeContext
import dev.rubentxu.pipeline.v2.runtime.input.INPUT_DECISIONS_CAPABILITY
import dev.rubentxu.pipeline.v2.runtime.input.InputDecisions
import dev.rubentxu.pipeline.v2.runtime.input.InputDenialReason
import dev.rubentxu.pipeline.v2.runtime.input.InputRequest
import dev.rubentxu.pipeline.v2.runtime.input.InputResolution
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The human-decision port reaches a step that DECLARES it, and the runtime is the one
 * that hands it over.
 *
 * ## The guarantee under trial
 *
 * That a step whose contract declares [INPUT_DECISIONS_CAPABILITY] is ADMITTED by the
 * runtime and RECEIVES the port: the handler reads the port out of the capability table
 * and the port it reads is the runtime's own, which publishes the question under the
 * control root of the run.
 *
 * ## Why the instrument is the PRODUCTION path
 *
 * Admission and injection are two different decisions, and a test that only looked at
 * the capability table would measure the first without the second. The instrument here
 * is the chain the runtime itself walks: the registry preparation that admits or
 * refuses before any effect, and the registry boundary that hands the handler the
 * capability table and runs it.
 *
 * ## The rows, and what each one tells apart
 *
 * ```text
 * a step that declares the human-decision capability is admitted and receives the port
 * a step that declares a capability the runtime does not supply is refused before its handler runs
 * ```
 *
 * The second row is the contrast that makes the first mean something: admission is
 * fail-closed, so the first row is a fact about the runtime and not a default that
 * every step would get.
 *
 * ## What this instrument cannot see
 *
 * It does not reach a socket, and it does not answer the question it publishes: the
 * wait is bounded to a millisecond so that the row measures the handover and not the
 * patience of the machine.
 */
class InputDecisionsPortAdmissionTest {

    @TempDir
    lateinit var control: Path

    @Test
    @DisplayName("a step that declares the human-decision capability is admitted and receives the port")
    fun `a step that declares the human-decision capability is admitted and receives the port`() = runBlocking {
        val resolutions = mutableListOf<InputResolution>()
        val registry = StepRegistryBuilder().add(askingStep(resolutions)).build()
        val bindings = CapabilityBindings()
        val context = runtimeContext()

        val preparation = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = KEY,
            encodedInput = EncodedStepValue(PAYLOAD),
            availableCapabilities = bindings.access(context).available(),
        )

        val ready = assertInstanceOf(
            ExecutionPreparation.Ready::class.java,
            preparation,
            "a step that declares the human-decision capability must be admitted, not refused",
        )
        val result = RegistryExecutionBoundary(bindings).execute(ready.prepared, context)

        assertInstanceOf(
            StepOutcome.Success::class.java,
            result.outcome,
            "the handler of an admitted step must run and succeed",
        )
        val denial = assertInstanceOf(
            InputResolution.Denied::class.java,
            resolutions.single(),
            "the port the handler received must answer with the reason there is no answer",
        )
        assertInstanceOf(
            InputDenialReason.TimedOut::class.java,
            denial.reason,
            "the port the handler received is the runtime's own: it waits and reports the wait",
        )
        val published = control
            .resolve(InputQuestionLayout.INPUTS_DIRECTORY)
            .resolve(InputQuestionLayout.directoryName(context.opId.format()))
            .resolve(InputQuestionLayout.REQUEST_FILE)
        assertTrue(
            Files.exists(published),
            "the question the port published must be on disk under the control root: $published",
        )
    }

    @Test
    @DisplayName("a step that declares a capability the runtime does not supply is refused before its handler runs")
    fun `a step that declares a capability the runtime does not supply is refused before its handler runs`() {
        val resolutions = mutableListOf<InputResolution>()
        val registry = StepRegistryBuilder().add(askingStep(resolutions, MILESTONE_OPERATIONS_CAPABILITY)).build()
        val bindings = CapabilityBindings()
        val context = runtimeContext()

        val preparation = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = KEY,
            encodedInput = EncodedStepValue(PAYLOAD),
            availableCapabilities = bindings.access(context).available(),
        )

        val rejected = assertInstanceOf(
            ExecutionPreparation.Rejected::class.java,
            preparation,
            "a capability the runtime does not supply must refuse the invocation",
        )
        assertTrue(
            rejected.reason.contains(MILESTONE_OPERATIONS_CAPABILITY.key),
            "the refusal must name the capability that is absent: ${rejected.reason}",
        )
        assertEquals(
            emptyList<InputResolution>(),
            resolutions,
            "a refused invocation must not reach the handler",
        )
    }

    /**
     * The step under trial: it declares [capability] and asks through the port.
     *
     * The wait is bounded to a millisecond, so the row measures the handover of the
     * port and not how long the machine is willing to wait for a person.
     */
    private fun askingStep(
        resolutions: MutableList<InputResolution>,
        capability: StepCapability = INPUT_DECISIONS_CAPABILITY,
    ): StepDefinition<String, String> = object : StepDefinition<String, String> {

        override val contract: StepContract<String, String> = StepContract(
            key = KEY,
            descriptor = StepDescriptor(stepId = KEY.value, name = "ask", configRef = "ask"),
            inputCodec = StringCodec,
            outputCodec = StringCodec,
            requiredCapabilities = setOf(capability),
        )

        override val handler: StepHandler<String, String> = StepHandler { _, context ->
            val decisions: InputDecisions = context.capabilities.get(INPUT_DECISIONS_CAPABILITY, InputDecisions::class)
            val resolution = decisions.awaitDecision(
                request = InputRequest(message = "Deploy?", ok = "Proceed", submitter = null, id = null),
                waitMillis = 1L,
            )
            resolutions += resolution
            "asked"
        }
    }

    /** The runtime context of one dispatch, over a control root that exists. */
    private fun runtimeContext(): RuntimeContext = RuntimeContext(
        opId = OpId(RUN_ID, 0, 0),
        runId = RUN_ID,
        stageName = "stage-0",
        stageIndex = 0,
        stepIndex = 0,
        shOptions = ShOptions(
            workspaceRoot = control.resolve("workspace"),
            captureStdout = false,
            timeoutMs = null,
            env = emptyMap(),
        ),
        controlDirRoot = control,
        eventSink = NullEventSink,
        clock = FixedClock,
        idGenerator = SequentialIdGenerator(),
    )

    /** A codec that transports a string, and nothing else. */
    private object StringCodec : StepCodec<String> {
        override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)

        override fun decode(encoded: EncodedStepValue): String = encoded.value
    }

    private companion object {
        val KEY: PluginStepId = PluginStepId("test.ask")
        const val RUN_ID = "run-1"
        const val PAYLOAD = "payload"
    }

    /** A clock that does not move, so nothing in these rows depends on the wall clock. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.ofEpochMilli(2_000L)
    }
}
