package dev.rubentxu.pipeline.v2.sdk.scm.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The decision the checkout makes about the tree it found.
 *
 * **The guarantee.** The decision is a function of three facts — the commit the
 * tree holds, the commit the run declared, and the state of the tree's tracked
 * content — and the four combinations of "the tree is clean / the tree carries
 * work" and "the declared commit changed / it did not" land on four DIFFERENT
 * answers. A decision that answered the same way for a clean tree and a dirty one
 * would be the no-op that announces a clean checkout over a dirty tree.
 *
 * **Why the instrument is the function itself.** The decider is pure: no git, no
 * clock, no workspace, no process. A test that set up a repository would exercise
 * git and the checkout rather than the decision, and the E2E layer AGENTS.md
 * section 2 forbids would prove the same thing by the same call. The doubles live
 * in [GitCheckoutTestSupport] and answer the OTHER question — what the checkout
 * does once it has decided.
 */
class GitCheckoutDecisionTest {

    @Test
    fun `a destination without a checkout is materialised`() {
        val decision = GitCheckoutDecision.decide(
            treeExists = false,
            headSha = null,
            declaredSha = DECLARED,
            localState = GitLocalState.CLEAN,
        )

        assertEquals(
            GitCheckoutDecision.Proceed(GitCheckoutOutcome.CLONED, GitLocalState.CLEAN, DECLARED),
            decision,
            "there is no tree to move and nothing to preserve: the checkout materialises one",
        )
    }

    @Test
    fun `a tree that holds the declared commit is left alone`() {
        val decision = GitCheckoutDecision.decide(
            treeExists = true,
            headSha = DECLARED,
            declaredSha = DECLARED,
            localState = GitLocalState.CLEAN,
        )

        assertEquals(
            GitCheckoutDecision.Proceed(GitCheckoutOutcome.ALREADY_CURRENT, GitLocalState.CLEAN, DECLARED),
            decision,
            "the tree already holds the declared commit: there is nothing to download and " +
                "nothing to move",
        )
    }

    @Test
    fun `a tree that holds the declared commit and carries work is left alone and the work is named`() {
        val decision = GitCheckoutDecision.decide(
            treeExists = true,
            headSha = DECLARED,
            declaredSha = DECLARED,
            localState = GitLocalState.MODIFIED,
        )

        assertEquals(
            GitCheckoutDecision.Proceed(GitCheckoutOutcome.ALREADY_CURRENT, GitLocalState.MODIFIED, DECLARED),
            decision,
            "the commit is the declared one and the tree is left alone, but the tree carries " +
                "changes that are not the run's: the answer has to name them, or the caller " +
                "reads a clean checkout over a dirty tree",
        )
    }

    @Test
    fun `a tree that does not hold the declared commit and carries no work is moved`() {
        val decision = GitCheckoutDecision.decide(
            treeExists = true,
            headSha = OTHER,
            declaredSha = DECLARED,
            localState = GitLocalState.CLEAN,
        )

        assertEquals(
            GitCheckoutDecision.Proceed(GitCheckoutOutcome.UPDATED, GitLocalState.CLEAN, DECLARED),
            decision,
            "the tree does not hold the declared commit and nothing is at risk: it is moved",
        )
    }

    @Test
    fun `a tree that does not hold the declared commit and carries work is refused`() {
        val decision = GitCheckoutDecision.decide(
            treeExists = true,
            headSha = OTHER,
            declaredSha = DECLARED,
            localState = GitLocalState.MODIFIED,
        )

        assertEquals(
            GitCheckoutDecision.Refuse(GitCheckoutRefusal.LOCAL_CHANGES, GitLocalState.MODIFIED, DECLARED),
            decision,
            "moving a tree that carries work this run did not make would destroy it: the " +
                "checkout does not move it",
        )
    }

    @Test
    fun `a tree whose state could not be read is refused rather than moved`() {
        val decision = GitCheckoutDecision.decide(
            treeExists = true,
            headSha = OTHER,
            declaredSha = DECLARED,
            localState = GitLocalState.UNKNOWN,
        )

        assertEquals(
            GitCheckoutDecision.Refuse(GitCheckoutRefusal.UNKNOWN_STATE, GitLocalState.UNKNOWN, DECLARED),
            decision,
            "a tree nobody inspected is not moved: the guess the checkout would have to make " +
                "is that there is nothing to lose",
        )
    }

    @Test
    fun `a remote that was not consulted leaves the tree alone and publishes its local state`() {
        val decision = GitCheckoutDecision.decide(
            treeExists = true,
            headSha = OTHER,
            declaredSha = null,
            localState = GitLocalState.MODIFIED,
        )

        assertEquals(
            GitCheckoutDecision.Proceed(GitCheckoutOutcome.LOCAL_ONLY, GitLocalState.MODIFIED, null),
            decision,
            "nothing declares a commit to move to, so the tree stays as it is and the state " +
                "it is in is published as the local state it is",
        )
    }

    @Test
    fun `a tree with no commit and a remote that was not consulted is refused`() {
        val decision = GitCheckoutDecision.decide(
            treeExists = true,
            headSha = null,
            declaredSha = null,
            localState = GitLocalState.CLEAN,
        )

        assertEquals(
            GitCheckoutDecision.Refuse(GitCheckoutRefusal.NO_COMMIT, GitLocalState.CLEAN, null),
            decision,
            "there is nothing to publish: the remote was not consulted and the tree holds no " +
                "commit",
        )
    }

    @Test
    fun `a tree with no commit and a declared commit is moved`() {
        val decision = GitCheckoutDecision.decide(
            treeExists = true,
            headSha = null,
            declaredSha = DECLARED,
            localState = GitLocalState.CLEAN,
        )

        assertEquals(
            GitCheckoutDecision.Proceed(GitCheckoutOutcome.UPDATED, GitLocalState.CLEAN, DECLARED),
            decision,
            "a tree that holds no commit holds nothing that could be lost: it is moved",
        )
    }

    private companion object {
        /** The commit the run declared. */
        const val DECLARED = "1111111111111111111111111111111111111111"

        /** The commit the tree held, which is not the declared one. */
        const val OTHER = "2222222222222222222222222222222222222222"
    }
}
