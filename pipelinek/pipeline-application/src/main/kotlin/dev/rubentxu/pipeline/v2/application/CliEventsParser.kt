package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.identity.EventField

// The `events` verb.
//
// The events journal is the ONLY authority that knows a stage/step NAME or a
// step's kind, so this verb reads the journal path from its own options and
// refuses a name it cannot back.

internal object CliEventsParser {
    /**
     * `pipelinek events --db <path> <runId> [opts]`.
     * Body moved out of the verb's own argument scan; the verb main calls this and renders the
     * typed rejection as `Error: <reason>` (the wire prefix the events verb has always used).
     * The follow-up flags (`--follow`, `--fields`, `--context`, `--tail`) ride on
     * [MainEventsCli.parseEventsInspectionFlags], which stays in the verb because it has its own
     * closed ADT (`EventsInspectionFlag`) the existing tests exercise directly.
    */
    fun parse(args: Array<String>): EventsCliParseResult {
        val scan = when (val outcome = scanOptions(args)) {
            is EventsScanResult.Rejected -> return EventsCliParseResult.Rejected(outcome.reason)
            is EventsScanResult.Scanned -> outcome.options
        }
        val db = scan.values["--db"]
        val runId = scan.runId
        if (db == null || runId == null) {
            return EventsCliParseResult.Rejected(EVENTS_USAGE_TEXT)
        }
        val limitArg = scan.values["--limit"]
        val parsedLimit = if (limitArg != null) {
            val p = limitArg.toIntOrNull()
            if (p == null || p <= 0) {
                return EventsCliParseResult.Rejected("--limit must be a positive integer, got: ${renderValue(limitArg)}")
            }
            p
        } else EVENTS_DEFAULT_LIMIT
        if (scan.projection == EventsRowProjection.Typed &&
            (scan.fieldsWhitelist != null || scan.contextWindow > 0 || scan.tailCount > 0)
        ) {
            return EventsCliParseResult.Rejected(
                "--typed is mutually exclusive with --fields / --context / --tail " +
                    "(the EventField ADT is bound to envelopes, not to DomainEvent)",
            )
        }
        return EventsCliParseResult.Parsed(
            EventsCliArgs(
                db = db,
                runId = runId,
                kind = scan.values["--kind"],
                subjectCanonical = scan.values["--subject"],
                limit = parsedLimit,
                afterCursor = scan.values["--after-cursor"],
                projection = scan.projection,
                follow = scan.follow,
                fieldsWhitelist = scan.fieldsWhitelist,
                contextWindow = scan.contextWindow,
                tailCount = scan.tailCount,
            ),
        )
    }

    /**
     * The options as the operator wrote them, read once.
     *
     * WHICH argument a valued option may consume is [readOptionValue]'s decision,
     * and it is the same decision everywhere in the CLI: `events --db --limit 5 run-1`
     * names the option that would have swallowed the other one, and `events --kind`
     * with no value is refused instead of being dropped and answered as if it had
     * never been written.
     *
     * The refusals here are SENTENCES, because the events verb has always
     * answered in sentences: the shared refusal is turned into words through
     * [toCliExit], the ONE mapping from an error to a sentence, and the verb's
     * own refusals keep their own words.
     */
    private fun scanOptions(args: Array<String>): EventsScanResult {
        val options = EventsScan()
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            when (arg) {
                "--typed" -> options.projection = EventsRowProjection.Typed
                "--follow" -> options.follow = true
                // The five options that carry a plain value. They are the same
                // KIND of option, so they are read together; the shared owner
                // says whether the argument after the token may be consumed.
                "--db", "--kind", "--subject", "--limit", "--after-cursor" -> {
                    when (val read = readEventsValue(arg, args, index + 1)) {
                        is EventsValue.Refusal -> return EventsScanResult.Rejected(read.reason)
                        is EventsValue.Value -> {
                            options.values[arg] = read.value
                            index += 1
                        }
                    }
                }

                "--fields" -> {
                    when (val read = readEventsValue(arg, args, index + 1)) {
                        is EventsValue.Refusal -> return EventsScanResult.Rejected(read.reason)
                        is EventsValue.Value -> {
                            options.fieldsWhitelist = parseEventsFieldsToken(read.value)
                                ?: return EventsScanResult.Rejected(
                                    "--fields has unknown token(s): ${read.value}",
                                )
                            index += 1
                        }
                    }
                }

                "--context" -> {
                    when (val read = readEventsValue(arg, args, index + 1)) {
                        is EventsValue.Refusal -> return EventsScanResult.Rejected(read.reason)
                        is EventsValue.Value -> {
                            options.contextWindow = parseEventsNonNegativeInt(read.value)
                                ?: return EventsScanResult.Rejected(
                                    "--context must be a non-negative Int, got '${read.value}'",
                                )
                            index += 1
                        }
                    }
                }

                "--tail" -> {
                    when (val read = readEventsValue(arg, args, index + 1)) {
                        is EventsValue.Refusal -> return EventsScanResult.Rejected(read.reason)
                        is EventsValue.Value -> {
                            options.tailCount = parseEventsNonNegativeInt(read.value)
                                ?: return EventsScanResult.Rejected(
                                    "--tail must be a non-negative Int, got '${read.value}'",
                                )
                            index += 1
                        }
                    }
                }

                else -> when {
                    arg.startsWith("--") -> return EventsScanResult.Rejected("unknown option: ${renderValue(arg)}")
                    options.runId != null ->
                        return EventsScanResult.Rejected("unexpected extra argument: ${renderValue(arg)}")

                    else -> options.runId = arg
                }
            }
            index++
        }
        return EventsScanResult.Scanned(options)
    }
}

