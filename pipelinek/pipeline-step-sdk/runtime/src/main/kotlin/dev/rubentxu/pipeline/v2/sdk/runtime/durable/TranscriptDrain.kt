package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the drain of a transcript has to do next.
 *
 * The drain answers one question: **is the transcript complete?** A transcript is complete when
 * every pump has read the child to its end and written what it read. Anything else is a transcript
 * that is short, ordered and plausible, which is the worst shape a transcript can have, because it
 * reads like a complete one.
 */
internal enum class TranscriptDrainDecision {
    /** Every pump finished: the transcript is complete. */
    DONE,

    /** Still moving, and still within the budgets: wait more. */
    KEEP_WAITING,

    /** A pump stopped moving: the child is holding the transcript open. */
    STUCK,

    /** Still moving when the ceiling arrived: the machine was too slow to finish in time. */
    CEILING_REACHED,
}

/**
 * What the drain observes about itself and about its pumps.
 *
 * @property allFinished every pump has finished.
 * @property waitingMs how long the live pump that has waited longest has been waiting for the
 *   child's bytes without working on the plane. A pump that is writing is MOVING even while it
 *   writes, because it read those bytes already; a pump that is waiting for the child is not
 *   moving at all, and the bytes it waits for may never come.
 * @property idleBudgetMs how long a pump may wait for the child before it is declared stuck.
 * @property waitedMs how long the drain has been waiting, in total.
 * @property ceilingMs the longest the drain may wait, whatever happens.
 */
internal data class TranscriptDrainState(
    val allFinished: Boolean,
    val waitingMs: Long,
    val idleBudgetMs: Long,
    val waitedMs: Long,
    val ceilingMs: Long,
)

/**
 * The rule of the drain, in one place.
 *
 * The order of the checks IS the rule, and it is the whole difference between a slow machine and a
 * broken transcript:
 *
 * 1. **Finished** wins over everything: a complete transcript is a complete transcript, whatever
 *    the budgets say.
 * 2. **A pump that stopped moving** is stuck, and it is stuck NOW. The child is holding the
 *    transcript open, so the bytes that are missing are missing because nobody will ever write
 *    them. Waiting longer cannot produce them.
 * 3. **The ceiling** ends the wait for a drain that IS moving. This is the only case that is a
 *    verdict about the machine rather than about the child.
 * 4. Otherwise the drain waits.
 *
 * A ceiling alone would have solved none of this: it would have refused slow drains exactly as it
 * does today, only later. A stuck pump refused early and a slow pump waited out is the difference
 * between a verdict about the child and a verdict about the load.
 */
internal fun decideTranscriptDrain(state: TranscriptDrainState): TranscriptDrainDecision = when {
    state.allFinished -> TranscriptDrainDecision.DONE
    state.waitingMs >= state.idleBudgetMs -> TranscriptDrainDecision.STUCK
    state.waitedMs >= state.ceilingMs -> TranscriptDrainDecision.CEILING_REACHED
    else -> TranscriptDrainDecision.KEEP_WAITING
}

/**
 * What the drain can see about one transcript pump.
 *
 * The drain needs three things from a pump: whether it is still running, whether it is waiting for
 * the child or working on the plane, and a way to wait for it. Everything else about a pump is the
 * pump's own business.
 */
internal interface TranscriptPumpView {
    /** The pump's name, for the refusal that names it. */
    val name: String

    /** Whether the pump is still running. */
    fun isAlive(): Boolean

    /**
     * Whether the pump is waiting for the child's next bytes.
     *
     * This is the signal that separates the two reasons a pump can still be running at the end of
     * a step. A pump that is WAITING is waiting for bytes nobody is going to write: the child
     * exited and something it started is holding the transcript open. A pump that is WORKING is
     * writing what it read to the output plane, and it is slow because the machine is busy — which
     * is a fact about the machine, not about the child.
     */
    fun isWaitingForInput(): Boolean

    /**
     * Waits for the pump to finish, for at most [millis].
     *
     * @return how long the wait actually lasted, in milliseconds. A wait that ends early because
     *   the pump finished returns the time it really took, so the drain never claims a wait it
     *   did not make.
     */
    fun awaitFinish(millis: Long): Long
}

/**
 * A pump backed by a thread.
 *
 * The thread is the pump's life; the flag is the pump's attention. They are published together
 * because the drain needs both and a pump that could report one without the other would be a pump
 * with half a story.
 */
internal class ThreadTranscriptPump(
    private val thread: Thread,
    private val waitingForInput: AtomicBoolean,
) : TranscriptPumpView {
    override val name: String get() = thread.name

    override fun isAlive(): Boolean = thread.isAlive

    override fun isWaitingForInput(): Boolean = waitingForInput.get()

    override fun awaitFinish(millis: Long): Long {
        val startedAt = System.nanoTime()
        thread.join(millis)
        return (System.nanoTime() - startedAt) / 1_000_000L
    }
}

