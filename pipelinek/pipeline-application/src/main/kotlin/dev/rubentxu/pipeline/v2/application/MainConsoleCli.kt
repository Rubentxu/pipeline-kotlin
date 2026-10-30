package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.console.ConsoleOperationSelection
import dev.rubentxu.pipeline.v2.application.console.OperationSelection
import dev.rubentxu.pipeline.v2.application.console.SelectionOutcome
import dev.rubentxu.pipeline.v2.application.console.SelectionRefusal
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import java.nio.file.Path

/**
 * The console reader CLI.
 *
 * ```text
 * pipeline console --control-dir <path> <runId> <opId> [--max-bytes N] [--after-cursor TOKEN]
 * pipeline console --control-dir <path> <runId> <opId> --range FROM:TO
 * pipeline console --control-dir <path> <runId> <opId> [--format text|jsonl...]
 * ```
 *
 * The console transcript goes to **stdout as bytes**, not as a rendered line, because a consumer
 * that has to strip a CLI's framing out of a transcript will eventually strip the wrong thing. The
 * continuation token goes to **stderr**, which is the convention [MainEventsCli] already uses and
 * keeps the two planes from colliding. A refusal is therefore also stderr-only: it must never reach
 * stdout, because a consumer reading bytes there cannot tell a refusal from an empty transcript.
 *
 * ## `--format text | jsonl`
 *
 * The default is `text` (raw bytes to stdout, unchanged). `--format jsonl` switches
 * the emitter to [dev.rubentxu.pipeline.v2.application.observation.ObservationJsonLines]: one record per page, with the canonical
 * `{type:"output", channel, operation, ordinal, from, to, text/base64}` shape, the same one
 * `pipelinek observe --view console --format jsonl` already produces. Stdout stays the protocol
 * surface (one record per line, newline-terminated, flushed); the continuation token still
 * goes to stderr, so a script can resume without parsing the jsonl stream for control bytes.
 *
 * The jsonl path is bounded to the operation's merged page; a future slice can wire per-frame
 * records through [dev.rubentxu.pipeline.v2.application.observation.FrameIndexedObservationOutputReader] once the per-operation index is
 * available.
 *
 * ## Two steps, in that order
 *
 * [CliConsoleParser.parse] collects the argv into one holder and validates it — conflicts
 * before types, so `--range 0:10 --max-bytes abc` says the budget has no meaning
 * there rather than complaining about the number, which would send the caller to edit a token they
 * should delete. [MainConsoleCli.execute] interprets the admitted request.
 *
 * ## One verb, four collaborators
 *
 * This object owns the verb's own surface and nothing else: the entry point, the interpretation
 * of the admitted request and the resolution of the selection. The read side
 * ([ConsoleFramePlane]), the follow loop ([ConsoleFollowLoop]) and the presentation
 * ([ConsolePresentation]) each own one part of what the verb needs, so no single object carries
 * the whole console surface and each part can change without touching the others.
 *
 * The split is a shape decision, not a behaviour change: the same requests are admitted, the same
 * bytes reach stdout and the same tokens reach stderr.
*/

object MainConsoleCli {

    /**
     * The closed ADT of console output formats.
     *
     * `TEXT` is the legacy raw-bytes path, unchanged. `JSONL` is the canonical
     * machine-readable projection [dev.rubentxu.pipeline.v2.application.observation.ObservationJsonLines] emits. Adding a third case is a
     * compile-time break for every caller of [ConsoleReadRequest.format] and for the emit
     * dispatch in [ConsolePresentation.emit].
*/
    enum class ConsoleFormat(val token: String) {
        /** Raw bytes to stdout. Default. */
        TEXT("text"),
        /** One [dev.rubentxu.pipeline.v2.application.observation.ObservationJsonLines] record per line. */
        JSONL("jsonl"),
        ;

        companion object {
            fun parse(token: String): ConsoleFormat? = values().firstOrNull { it.token == token }
        }
    }

