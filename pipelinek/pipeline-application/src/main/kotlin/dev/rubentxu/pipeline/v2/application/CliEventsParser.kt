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
    @Suppress("CyclomaticComplexMethod")
    fun parse(args: Array<String>): EventsCliParseResult {
        var db: String? = null
        var runId: String? = null
        var kind: String? = null
        var subjectCanonical: String? = null
        val limit = EVENTS_DEFAULT_LIMIT
        var afterCursor: String? = null
        var limitArg: String? = null
        var projection: EventsRowProjection = EventsRowProjection.Envelope
        var follow = false
        var fieldsWhitelist: List<EventField>? = null
        var contextWindow = 0
        var tailCount = 0

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when (arg) {
                "--db" -> db = args.getOrNull(++i)
                "--kind" -> kind = args.getOrNull(++i)
                "--subject" -> subjectCanonical = args.getOrNull(++i)
                "--limit" -> limitArg = args.getOrNull(++i)
                "--after-cursor" -> afterCursor = args.getOrNull(++i)
                "--typed" -> projection = EventsRowProjection.Typed
                "--follow" -> follow = true
                "--fields" -> {
                    val raw = args.getOrNull(++i)
                        ?: return EventsCliParseResult.Rejected("--fields requires a value")
                    val parsed = parseEventsFieldsToken(raw)
                        ?: return EventsCliParseResult.Rejected("--fields has unknown token(s): $raw")
                    fieldsWhitelist = parsed
                }
                "--context" -> {
                    val raw = args.getOrNull(++i)
                        ?: return EventsCliParseResult.Rejected("--context requires a value")
                    val n = parseEventsNonNegativeInt(raw)
                        ?: return EventsCliParseResult.Rejected(
                            "--context must be a non-negative Int, got '$raw'",
                        )
                    contextWindow = n
                }
                "--tail" -> {
                    val raw = args.getOrNull(++i)
                        ?: return EventsCliParseResult.Rejected("--tail requires a value")
                    val n = parseEventsNonNegativeInt(raw)
                        ?: return EventsCliParseResult.Rejected(
                            "--tail must be a non-negative Int, got '$raw'",
                        )
                    tailCount = n
                }
                else -> when {
                    arg.startsWith("--") -> return EventsCliParseResult.Rejected("unknown option: $arg")
                    runId != null -> return EventsCliParseResult.Rejected("unexpected extra argument: $arg")
                    else -> runId = arg
                }
            }
            i++
        }
        if (db == null || runId == null) {
            return EventsCliParseResult.Rejected(
                "Usage: pipeline events --db <path> <runId> [--kind K] " +
                    "[--subject v1:run:ID|v1:stage:ID:N|...] [--limit N] " +
                    "[--after-cursor TOKEN] [--follow] " +
                    "[--fields SEQ,KIND,EVENTID,SUBJECT,SOURCE] " +
                    "[--context N] [--tail N] " +
                    "[--typed]",
            )
        }
        val parsedLimit = if (limitArg != null) {
            val p = limitArg.toIntOrNull()
            if (p == null || p <= 0) {
                return EventsCliParseResult.Rejected("--limit must be a positive integer, got: $limitArg")
            }
            p
        } else limit
        if (projection == EventsRowProjection.Typed &&
            (fieldsWhitelist != null || contextWindow > 0 || tailCount > 0)
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
                kind = kind,
                subjectCanonical = subjectCanonical,
                limit = parsedLimit,
                afterCursor = afterCursor,
                projection = projection,
                follow = follow,
                fieldsWhitelist = fieldsWhitelist,
                contextWindow = contextWindow,
                tailCount = tailCount,
            ),
        )
    }
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
