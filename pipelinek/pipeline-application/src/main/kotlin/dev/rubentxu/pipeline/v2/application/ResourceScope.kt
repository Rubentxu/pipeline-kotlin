package dev.rubentxu.pipeline.v2.application

/**
 * A scope that owns the resources acquired inside it.
 *
 * ## THE RULE, IN ONE PLACE
 * A resource belongs to the scope that acquired it, from the moment it is
 * acquired until the scope ends. A scope ends by giving back everything it
 * holds, and it ends the same way on every path: a run that succeeds, a run
 * that fails and a run that refuses all give back the same resources, in the
 * same order.
 *
 * ## WHY THE ORDER IS REVERSED
 * A resource acquired later depends on the one acquired before it, so giving
 * them back in the reverse order is what keeps a release from touching
 * something that is already gone. The scope remembers the order; the place
 * that acquires a resource says what giving it back means.
 *
 * ## WHY A SCOPE AT ALL
 * A process that ends while it holds a lock does not release it: the operating
 * system does, and the record the lock left behind still claims a live owner.
 * A refusal is a decision about the run, not a reason to abandon what the run
 * already owns. The scope is what lets a decision be made without the process
 * ending in the middle of it.
 */
internal class ResourceScope : AutoCloseable {
    private val releases = mutableListOf<() -> Unit>()

    /**
     * Take ownership of a resource.
     *
     * [release] gives it back, and it runs when the scope closes, after every
     * resource acquired later has been given back.
     */
    fun own(release: () -> Unit) {
        releases += release
    }

    override fun close() {
        for (release in releases.asReversed()) {
            // A release that fails must not hide the outcome that made the
            // scope close, and it must not keep the other resources from being
            // given back.
            runCatching { release() }
        }
        releases.clear()
    }
}
