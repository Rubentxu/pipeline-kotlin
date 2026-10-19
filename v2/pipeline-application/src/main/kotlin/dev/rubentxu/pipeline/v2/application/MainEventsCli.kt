package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.events.EventRecordRead
import dev.rubentxu.pipeline.v2.events.UndecodableReason
import dev.rubentxu.pipeline.v2.events.durable.JsonEventLog
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.events.identity.EnvelopeCodec
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventField
import dev.rubentxu.pipeline.v2.events.identity.EventHistoryReader
import dev.rubentxu.pipeline.v2.events.identity.EventInspection
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.events.identity.PipelineEventEnvelope

/**
 * The events CLI now participates in the unified observability surface.
 *   pipeline events --db <path> <runId>
 *                   [--kind K] [--subject v1:kind:ID...|v1:stage:ID:N|...]
 *                   [--limit N] [--after-cursor TOKEN]
 *                   [--follow]
 *                   [--fields SEQ,KIND,EVENTID,SUBJECT,SOURCE]
 *                   [--context N] [--tail N]
 *                   [--typed]
 * What the WU adds on top of the original surface
 *   * `--follow` reuses the event-lane poll loop (one read per round trip) so a caller can watch
 *     a run finish without re-issuing `--after-cursor`. The typed projection also gets it; the
 *     follow terminates on `RunFinished` or on the JVM shutdown signal.
 * * `--fields` is a closed ADT of envelope fields (5 cases, `EventField` from) and
 *     produces a header line + one narrowed line per envelope.
 *   * `--context N` builds a context window around envelopes whose `kind` matches the `--kind`
 *     filter (or around every envelope when no filter is set): N preceding + the match + N following,
 *     deduped. The pure transform is `EventInspection.contextWindow`.
 *   * `--tail N` keeps the last N envelopes. The pure transform is `EventInspection.tail`.
 * What this slice is NOT:
 * * NOT a wholesale fold of the events parser into `CliParser.parseObservation` (territory)
 *     The local `while` loop stays; a future slice can promote the option list when the four
 *     verbs share a typed option table. The events-only flags (`--subject`, `--after-cursor`,
 *     `--typed`) are deliberately NOT in `CliParser`.
 * * NOT a wakeup-based follow. The follow polls the durable authority for its position
 * Wakeups are still unimplemented (_CLI_GAP_ANALYSIS)
 *   * NOT a typed-`EventReadRefusal` ADT. Refusals stay on stderr as `evt-refusal-v1:...`; the
 * Events-only refused-record carrier is `EventRecordRead.Undecodable` (territory)
 * `--typed` × `--fields / --context / --tail` are mutually exclusive
 * `EventField` is bound to `PipelineEventEnvelope` (the wire envelope, not the typed
 * `DomainEvent`). `--typed` produces `JsonEventLog.encodeOne(DomainEvent)` lines whose
 * shape is a different closed record. Projecting or windowing typed lines would either mean
 * re-deriving an envelope nobody read (lossy) or admitting a second field ADT. The verb
 * refuses `--typed` together with any of `--fields / --context / --tail` so the caller's
 * intent is not silently narrowed.
*/
/**
 * TooManyFunctions (16) and CyclomaticComplexMethod are suppressed at the
 * Object and function level respectively. moved the option table to
 * [CliParser.parseEvents], which now owns the parser cyclomatic count; this verb still carries
 * eleven CLI flags (--db, --kind, --subject, --limit, --after-cursor, --typed, --follow,
 * Fields, --context, --tail, plus the unknown-option arm) and every helper carries a
 * real concern (the [parseEventsInspectionFlags] sub-parser, the follow loop, the typed branch,
 * the four EventInspection transforms, the exit-code ADT, the refusal/continuation reporters).
 * The complexity is structural to the verb surface, not a quality signal (the same
 * justification `MainInspectCli.parseInspect` documents).
*/
@Suppress("TooManyFunctions")
object MainEventsCli {

