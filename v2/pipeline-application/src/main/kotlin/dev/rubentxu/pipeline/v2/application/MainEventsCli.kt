package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.events.EventRecordRead
import dev.rubentxu.pipeline.v2.events.UndecodableReason
import dev.rubentxu.pipeline.v2.events.durable.JsonEventLog
import dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore
import dev.rubentxu.pipeline.v2.events.identity.EnvelopeCodec
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventHistoryReader
import dev.rubentxu.pipeline.v2.events.identity.EventQuery

/**
 * EVT-2 minimal CLI for structured local history inspection:
 *
 *   pipeline events --db <path> <runId> [--kind K] [--subject KIND:ID...] [--limit N] [--after-cursor TOKEN]
 *
 * Reads history through the EventHistory port (never parses stdout, never
 * touches the console transcript). Output = one JSON envelope per line.
 * History != stdout rule: this command's stdout is the CLI's query output;
 * no process streams are involved.
 *
 * ## S5.4 — the refusal is on the wire, not merely in the type
 *
 * This used to read `history(run, query).filter { cursor == null || it.sequence > cursor.lastSequence }
 * .take(limit)`.
 *
 * **What that failure actually was, measured and not assumed.** The first draft of this note claimed
 * the unreadable row vanished silently. D-M5 says otherwise: restoring the `history` call and running
 * the tests shows `history` → `EventStore.eventsFor` THROWING `UndecodableEventRecordException` at the
 * unreadable row. `Sequence<DomainEvent>` has no case for a refusal, so the store fails closed there.
 *
 * So the real defect was not a quiet omission. It was that **an unreadable row destroyed the whole
 * observation**: an unhandled exception out of `main`, a stack trace on stderr, no history at all, and
 * an exit status produced by the JVM rather than by this command. An external observer gets a crash
 * where it asked for facts, and gets none of the forty readable rows that came before row 41. Naming
 * the row is not enough when the answer to "what happened" is "the process fell over".
 *
 * The second defect is real but was **unreachable**, which is worth saying because it would be easy to
 * credit it: the continuation was `envelopes.lastOrNull()?.sequence`, so a page ending in a refusal
 * would have produced a cursor pointing before it. The throw above always fired first. That cursor
 * property is proved where it is reachable — at the reader, in `EventPageDrainTest` — rather than
 * claimed here.
 *
 * So the read now goes through [EventPageDrain], which can only call `EventTail.readAfter`, and a
 * refusal is emitted on **stderr** as its own line:
 *
 * ```
 * evt-refusal-v1:<runId>:<sequence>:<malformedPayload|unknownKind>:<kind or ->
 * ```
 *
 * **stderr, not stdout, and the reason is the stdout contract.** stdout is documented as one JSON
 * envelope per line, and a refusal is not an envelope: it is the absence of an interpretation, and
 * putting a non-envelope there would make that sentence untrue for every consumer that parses the
 * stream. stderr already carries this command's non-envelope control output — the cursor — and an
 * external observer already has to read it to resume. A consumer that redirects stdout to a file and
 * ignores stderr is the consumer that cannot see refusals, and that is a choice it now has to make
 * visibly.
 *
 * The line is machine-readable, not prose: the run id and the kind are percent-escaped the same way
 * [EventCursor.encode] escapes the run id, because a refusal line is parsed by a program and an
 * unescaped colon inside a run id would silently shift the fields. The reason is rendered as its two
 * exhaustive tags rather than the decoder's free-text `detail`, which is not a wire format.
 *
 * Exit code stays 0 when a page carries refusals. The read did what was asked and reported it; a
 * non-zero code here would say "the command failed", which would be its own kind of untruth — and it
 * would be a DIFFERENT untruth from the one fixed above, where the status came from an unhandled
 * exception rather than from a decision. An unknown run is likewise a successful empty read:
 * observation stays read-only.
 */
object MainEventsCli {

    /**
     * What the command prints per row: the identity projection, or the typed event itself.
     *
     * This is a closed pair on purpose. It is a choice of CODEC, not of reader: both cases read the
     * same [EventPageDrain.Outcome] through the same store, with the same query, the same cursor and
     * the same exit status. Adding a third projection must be a decision here rather than a flag
     * threaded through the drain, because a projection is the only thing `--typed` is allowed to
     * change and the reason it cannot be allowed to change anything else.
     */
    private sealed interface RowProjection {
        /** The V1 envelope wire form. Byte-for-byte unchanged by the existence of `--typed`. */
        data object Envelope : RowProjection

        /**
         * The stored [dev.rubentxu.pipeline.v2.events.DomainEvent], one JSON object per line.
         *
         * This is the projection that carries `stageName`, `stepName`, `stepType` and `outcome`.
         * Those values are not reconstructed: they are read from the record, because the envelope
         * that the same drain also produces does not hold them.
         */
        data object Typed : RowProjection
    }

