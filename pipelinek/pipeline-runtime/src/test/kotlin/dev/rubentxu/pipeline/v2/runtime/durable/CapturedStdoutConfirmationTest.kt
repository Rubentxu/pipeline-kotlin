package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.events.NullEventSink
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.runtime.LiveSpanParent
import dev.rubentxu.pipeline.v2.runtime.NoopPipelineTelemetry
import dev.rubentxu.pipeline.v2.runtime.SequentialIdGenerator
import dev.rubentxu.pipeline.v2.runtime.StepMetadata
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.CapturedStdoutRead
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShellProtocolLayout
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * The captured stdout outlives the execution until the journal confirms it.
 *
 * **The guarantee.** The captured value of a `sh` step is durable from the moment the child
 * writes it until the moment the journal holds it, and it is NEVER durable in between as
 * anything else: the control directory that holds it is the value's only durable copy, so a
 * process that dies after the execution ended and before the journal's write still leaves
 * something a resumed run can read. When the journal has confirmed the execution, the value is
 * in the row and the directory has nothing left to keep.
 *
 * **What was wrong.** The executor read the capture and disposed of it, and then disposed of the
 * whole control directory, BOTH before the terminal reached the journal. The journal's write
 * happens after: the value is projected, the operation's tails are sealed, the output is encoded
 * and only then is the row appended. So the value's only durable copy was destroyed before
 * anybody recorded the value, and a death in that span left a resumed run with a LOST execution
 * instead of the value — a LOST execution that a rerun policy answers by repeating an effect
 * that already happened.
 *
 * **The observable capacity it serves.** A durable `sh` with `returnStdout` survives the death
 * of the worker that was running it, and the resumed run publishes the value the program printed
 * instead of losing it.
 *
 * **Why this is a unit test.** The window is not a race: it spans the projection, the seal, the
 * encoding and the append, and any of them can fail or the process can die inside it. What the
 * rows below observe is not a timing but a STATE — what the control directory holds after the
 * execution ended, and what the recovery observer publishes when it is asked — and that state is
 * fully observable in process. The observer takes a control directory and returns a terminal;
 * the executor's reader takes a control directory and returns what is known about the capture;
 * the release takes a control directory and an exit code. Nothing here starts a process, and the
 * harness that kills a worker mid-flight belongs to the harness repository, not to this one.
 *
 * **Why the reader is called without a policy.** The policy is the product's own default, and
 * the default is part of what these rows pin: a capture that the product disposes of at the
 * reading is a value that a death in the window can lose. Naming the policy in the test would
 * hide the decision the product actually makes.
 */
class CapturedStdoutConfirmationTest {

    /**
     * The directory this instrumentation writes into.
     *
     * The test framework creates it before the test and removes it when the test ends, so the
     * fixture owns its own temporary instead of leaving one behind in the shared temporary
     * directory of the machine.
     */
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `a capture the journal never confirmed is still readable by the recovery`() {
        val root = controlRootWith(captured = "hello\n")
        val executor = DurableShellExecutor()
        val opDir = root.resolve(OP_ID)

        // The fresh path publishes the value the program printed.
        assertEquals("hello", freshCapturedStdout(executor, opDir))
        // The execution ends. The journal has NOT confirmed it yet.
        executor.cleanup(opDir, 0)

        // A resumed run finds the value, not a hole where the value was.
        assertEquals("hello", recoveredCapturedStdout(root))
    }

    @Test
    fun `the control directory of an execution that still holds its capture survives the execution`() {
        val root = controlRootWith(captured = "hello\n")
        val opDir = root.resolve(OP_ID)

        DurableShellExecutor().cleanup(opDir, 0)

        assertTrue(Files.exists(opDir), "the control directory of a capture survives the execution")
        assertTrue(
            Files.exists(ShellProtocolLayout(opDir).outputFile),
            "the capture is still where the child left it",
        )
    }

    @Test
    fun `the confirmation releases the capture's home`() {
        val root = controlRootWith(captured = "hello\n")
        val opDir = root.resolve(OP_ID)
        val executor = DurableShellExecutor()
        executor.cleanup(opDir, 0)

        assertTrue(executor.confirmCapture(opDir), "the confirmation released the capture's home")
        assertFalse(Files.exists(opDir), "the confirmed control directory is gone")
    }

    @Test
    fun `the confirmation is idempotent`() {
        val root = controlRootWith(captured = "hello\n")
        val opDir = root.resolve(OP_ID)
        val executor = DurableShellExecutor()
        executor.cleanup(opDir, 0)

        assertTrue(executor.confirmCapture(opDir))
        assertFalse(executor.confirmCapture(opDir), "confirming twice releases once")
    }

