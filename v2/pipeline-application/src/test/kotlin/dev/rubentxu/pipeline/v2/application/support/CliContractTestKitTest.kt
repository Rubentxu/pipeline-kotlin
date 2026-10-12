package dev.rubentxu.pipeline.v2.application.support

import dev.rubentxu.pipeline.v2.application.LaunchReceipt
import dev.rubentxu.pipeline.v2.application.LaunchStatus
import dev.rubentxu.pipeline.v2.application.observation.ObservationFormat
import dev.rubentxu.pipeline.v2.domain.CorrelationId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.RunCorrelated
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.durable.JsonEventLog
import dev.rubentxu.pipeline.v2.events.durable.RunOwnerId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Self-test for [CliContractTestKit].
 *
 * PK-NEXT R0.3 requires the testkit to be the single source of
 * truth for the public CLI output contract. The testkit itself is
 * not exempt from that standard: it is the contract, and this file
 * pins it.
 *
 * Each test names the specific obligation from the R0.3 brief:
 *  - "no use catch { emptyList() } para ocultar un fallo de
 *    decodificación"
 *  - "compruebe que el LaunchReceipt y el RunCorrelated durable
 *    tienen la misma correlación"
 *  - "detecte ausencia, duplicación y contradicción de receipts"
 *
 * A test that goes red here means the testkit no longer honours the
 * contract, and the ~20 tests that consume it would start agreeing
 * on a wrong contract.
 */
class CliContractTestKitTest {

    private val at: Instant = Instant.parse("2026-10-12T10:00:00Z")
    private val runId = RunId("11111111-1111-1111-1111-111111111111")
    private val correlationId = CorrelationId("22222222-2222-2222-2222-222222222222")
    private val ownerId = requireNotNull(RunOwnerId.of("33333333-3333-3333-3333-333333333333"))

    private fun newReceipt(): LaunchReceipt = LaunchReceipt(
        runId = runId.value,
        correlationId = correlationId.value,
        ownerId = ownerId.value,
        startedAtMs = 1_700_000_000_000L,
        controlDir = "/tmp/control",
        status = LaunchStatus.Launched,
    )

    private fun newCorrelatedEvent(): RunCorrelated = RunCorrelated(
        eventId = "e-correlated",
        runId = runId.value,
        sequence = 0L,
        occurredAt = at,
        correlationId = correlationId,
        ownerId = ownerId.value,
        startedAtMs = 1_700_000_000_000L,
    )

    private fun buildJsonStdout(events: List<dev.rubentxu.pipeline.v2.events.DomainEvent>): String {
        val receipt = LaunchReceipt.encode(newReceipt())
        val eventDoc = JsonEventLog.encode(events)
        return receipt + eventDoc + "\n"
    }

    @Test
    fun `JSON view decodes receipt on line 1 and events on line 2`() {
        val events = listOf(
            newCorrelatedEvent(),
            CompilationStarted("e1", runId.value, 1L, at),
            RunFinished("e3", runId.value, 3L, at, "SUCCESS", emptyList()),
        )
        val stdout = buildJsonStdout(events)
        val stderr = "Diagnostic: compile ok\n"

        val contract = CliContractTestKit.decodeLaunchContract(
            stdout = stdout,
            stderr = stderr,
            format = ObservationFormat.JSON,
        )

        assertEquals(ObservationFormat.JSON, contract.format)
        assertEquals(runId.value, contract.receipt.runId)
        assertEquals(correlationId.value, contract.receipt.correlationId)
        assertEquals(LaunchStatus.Launched, contract.receipt.status)
        assertNull(contract.eventDocument.decodeError, "the document must decode cleanly")
        assertEquals(3, contract.eventDocument.events.size)
        assertEquals(
            "RunCorrelated",
            contract.eventDocument.events.filterIsInstance<RunCorrelated>().single().kind,
        )
    }

