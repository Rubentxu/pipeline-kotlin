package dev.rubentxu.pipeline.v2.application

/**
 * The authority for "the run ended".
 *
 * ## The rule
 * A run has ended when the store holds its terminal fact: the `RunFinished` event. That is a fact
 * about the RUN, and it is read from the rows the store RETURNED — never from the rows the client
 * was HANDED.
 *
 * ## Why it is not the client's rows
 * A filter decides what a client sees. It does not decide whether the run is over.
 *
 * The follow used to look for the terminal fact among the facts it had just delivered. With a
 * filter that excluded it — `--kind StepFinished` — it never saw it, and so it never stopped: the
 * store's cursor is never null, which made the "no more rows" exit unreachable, and the follow
 * polled forever on a run that had already finished. Measured against the distribution: `exit 124`
 * (the time exhausted) on a run that ended minutes earlier, while the same follow without a filter
 * exited 0 immediately.
 *
 * ## Which rule this is
 * The same rule the published follow states, in
 * [dev.rubentxu.pipeline.v2.events.follow.EventFollowState]: a `RunFinished` observation
 * transitions a follow from live to its terminal state. The verb was applying that rule to the
 * wrong rows. The published [dev.rubentxu.pipeline.v2.events.RunFinished] event is where the kind
 * comes from; naming it here does not fork it, it gives the verb one place to ask.
 */
internal object RunTerminal {

    /** The kind of the event that ends a run. */
    const val TERMINAL_KIND: String = "RunFinished"

    /**
     * Whether the run has ended, judged on the kinds of the rows the STORE returned.
     *
     * The kinds arrive as a [Sequence] so that a caller can map its own rows without building a
     * list of them first: the drain asks this question once per page, and a page is bounded by
     * rows, not by the handful of facts the client asked for.
     */
    fun hasEnded(kinds: Sequence<String>): Boolean = kinds.any { it == TERMINAL_KIND }
}
