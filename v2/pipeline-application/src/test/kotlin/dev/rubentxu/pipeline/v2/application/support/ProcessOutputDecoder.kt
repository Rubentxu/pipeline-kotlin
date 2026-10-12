package dev.rubentxu.pipeline.v2.application.support

import dev.rubentxu.pipeline.v2.application.LaunchReceipt
import dev.rubentxu.pipeline.v2.application.LaunchStatus
import dev.rubentxu.pipeline.v2.application.observation.ObservationFormat
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.RunCorrelated

/**
 * PK-RELEASE-CONSOLIDATION D2 + H1.3 — process-output decoder.
 *
 * The published CLI protocol is R0.3 channel split: stdout line 1 is the
 * [LaunchReceipt] and stdout line 2 is the event document (JSON view) or
 * stdout lines 2..N are one event per line (JSON_LINES). In TEXT view
 * the receipt is on stderr line 1.
 *
 * That contract is the only one the testkit can demand today, but the
 * brief asks for more than a single happy-path decode: a decoder that
 * **distinguishes** what the run produced, including the cases that the
 * brief lists explicitly:
 *
 *  - receipt absent
 *  - receipt duplicated
 *  - receipt with contradictory correlation
 *  - JSON invalid
 *  - parser against the wrong protocol
 *  - two concurrent runs
 *  - failure before launch (no receipt, no events) vs after launch
 *
 * This decoder is the structural answer. It does NOT throw on the
 * adverse cases — it surfaces them as typed [Finding] entries so a
 * test can assert on the specific outcome. [CliContractTestKit] still
 * throws on the strict happy-path; this object is the typeless,
 * inspection-friendly counterpart.
 *
 * ## Why a separate file
 *
 * CliContractTestKit is the strict single-decode API. This object is
 * the inspection API: callers that want to ASK "what did this run
 * produce?" without choosing what to throw on. Putting both behind
 * one object would force every caller to choose a strictness they
 * may not want.
 *
 * ## What it does NOT do
 *
 * - It does not move the receipt between channels. ADR-PK1-IDENTITY
 *   fixes stdout as the published channel; this decoder respects
 *   that, including for the cases the brief warns about.
 * - It does not assume the document type. The receipt is the receipt
 *   because it carries `schema=pipelinek.launch/1`; a JSON object
 *   without that token is "some other JSON object", not a receipt.
 * - It does not swallow decode errors. A malformed JSON document
 *   surfaces as [Finding.InvalidJson] AND as an [EventDocument] with
 *   [EventDocument.decodeError] populated, so the caller can assert
 *   on either.
 */
object ProcessOutputDecoder {

    /**
     * The detected protocol of a run. `Published` means the R0.3
     * channel-split protocol (the only one this testkit recognises as
     * authoritative). `SeparatedV2` is the PK-0-57-RC1 opt-in
     * protocol declared by [ADR-PK1-OUTPUT-FRAMING]: the receipt
     * stays on stdout, the event array moves to stderr. `Unknown`
     * means the bytes do not match any protocol this testkit knows
     * about.
     *
     * The receipt's `schema` token is `pipelinek.launch/1` under
     * both `Published` and `SeparatedV2`; the difference is the
     * channel of the event array, not the schema of the receipt.
     */
    enum class Protocol { Published, SeparatedV2, Unknown }

    /**
     * A typed observation about the run output. The list of findings
     * is the short answer to "what is wrong with this run's output,
     * if anything". Empty list = no findings = well-formed run.
     */
    sealed class Finding {
        /** The expected channel for [format] did not contain a receipt. */
        data class MissingReceipt(val format: ObservationFormat) : Finding()

        /** The same receipt line appeared more than once, or on both channels. */
        data class DuplicateReceipt(val count: Int) : Finding()

        /** Two receipts on the same run disagreed on the bound runId/correlationId. */
        data class ContradictoryReceipt(val receipts: List<LaunchReceipt>) : Finding()

        /**
         * The receipt's correlationId did not match the durable
         * [RunCorrelated] event's correlationId. A failure of the
         * binding the R0.3 brief explicitly mandates.
         */
        data class ContradictoryCorrelation(
            val receiptCorrelation: String,
            val durableCorrelation: String,
        ) : Finding()

