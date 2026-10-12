package dev.rubentxu.pipeline.v2.application.support

import dev.rubentxu.pipeline.v2.application.LaunchReceipt
import dev.rubentxu.pipeline.v2.application.LaunchStatus
import dev.rubentxu.pipeline.v2.application.observation.ObservationFormat
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.RunCorrelated
import dev.rubentxu.pipeline.v2.events.durable.JsonEventLog
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * PK-NEXT R0.3 testkit — decode the public stdout/stderr contract.
 *
 * ## Why this exists
 *
 * Before R0.3, a test that wanted to read the JSON event stream did
 * `JsonEventLog.decode(stdout.trim())` and that worked because stdout
 * WAS the event array. After R0.3 stdout line 1 is the LaunchReceipt
 * and line 2 is the array, so that decode either misses the receipt or
 * tries to parse a leading receipt as an event. Tests that asserted
 * `stdout.startsWith("[")` broke because line 1 is `{`.
 *
 * Two wrong fixes were available:
 *
 *  - Patch each test to skip the first line. Forbidden by the user
 *    (R0.3 brief: "NO debe resolverse cambiando indiscriminadamente
 *    los tests para ignorar la primera línea").
 *  - Pretend the contract did not change. That would re-create the
 *    rc3-class drift in reverse: the contract and the assertion would
 *    disagree, and the disagreement would be silent.
 *
 * The right answer is to make the contract a single named thing and
 * route every test through it. That is what this file does. Every
 * assertion that depends on stdout or stderr goes through
 * [decodeLaunchContract] or [decodeEventStream] and asserts what the
 * contract promises.
 *
 * ## What this testkit refuses to do
 *
 * - No `try { } catch { emptyList() }` around decodes. A malformed
 *   line is a contract violation and the test must fail loudly. This
 *   is the explicit obligation from the R0.3 brief: "no use
 *   `catch { emptyList() }` para ocultar un fallo de decodificación".
 * - No silent string trimming that loses the receipt. The receipt is
 *   a separate object on its own line; it is not "leading whitespace
 *   before the array".
 * - No assumption that a non-empty stdout contains a receipt. The
 *   testkit checks the contract for the view and fails closed if the
 *   channels are swapped (e.g. receipt on stdout in TEXT view).
 *
 * ## The contract, in one place
 *
 * For a run with `format = JSON`:
 *
 *  - stdout line 1 is a single JSON object: the LaunchReceipt.
 *  - stdout line 2 is a single JSON document: the event array
 *    (one bracketed array, no trailing newline required).
 *  - stderr holds diagnostics; it may also hold the receipt if the
 *    run did not get far enough to emit it on stdout, but that is
 *    out of contract for a successful run.
 *
 * For a run with `format = JSON_LINES`:
 *
 *  - stdout line 1 is a single JSON object: the LaunchReceipt.
 *  - stdout lines 2..N are DomainEvent JSON objects, one per line.
 *
 * For a run with `format = TEXT` (default):
 *
 *  - stdout holds human lines.
 *  - stderr line 1 is the LaunchReceipt JSON object, followed by
 *    diagnostics.
 *
 * See ADR-PK-NEXT-R0.3 for the rationale and the full surface.
 */
object CliContractTestKit {

    /**
     * The schema version this testkit expects. The constant matches
     * [LaunchReceipt.SCHEMA]; a mismatch fails closed.
     */
    const val EXPECTED_RECEIPT_SCHEMA: String = LaunchReceipt.SCHEMA

    internal val RECEIPT_DECODER: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    @Suppress("unused")
    private fun receiptDecoder(): Json = RECEIPT_DECODER

    /**
     * What the testkit extracted from a run. Every field is required —
     * a test that only needs the events still gets the receipt so it
     * can run the correlation assertion.
     */
    data class LaunchContract(
        val format: ObservationFormat,
        val receipt: LaunchReceipt,
        val eventDocument: EventDocument,
    )

    /**
     * The decoded event stream, regardless of which view emitted it.
     *
     * [lines] is the raw stdout, with the receipt line already removed
     * (so what remains is the event surface, in the wire order the
     * view defined). [events] is the decoded list — decode failures
     * surface as [decodeError] and not as an empty list.
     */
    data class EventDocument(
        val lines: List<String>,
        val events: List<DomainEvent>,
        val decodeError: String?,
    ) {
        val isWellFormed: Boolean get() = decodeError == null
    }

    // ------------------------------------------------------------------------
    // 1. Decode the whole contract.
    // ------------------------------------------------------------------------

    /**
     * Decode stdout and stderr according to [format] and return the
     * contract. A channel that does not match the contract is a
     * [ContractViolation] — the caller is expected to assert on it
     * explicitly, not absorb it.
     */
    fun decodeLaunchContract(
        stdout: String,
        stderr: String,
        format: ObservationFormat,
    ): LaunchContract {
        val receipt = decodeReceiptForFormat(stdout, stderr, format)
        val eventDocument = decodeEventStreamForFormat(stdout, format)
        return LaunchContract(format, receipt, eventDocument)
    }

    /**
     * Locate the receipt on the correct channel for [format] and parse
     * it. The channel selection is the contract: this method does not
     * sniff both, because sniffing is how the rc3-class drift is
     * re-introduced.
     */
    fun decodeReceiptForFormat(
        stdout: String,
        stderr: String,
        format: ObservationFormat,
    ): LaunchReceipt {
        val rawLine: String = when (format) {
            ObservationFormat.TEXT -> firstNonBlankLine(stderr)
                ?: throw contractViolation(
                    "TEXT view: expected the LaunchReceipt on stderr line 1, " +
                        "but stderr was empty. Did the run never reach the " +
                        "emit phase, or did the channel split regress?",
                )
            ObservationFormat.JSON, ObservationFormat.JSON_LINES -> firstNonBlankLine(stdout)
                ?: throw contractViolation(
                    "$format view: expected the LaunchReceipt on stdout line 1, " +
                        "but stdout was empty.",
                )
        }
        return parseReceipt(rawLine)
    }

    /**
     * Decode the event stream according to [format]. For the JSON
     * view this consumes stdout line 2 as a single bracketed array.
     * For JSON_LINES it consumes stdout lines 2..N as one event per
     * line. For TEXT the document is empty — events do not live on
     * stdout in the human view, they were rendered as human lines.
     */
    fun decodeEventStreamForFormat(
        stdout: String,
        format: ObservationFormat,
    ): EventDocument {
        val lines = stdout.lines()
        when (format) {
            ObservationFormat.TEXT -> {
                // The human view does not put events on stdout. We
                // still surface the line list so a caller can assert
                // on the rendered human output, but the events list
                // is necessarily empty.
                return EventDocument(lines, emptyList(), null)
            }
            ObservationFormat.JSON -> {
                // line 1 is the receipt; line 2 is the array. If the
                // array is missing or the channels are swapped the
                // contract is violated — the test must know.
                if (lines.size < 2) {
                    return EventDocument(
                        lines = lines,
                        events = emptyList(),
                        decodeError = "JSON view: expected at least 2 stdout lines " +
                            "(receipt, then the JSON array), got ${lines.size}",
                    )
                }
                val document = lines[1].trim()
                return decodeArrayDocument(document)
            }
            ObservationFormat.JSON_LINES -> {
                // line 1 is the receipt; lines 2..N are events.
                if (lines.size < 2) {
                    return EventDocument(
                        lines = lines,
                        events = emptyList(),
                        decodeError = "JSON_LINES view: expected at least 2 stdout lines " +
                            "(receipt, then events), got ${lines.size}",
                    )
                }
                return decodeLineDelimitedDocument(lines.drop(1))
            }
        }
    }

    // ------------------------------------------------------------------------
    // 2. Decode the events.
    // ------------------------------------------------------------------------

    /**
     * Parse a single bracketed JSON array as the event document.
     *
     * Unlike [JsonEventLog.decode], this surfaces a malformed document
     * as [EventDocument.decodeError] rather than as an empty list.
     * A document that the codec can split but whose elements fail to
     * decode is still a [decodeError]: the contract is "the document
     * is well-formed and parseable", not "the document yields some
     * subset of the events".
     */
    fun decodeArrayDocument(payload: String): EventDocument {
        if (payload.isBlank() || payload == "[]") {
            return EventDocument(listOf(payload), emptyList(), null)
        }
        if (!payload.startsWith("[") || !payload.endsWith("]")) {
            return EventDocument(
                listOf(payload),
                emptyList(),
                "JSON view: event document must be a single bracketed array, " +
                    "got: ${payload.take(80)}",
            )
        }
        return try {
            val events = JsonEventLog.decode(payload)
            EventDocument(listOf(payload), events, null)
        } catch (e: SerializationException) {
            EventDocument(listOf(payload), emptyList(), "JSON view: decode failed: ${e.message}")
        } catch (e: IllegalArgumentException) {
            EventDocument(listOf(payload), emptyList(), "JSON view: decode failed: ${e.message}")
        }
    }

    /**
     * Parse a stream of JSON objects, one per line, as the event
     * document. A line that fails to parse produces a
     * [EventDocument.decodeError] — the contract is per-line
     * parseability, not "best effort, return what you can".
     */
    fun decodeLineDelimitedDocument(eventLines: List<String>): EventDocument {
        if (eventLines.isEmpty()) {
            return EventDocument(emptyList(), emptyList(), null)
        }
        val events = mutableListOf<DomainEvent>()
        for ((index, line) in eventLines.withIndex()) {
            if (line.isBlank()) continue
            try {
                val event = JsonEventLog.decode("[$line]").firstOrNull()
                    ?: return EventDocument(
                        eventLines,
                        events,
                        "JSON_LINES line ${index + 1}: empty element after decode",
                    )
                events.add(event)
            } catch (e: SerializationException) {
                return EventDocument(
                    eventLines,
                    events,
                    "JSON_LINES line ${index + 1}: decode failed: ${e.message}",
                )
            } catch (e: IllegalArgumentException) {
                return EventDocument(
                    eventLines,
                    events,
                    "JSON_LINES line ${index + 1}: decode failed: ${e.message}",
                )
            }
        }
        return EventDocument(eventLines, events, null)
    }

    // ------------------------------------------------------------------------
    // 3. Receipt decode (single source of truth).
    // ------------------------------------------------------------------------

    /**
     * Parse a raw receipt line into a [LaunchReceipt]. The schema is
     * checked explicitly: a receipt with the wrong schema token is a
     * contract violation, not a "close enough" match.
     */
    fun parseReceipt(rawLine: String): LaunchReceipt {
        val object_ = try {
            jsonObjectOf(rawLine)
        } catch (e: SerializationException) {
            throw contractViolation("receipt is not a JSON object: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw contractViolation("receipt is not a JSON object: ${e.message}")
        }
        val schema = object_["schema"]?.jsonPrimitive?.content
            ?: throw contractViolation("receipt has no schema field: $rawLine")
        if (schema != EXPECTED_RECEIPT_SCHEMA) {
            throw contractViolation(
                "receipt schema $schema does not match expected " +
                    "$EXPECTED_RECEIPT_SCHEMA. The certifier must learn a new " +
                    "schema token before adopting it.",
            )
        }
        return try {
            buildReceiptFrom(object_)
        } catch (e: IllegalStateException) {
            throw contractViolation("receipt could not be decoded: ${e.message}")
        }
    }

    /**
     * Build a [LaunchReceipt] from a [JsonObject] field-by-field, so
     * the test source set does not depend on the
     * `kotlin("plugin.serialization")` plugin that the production
     * module does not apply. The field set MUST match
     * [LaunchReceipt]; any drift is a contract violation.
     */
    private fun buildReceiptFrom(o: JsonObject): LaunchReceipt {
        val runId = o.string("runId")
        val correlationId = o.string("correlationId")
        val ownerId = o.string("ownerId")
        val startedAtMs = o.long("startedAtMs")
        val controlDir = o.optionalString("controlDir")
        val statusRaw = o["status"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("status field is missing")
        val status = decodeStatus(statusRaw)
        return LaunchReceipt(
            runId = runId,
            correlationId = correlationId,
            ownerId = ownerId,
            startedAtMs = startedAtMs,
            controlDir = controlDir,
            status = status,
        )
    }

    private fun decodeStatus(raw: String): LaunchStatus = when {
        raw == "Launched" -> LaunchStatus.Launched
        raw.startsWith("Refused:") -> LaunchStatus.Refused(raw.removePrefix("Refused:"))
        else -> throw IllegalStateException("unrecognised status: $raw")
    }

    private fun JsonObject.string(field: String): String =
        this[field]?.jsonPrimitive?.content
            ?: throw IllegalStateException("required field $field is missing")

    private fun JsonObject.long(field: String): Long =
        this[field]?.jsonPrimitive?.content?.toLongOrNull()
            ?: throw IllegalStateException("required field $field is missing or not a Long")

    private fun JsonObject.optionalString(field: String): String? =
        this[field]?.jsonPrimitive?.content

    // ------------------------------------------------------------------------
    // 4. Correlation: receipt <-> durable RunCorrelated.
    // ------------------------------------------------------------------------

    /**
     * Assert the LaunchReceipt's [LaunchReceipt.correlationId] is
     * the same as the durable `RunCorrelated` event's correlationId.
     *
     * The R0.3 brief is explicit: the receipt is not a substitute
     * for the durable event. The two MUST agree; a mismatch means
     * one or the other was written by a different code path and the
     * binding is broken.
     */
    fun assertLaunchAndDurableCorrelate(contract: LaunchContract) {
        val receipt = contract.receipt
        val correlated = contract.eventDocument.events
            .filterIsInstance<RunCorrelated>()
            .firstOrNull()
            ?: throw contractViolation(
                "no RunCorrelated event in the durable stream. The receipt's " +
                    "correlationId cannot be bound to a durable event.",
            )
        if (correlated.correlationId.value != receipt.correlationId) {
            throw contractViolation(
                "LaunchReceipt.correlationId=${receipt.correlationId} does not match " +
                    "durable RunCorrelated.correlationId=${correlated.correlationId.value}. " +
                    "The receipt and the durable event must share one correlationId.",
            )
        }
    }

    /**
     * Assert there is exactly one LaunchReceipt per run. The contract
     * is "one per run" — a duplicate means the channel split regressed
     * and emitted on both channels, or the run path emitted twice.
     */
    fun assertNoDuplicateReceipts(receipts: List<LaunchReceipt>) {
        if (receipts.size > 1) {
            throw contractViolation(
                "expected exactly one LaunchReceipt, got ${receipts.size}. " +
                    "A duplicate means the channel split emitted the receipt " +
                    "on both channels or the run path emitted it twice.",
            )
        }
    }

    // ------------------------------------------------------------------------
    // 5. Helpers.
    // ------------------------------------------------------------------------

    private fun jsonObjectOf(raw: String): JsonObject {
        val trimmed = raw.trim()
        val element = try {
            RECEIPT_DECODER.parseToJsonElement(trimmed)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("not valid JSON: ${e.message}")
        }
        return element as? JsonObject
            ?: throw IllegalArgumentException("expected a JSON object, got ${element::class.simpleName}")
    }

    private fun firstNonBlankLine(payload: String): String? =
        payload.lines().firstOrNull { it.isNotBlank() }

    private fun contractViolation(message: String): ContractViolation =
        ContractViolation(message)

    /**
     * A typed exception so callers can choose to assert on it
     * explicitly rather than catch a generic [Throwable]. A
     * testkit that hides its failures inside `try { } catch
     * { emptyList() }` is the exact anti-pattern the R0.3 brief
     * forbids.
     */
    class ContractViolation(message: String) : AssertionError(message)
}