/**
 * The value of an option, or the sentence that refuses it.
 *
 * The events verb answers in sentences, so the shared refusal is turned into
 * words here, through [toCliExit], the ONE mapping from an error to a sentence.
 */
private sealed interface EventsValue {
    data class Value(val value: String) : EventsValue
    data class Refusal(val reason: String) : EventsValue
}

/** The value of the option [arg], read at the argument after it. */
private fun readEventsValue(arg: String, args: Array<String>, valueIndex: Int): EventsValue =
    when (val outcome = readOptionValue(arg, args, valueIndex, EVENTS_OWNED_TOKENS)) {
        is OptionValueOutcome.Present -> EventsValue.Value(outcome.value)
        is OptionValueOutcome.Refused -> EventsValue.Refusal(outcome.error.toCliExit().message)
    }

/**
 * The tokens the events verb owns that the shared vocabulary does not know.
 *
 * The verb's other tokens (`--db`, `--kind`, `--limit`, `--after-cursor`,
 * `--follow`) are in the shared table already, and [readOptionValue] refuses
 * those on its own. These five are the ones only this verb knows, and a value
 * spelled like one of them is refused the same way: `--subject` names a
 * subject, not a path.
 */
private val EVENTS_OWNED_TOKENS: Set<String> =
    setOf("--subject", "--typed", "--fields", "--context", "--tail")

/**
 * The events verb's options, read but not yet interpreted.
 *
 * The five plain values travel in one map because they are the same KIND of
 * option: a token and the argument after it. The three options that carry a
 * MEANING of their own (a field list, two counts) keep their own fields,
 * because their values are not read before they are checked.
 */
private class EventsScan(
    val values: MutableMap<String, String> = mutableMapOf(),
    var projection: EventsRowProjection = EventsRowProjection.Envelope,
    var follow: Boolean = false,
    var fieldsWhitelist: List<EventField>? = null,
    var contextWindow: Int = 0,
    var tailCount: Int = 0,
    /** The run this request is about; null means the operator wrote none. */
    var runId: String? = null,
)

/** The outcome of reading the events verb's options. */
private sealed interface EventsScanResult {
    data class Scanned(val options: EventsScan) : EventsScanResult
    data class Rejected(val reason: String) : EventsScanResult
}

/**
 * `--fields` token parse for the events verb. Kept private on the
 * parser; the closed vocabulary is the parser's contract, not the verb's.
*/
private fun parseEventsFieldsToken(raw: String): List<EventField>? {
    if (raw.isBlank()) return null
    val out = mutableListOf<EventField>()
    for (token in raw.split(',')) {
        val upper = token.trim().uppercase()
        val parsed = when (upper) {
            "SEQ", "SEQUENCE" -> EventField.Sequence
            "KIND" -> EventField.Kind
            "EVENTID", "EVENT_ID" -> EventField.EventId
            "SUBJECT" -> EventField.Subject
            "SOURCE" -> EventField.Source
            else -> return null
        }
        out.add(parsed)
    }
    return out
}

/** Non-negative Int parse for `--context` / `--tail`. */
private fun parseEventsNonNegativeInt(raw: String): Int? {
    val n = raw.toIntOrNull() ?: return null
    if (n < 0) return null
    return n
}

/** Events verb default page size: the legacy default the verb applied when `--limit` was absent. */
private const val EVENTS_DEFAULT_LIMIT: Int = 100

/** The shape of an events request, for an operator who did not write one. */
private const val EVENTS_USAGE_TEXT: String =
    "Usage: pipeline events --db <path> <runId> [--kind K] " +
        "[--subject v1:run:ID|v1:stage:ID:N|...] [--limit N] " +
        "[--after-cursor TOKEN] [--follow] " +
        "[--fields SEQ,KIND,EVENTID,SUBJECT,SOURCE] " +
        "[--context N] [--tail N] " +
        "[--typed]"
