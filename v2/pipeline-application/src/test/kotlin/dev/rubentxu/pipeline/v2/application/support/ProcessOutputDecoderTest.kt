package dev.rubentxu.pipeline.v2.application.support

import dev.rubentxu.pipeline.v2.application.LaunchReceipt
import dev.rubentxu.pipeline.v2.application.LaunchStatus
import dev.rubentxu.pipeline.v2.application.observation.ObservationFormat
import dev.rubentxu.pipeline.v2.domain.CorrelationId
import dev.rubentxu.pipeline.v2.events.RunCorrelated
import dev.rubentxu.pipeline.v2.events.durable.JsonEventLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * PK-RELEASE-CONSOLIDATION D2 + H1.3 — matrix tests for the
 * process-output decoder.
 *
 * The matrix is the one listed in ADR-PK1-OUTPUT-FRAMING under "Casos
 * que la matriz debe cubrir". Each row is its own test so a failure
 * points at one specific case and the test report names the property
 * the production code broke.
 *
 * The testkit must surface the case as a typed [ProcessOutputDecoder.Finding];
 * the assertions are on the FINDING, not on the absence of an exception.
 * A decoder that swallows the case into a `try { } catch { null }` is
 * the exact anti-pattern the brief forbids, and the test enforces the
 * alternative by inspecting the findings list directly.
 */
class ProcessOutputDecoderTest {

    // -- Helpers ------------------------------------------------------------

    private fun receiptJson(
        runId: String = "r-1",
        correlationId: String = "c-1",
        status: LaunchStatus = LaunchStatus.Launched,
    ): String {
        val r = LaunchReceipt(
            runId = runId,
            correlationId = correlationId,
            ownerId = "o-1",
            startedAtMs = 1_700_000_000_000,
            status = status,
        )
        return LaunchReceipt.encode(r).trim()
    }

    private fun eventsArray(vararg simpleEvents: String): String {
        // The testkit reads the array as-is; we only need a well-formed
        // JSON document of objects, with no semantic claim on shape,
        // because the cases we cover below assert on FINDINGS, not on
        // event contents.
        if (simpleEvents.isEmpty()) return "[]"
        return "[" + simpleEvents.joinToString(",") + "]"
    }

    /**
     * Build a real [RunCorrelated] from the typed API and encode it via
     * [JsonEventLog], so the wire shape is whatever the codec writes.
     * Tests that need a `RunCorrelated` in the array MUST go through
     * this helper: a hand-typed string is not parseable by the codec.
     */
    private fun runCorrelated(
        correlationId: String,
        ownerId: String = "o-1",
        runId: String = "r-1",
    ): String {
        val event = RunCorrelated(
            eventId = "evt-rc-$correlationId",
            runId = runId,
            sequence = 100_000L,
            occurredAt = Instant.parse("2024-01-01T00:00:00Z"),
            correlationId = CorrelationId(correlationId),
            ownerId = ownerId,
            startedAtMs = 1_704_067_200_000L,
        )
        return JsonEventLog.encode(listOf(event)).removePrefix("[").removeSuffix("]")
    }

    // -- Case 1: parser against the published protocol, happy path ---------

    @Test
    fun `JSON view — receipt line 1, array line 2, no findings`() {
        val stdout = receiptJson() + "\n" + eventsArray(runCorrelated("c-1"))
        val out = ProcessOutputDecoder.decode(stdout, "", ObservationFormat.JSON)
        assertEquals(ProcessOutputDecoder.Protocol.Published, out.protocol)
        assertNotNull(out.receipt)
        assertEquals("c-1", out.receipt!!.correlationId)
        assertEquals(1, out.receiptOccurrences)
        assertTrue(out.eventDocument.isWellFormed, "events should decode: ${out.eventDocument.decodeError}")
        assertTrue(out.findings.isEmpty(), "happy path must surface no findings, got ${out.findings}")
    }

    // -- Case 2: receipt absent on the expected channel --------------------

    @Test
    fun `JSON view — stdout without receipt triggers MissingReceipt finding`() {
        val stdout = eventsArray("""{"type":"X","v":1}""")
        val out = ProcessOutputDecoder.decode(stdout, "diagnostic noise", ObservationFormat.JSON)
        assertNull(out.receipt)
        assertTrue(out.findings.any { it is ProcessOutputDecoder.Finding.MissingReceipt },
            "MissingReceipt not surfaced: ${out.findings}")
    }

    // -- Case 3: receipt duplicated on the same channel --------------------

    @Test
    fun `JSON view — receipt line emitted twice triggers DuplicateReceipt finding`() {
        val r = receiptJson()
        val stdout = "$r\n$r\n[]"
        val out = ProcessOutputDecoder.decode(stdout, "", ObservationFormat.JSON)
        assertEquals(2, out.receiptOccurrences)
        assertTrue(out.findings.any { it is ProcessOutputDecoder.Finding.DuplicateReceipt },
            "DuplicateReceipt not surfaced: ${out.findings}")
    }

