package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageMarkedUnstable
import dev.rubentxu.pipeline.v2.events.StageSkipped
import dev.rubentxu.pipeline.v2.events.StepFailed

/**
 * How a run, stage or step ended, as ONE vocabulary across three producers that spell it three ways.
 *
 * ## Why this type exists, measured rather than assumed
 *
 * `--outcome` was the last query dimension without a reachable producer, and looking at why turned
 * up something a flag would have hidden. The three producers do not agree on spelling:
 *
 * ```text
 * RunFinished      outcome = "failure"     (from RunOutcome.Failure)
 * StageFinished    outcome = "failed"      (from StageOutcomeWire.FAILED)
 * StepFailed       outcome = "FAILURE"     (implied by being a StepFailed at all)
 * StageSkipped     outcome = "SKIPPED"     (the read model named it)
 * StageMarkedUnstable outcome = "UNSTABLE" (the read model named it)
 * ```
 *
 * So a filter over the raw strings answers a question about the PRODUCER — "which spelling did this
 * event happen to use" — rather than about the run. `--outcome FAILURE` would return every failed
 * step and no failed run; `--outcome failure` the reverse. Three of the five spellings are also a
 * different case from the other two, so even a case-insensitive match would not have fixed it.
 *
 * This enum collapses the spelling difference and names the set the producers already agree on
 * semantically. It is a NAMING, not a derivation: nothing here decides whether a run failed. The
 * Event Plane still says that, in whatever spelling it wrote, and a record's outcome is still read
 * off the event.
 *
 * ## The vocabulary is closed because the producers are
 *
 * `RunOutcome` has four cases, `StageOutcomeWire` has five, and the three hard-coded names above are
 * the remaining ones — so the union is exactly these six shapes. [Other] exists because
 * [RunFinished.outcome] and [StageFinished.outcome] are declared `String` and S8 freezes event
 * schemas, so a producer could still write a spelling this build does not name. Dropping such a
 * token to "no outcome" would delete the record from every outcome filter without saying so;
 * carrying it keeps the evidence visible and makes it unmatchable by name, which is the safe
 * direction.
 *
 * ## What this type is NOT
 *
 * It is not a second authority on how a run ended, and nothing may reconstruct one from a token to
 * decide something. `FArchE4b4WaitUntilTerminalAuthorityTest` is about `waitUntil`'s terminals and
 * does not scan these tokens; the reason its reasoning applies here anyway is the shape, not the
 * file: a reader that turned this enum back into a wire token to branch on would be doing exactly
 * what that law forbids.
 */
sealed interface ObservedOutcome {

    data object Success : ObservedOutcome
    data object Unstable : ObservedOutcome
    data object Failure : ObservedOutcome
    data object Skipped : ObservedOutcome
    data object Aborted : ObservedOutcome

    /**
     * A producer wrote a spelling this build does not name, and the spelling is KEPT.
     *
     * It was an enum constant with an empty token at first, and its own KDoc claimed it carried the
     * evidence — so the token was silently dropped on the floor while the documentation said
     * otherwise. A typed case is what makes the payload real; that is the whole reason this is a
     * sealed interface and not an enum.
     *
     * It matches no `--outcome` value, because [fromToken] never returns it. A caller who asked for
     * `failure` is not shown an unknown failure.
     */
    data class Other(val token: String) : ObservedOutcome

    /** The token that names this outcome on the wire, or `null` for one this build does not name. */
    val wireToken: String?
        get() = when (this) {
            Success -> "success"
            Unstable -> "unstable"
            Failure -> "failure"
            Skipped -> "skipped"
            Aborted -> "aborted"
            is Other -> null
        }

    companion object {
        /**
         * Every spelling the producers write, mapped onto the case it names.
         *
         * Both `failure` and `failed` are accepted because two producers write the same fact those
         * two ways — [dev.rubentxu.pipeline.v2.events.RunFinished] from `RunOutcome.Failure` and
         * [dev.rubentxu.pipeline.v2.events.StageFinished] from `StageOutcomeWire.FAILED`. Accepting
         * both is what makes the collapse honest; refusing one would make the filter answer about
         * the producer again.
         *
         * `null` for a name this build does not know, and never [Other]: a caller NAMING something
         * is a different situation from a producer WRITING something, and answering a typo with
         * [Other] would make `--outcome` accept a token nobody asked for.
         */
        private val BY_TOKEN: Map<String, ObservedOutcome> = mapOf(
            "success" to Success,
            "succeeded" to Success,
            "unstable" to Unstable,
            "failure" to Failure,
            "failed" to Failure,
            "skipped" to Skipped,
            "aborted" to Aborted,
        )

        /** The names `--outcome` accepts, for the error message and for `USAGE`. */
        val tokens: List<String> = listOf("success", "unstable", "failure", "skipped", "aborted")

        /** The outcome [value] names, ignoring case, or `null` when it names none of them. */
        fun fromToken(value: String): ObservedOutcome? = BY_TOKEN[value.lowercase()]
    }
}

/**
 * The outcome [event] reports, or `null` when it reports none.
 *
 * `null` is a fact and not a hole, and the distinctions are load-bearing:
 *
 * - `StepFinished` reports **no outcome token** because there is none — a step that finished IS the
 *   outcome, and the event type already says it. Treating its absence as "unknown" would put every
 *   successful step in the same bucket as a producer that wrote a spelling we cannot read;
 * - `StepFailed` reports [ObservedOutcome.Failure] because its type is the claim;
 * - `StageSkipped` and `StageMarkedUnstable` report the outcome their own types exist to report,
 *   and neither has a sibling `…Finished` event to carry a token instead.
 */
fun outcomeOf(event: DomainEvent): ObservedOutcome? = when (event) {
    is RunFinished -> ObservedOutcome.fromToken(event.outcome) ?: ObservedOutcome.Other(event.outcome)
    is StageFinished -> ObservedOutcome.fromToken(event.outcome) ?: ObservedOutcome.Other(event.outcome)
    is StageSkipped -> ObservedOutcome.Skipped
    is StageMarkedUnstable -> ObservedOutcome.Unstable
    is StepFailed -> ObservedOutcome.Failure
    else -> null
}
