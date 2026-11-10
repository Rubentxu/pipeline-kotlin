package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.AttemptId
import dev.rubentxu.pipeline.v2.runtime.inspect.IntrospectionFailure
import dev.rubentxu.pipeline.v2.runtime.inspect.IntrospectionRefusal
import dev.rubentxu.pipeline.v2.runtime.inspect.OperationInspectionResult
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionPort
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionResult
import dev.rubentxu.pipeline.v2.runtime.inspect.RunStructureResult
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.StructureRunOptions
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import dev.rubentxu.pipeline.v2.service.ServiceProtocol
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import dev.rubentxu.pipeline.v2.service.ServiceRequest
import dev.rubentxu.pipeline.v2.service.ServiceResponse

/**
 * What the service answers when it is asked.
 *
 * ## The guarantee under trial
 *
 * The socket answers about a run with the SAME operation the command line uses
 * (`RunStateQuery`), so the state it reports is the state the CLI reports over
 * the same store. What the socket adds is a NAME for every answer and every
 * refusal: a client reads `terminal` or `unknown-run`, and it never has to
 * parse a sentence to find out what happened.
 *
 * ## The capacity this serves
 *
 * A supervisor that watches many runs has to tell three things apart without
 * reading prose: a run that finished, a run that is going, and a run that
 * cannot be read at all. A refusal that arrived as a state would leave it
 * waiting for a run that is never coming, and a state that arrived as a
 * refusal would make it give up on a run that is fine.
 *
 * ## Why this instrument
 *
 * The operations are a pure function of the port's answer: the port is faked,
 * and nothing else exists. The cheapest instrument that can fail is a port
 * that answers one canned observation and a reading of what the operation
 * made of it.
 *
 * ## What this does not freeze
 *
 * The socket, the line format, and the transport.
 */
class ServiceOperationsTest {

    @Test
    fun `the version answers the version of the product and the schema of the protocol`() {
        val response = operations(observation = terminalRun()).answer(
            ServiceRequest.Version(ServiceProtocol.SCHEMA),
        )

        val result = resultOf(response)
        assertEquals(
            ServiceProtocol.SCHEMA,
            result["schema"]?.jsonPrimitive?.content,
            "the answer says which schema it speaks",
        )
        assertTrue(
            result["productVersion"]?.jsonPrimitive?.content?.isNotBlank() ?: false,
            "the answer names the version of the product",
        )
    }

    @Test
    fun `the health answers the substrate the service was composed with`() {
        val response = operations(observation = terminalRun()).answer(
            ServiceRequest.Health(ServiceProtocol.SCHEMA),
        )

        val result = resultOf(response)
        assertEquals(
            "/tmp/state/pipelinek.db",
            result["eventStore"]?.jsonPrimitive?.content,
            "the health names the store it serves",
        )
        assertEquals(
            "/tmp/state/ctl",
            result["controlRoot"]?.jsonPrimitive?.content,
            "the health names the control root it serves",
        )
    }