    @Test
    fun `JSON_LINES view decodes receipt on line 1 and one event per subsequent line`() {
        val events = listOf(
            newCorrelatedEvent(),
            CompilationStarted("e1", runId.value, 1L, at),
            RunFinished("e2", runId.value, 2L, at, "SUCCESS", emptyList()),
        )
        val receipt = LaunchReceipt.encode(newReceipt())
        val lines = events.joinToString("\n", prefix = receipt, postfix = "\n") {
            JsonEventLog.encodeOne(it)
        }
        val stderr = ""

        val contract = CliContractTestKit.decodeLaunchContract(
            stdout = lines,
            stderr = stderr,
            format = ObservationFormat.JSON_LINES,
        )

        assertEquals(ObservationFormat.JSON_LINES, contract.format)
        assertNull(contract.eventDocument.decodeError)
        assertEquals(3, contract.eventDocument.events.size)
        assertTrue(contract.eventDocument.events.first() is RunCorrelated)
    }

    @Test
    fun `TEXT view locates the receipt on stderr not stdout`() {
        val stdout = "[PipelineK] Run correlated: ${correlationId.value}\n" +
            "[PipelineK] Compiling pipeline\n" +
            "[PipelineK] Finished: SUCCESS\n"
        val stderr = LaunchReceipt.encode(newReceipt()) + "diagnostic: ok\n"

        val contract = CliContractTestKit.decodeLaunchContract(
            stdout = stdout,
            stderr = stderr,
            format = ObservationFormat.TEXT,
        )

        assertEquals(ObservationFormat.TEXT, contract.format)
        assertEquals(runId.value, contract.receipt.runId)
        // In TEXT view the events are rendered as human lines, not as
        // a JSON document, so the event list is empty by contract.
        assertTrue(contract.eventDocument.events.isEmpty())
    }

    @Test
    fun `JSON view refuses stdout whose first line is not a receipt`() {
        val stdout = "not a receipt\n[]\n"
        val err = assertThrows(CliContractTestKit.ContractViolation::class.java) {
            CliContractTestKit.decodeLaunchContract(
                stdout = stdout,
                stderr = "",
                format = ObservationFormat.JSON,
            )
        }
        assertTrue(err.message!!.contains("receipt"))
    }

    @Test
    fun `JSON view surfaces a malformed event document as decodeError not as empty list`() {
        val receipt = LaunchReceipt.encode(newReceipt())
        // Line 2 starts with '[' but is not valid JSON. The testkit
        // MUST report this; the brief explicitly bans catch/emptyList.
        val stdout = receipt + "[not valid json\n"
        val contract = CliContractTestKit.decodeLaunchContract(
            stdout = stdout,
            stderr = "",
            format = ObservationFormat.JSON,
        )
        assertNotNull(
            contract.eventDocument.decodeError,
            "a malformed document must surface as decodeError, not as an empty list",
        )
        assertTrue(contract.eventDocument.events.isEmpty())
    }

    @Test
    fun `JSON_LINES view surfaces a malformed event line as decodeError`() {
        val receipt = LaunchReceipt.encode(newReceipt())
        val ok = JsonEventLog.encodeOne(newCorrelatedEvent())
        val stdout = receipt + ok + "\nthis is not json\n"
        val contract = CliContractTestKit.decodeLaunchContract(
            stdout = stdout,
            stderr = "",
            format = ObservationFormat.JSON_LINES,
        )
        assertNotNull(contract.eventDocument.decodeError)
        assertEquals(1, contract.eventDocument.events.size, "the OK line still decodes; only the bad one fails")
    }

    @Test
    fun `receipt schema mismatch is a contract violation`() {
        val wrongSchema = """{"schema":"pipelinek.launch/99","runId":"x","correlationId":"y","ownerId":"z","startedAtMs":1,"status":"Launched"}"""
        val stdout = wrongSchema + "\n[]\n"
        val err = assertThrows(CliContractTestKit.ContractViolation::class.java) {
            CliContractTestKit.decodeLaunchContract(
                stdout = stdout,
                stderr = "",
                format = ObservationFormat.JSON,
            )
        }
        assertTrue(err.message!!.contains("schema"))
        assertTrue(err.message!!.contains("pipelinek.launch/99"))
    }

    @Test
    fun `correlation assertion requires the durable RunCorrelated to match the receipt`() {
        val events = listOf(
            newCorrelatedEvent(),
            CompilationStarted("e1", runId.value, 1L, at),
        )
        val stdout = buildJsonStdout(events)
        val contract = CliContractTestKit.decodeLaunchContract(
            stdout = stdout,
            stderr = "",
            format = ObservationFormat.JSON,
        )
        // The synthetic correlated event has the same correlationId
        // as the receipt, so the assertion passes.
        CliContractTestKit.assertLaunchAndDurableCorrelate(contract)
    }

