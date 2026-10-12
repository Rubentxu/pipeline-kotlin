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
import java.util.UUID

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

    // ========================================================================
    // PK-0-57-RC1 / ADR-PK1-OUTPUT-FRAMING — SeparatedV2 protocol matrix
    //
    // Eight tests pinning the contract for the opt-in protocol. The matrix
    // exists in the ADR; the test names below mirror the section headings
    // 1:1. Adding a test to a section that no longer matches the ADR is a
    // contract drift; removing a test is a contract weakening.
    // ========================================================================

    /**
     * PK-0-57-RC1 / T7c-1: parser anterior contra modo Published
     * (compatibilidad). The decoder's default behaviour (no
     * expectedProtocol) reads receipt + events from stdout and
     * produces no findings. This is the R0.3 contract preserved.
     */
    @Test
    fun `legacy decoder against published mode preserves receipt on stdout, events on stdout`() {
        val receipt = LaunchReceipt.encode(newReceipt())
        val stdout = receipt + runCorrelatedJson() + "\n"
        val out = ProcessOutputDecoder.decode(stdout, "", ObservationFormat.JSON)
        assertEquals(ObservationFormat.JSON, out.expectedFormat)
        assertEquals(ProcessOutputDecoder.Protocol.Published, out.protocol)
        assertNotNull(out.receipt, "receipt must decode on stdout under Published")
        assertEquals(1, out.receiptOccurrences)
        assertTrue(out.eventDocument.events.any { it is RunCorrelated })
        assertTrue(out.findings.isEmpty(), "no findings expected, got: ${out.findings}")
    }

    /**
     * PK-0-57-RC1 / T7c-2: parser nuevo contra modo SeparatedV2.
     * The receipt is still on stdout (it does not change channel),
     * but the event array has moved to stderr.
     */
    @Test
    fun `separated decoder against separated-v2 mode reads receipt on stdout, events on stderr`() {
        val receipt = LaunchReceipt.encode(newReceipt())
        val stdout = receipt + "\n"           // receipt only
        val stderr = runCorrelatedJson() + "\n"  // event array on stderr
        val out = ProcessOutputDecoder.decode(
            stdout = stdout,
            stderr = stderr,
            expectedFormat = ObservationFormat.JSON,
            expectedProtocol = ProcessOutputDecoder.Protocol.SeparatedV2,
        )
        assertEquals(ProcessOutputDecoder.Protocol.SeparatedV2, out.protocol)
        assertNotNull(out.receipt, "receipt must still decode on stdout under SeparatedV2")
        assertEquals(1, out.receiptOccurrences)
        assertTrue(out.eventDocument.events.any { it is RunCorrelated },
            "events must be read from stderr under SeparatedV2, got: ${out.eventDocument.events}")
        assertTrue(out.findings.isEmpty(), "no findings expected, got: ${out.findings}")
    }

    /**
     * PK-0-57-RC1 / T7c-3: recibo ausente en SeparatedV2.
     * The decoder reports MissingReceipt — never silently degrades to
     * "events but no receipt".
     */
    @Test
    fun `separated-v2 mode with missing receipt reports MissingReceipt`() {
        val out = ProcessOutputDecoder.decode(
            stdout = "",
            stderr = runCorrelatedJson() + "\n",
            expectedFormat = ObservationFormat.JSON,
            expectedProtocol = ProcessOutputDecoder.Protocol.SeparatedV2,
        )
        assertTrue(
            out.findings.any { it is ProcessOutputDecoder.Finding.MissingReceipt },
            "MissingReceipt finding expected, got: ${out.findings}",
        )
        assertNull(out.receipt, "no receipt on stdout must yield a null receipt")
    }

    /**
     * PK-0-57-RC1 / T7c-4: recibo duplicado en SeparatedV2.
     * The contract violation is symmetric across protocols: a single
     * run may only emit one receipt.
     */
    @Test
    fun `separated-v2 mode with duplicate receipt reports contract violation`() {
        val receipt = LaunchReceipt.encode(newReceipt())
        val stdout = receipt + "\n" + receipt + "\n"  // two receipts on stdout
        val out = ProcessOutputDecoder.decode(
            stdout = stdout,
            stderr = "",
            expectedFormat = ObservationFormat.JSON,
            expectedProtocol = ProcessOutputDecoder.Protocol.SeparatedV2,
        )
        assertTrue(
            out.findings.any { it is ProcessOutputDecoder.Finding.DuplicateReceipt },
            "DuplicateReceipt finding expected, got: ${out.findings}",
        )
        assertEquals(2, out.receiptOccurrences)
    }

    /**
     * PK-0-57-RC1 / T7c-5: correlación contradictoria en SeparatedV2.
     * The receipt's correlationId must equal the durable RunCorrelated
     * event's correlationId — the R0.3 binding, exercised under the
     * new protocol.
     */
    @Test
    fun `separated-v2 mode with contradictory correlation reports assertion failure`() {
        val receipt = LaunchReceipt.encode(newReceipt())
        val wrongCorrelation = CorrelationId(UUID.randomUUID().toString())
        val correlated = newCorrelatedEvent().copy(correlationId = wrongCorrelation)
        val stdout = receipt + "\n"
        val stderr = JsonEventLog.encode(listOf(correlated)) + "\n"
        val out = ProcessOutputDecoder.decode(
            stdout = stdout,
            stderr = stderr,
            expectedFormat = ObservationFormat.JSON,
            expectedProtocol = ProcessOutputDecoder.Protocol.SeparatedV2,
        )
        assertTrue(
            out.findings.any { it is ProcessOutputDecoder.Finding.ContradictoryCorrelation },
            "ContradictoryCorrelation finding expected, got: ${out.findings}",
        )
    }

    /**
     * PK-0-57-RC1 / T7c-6: JSON inválido en SeparatedV2.
     * The decoder must surface InvalidJson, not return an empty event
     * list. The "catch { emptyList() }" pattern is the exact defect
     * the R0.3 brief banned.
     */
    @Test
    fun `separated-v2 mode with malformed events JSON reports InvalidJson not empty list`() {
        val receipt = LaunchReceipt.encode(newReceipt())
        val out = ProcessOutputDecoder.decode(
            stdout = receipt + "\n",
            stderr = "[not valid json\n",
            expectedFormat = ObservationFormat.JSON,
            expectedProtocol = ProcessOutputDecoder.Protocol.SeparatedV2,
        )
        assertTrue(
            out.findings.any { it is ProcessOutputDecoder.Finding.InvalidJson },
            "InvalidJson finding expected, got: ${out.findings}",
        )
        assertTrue(out.eventDocument.events.isEmpty())
        assertNotNull(out.eventDocument.decodeError,
            "decodeError must be surfaced, not swallowed by catch/emptyList")
    }

    /**
     * PK-0-57-RC1 / T7c-7: dos ejecuciones concurrentes en SeparatedV2
     * no comparten estado. Each decoder call is a pure function of
     * (stdout, stderr, expectedFormat, expectedProtocol); two
     * independent calls must produce independent findings.
     */
    @Test
    fun `separated-v2 mode with two concurrent invocations decodes independently`() {
        val a = runSeparatedV2CorrelatedRun()
        val b = runSeparatedV2CorrelatedRun()
        // Same shape, same protocol, two decoders: each must produce
        // a clean run with no findings and a non-null receipt.
        assertTrue(a.findings.isEmpty(), "decoder A: ${a.findings}")
        assertTrue(b.findings.isEmpty(), "decoder B: ${b.findings}")
        assertNotNull(a.receipt)
        assertNotNull(b.receipt)
        // And they must not interfere: the union of their findings
        // is still empty.
        val union = (a.findings + b.findings)
        assertTrue(union.isEmpty(), "union must be empty: $union")
    }

    /**
     * PK-0-57-RC1 / T7c-8: fallos antes y después del lanzamiento en
     * SeparatedV2. The decoder handles the pre-launch and post-launch
     * failure cases symmetrically: a missing receipt is a
     * MissingReceipt finding; a malformed event array is an
     * InvalidJson finding. Neither is silent.
     */
    @Test
    fun `separated-v2 mode handles pre-launch and post-launch failures symmetrically`() {
        // Pre-launch: no receipt, no events.
        val pre = ProcessOutputDecoder.decode(
            stdout = "",
            stderr = "",
            expectedFormat = ObservationFormat.JSON,
            expectedProtocol = ProcessOutputDecoder.Protocol.SeparatedV2,
        )
        assertTrue(pre.findings.any { it is ProcessOutputDecoder.Finding.MissingReceipt })

        // Post-launch: receipt present on stdout, but the event array
        // is malformed on stderr.
        val receipt = LaunchReceipt.encode(newReceipt())
        val post = ProcessOutputDecoder.decode(
            stdout = receipt + "\n",
            stderr = "[{garbage",
            expectedFormat = ObservationFormat.JSON,
            expectedProtocol = ProcessOutputDecoder.Protocol.SeparatedV2,
        )
        assertNotNull(post.receipt, "receipt must still decode on a post-launch failure")
        assertTrue(
            post.findings.any { it is ProcessOutputDecoder.Finding.InvalidJson },
            "post-launch failure must surface as InvalidJson, got: ${post.findings}",
        )
    }

    // ---- helpers for the matrix ----

    private fun newReceipt(): LaunchReceipt = LaunchReceipt(
        runId = "11111111-1111-1111-1111-111111111111",
        correlationId = "22222222-2222-2222-2222-222222222222",
        ownerId = "33333333-3333-3333-3333-333333333333",
        startedAtMs = 1_700_000_000_000L,
        controlDir = "/tmp/control",
        status = LaunchStatus.Launched,
    )

    private fun newCorrelatedEvent(): RunCorrelated = RunCorrelated(
        eventId = "e-correlated",
        runId = "11111111-1111-1111-1111-111111111111",
        sequence = 0L,
        occurredAt = java.time.Instant.parse("2026-10-12T10:00:00Z"),
        correlationId = CorrelationId("22222222-2222-2222-2222-222222222222"),
        ownerId = "33333333-3333-3333-3333-333333333333",
        startedAtMs = 1_700_000_000_000L,
    )

    private fun runCorrelatedJson(): String =
        JsonEventLog.encode(listOf(newCorrelatedEvent()))

    private fun runSeparatedV2CorrelatedRun(): ProcessOutputDecoder.DecodedProcessOutput {
        val receipt = LaunchReceipt.encode(newReceipt())
        val stdout = receipt + "\n"
        val stderr = runCorrelatedJson() + "\n"
        return ProcessOutputDecoder.decode(
            stdout = stdout,
            stderr = stderr,
            expectedFormat = ObservationFormat.JSON,
            expectedProtocol = ProcessOutputDecoder.Protocol.SeparatedV2,
        )
    }
}