    @Test
    fun `a run that finished answers that it finished`() {
        val response = operations(observation = terminalRun()).answer(
            ServiceRequest.RunState(ServiceProtocol.SCHEMA, "run-1"),
        )

        val result = resultOf(response)
        assertEquals("terminal", result["state"]?.jsonPrimitive?.content)
        assertEquals("succeeded", result["outcome"]?.jsonPrimitive?.content)
        assertEquals("0", result["at"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a run that is going answers that it is going`() {
        val response = operations(
            observation = RuntimeObservation.Running(
                attempt = AttemptId(1),
                leaseHolder = null,
                fencingToken = 7L,
                journalPosition = dev.rubentxu.pipeline.v2.runtime.inspect.JournalPosition(
                    operations = 0,
                    latestOpId = null,
                    latestTerminalAtMs = null,
                ),
                outputTails = emptyList(),
            ),
        ).answer(ServiceRequest.RunState(ServiceProtocol.SCHEMA, "run-1"))

        val result = resultOf(response)
        assertEquals("running", result["state"]?.jsonPrimitive?.content)
        assertEquals("7", result["fencingToken"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a run whose substrate holds nothing answers empty and not a refusal`() {
        val response = operations(
            observation = RuntimeObservation.LiveButEmpty(attempt = AttemptId(1), reason = "no journal rows"),
        ).answer(ServiceRequest.RunState(ServiceProtocol.SCHEMA, "run-1"))

        val result = resultOf(response)
        assertEquals(
            "empty",
            result["state"]?.jsonPrimitive?.content,
            "an empty substrate is a fact about the substrate, and it is not a refusal",
        )
        assertEquals("no journal rows", result["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a run that cannot be observed answers that it could not be observed`() {
        val response = operations(
            observation = RuntimeObservation.Unobservable(IntrospectionFailure.StorageError("the disk is gone")),
        ).answer(ServiceRequest.RunState(ServiceProtocol.SCHEMA, "run-1"))

        val result = resultOf(response)
        assertEquals("unobservable", result["state"]?.jsonPrimitive?.content)
        assertEquals("storage-error", result["failure"]?.jsonPrimitive?.content)
        assertEquals("the disk is gone", result["cause"]?.jsonPrimitive?.content)
    }

    @Test
    fun `every way a run can end has its own word`() {
        val rows: List<Pair<TerminalObservation, String>> = listOf(
            TerminalObservation.Succeeded(1L) to "succeeded",
            TerminalObservation.Failed("compile", 2L) to "failed",
            TerminalObservation.Unstable(3L) to "unstable",
            TerminalObservation.Aborted(4L) to "aborted",
            TerminalObservation.Cancelled(5L) to "cancelled",
            TerminalObservation.Other("whatever", 6L) to "other",
        )

        rows.forEach { (terminal, word) ->
            val response = operations(
                observation = RuntimeObservation.Terminal(
                    attempt = AttemptId(1),
                    terminal = terminal,
                    terminalAtMs = 10L,
                ),
            ).answer(ServiceRequest.RunState(ServiceProtocol.SCHEMA, "run-1"))

            assertEquals(
                word,
                resultOf(response)["outcome"]?.jsonPrimitive?.content,
                "the terminal $terminal answers with its own word",
            )
        }
    }

    @Test
    fun `a failure carries the word the run recorded`() {
        val response = operations(
            observation = RuntimeObservation.Terminal(
                attempt = AttemptId(1),
                terminal = TerminalObservation.Failed("compile", 2L),
                terminalAtMs = 10L,
            ),
        ).answer(ServiceRequest.RunState(ServiceProtocol.SCHEMA, "run-1"))

        assertEquals(
            "compile",
            resultOf(response)["failureKind"]?.jsonPrimitive?.content,
            "the failure keeps the word the run recorded",
        )
    }

    @Test
    fun `a run nobody knows is refused and never answered as a state`() {
        val response = operations(
            observation = RuntimeObservation.LiveButEmpty(AttemptId(1), "no journal rows"),
            refusal = IntrospectionRefusal.UnknownRun("run-1"),
        ).answer(ServiceRequest.RunState(ServiceProtocol.SCHEMA, "run-1"))

        val refused = assertInstanceOf(ServiceResponse.Refused::class.java, response)
        assertEquals("unknown-run", refused.refusal.kind)
    }

    @Test
    fun `every refusal of the run state becomes a refusal of the wire, one by one`() {
        val rows: List<Pair<RunStateRefusal, ServiceRefusal>> = listOf(
            RunStateRefusal.UnknownRun("run-1") to ServiceRefusal.UnknownRun("run-1"),
            RunStateRefusal.NotConfigured(RunStateRefusal.Missing.CONTROL_ROOT) to
                ServiceRefusal.NotConfiguredControlRoot,
            RunStateRefusal.NotConfigured(RunStateRefusal.Missing.EVENT_PLANE) to
                ServiceRefusal.NotConfiguredEventPlane,
            RunStateRefusal.NotConfigured(RunStateRefusal.Missing.OUTPUT_PLANE) to
                ServiceRefusal.NotConfiguredOutputPlane,
            RunStateRefusal.StorageFailed("the disk is gone") to
                ServiceRefusal.StorageFailed("the disk is gone"),
            RunStateRefusal.HeldByAnother("owner-1", 7L) to
                ServiceRefusal.HeldByAnother("owner-1", 7L),
            RunStateRefusal.Inconsistent("the journal and the lease disagree") to
                ServiceRefusal.Inconsistent("the journal and the lease disagree"),
            RunStateRefusal.UnknownOperation("op-1", 2) to
                ServiceRefusal.UnknownRunOperation("op-1", 2),
        )

        rows.forEach { (runRefusal, expected) ->
            val refusal = ServiceOperations.refusalOf(runRefusal)

            assertEquals(
                expected,
                refusal,
                "the refusal $runRefusal lost its meaning on the way to the wire",
            )
        }
    }

    /**
     * The result of an answer, read as a document.
     */
    private fun resultOf(response: ServiceResponse): Map<String, kotlinx.serialization.json.JsonElement> {
        val answered = assertInstanceOf(ServiceResponse.Answered::class.java, response)
        return Json.parseToJsonElement(
            kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                answered.result,
            ),
        ).jsonObject
    }

    /**
     * The operations over a port that answers [observation] (or [refusal]).
     */
    private fun operations(
        observation: RuntimeObservation,
        refusal: IntrospectionRefusal? = null,
    ): ServiceOperations = ServiceOperations(
        introspection = AnsweringPort(
            if (refusal != null) {
                RuntimeIntrospectionResult.Refused(refusal)
            } else {
                RuntimeIntrospectionResult.Observation(observation)
            },
        ),
        substrate = {
            ServiceSubstrate(
                eventStore = "/tmp/state/pipelinek.db",
                controlRoot = "/tmp/state/ctl",
                eventStorePresent = true,
                controlRootPresent = true,
            )
        },
        starts = ServiceTestStarter(),
    )

    /**
     * A port that answers the same result every time, and refuses the two
     * shapes a run-state question never asks for.
     */
    private class AnsweringPort(
        private val answer: RuntimeIntrospectionResult,
    ) : RuntimeIntrospectionPort {

        override fun inspect(runId: String): RuntimeIntrospectionResult = answer

        override fun runStructure(
            runId: String,
            options: StructureRunOptions,
        ): RunStructureResult = throw UnsupportedOperationException(
            "a run-state question does not read the structure of a run",
        )

        override fun inspectOperation(
            operationId: String,
            attempt: Int,
        ): OperationInspectionResult = throw UnsupportedOperationException(
            "a run-state question does not read one operation",
        )
    }

    /**
     * A run that finished, with the instant it finished at.
     */
    private fun terminalRun(): RuntimeObservation = RuntimeObservation.Terminal(
        attempt = AttemptId(1),
        terminal = TerminalObservation.Succeeded(0L),
        terminalAtMs = 0L,
    )
}
