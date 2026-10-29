package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.StepMetadata
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.FailureOrigin
import dev.rubentxu.pipeline.v2.domain.durable.FailureRecord
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationOutput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.events.NullEventSink
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

/**
 * The durable row of one invocation has ONE owner, and this file observes what that ownership
 * means to the consumers of the row.
 *
 * ## The guarantee
 *
 * A durable invocation is ONE row under `(opId, attempt)`, and every fact about that row has
 * exactly one answer:
 *
 * ```text
 * read   → the row of THIS attempt
 * admit  → a RUNNING row, and only when there is no row yet
 * settle → the terminal row, in ONE shape, whatever the terminal was
 * ```
 *
 * Before the cut that row had three writers and two readers. The two readers each rebuilt the
 * same `PENDING` envelope for the divergence gate, and they asked the journal DIFFERENT
 * questions: the engine asked for the exact attempt and the scripted invoker asked for the
 * highest one. So the scripted surface compared the fingerprint of attempt 1 against a row that
 * could belong to another attempt — a comparison that could only ever end in the wrong answer,
 * and did so silently.
 *
 * ## The capacity this serves
 *
 * A resumed invocation must be able to answer one question — "what did THIS attempt already do?"
 * — and get one row back. The row it gets decides whether the invocation is replayed, reused or
 * refused, so the answer is not a detail: it decides the run.
 *
 * ## Why this instrument
 *
 * The instrument is the PRODUCTION journal ([InMemoryOperationJournal]), not a fake. That is a
 * deliberate choice and it is the opposite of the obvious one: a fake would let the row assert
 * whatever the test wanted, and the round-trip that matters — the row survives being written as
 * JSON and read back — is exactly the part a hand-written fake is most likely to get wrong.
 * The production journal is the SQLite implementation's storage-free twin, row for row, so a
 * green row here says something about the durable spine and not about a test double.
 *
 * Everything else in these rows is in memory too: the clock is fixed, the event sink discards,
 * the workspace and the control root point at paths that DO NOT EXIST (if any of these
 * compositions wrote there, the row would say so instead of passing unnoticed), and the
 * execution boundary returns a value instead of running anything.
 *
 * ## The four observations, and the mutation that kills each
 *
 * ```text
 * G1  the row an invocation reconciles against is the row of its OWN attempt
 * G2  the admission writes the RUNNING row only when there is no row yet
 * G3  the three terminal paths write ONE shape of row and keep their OWN status
 * G4  the row the owner reads back carries the value the execution produced
 * ```
 *
 * ## What this instrument cannot see
 *
 * It does not observe a real durable run: it does not execute a pipeline, does not touch a
 * database and does not survive the process. It observes the OWNERSHIP of the row — which
 * writer writes it, in which shape, under which attempt, and what the reader gets back — and
 * that is the part the cut changed.
 */
class DurableInvocationRecordTest {

    @Test
    @DisplayName("the row an invocation reconciles against is the row of its own attempt")
    fun theRowAnInvocationReconcilesAgainstIsTheRowOfItsOwnAttempt() {
        val input = input()
        val fingerprint = fingerprint(input)
        val journal = journal()
        // TWO attempts of the SAME operation, with the SAME fingerprint: the attempt is the
        // only thing that tells them apart. A reader that asks for the wrong attempt therefore
        // gets a row that is indistinguishable by fingerprint — which is precisely how the
        // defect stayed silent.
        journal.append(row(OP_ID, fingerprint, input, attempt = 1, status = OperationStatus.SUCCEEDED))
        journal.append(row(OP_ID, fingerprint, input, attempt = 2, status = OperationStatus.FAILED))

        val reconciled = resolver(DurableInvocationRecord(journal))
            .reconcileInvocation(metadata(), fingerprint, input, OP_ID)

        val journaled = rowOf(reconciled.journaled, "the row the reconciliation was made from")
        assertEquals(
            DurableInvocationRecord.ATTEMPT,
            journaled.attempt,
            "the reconciliation must be made against the row of the attempt it is about to write",
        )
        assertEquals(
            OperationStatus.SUCCEEDED,
            journaled.status,
            "the row of attempt 1 is SUCCEEDED; the FAILED row belongs to attempt 2",
        )
    }

