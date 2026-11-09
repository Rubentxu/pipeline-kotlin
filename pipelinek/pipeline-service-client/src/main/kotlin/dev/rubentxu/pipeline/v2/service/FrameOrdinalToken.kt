package dev.rubentxu.pipeline.v2.service

/**
 * The frame-ordinal continuation token: the position a console reader resumes from.
 *
 * ## Why this token lives with the wire
 *
 * The byte path encodes `out-cursor-v1:<stream>:<offset>`; the per-frame path
 * encodes `out-frame-ord-v1:<ordinal>`. Two prefixes, two planes, and a consumer
 * that pastes the wrong one in the wrong place is told so at decode, not by
 * being silently advanced to a position in the wrong bytes.
 *
 * The token is printed by the command line as the hint of where to resume, it
 * travels on the socket as the position of a frame, and the service refuses any
 * other family by name. Those are three uses of ONE token, so the token has
 * ONE owner: the module that owns the wire vocabulary. A second encoder of the
 * same token would be a second authority about a position.
 *
 * ## Why an ordinal and not a byte cursor
 *
 * A byte cursor names a position inside a stream, and a stream belongs to one
 * operation of one run. A frame ordinal names a position in the console of a
 * run, which is what a reader of the console asks for and what the service
 * can answer.
 */
object FrameOrdinalToken {

    /**
     * The prefix of the family, and the word the service refuses with when a
     * client offers a token of another family.
     *
     * A `val` and not a `const`: a `const` is INLINED into every consumer at
     * compile time, so changing its value would leave every consumer holding
     * the old prefix, and the ABI check would see nothing — a silent break of
     * the wire's vocabulary. Read through a getter, the value travels with the
     * artifact, and a consumer that upgrades picks the new one up.
     */
    val PREFIX: String = "out-frame-ord-v1"

    /**
     * The token that names the position after [ordinal].
     */
    fun encode(ordinal: Long): String = "$PREFIX:$ordinal"

    /**
     * The ordinal [token] names, or `null` when it names nothing of this family.
     *
     * A byte-cursor token is refused here as well: the two planes have two
     * decoders, and a decoder that accepted both would advance a reader to a
     * position in the wrong bytes.
     */
    fun decode(token: String): Long? {
        val parts = token.split(':')
        if (parts.size != 2) return null
        if (parts[0] != PREFIX) return null
        return parts[1].toLongOrNull()?.takeIf { it >= -1L }
    }
}