    fun main(args: Array<String>): Int = when (val admitted = CliConsoleParser.parse(args)) {
        is ConsoleCliParseResult.Rejected -> {
            System.err.println(renderConsoleCliRefusal(admitted.refusal))
            2
        }

        is ConsoleCliParseResult.Admitted -> {
            val request = admitted.request
            val root = Path.of(request.controlDir)
            // The first effect in the program. Everything above is a decision about the QUESTION;
            // this is the question being put to a filesystem that may not have the answer.
            if (!java.nio.file.Files.isDirectory(root)) {
                System.err.println(
                    renderConsoleCliRefusal(ConsoleCliRefusal.ControlDirNotFound(request.controlDir)),
                )
                2
            } else {
                // The dispatch is on the request variant: Paged / Range go
                // Through the byte-budgeted [ConsolePresentation.emit] path (unchanged); Frames
                // goes through the per-frame path, which reads the index directly and
                // emits one record per real frame.
                // FramesFiltered is the filtered per-frame path; the
                // filter is resolved at emit time by matching each frame's stream against
                // the closed [ConsoleFrameFilters.Active] set.
                // ONE selection, resolved here and consumed by whichever
                // interpreter runs. This is the whole point of the block: the single read
                // and the follow loop used to decide "which frames?" separately, and the
                // Follow path did not decide at all (audit). Resolving before the
                // dispatch is what makes them the same question.
                // Opening the journal HERE rather than in the parser is deliberate: admission
                // must perform no effect, so a missing or unreadable `--db` is a typed refusal
                // at interpretation time, not a parse error.
                val selection = when (val outcome = resolveSelection(request)) {
                    is SelectionOutcome.Selected -> outcome.selection
                    is SelectionOutcome.Refused -> {
                        System.err.println(
                            renderConsoleCliRefusal(ConsoleCliRefusal.FilterNotResolvable(outcome.refusal)),
                        )
                        return 2
                    }
                }
                when (request) {
                    is ConsoleReadRequest.Frames ->
                        ConsolePresentation.emitFrames(ConsoleFramePlane.readFrames(request, root), request, System.out, System.err)
                    // `--follow`. A loop over the per-frame path; the loop
                    // is a pure interpreter of [ConsoleReadRequest.FollowFrames] and does not
                    // mutate any journal state.
                    is ConsoleReadRequest.FollowFrames ->
                        // The loop returns a typed [FollowExit]; the ONLY place its
                        // exit code is projected. Stated here once so the mapping cannot drift,
                        // and so a reader can see that an exhausted poll budget is non-zero
                        // without reading the loop.
                        // A `--follow` carrying filters is a FILTERED follow.
                        // Under rc1 this branch shadowed the filter entirely: the operator
                        // asked for one stage, was shown the whole run, and got exit 0.
                        ConsoleFollowLoop.runFollowFrames(request, root, System.out, System.err, selection).also { outcome ->
                            if (outcome is FollowExit.PollBudgetExhausted) {
                                System.err.println(
                                    "console-follow-exhausted: ${outcome.polls} polls, " +
                                        "no terminal fact observed; last frame ordinal " +
                                        "${outcome.lastOrdinal}. The run was NOT observed to " +
                                        "finish — resume with --after-ordinal ${outcome.lastOrdinal}",
                                )
                            }
                        }.exitCode
                    is ConsoleReadRequest.FramesFiltered ->
                        ConsolePresentation.emitFramesFiltered(
                            ConsoleFramePlane.readFramesFiltered(request, root),
                            request,
                            System.out,
                            System.err,
                            selection,
                        )
                    is ConsoleReadRequest.Paged,
                    is ConsoleReadRequest.Range ->
                        ConsolePresentation.emit(execute(request, root), request, System.out, System.err)
                }
            }
        }
    }

