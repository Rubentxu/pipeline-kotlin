package dev.rubentxu.pipeline.v2.domain

/**
 * Port for obtaining a fresh, opaque identifier.
 *
 * The counterpart of [dev.rubentxu.pipeline.v2.domain.durable.Clock] for the
 * OTHER ambient read. An identifier drawn from the platform's entropy is not
 * reproducible, so a decision test cannot pin it and two runs cannot be
 * compared by it — the same defect the clock port solves for the instant.
 *
 * It sits beside [RunIdGenerator] because it is the general form of what that
 * one is for a run: a caller that needs the identifier to carry a meaning
 * builds that meaning on top of it, the way [RunIdGenerator] builds a
 * [RunId].
 *
 * The value is OPAQUE: no consumer may parse it, and no consumer may assume a
 * shape for it.
 */
fun interface IdGenerator {

    /**
     * Returns an identifier that differs from every other identifier this
     * generator returned.
     */
    fun next(): String
}
