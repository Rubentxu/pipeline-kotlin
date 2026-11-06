package dev.rubentxu.pipeline.v2.sdk.scm.git.step

import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.sdk.scm.git.GitLocalState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The state of the tree travels with the output of the checkout.
 *
 * **The guarantee.** The output says in which state the checkout found the
 * tracked content of the tree, and a payload that does not carry that state says
 * that it does not know it. It never says "clean" about a tree nobody inspected.
 *
 * **Why the instrument is the codec.** The observable contract of this output is
 * the JSON that crosses the step boundary: what a consumer reads is what the
 * payload carries. A test that asserted on the data class instead would certify
 * the shape of a Kotlin object and not the thing that travels.
 */
class GitCheckoutOutputCodecTest {

    @Test
    fun `the state of the tree travels with the output`() {
        val output = GitCheckoutOutput(
            resolvedSha = SHA,
            localPath = "/workspace",
            wasCloned = false,
            credentialApplied = false,
            remotePolled = true,
            localState = GitLocalState.MODIFIED,
        )

        val decoded = GitCheckoutOutputCodec.decode(GitCheckoutOutputCodec.encode(output))

        assertEquals(
            GitLocalState.MODIFIED,
            decoded.localState,
            "a consumer that reads the output has to be able to tell that the tree the " +
                "checkout left behind carries changes that are not the run's",
        )
    }

    @Test
    fun `a payload encoded before the field existed says the state is not known`() {
        val decoded = GitCheckoutOutputCodec.decode(
            EncodedStepValue(
                """{"resolvedSha":"$SHA","localPath":"/workspace","wasCloned":false,""" +
                    """"credentialApplied":false,"remotePolled":true}"""
            )
        )

        assertEquals(
            GitLocalState.UNKNOWN,
            decoded.localState,
            "that checkout never looked at the tree, so the payload says the state is not " +
                "known. It does NOT say the tree was clean",
        )
    }

    @Test
    fun `a payload that names a state that does not exist is refused`() {
        val refused = assertThrows(IllegalStateException::class.java) {
            GitCheckoutOutputCodec.decode(
                EncodedStepValue(
                    """{"resolvedSha":"$SHA","localPath":"/workspace","wasCloned":false,""" +
                        """"credentialApplied":false,"remotePolled":true,"localState":"DIRTY"}"""
                )
            )
        }

        assertEquals(
            true,
            refused.message!!.contains("local state that does not exist"),
            "the refusal has to say what was wrong with the payload; it said: ${refused.message}",
        )
    }

    private companion object {
        /** A commit id. */
        const val SHA = "1111111111111111111111111111111111111111"
    }
}