    @Test
    @DisplayName("the admission writes the RUNNING row only when there is no row yet")
    fun theAdmissionWritesTheRunningRowOnlyWhenThereIsNoRowYet() {
        val input = input()
        val fingerprint = fingerprint(input)
        val journal = journal()
        val records = DurableInvocationRecord(journal)

        records.admit(OP_ID, fingerprint, input, alreadyJournaled = null)

        val admitted = rowOf(records.read(OP_ID), "the admitted row")
        assertEquals(OperationStatus.RUNNING, admitted.status, "the admission writes a RUNNING row")

        // The second admission is the RESUMED invocation: the row is already there, so it must
        // not be written again. The port refuses a duplicate `(opId, attempt)` — that refusal is
        // the failure this row exists to prevent, and it is what a resumed run would hit.
        records.admit(OP_ID, fingerprint, input, records.read(OP_ID))

        assertEquals(1, journal.size(), "a resumed admission must not write a second row")
        assertEquals(
            OperationStatus.RUNNING,
            rowOf(records.read(OP_ID), "the admitted row").status,
            "the resumed admission left the row exactly as it found it",
        )
    }

    @Test
    @DisplayName("the three terminal paths write one shape of row and keep their own status")
    fun theThreeTerminalPathsWriteOneShapeOfRowAndKeepTheirOwnStatus() {
        val input = input()
        val fingerprint = fingerprint(input)

        // (a) the SCHEMA rejection: the resolver refuses before anything executes.
        val schemaJournal = journal()
        val schemaRecords = DurableInvocationRecord(schemaJournal)
        resolver(schemaRecords).rejectSchema(OP_ID, input, "schema mismatch")
        val schemaRow = rowOf(schemaRecords.read(OP_ID), "the schema rejection row")

        // (b) the EXECUTION: the executor admits, runs, and settles.
        val executionJournal = journal()
        val executionRecords = DurableInvocationRecord(executionJournal)
        runBlocking {
            executor(executionRecords).executeAndJournal(
                operationId = OP_ID,
                fingerprint = fingerprint,
                input = input,
                journaled = null,
                prepared = prepared(),
                runtime = runtime(),
                lifecycleContext = lifecycle(),
            )
        }
        val executionRow = rowOf(executionRecords.read(OP_ID), "the execution row")

        // (c) the RECOVERY: the interpreter settles a terminal whose outcome could not be
        // determined. It has no Step definition, so it cannot produce a value — but the terminal
        // it observed still owns the status.
        val recoveryJournal = journal()
        val recoveryRecords = DurableInvocationRecord(recoveryJournal)
        runBlocking {
            RecoveryInterpretationEngine(NullEventSink, recoveryRecords).interpret(
                resolution = InvocationReconciliation.RecoverRunning(lostTerminal()),
                request = RecoveryInterpretationEngine.Request(
                    operationId = OP_ID,
                    fingerprint = fingerprint,
                    input = input,
                    lifecycleContext = lifecycle(),
                    definition = null,
                ),
            )
        }
        val recoveryRow = rowOf(recoveryRecords.read(OP_ID), "the recovery row")

        // ONE shape: the same identity, the same attempt, the same input, the same fingerprint.
        for (row in listOf(schemaRow, executionRow, recoveryRow)) {
            assertEquals(OP_ID, row.id, "every terminal path writes the row of THIS operation")
            assertEquals(
                DurableInvocationRecord.ATTEMPT,
                row.attempt,
                "every terminal path writes the attempt of the protocol",
            )
            assertEquals(input, row.input, "every terminal path writes the same durable input")
            assertEquals(fingerprint, row.fingerprint, "every terminal path writes the same fingerprint")
        }

        // AND their own status: the three terminals are three DIFFERENT facts, and a row that
        // collapsed them into one would destroy the vocabulary a later run reconciles against.
        assertEquals(OperationStatus.FAILED, schemaRow.status, "a schema rejection is FAILED")
        assertEquals(OperationStatus.SUCCEEDED, executionRow.status, "a completed execution is SUCCEEDED")
        assertEquals(
            OperationStatus.LOST,
            recoveryRow.status,
            "a terminal whose outcome could not be determined is LOST, and LOST is not FAILED",
        )
    }

    @Test
    @DisplayName("the row the owner reads back carries the value the execution produced")
    fun theRowTheOwnerReadsBackCarriesTheValueTheExecutionProduced() {
        val input = input()
        val fingerprint = fingerprint(input)
        val journal = journal()
        val records = DurableInvocationRecord(journal)

        runBlocking {
            executor(records).executeAndJournal(
                operationId = OP_ID,
                fingerprint = fingerprint,
                input = input,
                journaled = null,
                prepared = prepared(),
                runtime = runtime(),
                lifecycleContext = lifecycle(),
            )
        }

        val row = rowOf(records.read(OP_ID), "the executed row")
        val output = assertInstanceOf(
            OperationOutput::class.java,
            row.output,
            "the row the owner reads back keeps the value it executed",
        )
        assertEquals(JsonPrimitive("42"), output.result, "the value the execution produced is the value the row carries")
        assertTrue(output.durationMs >= 0, "the measured duration is not negative")
        assertTrue(output.finishedAt > 0, "the row carries the instant the execution finished")
    }

