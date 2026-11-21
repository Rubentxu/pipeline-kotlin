package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskOutput
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.RecoveredProjection
import dev.rubentxu.pipeline.v2.domain.step.RecoveredStepProjection
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepRegistryBuilder
import dev.rubentxu.pipeline.v2.events.NullEventSink
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.runtime.durable.CapabilityBindings
import dev.rubentxu.pipeline.v2.runtime.durable.DurableInvocationRecord
import dev.rubentxu.pipeline.v2.runtime.durable.DurableInvocationResolver
import dev.rubentxu.pipeline.v2.runtime.durable.DurableOperationFactsReader
import dev.rubentxu.pipeline.v2.runtime.durable.DurableOperationFactsStore
import dev.rubentxu.pipeline.v2.runtime.durable.ExecutionPreparation
import dev.rubentxu.pipeline.v2.runtime.durable.InvocationReconciliation
import dev.rubentxu.pipeline.v2.runtime.durable.OpId
import dev.rubentxu.pipeline.v2.runtime.durable.RecoveredExecutionMaterializer
import dev.rubentxu.pipeline.v2.runtime.durable.RegistryExecutionBoundary
import dev.rubentxu.pipeline.v2.runtime.durable.RegistryExecutionPreparation
import dev.rubentxu.pipeline.v2.runtime.durable.RunningSubprocessObservation
import dev.rubentxu.pipeline.v2.runtime.durable.RunningSubprocessRecovery
import dev.rubentxu.pipeline.v2.runtime.durable.RuntimeContext
import dev.rubentxu.pipeline.v2.runtime.durable.UnobservableCause
import dev.rubentxu.pipeline.v2.runtime.durable.DURABLE_OPERATION_CAPABILITY
import dev.rubentxu.pipeline.v2.runtime.durable.DurableOperationFacts
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * An interrupted turn is recovered from the durable facts of the operation, and the agent is
 * not asked a second time.
 *
 * ## The guarantee under trial
 *
 * That a step which declares [DURABLE_OPERATION_CAPABILITY] obtains the durable facts of its
 * OWN operation, and that an execution interrupted halfway is recovered from them: the
 * terminal the step recorded reaches the step's own projection, and the effect — the agent
 * turn — is not repeated.
 *
 * ## The instrument is the PRODUCTION path, in three pieces
 *
 * ```text
 * the capability bridge   RuntimeCapabilityAccess binds the facts of the operation
 * the decision            DurableInvocationResolver reads them for an interrupted row
 * the projection          RecoveredExecutionMaterializer hands the terminal to the Step
 * ```
 *
 * Each piece is the engine's own, and the law walks them in the order the engine walks them.
 * The only things the law owns are the two effects that live outside the process: the agent
 * (a third party, counted) and the clock.
 *
 * ## What the rows tell apart
 *
 * ```text
 * the facts of an operation are the facts of THAT operation and of no other
 * an interrupted operation whose facts hold a terminal is RECOVERED, not executed again
 * the recovered terminal reaches the Step's own projection
 * the agent is asked exactly once across the whole law
 * ```
 *
 * The last row is the one the guarantee is about: the second request to the agent would be
 * a second prompt the run never made.
 */
class DurableOperationFactsRecoveryTest {

    @TempDir
    lateinit var control: Path

