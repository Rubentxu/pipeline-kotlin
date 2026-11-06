package dev.rubentxu.pipeline.v2.sdk.scm.git

/**
 * What the checkout did with the workspace.
 *
 * The classification used to be a STRING ("no-op", "fetch+reset", "clone"), and
 * a string is a promise nobody can check: the only reader compared it with
 * `== "clone"`, so a fourth outcome had nowhere to go and a typo in the
 * comparison read as "not a clone" instead of as a defect. A closed set of
 * cases says what happened, and the compiler says when a reader has forgotten
 * one of them.
 */
enum class GitCheckoutOutcome {
    /** The working tree was materialised by cloning the declared remote and branch. */
    CLONED,

    /** The declared branch was moved to the head the remote declared. */
    UPDATED,

    /** The working tree already held the head the remote declared: there was nothing to do. */
    ALREADY_CURRENT,

    /**
     * The remote was NOT consulted: the published state is the commit the local
     * working tree already held.
     *
     * This is the outcome of a checkout that declared `poll=false` over an
     * existing checkout. It says what it is so that a reader cannot mistake a
     * local state for a remote one.
     */
    LOCAL_ONLY,
}