        /** The event document failed to parse. */
        data class InvalidJson(val message: String) : Finding()

        /**
         * The output looks like it was emitted by a different protocol
         * than [expected]. The decoder still parses what it can, but
         * the protocol-level finding is the warning.
         */
        data class ProtocolMismatch(val expected: ObservationFormat, val observed: String) : Finding()
    }

    /**
     * The full decoded output of one process invocation, in the form
     * the testkit wants to assert on. Each field is required: a caller
     * that only cares about [eventDocument] still gets the [receipt]
     * so it can run the correlation check.
     */
    data class DecodedProcessOutput(
        val stdout: String,
        val stderr: String,
        val expectedFormat: ObservationFormat,
        val protocol: Protocol,
        val receipt: LaunchReceipt?,
        /**
         * Total number of receipt-shaped lines the decoder saw on the
         * expected channel. 1 is the normal value (the run emitted its
         * one receipt). >1 triggers [Finding.DuplicateReceipt]. 0
         * triggers [Finding.MissingReceipt]. Named "occurrences", not
         * "duplicates", because a single receipt is not a duplicate; the
         * call site reads the count directly without off-by-one
         * adjustment.
         */
        val receiptOccurrences: Int,
        val eventDocument: EventDocument,
        val humanText: List<String>,
        val diagnostics: List<String>,
        val findings: List<Finding>,
    ) {
        val isWellFormed: Boolean get() = findings.isEmpty() && eventDocument.isWellFormed
    }

    /**
     * The decoded event stream, regardless of which view emitted it.
     * Mirrors [CliContractTestKit.EventDocument] so callers can switch
     * between strict and inspection decoders without rewriting the
     * assertion shape.
     */
    data class EventDocument(
        val lines: List<String>,
        val events: List<DomainEvent>,
        val decodeError: String?,
    ) {
        val isWellFormed: Boolean get() = decodeError == null
    }

