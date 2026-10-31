package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.output.OutputRefusal

/**
 * The row model of an inspection: what one inspected record IS.
 *
 * ## Where it lives and why
 *
 * These two types are the vocabulary the verb's three responsibilities share: the read
 * answers with a console tail, the selection turns events into rows, and the presentation
 * renders rows. They used to sit at the bottom of `MainInspectCli.kt`, next to the verb that
 * used them; they now have their own file, because a type that three owners read is not the
 * verb's private vocabulary.
 *
 * They are top-level and public, exactly as they were: the FQN does not change, so nothing
 * that names them stops compiling.
*/

/**
 * What the console tail of an inspected record actually IS.
 *
 * Three states, because the Output Plane's own contract keeps them apart and a nullable `String`
 * did not. [dev.rubentxu.pipeline.v2.application.observation.ObservationOutputRead] already
 * distinguishes "the page" from "the store could not answer", and
 * [dev.rubentxu.pipeline.v2.output.OutputRefusal] spells out what a refusal means:
 * the bytes are NOT absent, they are unread. Rendering that as an empty transcript told a consumer
 * that a step printed nothing, which is the one fact it would act on.
 *
 * The empty [Bytes] case is deliberately still a value rather than folded into [NotRequested]: "this
 * step ran and printed nothing" and "nothing was asked for" are different answers to different
 * questions, and only the first one is evidence about the step.
*/
sealed interface InspectConsoleTail {

    /** `--log-tail` was not requested, so there is nothing to report either way. */
    data object NotRequested : InspectConsoleTail

    /** The bytes the Output Plane served, decoded. Empty is a FACT: the step printed nothing. */
    data class Bytes(val text: String) : InspectConsoleTail

    /** A tail was requested and the plane could not answer. Never render this as empty output. */
    data class Unreadable(val reason: OutputRefusal) : InspectConsoleTail
}

/**
 * One inspected record — the row shape that the projection fields read from.
 *
 * Kept as a data class, not a sealed ADT: every record carries all five columns, with `null` for
 * the ones that do not apply, because the consumer chooses which fields to project and a row that
 * has `null` for un-requested fields is not a different SHAPE of record.
 *
 * [consoleTail] is the exception, and the exception is the point: its states are not "which columns
 * this row fills in" but "what the Output Plane answered". A `null` there would have to mean both
 * "un-requested" and "refused", which are different facts about a run, so it is a closed ADT
 * instead. Every other field is still a nullable projection.
*/
data class InspectionRecord(
    val stage: String? = null,
    val stepOrdinal: Long? = null,
    val outcome: String = "",
    val message: String? = null,
    val consoleTail: InspectConsoleTail = InspectConsoleTail.NotRequested,
)