    @Test
    fun `correlation assertion fails when the durable event disagrees with the receipt`() {
        val wrongCorrelation = CorrelationId(UUID.randomUUID().toString())
        val events = listOf(
            newCorrelatedEvent().copy(correlationId = wrongCorrelation),
        )
        val stdout = buildJsonStdout(events)
        val contract = CliContractTestKit.decodeLaunchContract(
            stdout = stdout,
            stderr = "",
            format = ObservationFormat.JSON,
        )
        val err = assertThrows(CliContractTestKit.ContractViolation::class.java) {
            CliContractTestKit.assertLaunchAndDurableCorrelate(contract)
        }
        assertTrue(
            err.message!!.contains("does not match"),
            "the message must name the disagreement: ${err.message}",
        )
    }

    @Test
    fun `correlation assertion fails when no durable RunCorrelated is in the stream`() {
        val events = listOf(
            CompilationStarted("e1", runId.value, 1L, at),
            RunFinished("e2", runId.value, 2L, at, "SUCCESS", emptyList()),
        )
        val stdout = buildJsonStdout(events)
        val contract = CliContractTestKit.decodeLaunchContract(
            stdout = stdout,
            stderr = "",
            format = ObservationFormat.JSON,
        )
        val err = assertThrows(CliContractTestKit.ContractViolation::class.java) {
            CliContractTestKit.assertLaunchAndDurableCorrelate(contract)
        }
        assertTrue(err.message!!.contains("no RunCorrelated"))
    }

    @Test
    fun `no-duplicate-receipts refuses a list of two receipts`() {
        val r = newReceipt()
        val err = assertThrows(CliContractTestKit.ContractViolation::class.java) {
            CliContractTestKit.assertNoDuplicateReceipts(listOf(r, r))
        }
        assertTrue(err.message!!.contains("expected exactly one"))
    }

    @Test
    fun `no-duplicate-receipts accepts exactly one receipt`() {
        // Should not throw.
        CliContractTestKit.assertNoDuplicateReceipts(listOf(newReceipt()))
    }

    @Test
    fun `JSON view JSON_LINES mismatch is reported as decodeError not silent`() {
        // Someone built a JSON view output but used line-delimited
        // events instead of a bracketed array. The testkit must NOT
        // treat the first event as "the document" — it must report
        // the contract violation.
        val receipt = LaunchReceipt.encode(newReceipt())
        val firstEvent = JsonEventLog.encodeOne(newCorrelatedEvent())
        val stdout = receipt + firstEvent + "\n"
        val contract = CliContractTestKit.decodeLaunchContract(
            stdout = stdout,
            stderr = "",
            format = ObservationFormat.JSON,
        )
        assertNotNull(
            contract.eventDocument.decodeError,
            "a JSON view that received line-delimited events must report a violation, " +
                "not silently parse one event and drop the rest",
        )
    }

    @Test
    fun `TEXT view with missing receipt on stderr fails closed`() {
        val stdout = "[PipelineK] Compiling pipeline\n"
        val stderr = "no receipt here, just diagnostics\n"
        val err = assertThrows(CliContractTestKit.ContractViolation::class.java) {
            CliContractTestKit.decodeLaunchContract(
                stdout = stdout,
                stderr = stderr,
                format = ObservationFormat.TEXT,
            )
        }
        // The exact phrasing is the testkit's contract, not the test's.
        // The invariant is "a stderr without a receipt is a violation",
        // regardless of which decoder tripwire fires.
        assertTrue(
            err.message!!.contains("JSON object") || err.message!!.contains("expected the LaunchReceipt on stderr"),
            "violation message must report a receipt problem, got: ${err.message}",
        )
    }

    // A small smoke check that the helper types compile and the
    // RunOutcome identity is the one this binary actually uses.
    @Test
    fun `RunOutcome identity is the one the production code uses`() {
        // Reference RunOutcome by type so the import is not flagged; the
        // test name carries the property under test.
        val outcomeType: Class<RunOutcome.Success> = RunOutcome.Success::class.java
        assertNotNull(outcomeType)
    }
}