    fun main(args: Array<String>): Int {
        var db: String? = null
        var runId: String? = null
        var kind: String? = null
        var subjectCanonical: String? = null
        var limit = DEFAULT_LIMIT
        var afterCursor: String? = null
        var limitArg: String? = null
        var projection: RowProjection = RowProjection.Envelope

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when (arg) {
                "--db" -> db = args.getOrNull(++i)
                "--kind" -> kind = args.getOrNull(++i)
                "--subject" -> subjectCanonical = args.getOrNull(++i)
                "--limit" -> limitArg = args.getOrNull(++i)
                "--after-cursor" -> afterCursor = args.getOrNull(++i)
                "--typed" -> projection = RowProjection.Typed
                else -> when {
                    // A leading `--` that is not a known option is a typo or a newer flag from a
                    // different build. Ignoring it would run a different command than the one the
                    // caller typed and still exit 0, which is the failure this rejects. AUD-04.
                    arg.startsWith("--") -> {
                        System.err.println("Error: unknown option: $arg")
                        return 2
                    }
                    // A second positional is not a second run id: the command reads ONE run. It
                    // used to be dropped on the floor and the first positional silently kept.
                    // AUD-04.
                    runId != null -> {
                        System.err.println("Error: unexpected extra argument: $arg")
                        return 2
                    }
                    else -> runId = arg
                }
            }
            i++
        }

        if (db == null || runId == null) {
            System.err.println(
                "Usage: pipeline events --db <path> <runId> [--kind K] " +
                    "[--subject v1:run:ID|v1:stage:ID:N|...] [--limit N] " +
                    "[--after-cursor TOKEN] [--typed]",
            )
            return 2
        }

        // A flag that does not parse must not become the default. `--limit abc` used to read as 100,
        // which is the same shape as a command that silently did something other than what was asked.
        if (limitArg != null) {
            val parsed = limitArg.toIntOrNull()
            if (parsed == null || parsed <= 0) {
                System.err.println("Error: --limit must be a positive integer, got: $limitArg")
                return 2
            }
            limit = parsed
        }

        if (!java.nio.file.Files.exists(java.nio.file.Path.of(db))) {
            System.err.println("Error: db not found: $db")
            return 2
        }

        val cursor = afterCursor?.let { token ->
            EventCursor.decode(token) ?: run {
                System.err.println("Error: invalid cursor token: $token")
                return 2
            }
        }
        // The token carries its run id precisely so it cannot be read as another run's position. A
        // cursor from run A applied to run B used to be accepted and would have skipped B's rows.
        if (cursor != null && cursor.runId != runId) {
            System.err.println("Error: cursor belongs to run '${cursor.runId}', not to '$runId'")
            return 2
        }

        val store = SqliteEventStore(db)
        try {
            val reader = EventHistoryReader(store)
            val run = ResourceRefs.run(runId)
            val query: EventQuery = when {
                kind != null -> EventQuery.ByKind(kind)
                subjectCanonical != null ->
                    EventQuery.BySubject(
                        decodeSubject(subjectCanonical)
                            ?: run {
                                System.err.println("Error: cannot parse subject ref: $subjectCanonical")
                                return 2
                            },
                    )
                else -> EventQuery.All
            }

            val outcome = when (projection) {
                RowProjection.Envelope -> EventPageDrain.drain(reader, run, query, cursor, limit)
                RowProjection.Typed -> return reportTyped(store, run, query, cursor, limit, runId)
            }
            when (outcome) {
                is EventPageDrain.Outcome.Answered -> {
                    outcome.page.envelopes.forEach { println(EnvelopeCodec.encode(it)) }
                    reportRefusals(runId, outcome.page.refusals)
                    reportContinuation(outcome.page.nextCursor)
                }

                is EventPageDrain.Outcome.Stalled -> {
                    // The store said there was more and the continuation stopped moving. Answering
                    // as if history ended would be a silent truncation, so it is named on the wire.
                    outcome.page.envelopes.forEach { println(EnvelopeCodec.encode(it)) }
                    reportRefusals(runId, outcome.page.refusals)
                    reportContinuation(outcome.page.nextCursor)
                    System.err.println(
                        "evt-stalled-v1:$runId:${outcome.page.nextCursor?.lastSequence ?: 0L}: " +
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
     * The `--typed` branch: the same read, the same query, the same cursor and the same status
     * contract as the envelope branch, printed through the typed codec instead.
     *
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
     *
     * ## Why this exists rather than a bare `return 0`
     *
     * The command returned `0` on both [EventPageDrain.Outcome.Answered] and
     * [EventPageDrain.Outcome.Stalled], and nothing recorded that as a decision. A `Stalled` page
     * means the store claimed more rows existed and the continuation could not reach them; the
     * diagnostic goes to stderr as a structured token and the status is still `0`. That is the
     * intended contract (observation is read-only and reports what it saw), but it was an accident
     * of the code rather than a pinned contract (AUD-05). Routing both cases through one exhaustive
     * function makes the value a decision: adding a case to `Outcome` forces a status to be chosen,
     * and a test asserts both cells.
     *
     * ## Contract
     *
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
     *
     * It is a second statement of one rule rather than a second rule. The two ADTs differ in the
     * payload they carry — [EventPageDrain.TypedPage] holds `DomainEvent`s where [EventPageDrain.Page]
     * holds envelopes — and nothing else: both are `Answered` or `Stalled`, and the status depends
     * only on which. Duplicating the two cells is the honest encoding of that. Converting one outcome
     * into the other to reuse a single function would either fabricate envelopes nobody reads or
     * weaken [exitCodeFor] to take a flag, and both trade a real property (exhaustiveness over a
     * closed ADT) for a cosmetic one (a shorter file).
     *
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

private const val DEFAULT_LIMIT = 100

/** Stands in for an absent value on a `:`-separated wire line, rather than printing nothing. */
private const val ABSENT = "-"
