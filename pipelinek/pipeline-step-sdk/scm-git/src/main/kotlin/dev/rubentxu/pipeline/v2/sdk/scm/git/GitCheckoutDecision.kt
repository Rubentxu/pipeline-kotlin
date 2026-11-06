package dev.rubentxu.pipeline.v2.sdk.scm.git

/**
 * What the checkout decided to do with the tree it found.
 *
 * The decision is a PURE function of three facts — the commit the tree holds, the
 * commit the run declared, and the state of the tree's tracked content — and of
 * nothing else: no git, no clock, no workspace. A decision that could not be
 * computed without asking git is a decision nobody can test on its own, and the
 * cases that matter (a tree that is clean against a tree that is not) are exactly
 * the ones a test cannot set up with a repository.
 *
 * **The policy this decision carries.** The checkout never discards local changes.
 * It does not move a tree that carries work it did not make, and it says so instead
 * of destroying it. A tree whose state could not be read is not moved either: the
 * checkout does not guess, because the guess it would have to make is "there is
 * nothing to lose".
 */
sealed interface GitCheckoutDecision {

    /**
     * The checkout goes on.
     *
     * @param outcome what the checkout does with the workspace. The vocabulary lives
     *   in [GitCheckoutOutcome] and nowhere else: the decision names the outcome, it
     *   does not invent a second name for it.
     * @param localState the state of the tracked content of the tree the checkout
     *   leaves behind. It travels with the outcome because the two are what a
     *   reader needs to tell an idempotency of the COMMIT from an idempotency of
     *   the TREE.
     */
    data class Proceed(
        val outcome: GitCheckoutOutcome,
        val localState: GitLocalState,
        /**
         * The commit the run declared, when there was one: the commit the checkout
         * installs, or the one it found already installed.
         */
        val declaredSha: String?,
    ) : GitCheckoutDecision

    /**
     * The checkout refuses to act on the tree it found.
     *
     * @param reason why the checkout refused.
     * @param localState the state the tree was found in.
     * @param declaredSha the commit the run declared, when there was one: the commit
     *   the checkout did NOT install.
     */
    data class Refuse(
        val reason: GitCheckoutRefusal,
        val localState: GitLocalState,
        val declaredSha: String?,
    ) : GitCheckoutDecision

    companion object {

        /**
         * The decision the checkout makes about the tree it found.
         *
         * @param treeExists whether a checkout is already at the destination.
         * @param headSha the commit the tree holds, or null when it holds none.
         * @param declaredSha the commit the remote declared, or null when the remote
         *   was not consulted at all (`poll=false`).
         * @param localState the state of the tracked content of the tree.
         */
        fun decide(
            treeExists: Boolean,
            headSha: String?,
            declaredSha: String?,
            localState: GitLocalState,
        ): GitCheckoutDecision {
            // There is no checkout at the destination: there is nothing to move and
            // nothing to preserve, and the tree a clone leaves behind is clean by
            // construction.
            if (!treeExists) {
                return Proceed(GitCheckoutOutcome.CLONED, GitLocalState.CLEAN, declaredSha)
            }

            // The remote was not consulted: nothing declares a commit to move to, so
            // the tree is left exactly as it was found and what it holds is published
            // as the LOCAL state it is.
            if (declaredSha == null) {
                return if (headSha == null) {
                    Refuse(GitCheckoutRefusal.NO_COMMIT, localState, null)
                } else {
                    Proceed(GitCheckoutOutcome.LOCAL_ONLY, localState, null)
                }
            }

            // The tree already holds the commit the remote declared: there is nothing
            // to download and nothing to move. The state the tree is in is still
            // published, because a tree that carries changes is not a clean checkout
            // even when its commit is the declared one.
            if (headSha == declaredSha) {
                return Proceed(GitCheckoutOutcome.ALREADY_CURRENT, localState, declaredSha)
            }

            // The tree does not hold the declared commit: whether it may be moved is
            // answered by the state of its tracked content. A tree that carries work
            // this run did not make is not moved — a checkout that does not force
            // carries those changes over to the new commit (measured with git
            // 2.56.0), and the tree it leaves behind is then neither the old commit
            // nor the new one.
            return when (localState) {
                GitLocalState.CLEAN ->
                    Proceed(GitCheckoutOutcome.UPDATED, localState, declaredSha)
                GitLocalState.MODIFIED ->
                    Refuse(GitCheckoutRefusal.LOCAL_CHANGES, localState, declaredSha)
                GitLocalState.UNKNOWN ->
                    Refuse(GitCheckoutRefusal.UNKNOWN_STATE, localState, declaredSha)
            }
        }
    }
}

/**
 * Why the checkout refused to act on the tree it found.
 *
 * The reason is a value and not a sentence: the caller classifies the refusal by
 * it, and a message that says the same thing in five words says it in one.
 */
enum class GitCheckoutRefusal {

    /**
     * The tree carries changes that this run did not make, and moving it would
     * destroy them.
     */
    LOCAL_CHANGES,

    /**
     * The state of the tree could not be read, so the checkout cannot tell whether
     * moving it would destroy work nobody saw.
     */
    UNKNOWN_STATE,

    /**
     * The remote was not consulted and the tree holds no commit to publish.
     */
    NO_COMMIT,
}

/**
 * The checkout refused to act on the tree it found.
 *
 * It is a TYPED refusal: the caller reads [reason] instead of reading a message and
 * matching words in it. The message exists for the human who reads the run's
 * record, and it says what was refused and what the remedy is.
 */
class GitCheckoutRefused(
    /** Why the checkout refused. */
    val reason: GitCheckoutRefusal,
    /** The commit the run declared, when there was one. */
    val declaredSha: String?,
    /** The state the tree was found in. */
    val localState: GitLocalState,
) : IllegalStateException(refusalMessage(reason, declaredSha, localState))

/**
 * The diagnostic a refusal travels with.
 *
 * It names the commit the checkout did NOT install and the state it found, because
 * a refusal that does not say what was at stake leaves the reader guessing which of
 * the tree's changes survived.
 */
private fun refusalMessage(
    reason: GitCheckoutRefusal,
    declaredSha: String?,
    localState: GitLocalState,
): String = when (reason) {
    GitCheckoutRefusal.LOCAL_CHANGES ->
        "scm-git.checkout: the working tree carries local changes that this run did not " +
            "make, and moving it to $declaredSha would destroy them. The checkout never " +
            "discards local changes: clean the tree, or point the checkout at a tree that " +
            "is already clean. (the tree was found $localState)"
    GitCheckoutRefusal.UNKNOWN_STATE ->
        "scm-git.checkout: the state of the working tree could not be read, so the checkout " +
            "cannot tell whether moving it to $declaredSha would destroy local work. It " +
            "refuses to move a tree it could not inspect. (the tree was found $localState)"
    GitCheckoutRefusal.NO_COMMIT ->
        "scm-git.checkout: the working tree has no commit to publish: the remote was not " +
            "consulted (poll=false) and there is no HEAD to read"
}