    @Test
    fun `a failed execution keeps its directory after the confirmation`() {
        val root = controlRootWith(captured = "hello\n", exitCode = 42)
        val opDir = root.resolve(OP_ID)
        val executor = DurableShellExecutor()
        executor.cleanup(opDir, 42)

        assertFalse(executor.confirmCapture(opDir), "a failed execution is not released")
        assertTrue(Files.exists(opDir), "the evidence of a failed execution stays")
    }

    @Test
    fun `an expired execution keeps its directory after the confirmation`() {
        val root = controlRootWith(captured = "hello\n")
        val opDir = root.resolve(OP_ID)
        // The timeout marker is written BEFORE the kill, so it is the durable authority on an
        // expiry even when the wrapper published a zero after its child was killed.
        Files.writeString(ShellProtocolLayout(opDir).timeoutFlag, "1700000000000")
        val executor = DurableShellExecutor()

        assertFalse(executor.confirmCapture(opDir), "an expired execution is not released")
        assertTrue(Files.exists(opDir), "the evidence of an expired execution stays")
    }

    @Test
    fun `the confirmation happens after the journal holds the row`() {
        val records = DurableInvocationRecord(InMemoryOperationJournal(FixedClock))
        // The port is the witness: it reads the journal at the MOMENT it is called, so the row
        // it finds says whether the value was already recorded when the leftovers were released.
        var rowAtConfirmation: DurableOperation? = null
        val executor = DurableStepExecutor(
            eventSink = NullEventSink,
            executionBoundary = CommonExecutionBoundary { _, _ ->
                CommonExecutionResult(StepOutcome.Success, EncodedStepValue("42"))
            },
            records = records,
            telemetry = NoopPipelineTelemetry,
            clock = FixedClock,
            idGenerator = SequentialIdGenerator(),
            journalConfirmation = JournalConfirmation { operationId ->
                rowAtConfirmation = records.read(operationId)
            },
        )

        runBlocking {
            executor.executeAndJournal(
                operationId = OP_ID,
                fingerprint = fingerprint(),
                input = input(),
                journaled = null,
                prepared = prepared(),
                runtime = runtime(),
                lifecycleContext = lifecycle(),
                parent = LiveSpanParent.Root,
            )
        }

        val row = requireNotNull(
            rowAtConfirmation,
        ) { "the confirmation ran, and the journal answered it with a row" }
        assertEquals(
            OperationStatus.SUCCEEDED,
            row.status,
            "the journal already held the terminal row when the leftovers were released",
        )
    }

    @Test
    fun `a resumed run recovers the execution instead of losing it`() {
        val root = controlRootWith(captured = "hello\n")
        val opDir = root.resolve(OP_ID)
        // The execution ended and the journal has NOT confirmed it: the state a death in the
        // window leaves behind.
        DurableShellExecutor().cleanup(opDir, 0)

        // The journal holds a RUNNING row: the invocation was admitted and never settled.
        val records = DurableInvocationRecord(InMemoryOperationJournal(FixedClock))
        records.admit(OP_ID, fingerprint(), input(), alreadyJournaled = null)

        val resolution = DurableInvocationResolver(
            divergenceDetector = StrictFingerprintDivergenceDetector(),
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            records = records,
            runningSubprocessRecovery = ExternalSubprocessRecovery(FixedClock, root),
        ).reconcileInvocation(
            metadata = StepMetadata(
                effects = emptySet(),
                replayPolicy = ReplayPolicy.RERUN,
                recoveryPolicy = RecoveryPolicy.ExternalSubprocess,
            ),
            fingerprint = fingerprint(),
            input = input(),
            operationId = OP_ID,
        ).resolution

        // The DECISION, not the reading: a recovered execution is not a lost one, and not a
        // re-execution either — the effect already happened and the value is still here.
        val recovered = assertInstanceOf(
            InvocationReconciliation.RecoverRunning::class.java,
            resolution,
            "the reconciler did not recover the execution",
        )
        val exited = assertInstanceOf(DurableTaskTerminal.Exited::class.java, recovered.terminal)
        assertEquals(
            "hello",
            exited.output.capturedStdout,
            "the recovered execution carries the value the program printed",
        )
    }

    @Test
    fun `an empty capture is a value and an absent one leaves nothing to keep`() {
        val executor = DurableShellExecutor()

        val empty = controlRootWith(captured = "")
        executor.cleanup(empty.resolve(OP_ID), 0)
        assertEquals("", recoveredCapturedStdout(empty), "an empty capture is a value, not an absence")

        val absent = controlRootWith(captured = null)
        executor.cleanup(absent.resolve(OP_ID), 0)
        assertFalse(
            Files.exists(absent.resolve(OP_ID)),
            "an absent capture leaves nothing to keep, and the directory goes",
        )
    }

