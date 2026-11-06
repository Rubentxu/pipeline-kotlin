package dev.rubentxu.pipeline.v2.sdk.scm.git.step

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.scm.GitRemotePolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ProcessOutputRefusal
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitCheckoutRefusal
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitCheckoutRefused
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitLocalState
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitTranscriptRefused
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The kind of failure a checkout refusal carries.
 *
 * **The guarantee.** The kind of a failure is decided by the TYPE of the failure
 * and by nothing else — not by the words of its message, which may carry a
 * credential the run already scrubbed. A refusal over a tree that carries local
 * work is a decision the run's owner has to make, not a broken machine, and it
 * says so by its type.
 *
 * **Why the instrument is the classifier and not the Step.** What decides this
 * guarantee is the mapping from a type of failure to a kind. The Step is the
 * place the mapping is applied, and a test that drove the whole Step to observe
 * one word of the mapping would also need a workspace, a transcript and a git
 * that fails on cue — the E2E layer AGENTS.md section 2 forbids, for a question
 * the mapping answers on its own.
 */
class GitCheckoutFailureKindTest {

    @Test
    fun `a refusal over a tree that carries local work is a decision, not a broken machine`() {
        val refusal = GitCheckoutRefused(
            reason = GitCheckoutRefusal.LOCAL_CHANGES,
            declaredSha = SHA,
            localState = GitLocalState.MODIFIED,
        )

        assertEquals(
            FailureKind.USER,
            classifyCheckoutFailure(refusal),
            "the tree the run was pointed at holds changes the run did not make: the remedy " +
                "is a decision about that tree, and no retry resolves it",
        )
    }

    @Test
    fun `a remote the policy refuses is a decision of the author`() {
        val refusal = GitRemotePolicy.GitRemoteRefused("the remote may not be used")

        assertEquals(
            FailureKind.USER,
            classifyCheckoutFailure(refusal),
            "the author wrote a remote that may not be used, and nothing about the machine " +
                "decides it",
        )
    }

    @Test
    fun `a transcript that could not be persisted is a fault of the machine`() {
        val refusal = GitTranscriptRefused(
            ProcessOutputRefusal.WriteFailed(
                detail = "the destination refused the bytes",
                cause = IllegalStateException("the sink is closed"),
            )
        )

        assertEquals(
            FailureKind.INFRASTRUCTURE,
            classifyCheckoutFailure(refusal),
            "the run's record of what the checkout printed is missing: that is an " +
                "infrastructure fault whatever the git command said",
        )
    }

    @Test
    fun `a failure that is not one of the typed ones keeps the default kind`() {
        assertEquals(
            FailureKind.INFRASTRUCTURE,
            classifyCheckoutFailure(IllegalStateException("the checkout failed")),
            "a failure nobody typed keeps the kind it always had",
        )
    }

    private companion object {
        /** A commit id. */
        const val SHA = "1111111111111111111111111111111111111111"
    }
}