    // -- Case 4: receipt with contradictory correlation --------------------

    @Test
    fun `JSON view — receipt and durable RunCorrelated disagree triggers ContradictoryCorrelation finding`() {
        val stdout = receiptJson(correlationId = "c-receipt") + "\n" + eventsArray(
            runCorrelated("c-durable"),
        )
        val out = ProcessOutputDecoder.decode(stdout, "", ObservationFormat.JSON)
        val finding = out.findings.filterIsInstance<ProcessOutputDecoder.Finding.ContradictoryCorrelation>().firstOrNull()
        assertNotNull(finding, "ContradictoryCorrelation not surfaced: ${out.findings}")
        assertEquals("c-receipt", finding!!.receiptCorrelation)
        assertEquals("c-durable", finding.durableCorrelation)
    }

    // -- Case 5: invalid JSON event document --------------------------------

    @Test
    fun `JSON view — event document not a JSON array triggers InvalidJson finding`() {
        val stdout = receiptJson() + "\n" + """{"oops":"not an array"}"""
        val out = ProcessOutputDecoder.decode(stdout, "", ObservationFormat.JSON)
        assertNotNull(out.eventDocument.decodeError, "decode error should be surfaced")
        assertTrue(out.findings.any { it is ProcessOutputDecoder.Finding.InvalidJson },
            "InvalidJson not surfaced: ${out.findings}")
    }

    // -- Case 6: parser against the wrong format ---------------------------

    @Test
    fun `TEXT view — receipt on stderr is read, stdout without receipt triggers MissingReceipt`() {
        // In TEXT the receipt is on stderr; the test should NOT confuse
        // stdout-empty with receipt-missing. Here the caller asked for
        // TEXT, so an empty stderr AND an empty stdout is a finding
        // about the protocol, not about stdout alone.
        val out = ProcessOutputDecoder.decode("", "", ObservationFormat.TEXT)
        assertNull(out.receipt)
        assertTrue(out.findings.any { it is ProcessOutputDecoder.Finding.MissingReceipt })
    }

    @Test
    fun `TEXT view — receipt on stderr is read and matched`() {
        val stderr = receiptJson() + "\n" + "diagnostic 1\ndiagnostic 2"
        val stdout = "human line 1\nhuman line 2"
        val out = ProcessOutputDecoder.decode(stdout, stderr, ObservationFormat.TEXT)
        assertNotNull(out.receipt, "TEXT view must read the receipt from stderr")
        assertEquals(listOf("diagnostic 1", "diagnostic 2"), out.diagnostics)
        assertEquals(listOf("human line 1", "human line 2"), out.humanText)
    }

    // -- Case 7: two runs do not contaminate each other --------------------

    @Test
    fun `two independent decodings do not leak state`() {
        val a = ProcessOutputDecoder.decode(
            receiptJson(runId = "r-a", correlationId = "c-a") + "\n" + eventsArray(runCorrelated("c-a")),
            "",
            ObservationFormat.JSON,
        )
        val b = ProcessOutputDecoder.decode(
            receiptJson(runId = "r-b", correlationId = "c-b") + "\n" + eventsArray(runCorrelated("c-b")),
            "",
            ObservationFormat.JSON,
        )
        assertEquals("r-a", a.receipt?.runId)
        assertEquals("r-b", b.receipt?.runId)
        // No contamination: each decoding is independent. The findings
        // are per-decoding; cross-pollination would surface as either
        // a false DuplicateReceipt or as the wrong runId.
        assertTrue(a.findings.isEmpty(), "a should have no findings: ${a.findings}")
        assertTrue(b.findings.isEmpty(), "b should have no findings: ${b.findings}")
    }

    // -- Case 8: failure before launch (no receipt, no events) -------------

    @Test
    fun `failure before launch — empty stdout and stderr, only MissingReceipt finding`() {
        val out = ProcessOutputDecoder.decode("", "", ObservationFormat.JSON)
        assertTrue(out.findings.any { it is ProcessOutputDecoder.Finding.MissingReceipt })
        assertTrue(out.eventDocument.events.isEmpty())
        assertFalse(out.isWellFormed)
    }

    // -- Sanity: the strict testkit still throws on a malformed receipt ---

    @Test
    fun `strict testkit still throws on a malformed receipt — decoder must coexist`() {
        // The brief is explicit: a "test that just drops the first line
        // and declares PASS" is forbidden. The strict testkit throws on
        // malformed shapes; the decoder surfaces them. Both APIs must
        // exist; the test enforces that the strict one has not been
        // absorbed into the lenient one.
        val malformed = """{"schema":"pipelinek.launch/1","runId":"only"}"""
        val ex = runCatching { CliContractTestKit.parseReceipt(malformed) }.exceptionOrNull()
        assertNotNull(ex, "strict testkit must throw on a receipt missing required fields")
        assertTrue(ex is CliContractTestKit.ContractViolation,
            "strict testkit should throw ContractViolation, got ${ex!!::class.simpleName}")
    }
}