    /**
     * The value the RECOVERED path publishes: the observer's terminal, read through the Step's
     * own vocabulary. The observer has no policy and no notion of a step outcome, so the
     * captured stdout is the only thing this test asks it for.
     */
    private fun recoveredCapturedStdout(root: Path): String? {
        val observation = ExternalSubprocessRecovery(FixedClock, root).observe(OP_ID)
        val observed = assertInstanceOf(RunningSubprocessObservation.Observed::class.java, observation)
        val exited = assertInstanceOf(DurableTaskTerminal.Exited::class.java, observed.terminal)
        return exited.output.capturedStdout
    }

    /**
     * The value the FRESH path publishes: the executor's own reader, under the product's own
     * default.
     *
     * The reader answers with a FACT about the channel, and this projects that fact into the
     * vocabulary of the comparison: an absence is no value, a value is the value. A refusal is
     * neither, and it fails the row loudly instead of being read as an empty value — which is
     * exactly what the reader must never do.
     */
    private fun freshCapturedStdout(executor: DurableShellExecutor, opDir: Path): String? =
        when (val read = executor.readOutputText(opDir)) {
            is CapturedStdoutRead.Absent -> null
            is CapturedStdoutRead.Present -> read.text
            is CapturedStdoutRead.Unreadable -> fail("the capture could not be read: ${read.reason}")
            is CapturedStdoutRead.NotDisposed ->
                fail("the capture could not be disposed of: ${read.reason}")
        }

    /**
     * A control directory holding a finished operation: `result.txt` publishes the exit code, so
     * the reconciler classifies it as COMPLETE, and `output.txt` holds the captured stdout — or
     * is absent, which is a different fact from an empty one.
     */
    private fun controlRootWith(captured: String?, exitCode: Int = 0): Path {
        val root = tempDir.resolve("capture-confirmation-${captured?.length ?: -1}-$exitCode")
        val opDir = root.resolve(OP_ID)
        Files.createDirectories(opDir)
        val protocol = ShellProtocolLayout(opDir)
        Files.writeString(protocol.resultFile, exitCode.toString())
        if (captured != null) {
            Files.writeString(protocol.outputFile, captured)
        }
        return root
    }

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.ofEpochMilli(2_000L)
    }

    /** The durable input of the invocation under observation. */
    private fun input(): OperationInput = OperationInput(
        stepId = STEP_ID,
        params = mapOf("payload" to JsonPrimitive("value")),
        runId = RUN_ID,
        attempt = DurableInvocationRecord.ATTEMPT,
    )

    /** The fingerprint of the invocation under observation, as the protocol computes it. */
    private fun fingerprint(): Fingerprint =
        Fingerprint.compute(input(), STEP_ID, ReplayPolicy.RERUN, DurableInvocationRecord.ATTEMPT)

    /** The prepared execution: a real Step definition, with no handler ever invoked. */
    private fun prepared(): PreparedRegistryExecution = PreparedRegistryExecution(
        key = PluginStepId(STEP_ID),
        definition = definition(),
        decodedInput = "value",
    )

    /** A Step definition that would work, if anything ever called it. */
    private fun definition(): StepDefinition<String, String> = object : StepDefinition<String, String> {
        override val contract: StepContract<String, String> = StepContract(
            key = PluginStepId(STEP_ID),
            descriptor = StepDescriptor(stepId = STEP_ID, name = "probe", configRef = "probe"),
            inputCodec = StringCodec,
            outputCodec = StringCodec,
        )

        override val handler: StepHandler<String, String> = StepHandler { _, _ -> "value" }
    }

    /** A codec that carries a string, and nothing else. */
    private object StringCodec : StepCodec<String> {
        override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)
        override fun decode(encoded: EncodedStepValue): String = encoded.value
    }

    /** The runtime context of the invocation, pointing at paths that do not exist. */
    private fun runtime(): RuntimeContext = RuntimeContext(
        opId = OpId(RUN_ID, 0, 0),
        runId = RUN_ID,
        stageName = "stage-0",
        stageIndex = 0,
        stepIndex = 0,
        shOptions = ShOptions(
            workspaceRoot = Path.of("/nonexistent-workspace"),
            captureStdout = false,
            timeoutMs = null,
            env = emptyMap(),
        ),
        controlDirRoot = Path.of("/nonexistent-control-root"),
        eventSink = NullEventSink,
        clock = FixedClock,
        idGenerator = SequentialIdGenerator(),
    )

    /** The lifecycle coordinates of the invocation. */
    private fun lifecycle(): StepLifecycleContext = StepLifecycleContext(
        runId = RUN_ID,
        stageIndex = 0,
        stepIndex = 0,
        stepName = "probe",
        stepType = STEP_ID,
    )

    private companion object {
        const val OP_ID = "op-1"
        const val RUN_ID = "run-1"
        const val STEP_ID = "core.probe"
    }
}