/**
 * The drain of a transcript, over pumps the caller can see.
 *
 * The drain waits in slices rather than in one long wait, because the question it asks is not
 * "has the pump finished by now" but "is the pump still moving". A slice is short enough to notice
 * that a pump stopped, and long enough not to spend the wait asking.
 *
 * @param pumps the pumps to drain.
 * @param idleBudgetMs how long a pump may wait for the child before it is declared stuck.
 * @param ceilingMs the longest the drain may wait, whatever happens.
 * @param sliceMs how long one wait lasts.
 * @return the refusal when the transcript cannot be completed, or `null` when it can.
 */
internal fun drainTranscriptPumps(
    pumps: List<TranscriptPumpView>,
    idleBudgetMs: Long,
    ceilingMs: Long,
    sliceMs: Long,
): ProcessOutputRefusal? {
    if (pumps.isEmpty()) return null
    val waitingSince = mutableMapOf<TranscriptPumpView, Long>()
    for (pump in pumps) {
        waitingSince[pump] = 0L
    }
    var waited = 0L
    while (true) {
        val live = pumps.filter { it.isAlive() }
        if (live.isEmpty()) return null
        var roundWaited = 0L
        for (pump in live) {
            roundWaited += pump.awaitFinish(sliceMs)
        }
        waited += roundWaited
        for (pump in live) {
            waitingSince[pump] = if (pump.isWaitingForInput()) {
                (waitingSince[pump] ?: 0L) + roundWaited
            } else {
                0L
            }
        }
        val stillLive = live.filter { it.isAlive() }
        val state = TranscriptDrainState(
            allFinished = stillLive.isEmpty(),
            waitingMs = stillLive.maxOfOrNull { waitingSince[it] ?: 0L } ?: 0L,
            idleBudgetMs = idleBudgetMs,
            waitedMs = waited,
            ceilingMs = ceilingMs,
        )
        when (decideTranscriptDrain(state)) {
            TranscriptDrainDecision.DONE -> return null
            TranscriptDrainDecision.KEEP_WAITING -> Unit
            TranscriptDrainDecision.STUCK -> {
                val stuck = stillLive.filter { (waitingSince[it] ?: 0L) >= idleBudgetMs }
                val stuckName = stuck.firstOrNull()?.name ?: stillLive.joinToString(", ") { it.name }
                val waitedForInput = stuck.maxOfOrNull { waitingSince[it] ?: 0L } ?: 0L
                return ProcessOutputRefusal.DrainIncomplete(
                    detail = "transcript drain stuck: $stuckName waited ${waitedForInput}ms for the " +
                        "child's bytes without writing any. The child is holding the transcript open, " +
                        "so the bytes that are missing will never arrive",
                    waitedMs = waited,
                    cause = TranscriptDrainCause.STUCK_CHILD,
                )
            }
            TranscriptDrainDecision.CEILING_REACHED -> {
                return ProcessOutputRefusal.DrainIncomplete(
                    detail = "transcript drain unfinished after ${waited}ms: the pumps were still working " +
                        "(${stillLive.joinToString(", ") { it.name }}) when the drain ceiling of " +
                        "${ceilingMs}ms arrived. The transcript is behind, not broken",
                    waitedMs = waited,
                    cause = TranscriptDrainCause.OUT_OF_TIME,
                )
            }
        }
    }
}

/**
 * How long ONE wait of the transcript drain lasts.
 *
 * The drain asks the pumps whether they are still MOVING, so it has to look more than once:
 * a slice short enough to notice that a pump stopped, and long enough not to spend the
 * wait asking. The budgets that decide are the drain's own, in [DurableShConfig], because
 * they are the operator's levers and not the runtime's taste.
 */
const val TRANSCRIPT_PUMP_DRAIN_SLICE_MS: Long = 250

/**
 * The verdict on ONE launch's transcript: the refusal that stops the step from being
 * reported on a transcript the runtime cannot vouch for, or `null` when it can.
 *
 * ## Why the launch is the argument
 *
 * The verdict is about the transcript of ONE child. It is computed from the pumps that
 * child started and from the sinks those pumps wrote into — and from nothing else. That
 * is the whole answer to "can two executions swap their drain objects": there is no
 * ambient state left in the runtime for them to swap, because the state IS the argument.
 *
 * The drain is checked FIRST: if a pump was still alive, nothing downstream of it can
 * claim the transcript is complete, so that refusal subsumes whatever the sinks recorded
 * in the meantime.
 */
internal fun transcriptVerdict(
    shell: LaunchedShell,
    config: DurableShConfig,
): ProcessOutputRefusal? =
    drainTranscriptPumps(
        pumps = shell.transcriptPumps,
        idleBudgetMs = config.transcriptDrainIdleBudgetMs,
        ceilingMs = config.transcriptDrainCeilingMs,
        sliceMs = TRANSCRIPT_PUMP_DRAIN_SLICE_MS,
    ) ?: shell.transcriptRefusal()
