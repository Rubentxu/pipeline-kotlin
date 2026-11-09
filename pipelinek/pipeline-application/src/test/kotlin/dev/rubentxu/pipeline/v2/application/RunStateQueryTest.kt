package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.AttemptId
import dev.rubentxu.pipeline.v2.runtime.inspect.IntrospectionRefusal
import dev.rubentxu.pipeline.v2.runtime.inspect.OperationInspectionResult
import dev.rubentxu.pipeline.v2.runtime.inspect.RunStructureResult
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionPort
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionResult
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.StructureRunOptions
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the run-state query answers, and what it refuses.
 *
 * ## The guarantee under trial
 *
 * The query answers a question about a RUN, and it answers it in a vocabulary the
 * caller can act on. A run nobody knows, a substrate that is not configured and a
 * store that failed are three different answers, and none of them is ever reported
 * as an observation: a refusal that arrives as a success is the one answer a caller
 * cannot recover from.
 *
 * ## The capacity this serves
 *
 * A consumer of the product's durable state — the command line today, a service
 * tomorrow — asks about a run and has to be able to tell, without reading prose,
 * whether it should wait for the run, fix its configuration, or report that the
 * store failed. A single "error" case would leave that decision to a string
 * comparison, and two callers would compare it differently.
 *
 * ## Why this instrument
 *
 * The decision is a pure function of the port's answer: no store, no filesystem, no
 * clock, no process. The cheapest instrument that can fail is a port that answers
 * one canned result and a reading of the query's verdict. The rows below walk the
 * port's whole refusal vocabulary, and the `when` that walks it is EXHAUSTIVE over
 * that vocabulary: a port that grows a refusal does not compile here, so the new
 * reason cannot slip into the query without a row that says what it means.
 *
 * ## What this does not freeze
 *
 * The substrate the port reads, and the lines a caller renders the answer in.
 */
class RunStateQueryTest {

    @Test
    fun `an observed run is answered with the observation the port made`() {
        val observation = RuntimeObservation.Terminal(
            attempt = AttemptId(1),
            terminal = TerminalObservation.Succeeded(1_792_575_000_000L),
            terminalAtMs = 1_792_575_000_000L,
        )

        val answer = RunStateQuery(AnsweringPort(RuntimeIntrospectionResult.Observation(observation)))
            .stateOf("run-1")

        assertEquals(
            RunStateAnswer.Observed(observation),
            answer,
            "the query hands the observation over unchanged",
        )
    }

    @Test
    fun `a run nobody knows is refused as an unknown run`() {
        val answer = RunStateQuery(
            AnsweringPort(RuntimeIntrospectionResult.Refused(IntrospectionRefusal.UnknownRun("run-1"))),
        ).stateOf("run-1")

        assertEquals(
            RunStateAnswer.Refused(RunStateRefusal.UnknownRun("run-1")),
            answer,
            "the run that nobody knows is named, and it is not another refusal",
        )
    }

    @Test
    fun `a substrate that is not configured is not an unknown run`() {
        val answer = RunStateQuery(
            AnsweringPort(RuntimeIntrospectionResult.Refused(IntrospectionRefusal.NoEventStore)),
        ).stateOf("run-1")

        val refusal = assertInstanceOf(RunStateAnswer.Refused::class.java, answer).refusal
        assertEquals(
            RunStateRefusal.NotConfigured(RunStateRefusal.Missing.EVENT_PLANE),
            refusal,
            "the piece that is missing is named, so the caller knows what to provide",
        )
        assertFalse(
            refusal is RunStateRefusal.UnknownRun,
            "a substrate that is not configured is not a run that does not exist: " +
                "the caller waits for a run that is never coming",
        )
    }

    @Test
    fun `a store that failed is not an unknown run`() {
        val answer = RunStateQuery(
            AnsweringPort(
                RuntimeIntrospectionResult.Refused(IntrospectionRefusal.StorageError("the disk is gone")),
            ),
        ).stateOf("run-1")

        val refusal = assertInstanceOf(RunStateAnswer.Refused::class.java, answer).refusal
        assertEquals(
            RunStateRefusal.StorageFailed("the disk is gone"),
            refusal,
            "the store's failure keeps its diagnostic",
        )
        assertFalse(
            refusal is RunStateRefusal.UnknownRun,
            "a store that failed is not a run that does not exist: the run may well be there",
        )
    }

    /**
     * The whole refusal vocabulary, one row per reason.
     *
     * The `when` below is exhaustive over the port's refusals, so a refusal the port
     * gains does not compile until this row says what the query makes of it.
     */
    @Test
    fun `every refusal the port can answer keeps its meaning and its facts`() {
        val rows: List<Pair<IntrospectionRefusal, RunStateRefusal>> = listOf(
            IntrospectionRefusal.UnknownRun("run-1") to RunStateRefusal.UnknownRun("run-1"),
            IntrospectionRefusal.NoControlRoot to
                RunStateRefusal.NotConfigured(RunStateRefusal.Missing.CONTROL_ROOT),
            IntrospectionRefusal.NoEventStore to
                RunStateRefusal.NotConfigured(RunStateRefusal.Missing.EVENT_PLANE),
            IntrospectionRefusal.NoOutputPlane to
                RunStateRefusal.NotConfigured(RunStateRefusal.Missing.OUTPUT_PLANE),
            IntrospectionRefusal.StorageError("the disk is gone") to
                RunStateRefusal.StorageFailed("the disk is gone"),
            IntrospectionRefusal.LeaseHeldByAnother("owner-1", 7L) to
                RunStateRefusal.HeldByAnother("owner-1", 7L),
            IntrospectionRefusal.InconsistentLeaseState("the journal and the lease disagree") to
                RunStateRefusal.Inconsistent("the journal and the lease disagree"),
            IntrospectionRefusal.UnknownOperation("op-1", 2) to
                RunStateRefusal.UnknownOperation("op-1", 2),
        )

        rows.forEach { (portRefusal, expected) ->
            val answer = RunStateQuery(
                AnsweringPort(RuntimeIntrospectionResult.Refused(portRefusal)),
            ).stateOf("run-1")

            assertTrue(
                answer is RunStateAnswer.Refused,
                "a refusal is never answered as an observation; $portRefusal was answered as $answer",
            )
            assertEquals(
                expected,
                (answer as RunStateAnswer.Refused).refusal,
                "the refusal $portRefusal lost its meaning in the translation",
            )
        }
    }

    /**
     * A port that answers the same result every time, and refuses the two shapes a
     * run-state query never asks for.
     */
    private class AnsweringPort(
        private val answer: RuntimeIntrospectionResult,
    ) : RuntimeIntrospectionPort {

        override fun inspect(runId: String): RuntimeIntrospectionResult = answer

        override fun runStructure(
            runId: String,
            options: StructureRunOptions,
        ): RunStructureResult = throw UnsupportedOperationException(
            "a run-state query does not read the structure of a run",
        )

        override fun inspectOperation(
            operationId: String,
            attempt: Int,
        ): OperationInspectionResult = throw UnsupportedOperationException(
            "a run-state query does not read one operation",
        )
    }
}
