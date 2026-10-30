package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputPin

/**
 * The question the retention side asks about ONE directory of the Output Plane:
 * "is anybody holding this directory right now?".
 *
 * ## What this seam is for
 *
 * The byte store reaches a stream by its **directory**, never by an id it can rebuild: the on-disk
 * name is a lossy fold of the stream id, so the reverse direction does not exist. The published pin
 * port, on the other hand, answers by **stream**. Those two vocabularies do not meet, and the
 * previous composition tried to bridge them by *guessing* the stream back from the directory name —
 * a fold that is lossy in general and therefore could not be trusted to answer a question about
 * deleting bytes.
 *
 * This seam closes the gap in the only direction that is exact: the directory's name is the fold of
 * the stream id, so the CONSULT is asked by name and the ANSWER names the real stream. No id is
 * reconstructed on the way, and none has to be.
 *
 * ## Why the answer is a sealed pair, not a list
 *
 * "There are no pins" and "I could not find out" are different facts, and collapsing them is how a
 * retention pass deletes bytes somebody is holding. The second one is [DirectoryPinAnswer.Unavailable]:
 * a substrate that cannot be read is not an empty substrate, and the caller that cannot tell them
 * apart is the caller that deletes.
 *
 * The seam is deliberately narrow — one question, one answer — so the holder of the substrate and
 * the consumer of the answer cannot drift into two different notions of "pinned".
 */
interface DirectoryPinConsult {

    /**
     * The pins that currently hold the directory named [directoryName], or the reason they could
     * not be established.
     *
     * @param directoryName the **name** of a stream directory, as it appears on disk.
     */
    fun pinsOfDirectory(directoryName: String): DirectoryPinAnswer
}

/**
 * The answer to [DirectoryPinConsult.pinsOfDirectory].
 *
 * [Answered] with an empty list means "nobody holds this directory". [Unavailable] means the
 * question could not be answered, and it is NOT the same answer: the second one means the directory
 * must be treated as held.
 */
sealed interface DirectoryPinAnswer {

    /** The substrate answered, and these are the pins that hold the directory. */
    data class Answered(val pins: List<OutputPin>) : DirectoryPinAnswer

    /** The substrate could not be read; [cause] says why, in one bounded line. */
    data class Unavailable(val cause: String) : DirectoryPinAnswer
}