    // --- the fixtures ------------------------------------------------------

    /** The journal of production: the storage-free twin of the SQLite one, row for row. */
    private fun journal(): InMemoryOperationJournal = InMemoryOperationJournal(FixedClock)

    /** The resolver, composed over the record it reads through. */
    private fun resolver(records: DurableInvocationRecord): DurableInvocationResolver =
        DurableInvocationResolver(
            divergenceDetector = StrictFingerprintDivergenceDetector(),
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            records = records,
            runningSubprocessRecovery = RunningSubprocessRecovery {
                RunningSubprocessObservation.Unavailable(UnobservableCause.NoControlRootConfigured)
            },
        )

    /**
     * The executor, composed over the record it writes through.
     *
     * The boundary returns a value instead of running anything: these rows observe the DURABLE
     * row, and an execution that really ran would add a process to a guarantee that does not
     * need one.
     */
    private fun executor(records: DurableInvocationRecord): DurableStepExecutor =
        DurableStepExecutor(
            eventSink = NullEventSink,
            executionBoundary = CommonExecutionBoundary { _, _ ->
                CommonExecutionResult(StepOutcome.Success, EncodedStepValue("42"))
            },
            records = records,
        )

    /** One row, in the shape the journal holds it. */
    private fun row(
        id: String,
        fingerprint: Fingerprint,
        input: OperationInput,
        attempt: Int,
        status: OperationStatus,
        output: OperationOutput? = null,
    ): RerunOperation = RerunOperation(
        id = id,
        fingerprint = fingerprint,
        input = input,
        output = output,
        status = status,
        attempt = attempt,
    )

    /** The durable input of the invocation under observation. */
    private fun input(): OperationInput = OperationInput(
        stepId = STEP_ID,
        params = mapOf("payload" to JsonPrimitive("value")),
        runId = RUN_ID,
        attempt = DurableInvocationRecord.ATTEMPT,
    )

    /** The fingerprint of the invocation under observation, as the protocol computes it. */
    private fun fingerprint(input: OperationInput): Fingerprint =
        Fingerprint.compute(input, input.stepId, ReplayPolicy.RERUN, DurableInvocationRecord.ATTEMPT)

    /** The declared properties of the Step under observation. */
    private fun metadata(): StepMetadata = StepMetadata(
        effects = emptySet(),
        replayPolicy = ReplayPolicy.RERUN,
    )

    /** A terminal whose outcome could not be determined. */
    private fun lostTerminal(): DurableTaskTerminal = DurableTaskTerminal.Lost(
        FailureRecord(
            code = "TEST_LOST",
            kind = FailureKind.INFRASTRUCTURE,
            message = "the operation's outcome could not be determined",
            origin = FailureOrigin.RECONCILIATION,
            retryable = false,
            operationId = OP_ID,
        ),
    )

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

    /** The runtime context of the invocation, pointing at paths that do not exist. */
    private fun runtime(): CanonicalRuntimeContext = CanonicalRuntimeContext(
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
    )

    /** The lifecycle coordinates of the invocation. */
    private fun lifecycle(): StepLifecycleContext = StepLifecycleContext(
        runId = RUN_ID,
        stageIndex = 0,
        stepIndex = 0,
        stepName = "probe",
        stepType = STEP_ID,
    )

    /** A journalled row, or a red row saying why there is none. */
    private fun rowOf(row: DurableOperation?, label: String): RerunOperation {
        assertNotNull(row, "$label must be journalled")
        return assertInstanceOf(RerunOperation::class.java, row, "$label must be a RERUN row")
    }

    /** A clock that does not move, so nothing in these rows depends on the wall clock. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    /** A codec that carries a string, and nothing else. */
    private object StringCodec : StepCodec<String> {
        override fun encode(value: String): EncodedStepValue = EncodedStepValue(value)

        override fun decode(encoded: EncodedStepValue): String = encoded.value
    }

    private companion object {
        const val RUN_ID = "run-1"
        const val STEP_ID = "core.probe"
        const val OP_ID = "run-1-s0-0"
    }
}