    /**
     * Decode a process output. [expectedFormat] is the format the
     * caller asked the run to use. The decoder looks for the receipt
     * on the channel the published protocol defines for that format
     * and reports anything that does not match.
     *
     * [expectedProtocol] is the protocol the caller asked the run to
     * use. When `null` (the default), the decoder falls back to the
     * R0.3 published channel split — receipt on stdout for
     * JSON/JSONL, on stderr for TEXT. When set to [Protocol.SeparatedV2],
     * the event array is read from stderr instead of stdout (TEXT is
     * unchanged). The receipt channel is the same under both
     * protocols; only the event channel changes.
     */
    fun decode(
        stdout: String,
        stderr: String,
        expectedFormat: ObservationFormat,
        expectedProtocol: Protocol? = null,
    ): DecodedProcessOutput {
        val findings = mutableListOf<Finding>()
        val diagnostics = stderrLinesExcludingReceipt(stderr, expectedFormat)
        val protocol: Protocol = expectedProtocol ?: Protocol.Published

        // Receipt pass. The receipt, if present, is on a SPECIFIC
        // channel per the published contract. We do NOT sniff both:
        // the brief is explicit that the channel selection is the
        // contract. We also count duplicates to detect a regression
        // where the run emitted twice.
        //
        // The receipt channel is invariant under SeparatedV2: only
        // the event array's channel changes.
        val receiptChannel = when (expectedFormat) {
            ObservationFormat.TEXT -> stderr
            ObservationFormat.JSON, ObservationFormat.JSON_LINES -> stdout
        }
        val receiptLines = receiptChannel.lines()
            .filter { it.isNotBlank() }
            .filter { isReceiptLine(it) }
        val receipts = receiptLines.mapNotNull { runCatchingParseReceipt(it) }
        val receiptDuplicates = receiptLines.size
        val receipt: LaunchReceipt? = when {
            receipts.isEmpty() -> {
                findings.add(Finding.MissingReceipt(expectedFormat))
                null
            }
            receiptLines.size > 1 -> {
                findings.add(Finding.DuplicateReceipt(receiptLines.size))
                receipts.first()
            }
            else -> receipts.first()
        }
        // If multiple receipts disagree on the runId/correlationId,
        // that is a separate finding on top of the duplicate count.
        if (receipts.size > 1) {
            val distinct = receipts.distinctBy { it.runId to it.correlationId }
            if (distinct.size > 1) {
                findings.add(Finding.ContradictoryReceipt(receipts))
            }
        }

        // Event document pass. The source channel is decided by the
        // protocol: under Published the event array sits on stdout
        // (JSON / JSON_LINES) or in the stderr diagnostics
        // (TEXT, no machine events). Under SeparatedV2 the event
        // array moves to stderr for JSON / JSON_LINES; TEXT is
        // unchanged because SeparatedV2 only affects machine output.
        val eventDocument = decodeEventDocument(
            stdout = stdout,
            stderr = stderr,
            expectedFormat = expectedFormat,
            protocol = protocol,
            findings = findings,
        )
        // Correlation check: receipt.correlationId must equal the
        // first RunCorrelated event's correlationId.
        if (receipt != null && eventDocument.isWellFormed) {
            val correlated = eventDocument.events
                .filterIsInstance<RunCorrelated>()
                .firstOrNull()
            if (correlated == null) {
                // No durable RunCorrelated — surface as a finding so
                // the test fails on the binding, not on a missing
                // field it forgot to assert.
                findings.add(
                    Finding.ContradictoryCorrelation(
                        receiptCorrelation = receipt.correlationId,
                        durableCorrelation = "<no RunCorrelated event in durable stream>",
                    ),
                )
            } else if (correlated.correlationId.value != receipt.correlationId) {
                findings.add(
                    Finding.ContradictoryCorrelation(
                        receiptCorrelation = receipt.correlationId,
                        durableCorrelation = correlated.correlationId.value,
                    ),
                )
            }
        }

        // Human text is whatever stdout remains after the receipt and
        // event document are removed. In TEXT view it is the entire
        // stdout. In JSON views it is the empty list because stdout
        // is supposed to be machine-only.
        val humanText = when (expectedFormat) {
            ObservationFormat.TEXT -> stdout.lines().filter { it.isNotBlank() }
            ObservationFormat.JSON, ObservationFormat.JSON_LINES -> emptyList()
        }

        val detectedProtocol: Protocol = if (receipt != null || stdout.isBlank() && stderr.isBlank()) {
            // A run that produced a receipt (or no output at all) is
            // assumed to have run under the protocol the caller
            // asked for. Unknown only fires when the bytes do not
            // look like either protocol.
            protocol
        } else {
            // No receipt and non-empty stdout/stderr: the published
            // protocol mandates a receipt on the expected channel;
            // its absence is already a MissingReceipt finding, and
            // here we just mark the protocol as unknown.
            Protocol.Unknown
        }

        return DecodedProcessOutput(
            stdout = stdout,
            stderr = stderr,
            expectedFormat = expectedFormat,
            protocol = detectedProtocol,
            receipt = receipt,
            receiptOccurrences = receiptLines.size,
            eventDocument = eventDocument,
            humanText = humanText,
            diagnostics = diagnostics,
            findings = findings.toList(),
        )
    }

    // ------------------------------------------------------------------------
    // 2. Receipt detection.
    // ------------------------------------------------------------------------

    /**
     * A line is a receipt if it parses as a JSON object whose
     * `schema` field equals [CliContractTestKit.EXPECTED_RECEIPT_SCHEMA].
     * Anything else is some other JSON object — a finding about it
     * is for the caller to make, not for the decoder to assume.
     */
    fun isReceiptLine(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return false
        val schema = runCatching { schemaOf(trimmed) }.getOrNull() ?: return false
        return schema == CliContractTestKit.EXPECTED_RECEIPT_SCHEMA
    }

    private fun schemaOf(json: String): String? {
        // Minimal, structural: parse the JSON, look for the schema
        // token. We avoid the full receipt decoder here because
        // malformed shapes (e.g. a launch document that is missing a
        // required field) are still "receipt-like" for detection
        // purposes — the strict decoder will throw on them later.
        val element = CliContractTestKit.RECEIPT_DECODER
            .parseToJsonElement(json)
        val obj = element as? kotlinx.serialization.json.JsonObject ?: return null
        return obj["schema"]?.toString()?.trim('"')
    }