    private fun execute(request: ConsoleReadRequest, root: Path): ConsoleReadService.Result =
        when (request) {
            is ConsoleReadRequest.Paged -> ConsoleReadService.read(
                root,
                request.runId,
                request.opId,
                request.after,
                request.maxBytes,
            )

            is ConsoleReadRequest.Range -> ConsoleReadService.readRange(
                root,
                request.runId,
                request.opId,
                request.from,
                request.to,
            )

            // `Frames` is handled in [MainConsoleCli.main] via [ConsoleFramePlane.readFrames] +
            // [ConsolePresentation.emitFrames] before [MainConsoleCli.execute] is reached.
            // The branch is exhaustive; this case is unreachable but makes the
            // closed [ConsoleReadRequest] ADT explicit to the type-checker.
            is ConsoleReadRequest.Frames ->
                error("unreachable: Frames requests bypass execute() and use readFrames+emitFrames instead")

            // `FollowFrames` is handled in [MainConsoleCli.main] via [ConsoleFollowLoop.runFollowFrames] before [MainConsoleCli.execute]
            // is reached. Same shape as `Frames`: the dispatch in `main` reads the request
            // type and routes accordingly; [MainConsoleCli.execute] is only the byte-budgeted path.
            is ConsoleReadRequest.FollowFrames ->
                error("unreachable: FollowFrames requests bypass execute() and use runFollowFrames instead")
            // `FramesFiltered` follows the same path: handled in [MainConsoleCli.main] via
            // [ConsoleFramePlane.readFramesFiltered] + [ConsolePresentation.emitFramesFiltered]. The branch is exhaustive; this
            // case is unreachable but keeps the closed [ConsoleReadRequest] ADT honest.
            is ConsoleReadRequest.FramesFiltered ->
                error(
                    "unreachable: FramesFiltered requests bypass execute() and use " +
                        "readFramesFiltered+emitFramesFiltered instead",
                )
        }

    /**
     * Turn a request's filter tokens into ONE [OperationSelection].
     *
     * The only place the events journal is opened for a console read. Everything downstream —
     * the single filtered read AND the follow loop — receives the resulting selection as a value
     * and never asks the journal anything, so the two paths cannot drift apart.
     *
     * `dbPath == null` is passed through as `null` rather than as an empty history. Those are
     * different facts and the resolver gives them different answers: an empty history means "this
     * run has no such stage", while no journal at all means "nobody can tell", which is a refusal
     * naming the axis. Collapsing them would make `--db` optional in the worst possible way —
     * silently narrowing the answer.
     *
     * A request with no filter at all short-circuits to [OperationSelection.AllOperations]
     * WITHOUT opening the journal, so the byte paths and the unfiltered per-frame read are
     * untouched by this block.
*/
    private fun resolveSelection(request: ConsoleReadRequest): SelectionOutcome {
        val filters = when (request) {
            is ConsoleReadRequest.FramesFiltered -> request.filters
            is ConsoleReadRequest.FollowFrames -> request.filters
            else -> return SelectionOutcome.Selected(OperationSelection.AllOperations)
        }
        if (filters is ConsoleFrameFilters.None) {
            return SelectionOutcome.Selected(OperationSelection.AllOperations)
        }
        val active = filters as ConsoleFrameFilters.Active
        val dbPath = request.dbPath
            ?: return ConsoleOperationSelection.resolve(request.runId, active.stages, active.steps, active.kinds, null)

        // The shared admission, not a second opinion about the same path. `ControlDirNotFound`
        // above already refused the OTHER durable lane with a name; this verb was letting the event
        // lane's equivalent escape as a raw java.sql.SQLException.
        val admission = admitEventStore(dbPath, EventStorePurpose.Read)
        if (admission is EventStoreAdmission.Refused) {
            return SelectionOutcome.Refused(SelectionRefusal.EventStoreRefused(admission.refusal))
        }
        // Open the journal, read, close — in that order. The store is closed even if resolution
        // throws, so a refusal does not leak a file handle into a follow that may run for a minute.
        return (admission as EventStoreAdmission.Admitted).store.use { store ->
            ConsoleOperationSelection.resolve(
                request.runId, active.stages, active.steps, active.kinds,
                store.eventsFor(request.runId).asSequence(),
            )
        }
    }
}