    @Test
    @DisplayName("an interrupted turn is recovered from the durable facts of the operation, and the agent is asked once")
    fun `an interrupted turn is recovered from the durable facts of the operation, and the agent is asked once`() = runBlocking {
        val journal = InMemoryOperationJournal(FixedClock)
        val bindings = CapabilityBindings(journal = journal)
        val context = runtimeContext()
        val agent = CountingAgent()
        val projected = mutableListOf<DurableTaskTerminal>()
        val definition = agentTurnStep(agent, projected)

        // ── the execution that was interrupted ──────────────────────────────────────
        val registry = StepRegistryBuilder().add(definition).build()
        val preparation = RegistryExecutionPreparation.prepare(
            registry = registry,
            key = KEY,
            encodedInput = EncodedStepValue(PAYLOAD),
            availableCapabilities = bindings.access(context).available(),
        )
        val ready = assertInstanceOf(
            ExecutionPreparation.Ready::class.java,
            preparation,
            "a step that declares the durable-facts capability must be admitted, not refused",
        )
        val interrupted = RegistryExecutionBoundary(bindings).execute(ready.prepared, context)

        assertInstanceOf(
            StepOutcome.Failure::class.java,
            interrupted.outcome,
            "the turn was interrupted, so the execution cannot have succeeded",
        )
        assertEquals(
            1,
            agent.requests,
            "the agent is asked once, by the execution that was interrupted",
        )

        // The facts the step recorded are its own operation's, and they hold the terminal
        // the turn produced.
        val facts: DurableOperationFacts = bindings.access(context)
            .get(DURABLE_OPERATION_CAPABILITY, DurableOperationFacts::class)
        assertEquals(
            OperationStatus.SUCCEEDED,
            facts.recordedStatus(),
            "the facts of the operation must say that the turn was recorded",
        )
        val recorded = assertInstanceOf(
            DurableTaskTerminal.Exited::class.java,
            facts.recordedTerminal(),
            "the facts of the operation must hold the terminal the turn produced",
        )

        // ── the engine's row of the interrupted invocation ───────────────────────────
        // The engine writes a RUNNING row before the effect, and the interruption left it
        // there: the invocation is still in flight, which is what makes it recoverable.
        val records = DurableInvocationRecord(journal)
        val operationId = context.opId.format()
        val input = OperationInput(
            stepId = KEY.value,
            params = emptyMap(),
            runId = RUN_ID,
            attempt = 1,
        )
        val fingerprint = Fingerprint.compute(input, KEY.value, ReplayPolicy.RERUN, 1)
        records.admit(operationId, fingerprint, input, alreadyJournaled = null)

        // ── the decision that lets a plugin serve the recorded completion ───────────
        // The rule that says «a recorded completion may be served again instead of
        // asking the agent a second time» is the PUBLISHED one, and a plugin reads it
        // to know what it may do. The law reads the same rule, so a defect in the
        // published table falls here and not only in the table's own module.
        val decision = dev.rubentxu.pipeline.v2.domain.durable.DefaultEffectReplayPolicy().decide(
            replayPolicy = ReplayPolicy.RERUN,
            effects = setOf(Effect.EXECUTES_SUBPROCESS),
            hasJournalEntry = true,
            journaledOutcome = OperationStatus.SUCCEEDED,
        )
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.durable.ReplayDecision.SKIP,
            decision,
            "the published rule must let a recorded completion be served again",
        )

        // ── the recovery ─────────────────────────────────────────────────────────────
        val resolution = resolver(journal).reconcileInvocation(
            metadata = StepMetadata(
                effects = setOf(Effect.EXECUTES_SUBPROCESS),
                replayPolicy = ReplayPolicy.RERUN,
                recoveryPolicy = RecoveryPolicy.None,
            ),
            fingerprint = fingerprint,
            input = input,
            operationId = operationId,
        ).resolution

        assertInstanceOf(
            InvocationReconciliation.RecoverRunning::class.java,
            resolution,
            "an interrupted operation whose facts hold a terminal must be recovered, not executed again",
        )