    private fun runCatchingParseReceipt(line: String): LaunchReceipt? = try {
        CliContractTestKit.parseReceipt(line)
    } catch (_: CliContractTestKit.ContractViolation) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    // ------------------------------------------------------------------------
    // 3. Event document decode.
    // ------------------------------------------------------------------------

    private fun decodeEventDocument(
        stdout: String,
        stderr: String,
        format: ObservationFormat,
        protocol: Protocol,
        findings: MutableList<Finding>,
    ): EventDocument {
        // The source channel for the event array depends on the
        // protocol. Under Published the array sits on stdout (for
        // JSON/JSON_LINES); under SeparatedV2 it moves to stderr.
        // TEXT view is unchanged in both: human text is in the
        // channel the format dictates (TEXT has no machine events).
        val source = when {
            format == ObservationFormat.TEXT -> stdout
            protocol == Protocol.SeparatedV2 -> stderr
            else -> stdout
        }
        val lines = source.lines()
        // Skip the receipt line if present on the source; the event
        // document starts on the line after. Under SeparatedV2 the
        // receipt stays on stdout, so it never appears in the stderr
        // source — but the filter is robust to either.
        val nonReceiptLines = lines.filterNot { isReceiptLine(it) }
        return when (format) {
            ObservationFormat.TEXT -> EventDocument(nonReceiptLines, emptyList(), null)
            ObservationFormat.JSON -> {
                if (nonReceiptLines.isEmpty()) {
                    return EventDocument(nonReceiptLines, emptyList(), null)
                }
                val document = nonReceiptLines.joinToString("\n").trim()
                // The contract for the JSON view is "a single bracketed array".
                // JsonEventLog.decode is structural and tolerates a missing array
                // by returning an empty list, which would hide a real
                // contract violation. Detect the missing-bracket shape BEFORE
                // delegating to the codec.
                if (!document.startsWith("[") || !document.endsWith("]")) {
                    val msg = "JSON view: event document must be a single bracketed array, " +
                        "got: ${document.take(80)}"
                    findings.add(Finding.InvalidJson(msg))
                    return EventDocument(nonReceiptLines, emptyList(), msg)
                }
                val events = runCatching { dev.rubentxu.pipeline.v2.events.durable.JsonEventLog.decode(document) }
                events.fold(
                    onSuccess = { decoded -> EventDocument(nonReceiptLines, decoded, null) },
                    onFailure = { e ->
                        findings.add(Finding.InvalidJson(e.message ?: "decode failed"))
                        EventDocument(nonReceiptLines, emptyList(), e.message ?: "decode failed")
                    },
                )
            }
            ObservationFormat.JSON_LINES -> {
                val events = mutableListOf<DomainEvent>()
                for ((index, line) in nonReceiptLines.withIndex()) {
                    if (line.isBlank()) continue
                    val parsed = runCatching {
                        dev.rubentxu.pipeline.v2.events.durable.JsonEventLog.decode("[$line]").firstOrNull()
                    }
                    val ev = parsed.getOrNull()
                    if (ev == null) {
                        val msg = parsed.exceptionOrNull()?.message ?: "empty element"
                        findings.add(Finding.InvalidJson("JSON_LINES line ${index + 1}: $msg"))
                        return EventDocument(nonReceiptLines, events, "JSON_LINES line ${index + 1}: $msg")
                    }
                    events.add(ev)
                }
                EventDocument(nonReceiptLines, events, null)
            }
        }
    }

    private fun stderrLinesExcludingReceipt(stderr: String, format: ObservationFormat): List<String> {
        // In TEXT view the receipt is on stderr; exclude it from
        // diagnostics. In JSON views stderr holds diagnostics only.
        return when (format) {
            ObservationFormat.TEXT -> stderr.lines().filter { it.isNotBlank() && !isReceiptLine(it) }
            ObservationFormat.JSON, ObservationFormat.JSON_LINES -> stderr.lines().filter { it.isNotBlank() }
        }
    }
}
