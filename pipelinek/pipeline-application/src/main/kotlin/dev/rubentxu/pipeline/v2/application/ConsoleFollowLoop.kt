package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.console.OperationSelection
import dev.rubentxu.pipeline.v2.application.observation.FollowDecision
import dev.rubentxu.pipeline.v2.application.observation.FrameIndexedObservationOutputReader
import dev.rubentxu.pipeline.v2.application.observation.ObservationOutputRead
import dev.rubentxu.pipeline.v2.application.observation.RunPlane
import dev.rubentxu.pipeline.v2.application.observation.followDecision
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import java.io.PrintStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Why a `--follow` loop stopped.
 *
 * ## Why this is an ADT and not an `Int`
 *
 * The loop previously returned a bare `Int`, with `0` meaning both "the output lane is final" and
 * "we ran out of polls" and `1`/`2` meaning a refusal and a signal. Three facts about a run were
 * encoded in one number with no way to tell them apart, and two of them — a finished follow and an
 * abandoned one — were reported **identically**. A caller could not distinguish "this operation's
 * output is complete" from "we gave up looking", which is the same false-green the follow
 * decision itself was fixed for: a truncated transcript that looks like a clean exit.
 *
 * [exitCode] is a *projection* of the case, defined once here so the mapping is stated in exactly
 * one place and cannot drift per call site.
 *
 * ## Why `PollBudgetExhausted` is NOT zero
 *
 * Running out of polls means the loop never observed a terminal fact. It says nothing about the
 * run: the run may be alive, may have failed, may have finished a millisecond after the last poll.
 * Reporting that as success claims the product completed an observation it never completed.
 *
 * The cost is real and deliberate: this verb has **no event-plane authority** (it never opens the
 * journal — see the note on the filter resolver), so for an op that owns no stream — a silent
 * `sh("true")` — the poll budget is the ONLY exit, and such a follow now reports exhaustion rather
 * than a clean completion. That is the honest answer. The alternative was a `0` that lied.
 *
 * Closing the gap properly means giving this verb a run-plane authority (see `RunPlane` and the
 * `--db` gap recorded in the RC4 receipt); until then this case is the honest ceiling, not a bug
 * to be tuned away.
*/
sealed interface FollowExit {
    /** The process exit code this outcome projects to. Stated once, never re-derived per call site. */
    val exitCode: Int

    /** Nothing pending and every known stream sealed — the output lane's own authority answered. */
    data object Completed : FollowExit {
        override val exitCode: Int = 0
    }

    /** The output plane refused to answer the read in flight. */
    data class Refused(val reason: OutputRefusal) : FollowExit {
        override val exitCode: Int = 1
    }

    /** SIGINT/SIGTERM, so a `Ctrl-C` is distinguishable from every other way out. */
    data object Signalled : FollowExit {
        override val exitCode: Int = 2
    }

    /**
     * The poll budget ran out before any terminal fact was observed.
     *
     * Its own case because none of the others happened. Deliberately non-zero — see the type KDoc.
*/
    data class PollBudgetExhausted(val polls: Int, val lastOrdinal: Long) : FollowExit {
        override val exitCode: Int = 3
    }
}

object ConsoleFollowLoop {

    /**
     * The `--follow` entry point over the durable plane.
     *
     * It builds the per-frame reader from the control root and hands it to the loop, so the
     * verb and the tests run the SAME interpreter over different planes. The loop reads,
     * emits what the selection admits and mutates no journal state. This entry point owns the
     * shutdown hook: the hook flips a flag and the loop checks it between rounds, so a
     * SIGINT does not leave a poll thread behind.
     */
    fun runFollowFrames(
        request: ConsoleReadRequest.FollowFrames,
        root: Path,
        out: PrintStream,
        err: PrintStream,
        selection: OperationSelection = OperationSelection.AllOperations,
    ): FollowExit {
        val reader = ConsoleFramePlane.frameReader(root)

        // JVM-shutdown hook so a SIGINT/SIGTERM does not leave a poll thread behind. The hook
        // flips a flag; the loop checks it between rounds.
        val shutdown = AtomicBoolean(false)
        val hook = Thread { shutdown.set(true) }
        Runtime.getRuntime().addShutdownHook(hook)

        try {
            return followFrames(
                request = request,
                reader = reader,
                out = out,
                err = err,
                selection = selection,
                isStopping = { shutdown.get() },
            )
        } finally {
            try { Runtime.getRuntime().removeShutdownHook(hook) } catch (_: IllegalStateException) { /* shutdown in flight */ }
        }
    }