    /**
     * The closed ADT of NEW flags added to the events CLI.
     * Distinct from the existing CLI surface (--db, --kind, --subject, --limit, --after-cursor,
     * --typed), which is parsed inline in [main]. Each case here carries the typed payload the
     * rest of the read needs: a follow decision, a field whitelist, a window size, or a tail count.
     * Adding a sixth case is a compile-time break for every caller of [parseEventsInspectionFlags].
*/
    sealed class EventsInspectionFlag {
        /** `--follow` — keep reading the event lane until `RunFinished` or the JVM shuts down. */
        data object Follow : EventsInspectionFlag()

        /**
         * Closed ADT of envelope fields. Empty means a refusal (the user
         * typed `--fields` with no value).
*/
        data class Fields(val fields: List<EventField>) : EventsInspectionFlag()

        /** `--context N` — N preceding + match + N following, deduped, around `--kind` matches. */
        data class Context(val n: Int) : EventsInspectionFlag()

        /** `--tail N` — keep the last N envelopes in the read. */
        data class Tail(val n: Int) : EventsInspectionFlag()
    }

    /**
     * Typed outcome of parsing the / inspection flags
     * Kept separate from the verb's main usage error channel so the existing /
     * contract (`Error: ...` on stderr, exit 2) keeps one shape. A [EventsInspectionFlag] is
     * recognised, then validated; a [EventsInspectionParseResult.Rejected] carries the typed
     * reason so the caller renders it as `Error: ...` exactly once.
*/
    sealed class EventsInspectionParseResult {
        data class Parsed(val flags: List<EventsInspectionFlag>) : EventsInspectionParseResult()
        data class Rejected(val reason: String) : EventsInspectionParseResult()
    }

