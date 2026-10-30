package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputPin

/**
 * What may be done with ONE directory of the Output Plane, once its pins have been consulted.
 *
 * ## The decision and nothing else
 *
 * This type is the decision, separated from the I/O that acts on it, so the decision can be
 * exercised without a filesystem. It answers a single question — may this directory be deleted? —
 * and it answers it with the two facts that can hold it back: somebody pinned it, or the pin
 * substrate could not be read.
 *
 * The second reason is the one that is easy to forget and expensive to get wrong. "I could not
 * find out" is not "there is nobody", and a retention pass that reads it as "there is nobody"
 * deletes bytes somebody is holding.
 */
internal sealed interface DirectoryRetention {

    /** Nothing holds this directory; it may be deleted. */
    data object Deletable : DirectoryRetention

    /** Something holds this directory; it must survive. [hold] says what. */
    data class Retained(val hold: RetentionHold) : DirectoryRetention
}

/** What holds a directory back. */
internal sealed interface RetentionHold {

    /** These pins hold the directory; they survive it. */
    data class Pinned(val pins: List<OutputPin>) : RetentionHold

    /** The pin substrate could not be read, so nothing can be said about the pins. */
    data class SubstrateUnavailable(val cause: String) : RetentionHold
}

/**
 * Decide what may be done with a directory, from the answer to the consult.
 *
 * The rule is short enough to state in one line and long enough to be worth a test: an unavailable
 * substrate RETAINS the directory, and an answered consult retains it only when somebody actually
 * pinned it.
 */
internal fun decideDirectoryRetention(answer: DirectoryPinAnswer): DirectoryRetention = when (answer) {
    is DirectoryPinAnswer.Unavailable ->
        DirectoryRetention.Retained(RetentionHold.SubstrateUnavailable(answer.cause))
    is DirectoryPinAnswer.Answered ->
        if (answer.pins.isEmpty()) {
            DirectoryRetention.Deletable
        } else {
            DirectoryRetention.Retained(RetentionHold.Pinned(answer.pins))
        }
}
