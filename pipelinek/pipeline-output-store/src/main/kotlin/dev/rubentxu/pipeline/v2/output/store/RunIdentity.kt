package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.domain.RunId

/**
 * The on-disk name of a run in the artifact plane.
 *
 * ## The name IS the identity
 *
 * A run's directory here is named after the run, so the name has to keep two
 * runs apart: `run-7` and `run_7` are two different runs and must never share
 * a directory, a frame file or an owner record.
 *
 * ## Why the identity is refused instead of folded
 *
 * Folding every character outside `[a-z0-9._-]` onto `_` is irreversible,
 * and it makes `a/b` and `a_b` the same directory: two different runs, one
 * directory, and the frame index of one answering with the frames of the
 * other. A collision that is silent has to be prevented where the name is
 * decided, which is here.
 *
 * ## Who decides, and why there is only one decision
 *
 * The rule belongs to [RunId], the type that says what a run identity is, so
 * this asks it instead of keeping a second copy of the alphabet. The durable
 * lease already answers the same question the same way, which is what makes
 * the two planes agree: an identity the lease can name is an identity this
 * plane can name, and an identity neither can name is refused by both.
 *
 * ## What it refuses
 *
 * An identity that cannot name a directory: one holding a path separator, or
 * a character outside the durable alphabet. The alphabet — and the reason it
 * admits ASCII lowercase only, so that a name cannot depend on the
 * filesystem's case distinction — belongs to [RunId], and this asks it instead
 * of keeping a second copy. Refusing an identity is also what keeps the
 * artifact root the boundary it is — a name is never assembled out of an
 * identity the operator supplied.
 */
internal fun runDirectoryName(runId: String): String {
    if (!RunId.isNameable(runId)) throw UnnameableRunIdentity(runId)
    return runId
}

/**
 * The on-disk namespace of a run: its identity, and the prefix every directory it owns carries.
 *
 * The prefix is the run's name and the separator that follows it. The identity is NOT folded, and an
 * identity that cannot name a directory is refused rather than repaired — see [runDirectoryName].
 *
 * ## What the prefix is not
 * A prefix is a filter, and a filter that authorises a deletion is an identity whether it was meant
 * to be one or not: "run_" is also the beginning of "run_x_". So the prefix is only ever a LAST
 * resort, and the rule that uses it says which evidence answers first.
 *
 * @property runId the run's identity, as the caller stated it.
 */
internal data class RunNamespace(val runId: String) {

    /**
     * The prefix every directory of this run's streams carries.
     *
     * It is derived from the identity by the one rule that names a run's directories, so a namespace
     * cannot be built whose prefix belongs to another run, and an identity that cannot name a
     * directory is refused here rather than repaired.
     */
    val prefix: String = runDirectoryName(runId) + "_"
}