        // The recovered terminal reaches the Step's OWN projection, and the value it
        // yields is the one the turn produced.
        val materialised = RecoveredExecutionMaterializer.materialize(
            definition = definition,
            encodedInput = input,
            terminal = recorded,
            stepPayload = PAYLOAD,
        )
        assertInstanceOf(
            dev.rubentxu.pipeline.v2.runtime.durable.RecoveryMaterialisation.Materialised::class.java,
            materialised,
            "the recovered terminal must yield a value under the Step's own contract",
        )
        assertEquals(
            listOf<DurableTaskTerminal>(recorded),
            projected,
            "the recovered terminal must reach the Step's own projection exactly once",
        )
        assertEquals(
            1,
            agent.requests,
            "the agent must not be asked again: the turn was already had",
        )
        assertTrue(
            agent.requests == 1,
            "the effect is repeated exactly when the agent receives a second request",
        )
    }

    @Test
    @DisplayName("the facts of an operation are the facts of that operation and of no other")
    fun `the facts of an operation are the facts of that operation and of no other`() {
        val journal = InMemoryOperationJournal(FixedClock)
        val bindings = CapabilityBindings(journal = journal)
        val context = runtimeContext()

        val facts: DurableOperationFacts = bindings.access(context)
            .get(DURABLE_OPERATION_CAPABILITY, DurableOperationFacts::class)

        assertEquals(
            RUN_ID,
            facts.identity.runId,
            "the facts must name the run of the operation they belong to",
        )
        assertEquals(
            context.opId.format(),
            facts.identity.opId,
            "the facts must name the operation they belong to, as the runtime formatted it",
        )
        assertNull(
            facts.recordedStatus(),
            "an operation that recorded nothing has no status to report",
        )
        assertNull(
            facts.recordedTerminal(),
            "an operation that recorded nothing has no terminal to report",
        )
    }

    /**
     * The step under trial: it asks the agent, records what the turn produced, and is then
     * interrupted before it can return.
     */
    private fun agentTurnStep(
        agent: CountingAgent,
        projected: MutableList<DurableTaskTerminal>,
    ): StepDefinition<String, String> = object : StepDefinition<String, String>,
        RecoveredStepProjection<String, String> {

        override val contract: StepContract<String, String> = StepContract(
            key = KEY,
            descriptor = StepDescriptor(stepId = KEY.value, name = "turn", configRef = "turn"),
            inputCodec = StringCodec,
            outputCodec = StringCodec,
            requiredCapabilities = setOf(DURABLE_OPERATION_CAPABILITY),
        )

        override val handler: StepHandler<String, String> = StepHandler { input, context ->
            val facts: DurableOperationFacts = context.capabilities
                .get(DURABLE_OPERATION_CAPABILITY, DurableOperationFacts::class)
            // The effect is in flight before it happens: a turn that is interrupted must
            // leave that fact behind, or the next run cannot tell it from a turn that
            // never started.
            facts.begin()
            val reply = agent.ask(input)
            facts.record(
                DurableTaskTerminal.Exited(
                    exitCode = 0,
                    output = DurableTaskOutput(controlDir = control.toString(), capturedStdout = reply),
                ),
            )
            // The turn is cut here: the facts are durable, and the step never returns.
            throw InterruptedTurnException()
        }

        override fun project(
            input: String,
            terminal: DurableTaskTerminal,
        ): RecoveredProjection<String> {
            projected += terminal
            return when (terminal) {
                is DurableTaskTerminal.Exited -> RecoveredProjection.Materialised(
                    value = terminal.output.capturedStdout ?: "",
                    outcome = StepOutcome.Success,
                )
                else -> RecoveredProjection.InsufficientEvidence(
                    "the recovered terminal carries no reply of the turn",
                )
            }
        }
    }

    /** The resolver of the engine, with the facts reader that reads the run's own journal. */
    private fun resolver(journal: InMemoryOperationJournal): DurableInvocationResolver =
        DurableInvocationResolver(
            divergenceDetector = StrictFingerprintDivergenceDetector(),
            effectReplayPolicy = dev.rubentxu.pipeline.v2.domain.durable.DefaultEffectReplayPolicy(),
            records = DurableInvocationRecord(journal),
            runningSubprocessRecovery = RunningSubprocessRecovery {
                RunningSubprocessObservation.Unavailable(UnobservableCause.NoControlRootConfigured)
            },
            durableFacts = DurableOperationFactsReader { operationId ->
                DurableOperationFactsStore.recordedTerminal(journal, operationId)
            },
        )

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

    /** The third party the turn asks: it counts the requests it receives. */
    private class CountingAgent {
        var requests: Int = 0
            private set

        fun ask(input: String): String {
            requests += 1
            return "the reply to $input"
        }
    }

    /** The interruption of the turn, thrown by the step under trial. */
    private class InterruptedTurnException : RuntimeException("the turn was interrupted")

    private companion object {
        val KEY: PluginStepId = PluginStepId("test.turn")
        const val RUN_ID = "run-1"
        const val PAYLOAD = "the prompt"
    }

    /** A clock that does not move, so nothing in these rows depends on the wall clock. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.ofEpochMilli(2_000L)
    }
}