     /**
     * `--follow` interpreter. A pure interpreter of [ConsoleReadRequest.FollowFrames].
     *
     * The loop is reading, not deciding: that separation is in force. The typed decision
     * ([ConsoleReadRequest.FollowFrames]) is what reached this method; the loop interprets it and walks the
     * durable authority via the per-frame reader.
     *
     * The same separation is in force here: the `afterOrdinal` cursor is local state threaded
     * as a parameter, never a global or thread-local. Each poll derives a fresh `afterOrdinal`
     * from the previous one; the durable authority is unchanged by the loop.
     *
     * ## Exit conditions
     *
     * The loop exits when any of:
     *
     * - **`followDecision` returns [FollowDecision.Finished].** That is: the page returned no
     * new frames AND every known stream this run owns is
     * [dev.rubentxu.pipeline.v2.output.OutputTailState.Sealed]. The durable authority
     * promises nothing more will arrive on this run.
     * - **`maxPolls` reached.** Bounded at admission. An op that owns no stream (e.g.
     * `sh("true")`) cannot reach `Finished` because no stream exists to seal — this verb
     * has no run-plane authority to ask instead — so the bound is the only exit for a silent
     * op, and it reports [FollowExit.PollBudgetExhausted] rather than a clean completion.
     * - **SIGINT/SIGTERM.** Shutdown hook flips a flag; the loop checks it between rounds.
     *
     * ## Status mapping
     *
     * - 0 on [FollowExit.Completed] — nothing pending and every known stream sealed.
     * - 1 on [FollowExit.Refused] — the store refused to answer the call in flight.
     * - 2 on [FollowExit.Signalled] — a `Ctrl-C` is distinguishable from every other way out.
     * - 3 on [FollowExit.PollBudgetExhausted] — the loop ran out of polls without ever observing
     * a terminal fact. **Non-zero on purpose:** an abandoned follow and a completed one are
     * different facts and must not share an exit code.
     *
     * ## Why this loop has TWO cursors and not one
     *
     * The loop runs over a plane the caller supplies, and it answers two questions with
     * different answers:
     * - WHERE THE SCAN IS: the last frame the loop EXAMINED. A frame the selection dropped was
     * examined all the same — it was read and it was rejected — so asking for it again on the
     * next poll re-reads the same window for ever. Pinning the window to the last DELIVERED
     * frame is the defect this function fixes: a filter that dropped every frame of the
     * first window kept the window pinned, burned the whole poll budget and delivered
     * nothing, while the frames the filter DID admit sat unread beyond it.
     * - WHERE THE CONSUMER IS: the last frame the consumer was SHOWN. That is what an
     * exhausted poll budget reports and what `--after-ordinal` resumes from, because a
     * resume must not show a frame twice and must not skip one the consumer never saw.
     *
     * The two cursors coincide when every frame is admitted, and they part exactly when the
     * selection drops something — which is the only case in which the distinction matters.
     *
     * ## The seams
     *
     * [isStopping] is the flag the JVM shutdown hook flips, and [waitBetweenPolls] is the wait
     * between rounds. Both are parameters so the loop can be driven in process, over an in-memory
     * plane, with no clock and no sleep: the cheapest instrument that can fail is the real loop
     * over frames that are already in memory.
     */
    internal fun followFrames(
        request: ConsoleReadRequest.FollowFrames,
        reader: FrameIndexedObservationOutputReader,
        out: PrintStream,
        err: PrintStream,
        selection: OperationSelection,
        isStopping: () -> Boolean = { false },
        waitBetweenPolls: (Long) -> Unit = { millis -> Thread.sleep(millis) },
    ): FollowExit {
        // The SCAN cursor: the last frame the loop EXAMINED, admitted or not.
        var scanOrdinal = request.afterOrdinal
        // The DELIVERY cursor: the last frame the consumer was SHOWN. A frame the selection
        // dropped never moves it, and it is what an exhausted poll budget reports.
        var deliveredOrdinal = request.afterOrdinal
        val maxPolls = request.followConfig.maxPolls
        val pollIntervalMillis = request.followConfig.pollIntervalMillis

        for (poll in 0 until maxPolls) {
            if (isStopping()) return FollowExit.Signalled

            // OpId-scoped read; see readFrames.
            val page = when (val read = ConsoleFramePlane.readFramePage(reader, request.runId, request.opId, scanOrdinal, request.frameLimit)) {
                is ObservationOutputRead.Refused -> {
                    err.println(ConsoleReadService.renderRefusal(read.reason))
                    return FollowExit.Refused(read.reason)
                }
                is ObservationOutputRead.Page -> read.page
            }

            // Audit. The filter is applied to EVERY page the loop emits,
            // using the same [selection] the single read consults. rc1 applied no filter here
            // at all: `--frames --follow --stage build` was admitted as a plain follow and the
            // operator was shown the whole run with exit 0.
            val admitted = page.records.filter { ConsoleFramePlane.selectionSelects(selection, it.frame.stream.value) }
            if (admitted.isNotEmpty()) {
                ConsolePresentation.emitFollowPage(admitted, request.format, out, err)
                deliveredOrdinal = admitted.last().frame.ordinal
            }

            // The SCAN cursor advances to the last frame the loop EXAMINED, whether or not the
            // selection admitted it. The reader is strictly `ordinal > afterOrdinal` (see
            // SegmentFrameIndex.framesOfRun), so passing the last examined ordinal resumes at
            // the NEXT frame and never skips one. An empty page hands back the position it was
            // given, so a poll that found nothing leaves the cursor where it is and reads the
            // same window again — which is right: there was nothing new to skip, and the next
            // poll may find a frame that arrived.
            scanOrdinal = page.lastOrdinal

            // EOF detection. Three facts, each from a different authority:
            //  - moreFrames: did the index know there were frames beyond what it gave us
            //    (a window-shape fact);
            //  - tailStates: what the durable stores say about each stream this run owns
            //    (whether more bytes can ever arrive on it);
            //  RunPlane: what the EXECUTION plane can say about the run. / — this
            //    verb never opens the events journal, so it has NO run-plane authority and
            //    says so explicitly with [RunPlane.Unavailable] instead of passing a `false`
            //    that was indistinguishable from "the run is still going". The distinction is
            //    what lets `followDecision` degrade to the output lane's own authority here
            //    (keeping a console-only follow terminatable) while still refusing to treat
            //    sealing as sufficient on a lane that CAN ask. Closing the gap means giving
            //    this verb a `--db` to read `RunFinished` from; recorded as NOT_RUN in the
            //    RC4 receipt rather than papered over.
            val tails = reader.tailStatesOf(request.runId)
            val decision = followDecision(
                moreFrames = page.moreFrames,
                tailStates = tails,
                runPlane = RunPlane.Unavailable,
            )
            when (decision) {
                FollowDecision.Finished -> return FollowExit.Completed
                FollowDecision.ReadAgain -> {
                    // Only sleep between rounds. The last iteration need not sleep.
                    if (poll < maxPolls - 1) {
                        try {
                            waitBetweenPolls(pollIntervalMillis)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return FollowExit.Signalled
                        }
                    }
                }
            }
        }
        // The budget ran out and nothing terminal was ever observed. NOT a completion: see
        // `FollowExit.PollBudgetExhausted`. The cursor is reported so a caller can tell how
        // far the follow got and resume with `--after-ordinal` — and it is the cursor of the
        // DELIVERED frames, so a resume shows the consumer nothing twice and skips nothing it
        // was never shown.
        return FollowExit.PollBudgetExhausted(polls = maxPolls, lastOrdinal = deliveredOrdinal)
    }

}
