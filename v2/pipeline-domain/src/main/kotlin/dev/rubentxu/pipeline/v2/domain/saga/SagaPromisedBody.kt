package dev.rubentxu.pipeline.v2.domain.saga

/**
 * The structure of a compensating body, as a digest.
 *
 * ## What a digest is NOT
 *
 * It is not the body's NAME and not the slot it is reached by. A slot is a LOCAL name: it means
 * something only inside the body that declares it, so two bodies of two plugins can carry the
 * same slot name and mean two different things. A digest is the body's SHAPE — its children,
 * their kinds, their payloads — so it means the same thing everywhere, which is the only way to
 * compare a body that was promised against a body that is about to run.
 *
 * ## Why the shape and not the body
 *
 * A body is code: it cannot be written down, compared or stored. What CAN be written down is the
 * shape of it, and the shape is what decides whether the body that is about to run is the body
 * that was promised. A body whose payload changed is a different body, even though it answers to
 * the same name.
 */
data class CompensationBodyDigest(val hex: String) {

    init {
        require(hex.isNotBlank()) { "a body digest is not blank" }
    }
}

/**
 * What a promised body means when it meets the body that is about to run.
 *
 * ## The vocabulary is closed
 *
 * There are exactly two answers, and both of them are decisions rather than states: either the
 * body that will run IS the body that was promised, or it is not. A third case — "the body
 * cannot be found", "the body is unknown" — would be a way of NOT deciding, and a way of not
 * deciding here is a way of running somebody else's body. The absence of a body is therefore
 * NOT a case of its own: it is a [Diverged] whose current body is absent, because a promise
 * that named a body and a body that is not there do not agree.
 */
sealed interface SagaBodyVerdict {

    /**
     * The promise and the body that will run agree.
     *
     * It also covers the promise that named NO body: a promise that says nothing about a body
     * cannot contradict one, so there is nothing to refuse. See [SagaBodyPromise] for why that
     * is the safe reading and not a loophole.
     */
    data object Agreed : SagaBodyVerdict

    /**
     * The promise named a body and the body that will run is a different one.
     *
     * @property promised the digest of the body the promise named.
     * @property current the digest of the body that is about to run, or `null` when there is no
     * such body: the slot named nothing, or named several children at once.
     */
    data class Diverged(
        val promised: CompensationBodyDigest,
        val current: CompensationBodyDigest?,
    ) : SagaBodyVerdict {

        /**
         * The sentence an operator reads.
         *
         * It says BOTH digests, because "the body changed" is not actionable: what the reader
         * needs is which body was promised and which one was going to run, so they can go and
         * look at those two bodies. A reason that only says "they differ" sends the reader
         * hunting.
         */
        fun describe(): String {
            val running = current?.hex ?: "no body"
            return "the promise named body '${promised.hex}' and the body that would run is " +
                "'$running'"
        }
    }
}

/**
 * Whether the body that is about to run is the body that was promised.
 *
 * ## The rule
 *
 * A promise that named a body is paid with THAT body or not at all. If the body that is about to
 * run is a different one — or is not there — the promise is not kept by running it, and the
 * engine says so instead of undoing the effect with a body nobody promised.
 *
 * ## What is deliberately NOT refused
 *
 * A promise that named NO body is not refused, and this is the same principle the ownership rule
 * follows: a promise that cannot prove anything cannot block anything. Refusing it would strand
 * promises that are perfectly valid — a promise armed by a build that did not keep the digest,
 * or a promise about an action that has nothing to undo.
 *
 * The price of that leniency is stated rather than hidden: a promise that names no body is a
 * promise the engine cannot CHECK, so it is the plugin's job to name one when it means to be
 * checked.
 */
object SagaBodyPromise {

    /**
     * Whether the body that is about to run, [current], is the body the promise named,
     * [promised].
     *
     * Pure and total: it answers for every pair, including the two absences — a promise that
     * named nothing, and a body that is not there.
     *
     * @param promised the digest of the body the promise named, or `null` when it named none.
     * @param current the digest of the body that is about to run, or `null` when there is none.
     */
    fun verdict(
        promised: CompensationBodyDigest?,
        current: CompensationBodyDigest?,
    ): SagaBodyVerdict {
        if (promised == null) return SagaBodyVerdict.Agreed
        if (current == null) return SagaBodyVerdict.Diverged(promised = promised, current = null)
        if (promised == current) return SagaBodyVerdict.Agreed
        return SagaBodyVerdict.Diverged(promised = promised, current = current)
    }
}
