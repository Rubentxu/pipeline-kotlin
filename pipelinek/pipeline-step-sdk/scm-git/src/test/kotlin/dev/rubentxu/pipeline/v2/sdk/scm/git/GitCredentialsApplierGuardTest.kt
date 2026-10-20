package dev.rubentxu.pipeline.v2.sdk.scm.git

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The guard exists so a credential never reaches `argv` — and it must not become
 * the reason one reaches the event log.
 * **The guarantee under test.** When argv is refused because it carries a
 * credential, the refusal identifies the offending argument well enough for an
 * operator to act on, and never reproduces the credential itself. This matters
 * because a refusal message is not a local throwaway: the guard runs OUTSIDE
 * `GitCheckoutExecutor`'s `try`, so the exception is not caught by the scrub,
 * and `RegistryExecutionBoundary` copies `e.message` straight into a durable
 * `StepFailed` event. Whatever this guard prints is what gets persisted.
 * Pure: both functions are on the companion object, take a `String` or a
 * `List<String>`, and touch no filesystem and no clock.
*/
class GitCredentialsApplierGuardTest {

    /** A password that appears ONLY inside the URL below. */
    private val password = "s3cr3t-p4ssw0rd"

    private val urlWithCredentials = "https://build-user:$password@git.example.test/org/repo.git"

    @Test
    fun `the URL guard refuses argv carrying embedded credentials`() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            GitCredentialsApplier.guardProcessBuilderArgs(
                listOf("git", "ls-remote", urlWithCredentials, "main"),
            )
        }

        assertTrue(
            thrown.message!!.contains("git.example.test"),
            "the operator must still learn WHICH remote was offending, or the guard is " +
                "useless; message was: ${thrown.message}",
        )
    }

    @Test
    fun `the URL refusal never reproduces the credential`() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            GitCredentialsApplier.guardProcessBuilderArgs(
                listOf("git", "ls-remote", urlWithCredentials, "main"),
            )
        }

        assertFalse(
            thrown.message!!.contains(password),
            "THE DEFECT: the refusal echoed the argument it had just identified as " +
                "carrying a credential, so the password it refused to put in argv was " +
                "written into a durable event instead. Message was: ${thrown.message}",
        )
        assertFalse(
            thrown.message!!.contains("build-user"),
            "the userinfo half is as sensitive as the password; message was: ${thrown.message}",
        )
    }

    @Test
    fun `the forbidden-substring refusal never echoes the argument`() {
        val bearer = "Authorization: Bearer " + "glpat-also-a-secret"
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            GitCredentialsApplier.guardProcessBuilderArgs(
                listOf("git", "config", "http.extraheader=$bearer"),
            )
        }

        assertFalse(
            thrown.message!!.contains("glpat-also-a-secret"),
            "the token is the whole payload of that argument; echoing the argument " +
                "publishes it. Message was: ${thrown.message}",
        )
    }

    @Test
    fun `extracting a host from a URL with no host does not echo the URL`() {
        // The userinfo starts at the colon, so stripping "host" leaves nothing and the
        // function refuses. `https://user:$password@` would NOT reach that branch: it
        // yields the user "user" and returns normally, which is why this input is not
        // the obvious one.
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            GitCredentialsApplier.extractHost("https://:$password@")
        }

        assertFalse(
            thrown.message!!.contains(password),
            "a URL that cannot yield a host is exactly a URL whose message would carry " +
                "whatever it carried; message was: ${thrown.message}",
        )
    }
}