    /**
     * Parse the new / flags from a sub-list of args. `--follow` consumes nothing
     * the valued flags (`--fields`, `--context`, `--tail`) consume the next token.
     * Returns a closed ADT of [EventsInspectionFlag] values plus any typed rejection. The caller
     * is responsible for turning the rejection into the same `Error: ...` stderr + exit 2
     * shape the rest of the parser uses (do not duplicate the prefix here).
*/
    fun parseEventsInspectionFlags(args: Array<String>): EventsInspectionParseResult {
        val out = mutableListOf<EventsInspectionFlag>()
        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "--follow" -> {
                    out.add(EventsInspectionFlag.Follow)
                    i++
                }
                "--fields" -> {
                    val raw = args.getOrNull(i + 1)
                        ?: return EventsInspectionParseResult.Rejected("--fields requires a value")
                    val parsed = parseFieldsToken(raw)
                        ?: return EventsInspectionParseResult.Rejected(
                            "--fields has unknown token(s): $raw",
                        )
                    out.add(EventsInspectionFlag.Fields(parsed))
                    i += 2
                }
                "--context" -> {
                    val raw = args.getOrNull(i + 1)
                        ?: return EventsInspectionParseResult.Rejected("--context requires a value")
                    val n = parseNonNegativeInt(raw)
                        ?: return EventsInspectionParseResult.Rejected(
                            "--context must be a non-negative Int, got '$raw'",
                        )
                    out.add(EventsInspectionFlag.Context(n))
                    i += 2
                }
                "--tail" -> {
                    val raw = args.getOrNull(i + 1)
                        ?: return EventsInspectionParseResult.Rejected("--tail requires a value")
                    val n = parseNonNegativeInt(raw)
                        ?: return EventsInspectionParseResult.Rejected(
                            "--tail must be a non-negative Int, got '$raw'",
                        )
                    out.add(EventsInspectionFlag.Tail(n))
                    i += 2
                }
                else -> {
                    // Not a / flag. Skip one position so the caller (which has the
                    // original while loop) can recognise it as its own. The caller is the
                    // single source of truth for unknown-option refusal.
                    i++
                }
            }
        }
        return EventsInspectionParseResult.Parsed(out)
    }

    /**
     * Parse `--fields` into a closed [EventField] list. Comma-separated, case-insensitive.
     * Returns null on the first unknown token so the caller names it.
*/
    private fun parseFieldsToken(raw: String): List<EventField>? {
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

    /** Non-negative Int. Rejecting negative (or `0` is allowed; the transforms handle that). */
    private fun parseNonNegativeInt(raw: String): Int? {
        val n = raw.toIntOrNull() ?: return null
        if (n < 0) return null
        return n
    }

    fun main(args: Array<String>): Int {
        // Admission is [CliParser.parseEvents]. The wire prefix `Error:`
        // (the / contract for events argument refusals) is preserved here in the
        // translation, exactly as before. Refusal of the data-plane read (a different concern, the
        // one [MainEventsCli.reportRefusals] emits on stdout / stderr as `evt-refusal-v1:` /
        // `evt-refusals-v1:`) is downstream of this and unchanged.
        val parsed = CliParser.parseEvents(args)
        if (parsed is EventsCliParseResult.Rejected) {
            System.err.println("Error: ${parsed.reason}")
            return 2
        }
        val p = (parsed as EventsCliParseResult.Parsed).args
        return runWithArgs(p)
    }

    /**
     * Run the typed read after [CliParser.parseEvents] has succeeded: validate the store path, decode
     * the cursor, build the query, then dispatch to the follow loop, the typed projection, or
     * the envelope projection.
*/
    private fun runWithArgs(p: EventsCliArgs): Int {
        // This verb had its own copy of the admission rule, written out here and once more in
        // MainEventsVerifyCli. Three reactions to one condition had accumulated across the verbs;
        // the decision now lives in `admitEventStore` and only the exit code stays here.
        val dbAdmission = admitEventStore(p.db, EventStorePurpose.Read)
        if (dbAdmission is EventStoreAdmission.Refused) {
            System.err.println(dbAdmission.refusal.render())
            return 2
        }
        val cursor = p.afterCursor?.let { token ->
            EventCursor.decode(token) ?: run {
                System.err.println("Error: invalid cursor token: $token")
                return 2
            }
        }
        // The token carries its run id precisely so it cannot be read as another run's position. A
        // cursor from run A applied to run B used to be accepted and would have skipped B's rows.
        if (cursor != null && cursor.runId != p.runId) {
            System.err.println("Error: cursor belongs to run '${cursor.runId}', not to '${p.runId}'")
            return 2
        }
        val store = (dbAdmission as EventStoreAdmission.Admitted).store
        try {
            val reader = EventHistoryReader(store)
            val run = ResourceRefs.run(p.runId)
            val query: EventQuery = when {
                p.kind != null -> EventQuery.ByKind(p.kind)
                p.subjectCanonical != null ->
                    EventQuery.BySubject(
                        decodeSubject(p.subjectCanonical)
                            ?: run {
                                System.err.println(
                                    "Error: cannot parse subject ref: ${p.subjectCanonical}",
                                )
                                return 2
                            },
                    )
                else -> EventQuery.All
            }
            // `--follow` is a different execution shape: poll the event lane
            // until `RunFinished` (or JVM shutdown). Routing it BEFORE the drain keeps the
            // typed-vs-envelope check in one place.
            if (p.follow) {
                return runFollow(
                    store = store,
                    run = run,
                    query = query,
                    cursor = cursor,
                    limitPerPage = p.limit,
                    runId = p.runId,
                    projection = p.projection,
                )
            }
            val outcome = when (p.projection) {
                EventsRowProjection.Envelope -> EventPageDrain.drain(reader, run, query, cursor, p.limit)
                EventsRowProjection.Typed -> return reportTyped(store, run, query, cursor, p.limit, p.runId)
            }
            when (outcome) {
                is EventPageDrain.Outcome.Answered -> {
                    emitEnvelopes(
                        envelopes = outcome.page.envelopes,
                        contextWindow = p.contextWindow,
                        kindFilter = p.kind,
                        tailCount = p.tailCount,
                        fields = p.fieldsWhitelist,
                    )
                    reportRefusals(p.runId, outcome.page.refusals)
                    reportContinuation(outcome.page.nextCursor)
                }

                is EventPageDrain.Outcome.Stalled -> {
                    // The store said there was more and the continuation stopped moving. Answering
                    // as if history ended would be a silent truncation, so it is named on the wire.
                    emitEnvelopes(
                        envelopes = outcome.page.envelopes,
                        contextWindow = p.contextWindow,
                        kindFilter = p.kind,
                        tailCount = p.tailCount,
                        fields = p.fieldsWhitelist,
                    )
                    reportRefusals(p.runId, outcome.page.refusals)
                    reportContinuation(outcome.page.nextCursor)
                    System.err.println(
                        "evt-stalled-v1:${p.runId}:${outcome.page.nextCursor?.lastSequence ?: 0L}: " +
                            "the store reported more rows and the continuation did not advance; " +
                            "history past this point was NOT read",
                    )
                }
            }
            return exitCodeFor(outcome)
        } finally {
            store.close()
        }
    }

    /**
     * Apply the [EventInspection] transforms in their order of composition:
     *  1. `contextWindow(kind, n, n)` when `--context` is set, around `--kind` matches (or every
     *     envelope when no `--kind` was given).
     *  2. `tail(n)` when `--tail` is set, on the result of step 1.
     *  3. `projectFields(fields)` when `--fields` is set, on the result of step 2.
     * Each step is a pure transform over the previous list. The composition is right-to-left
     * in the pure sense: a `tail` of a `contextWindow` keeps the LAST N envelopes of the windowed
     * view, which is what a caller asking for both meant (a tail of the un-windowed set would
     * drift the context off the records the caller cared about).
*/
    private fun emitEnvelopes(
        envelopes: List<PipelineEventEnvelope>,
        contextWindow: Int,
        kindFilter: String?,
        tailCount: Int,
        fields: List<EventField>?,
    ) {
        // 1. context window. With no --kind to anchor on, the window is the identity transform;
        // we keep `envelopes` untouched and let the caller narrow via --tail / --fields.
        var current: List<PipelineEventEnvelope> = if (contextWindow > 0 && kindFilter != null) {
            EventInspection.contextWindow(envelopes, kindFilter, contextWindow, contextWindow)
        } else {
            envelopes
        }
        // 2. tail
        if (tailCount > 0) {
            current = EventInspection.tail(current, tailCount)
        }
        // 3. fields projection (emits header + body lines, in `EventInspection.projectFields` shape)
        if (fields != null) {
            EventInspection.projectFields(current, fields).forEach { println(it) }
        } else {
            current.forEach { println(EnvelopeCodec.encode(it)) }
        }
    }

    /**
     * `--follow` poll loop. Reads pages through the same drain used by the
     * one-shot path, advances the cursor, and sleeps briefly when the store has caught up.
     * Terminates on `RunFinished` (the kind the events domain closes a run with) or on the
     * JVM shutdown signal (so a Ctrl-C does not leave a polling thread behind).
     * The follow is the same shape for both projections: one page per round trip, the same
     * drain, the same exit-status mapping. `--fields / --context / --tail` were already
     * rejected on top of `--typed` at admission, so the projection is the envelope here
     * (the typed branch is a follow over [EventPageDrain.drainTyped], kept on the same page
     * shape, with the typed codec).
*/
    private fun runFollow(
        store: SqliteEventStore,
        run: ResourceRef,
        query: EventQuery,
        cursor: EventCursor?,
        limitPerPage: Int,
        runId: String,
        projection: EventsRowProjection,
    ): Int {
        val reader = EventHistoryReader(store)
        var currentCursor = cursor
        var runFinished = false
        // JVM-shutdown hook so a SIGINT/SIGTERM does not leave a poll thread behind. The hook
        // flips a flag; the loop checks the flag between rounds.
        val shutdown = java.util.concurrent.atomic.AtomicBoolean(false)
        Runtime.getRuntime().addShutdownHook(Thread {
            shutdown.set(true)
            // closing the store here lets the in-flight `read` fail fast instead of blocking
            // until the daemon takes it down.
            try { store.close() } catch (_: Exception) { /* already closed */ }
        })

        while (!shutdown.get() && !runFinished) {
            when (projection) {
                EventsRowProjection.Envelope -> {
                    when (val outcome = EventPageDrain.drain(reader, run, query, currentCursor, limitPerPage)) {
                        is EventPageDrain.Outcome.Answered -> {
                            outcome.page.envelopes.forEach { envelope ->
                                println(EnvelopeCodec.encode(envelope))
                                if (envelope.kind == "RunFinished") runFinished = true
                            }
                            reportRefusals(runId, outcome.page.refusals)
                            val next = outcome.page.nextCursor
                            if (next != null) {
                                reportContinuation(next)
                                currentCursor = next
                            } else {
                                // store said no more rows; nothing to follow.
                                runFinished = true
                            }
                        }
                        is EventPageDrain.Outcome.Stalled -> {
                            outcome.page.envelopes.forEach { envelope ->
                                println(EnvelopeCodec.encode(envelope))
                                if (envelope.kind == "RunFinished") runFinished = true
                            }
                            reportRefusals(runId, outcome.page.refusals)
                            reportContinuation(outcome.page.nextCursor)
                            System.err.println(
                                "evt-stalled-v1:$runId:${outcome.page.nextCursor?.lastSequence ?: 0L}: " +
                                    "the store reported more rows and the continuation did not advance; " +
                                    "history past this point was NOT read",
                            )
                            return exitCodeFor(outcome)
                        }
                    }
                }
                EventsRowProjection.Typed -> {
                    when (val outcome = EventPageDrain.drainTyped(store, run, query, currentCursor, limitPerPage)) {
                        is EventPageDrain.TypedOutcome.Answered -> {
                            outcome.page.events.forEach { event ->
                                println(JsonEventLog.encodeOne(event))
                                if (event.kind == "RunFinished") runFinished = true
                            }
                            reportRefusals(runId, outcome.page.refusals)
                            val next = outcome.page.nextCursor
                            if (next != null) {
                                reportContinuation(next)
                                currentCursor = next
                            } else {
                                runFinished = true
                            }
                        }
                        is EventPageDrain.TypedOutcome.Stalled -> {
                            outcome.page.events.forEach { println(JsonEventLog.encodeOne(it)) }
                            reportRefusals(runId, outcome.page.refusals)
                            reportContinuation(outcome.page.nextCursor)
                            System.err.println(
                                "evt-stalled-v1:$runId:${outcome.page.nextCursor?.lastSequence ?: 0L}: " +
                                    "the store reported more rows and the continuation did not advance; " +
                                    "history past this point was NOT read",
                            )
                            return typedExitCodeFor(outcome)
                        }
                    }
                }
            }
            if (!runFinished && !shutdown.get()) {
                try {
                    Thread.sleep(FOLLOW_POLL_MILLIS)
                } catch (_: InterruptedException) {
                    // Shutdown hook may have set the flag; the loop will exit on the next check.
                    Thread.currentThread().interrupt()
                }
            }
        }
        // Follow completed: 0 if RunFinished was reached, 2 if interrupted (the caller can
        // distinguish by `evt-stalled-v1` on stderr, which the loop emits on a stall).
        return if (shutdown.get()) 2 else 0
    }

    /**
     * The `--typed` branch: the same read, the same query, the same cursor and the same status
     * contract as the envelope branch, printed through the typed codec instead.
     * It is a separate method rather than a branch inside the envelope loop because the two
     * projections genuinely carry different things: [dev.rubentxu.pipeline.v2.events.identity.PipelineEventEnvelope]
     * has identity, and the [dev.rubentxu.pipeline.v2.events.DomainEvent] has the semantic fields the
     * envelope dropped. What they share — refusal reporting, continuation reporting, the exit status —
     * is the part that must not fork, so it calls the SAME [reportRefusals] and [reportContinuation]
     * the envelope path uses and returns the SAME [exitCodeFor] mapping by routing through
     * [EventPageDrain.drainTyped]'s outcome.
*/
    private fun reportTyped(
        store: dev.rubentxu.pipeline.v2.events.EventStore,
        run: ResourceRef,
        query: EventQuery,
        cursor: EventCursor?,
        limit: Int,
        runId: String,
    ): Int {
        val outcome = EventPageDrain.drainTyped(store, run, query, cursor, limit)
        val page = outcome.page
        page.events.forEach { println(JsonEventLog.encodeOne(it)) }
        reportRefusals(runId, page.refusals)
        reportContinuation(page.nextCursor)
        if (outcome is EventPageDrain.TypedOutcome.Stalled) {
            System.err.println(
                "evt-stalled-v1:$runId:${page.nextCursor?.lastSequence ?: 0L}: " +
                    "the store reported more rows and the continuation did not advance; " +
                    "history past this point was NOT read",
            )
        }
        // Routed through [typedExitCodeFor], which states the SAME contract as [exitCodeFor] over the typed
        // ADT. A literal `0` happened to agree with it, but it did not INHERIT it: a function that is
        // exhaustive over an ADT is what forces a new case to choose a status, and `return 0` has no
        // such forcing function. Two overloads rather than a conversion between them: converting a
        // typed page into an envelope-shaped one would mean fabricating envelopes the caller never
        // reads, which is wasted work in the exit-code path of a command whose whole point is not to
        // lose information.
        return typedExitCodeFor(outcome)
    }

    /**
     * The exit-code contract, stated once and total over [EventPageDrain.Outcome].
     * ## Why this exists rather than a bare `return 0`
     * The command returned `0` on both [EventPageDrain.Outcome.Answered] and
     * [EventPageDrain.Outcome.Stalled], and nothing recorded that as a decision. A `Stalled` page
     * means the store claimed more rows existed and the continuation could not reach them; the
     * diagnostic goes to stderr as a structured token and the status is still `0`. That is the
     * intended contract (observation is read-only and reports what it saw), but it was an accident
     * Of the code rather than a pinned contract. Routing both cases through one exhaustive
     * function makes the value a decision: adding a case to `Outcome` forces a status to be chosen,
     * and a test asserts both cells.
     * ## Contract
     * ```text
     * 0     the observation completed. Whether it carried refusals (Answered) or the store
     *       reported more rows than were reachable (Stalled), the read did what was asked and
     *       reported it on stderr. An unknown run and a filter that matches nothing are also 0.
     * 2     the command was NOT run: usage/argument error. Missing --db or <runId>, an unknown
     *       --option, an extra positional, a non-positive/non-numeric --limit, an invalid or
     *       foreign cursor, or a db path that does not exist.
     * other an unhandled exception escaped main; the JVM produced that status, this command did
     *       not choose it. That is a defect, not a designed outcome.
     * ```
*/
    internal fun exitCodeFor(outcome: EventPageDrain.Outcome): Int = when (outcome) {
        is EventPageDrain.Outcome.Answered -> 0
        // The stall is reported on stderr (evt-stalled-v1:...); the process still completed an
        // observation and must not be read as a failure by a caller that only looks at the status.
        is EventPageDrain.Outcome.Stalled -> 0
    }

    /**
     * The SAME exit-code contract as [exitCodeFor], stated over the typed outcome.
     * It is a second statement of one rule rather than a second rule. The two ADTs differ in the
     * payload they carry — [EventPageDrain.TypedPage] holds `DomainEvent`s where [EventPageDrain.Page]
     * holds envelopes — and nothing else: both are `Answered` or `Stalled`, and the status depends
     * only on which. Duplicating the two cells is the honest encoding of that. Converting one outcome
     * into the other to reuse a single function would either fabricate envelopes nobody reads or
     * weaken [exitCodeFor] to take a flag, and both trade a real property (exhaustiveness over a
     * closed ADT) for a cosmetic one (a shorter file).
     * A change to one that is not mirrored in the other is a defect in the contract, not in the code.
*/
    internal fun typedExitCodeFor(outcome: EventPageDrain.TypedOutcome): Int = when (outcome) {
        is EventPageDrain.TypedOutcome.Answered -> 0
        // Same reasoning as the envelope branch: the stall is named on stderr as `evt-stalled-v1:...`
        // and the observation still completed, so a status-only consumer must not read it as failure.
        is EventPageDrain.TypedOutcome.Stalled -> 0
    }

    /** One line per refusal, then a count, so "were there any" is a single-token question. */
    private fun reportRefusals(runId: String, refusals: List<EventRecordRead.Undecodable>) {
        if (refusals.isEmpty()) return
        refusals.forEach { refusal ->
            val kind = refusal.kind?.let { escape(it) } ?: ABSENT
            System.err.println("evt-refusal-v1:${escape(runId)}:${refusal.sequence}:${reasonTag(refusal.reason)}:$kind")
        }
        System.err.println("evt-refusals-v1:${escape(runId)}:${refusals.size}")
    }

    /**
     * The store's own position, encoded by [EventCursor.encode] rather than by string
     * interpolation here. This command both emits and accepts that token, and a hand-built copy of
     * its wire form is a second place that can disagree with the one that parses it.
*/
    private fun reportContinuation(nextCursor: EventCursor?) {
        val cursor = nextCursor ?: return
        System.err.println(cursor.encode())
    }

    /** Exhaustive over the closed reason type: a new case must be named here, not defaulted. */
    private fun reasonTag(reason: UndecodableReason): String = when (reason) {
        is UndecodableReason.MalformedPayload -> "malformedPayload"
        is UndecodableReason.UnknownKind -> "unknownKind"
    }

    /** Same escaping [EventCursor.encode] uses, so a run id with a colon cannot shift the fields. */
    private fun escape(text: String): String = java.net.URLEncoder.encode(text, Charsets.UTF_8)

    /**
     * Parses a canonical ResourceRef text (`v1:kind:seg0:seg1...`) back to a
     * ResourceRef by re-joining segments after the version/kind prefix.
*/
    private fun decodeSubject(text: String): ResourceRef? {
        val colonParts = text.split(":", limit = 3)
        if (colonParts.size < 3 || colonParts[0] != "v1") return null
        return try {
            val kind = dev.rubentxu.pipeline.v2.domain.identity.ResourceKind.valueOf(colonParts[1].uppercase())
            // Accept the full canonical text only (copied from an envelope):
            // "v1:<kind>:<seg>/<seg>/..." must reconstruct the identical
            // canonical text — no shorthand inference.
            ResourceRef(kind, colonParts[2].split("/"))
        } catch (_: Exception) {
            null
        }
    }
}

/** Stands in for an absent value on a `:`-separated wire line, rather than printing nothing. */
private const val ABSENT = "-"

/**
 * Follow poll interval, in milliseconds. The same value
 * `MainObserveCli.FOLLOW_IDLE_MILLIS` uses; the events follow reads the same durable
 * Authority and has the same latency budget. Wakeup-based follow is territory and
 * is not in this slice.
*/
private const val FOLLOW_POLL_MILLIS: Long = 25L
