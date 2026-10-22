package dev.rubentxu.pipeline.v2.sdk.scm.git

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The argv guard: the decision that keeps a credential out of the process table.
 *
 * **The guarantee.** A credential must never travel in `argv`. `argv` is world-readable
 * through `/proc`, and it is written to every log line that prints a command. The guard is
 * the one place that decides whether a git invocation may be spawned, so the decision lives
 * here and nowhere else.
 *
 * **The dangerous row is the one that looks harmless.** `https://TOKEN@host/repo` carries no
 * password, so a rule that only recognises `user:pass@` waves it through — and that URL is
 * exactly how a personal access token is used against a forge: the token IS the username. A
 * rule that only recognised the user-and-password form would let that token travel.
 *
 * The other row that is easy to miss is the capitalised scheme: `HTTPS://user:pass@host` is
 * the same URL, and a rule that matches the original argument while lowercasing everything
 * else refuses to see it.
 *
 * **The allowed rows are as much a part of the table as the refused ones.** A guard that
 * refuses everything protects nothing: it gets turned off, and then it protects nothing at
 * all. The ordinary shapes of a git invocation — an SSH user, an `@` inside a path, a port —
 * have to keep working, and this table is what says so.
 */
class GitArgvGuardTest {

    @Test
    fun `an argv that carries no credential is accepted`() {
        val allowed = listOf(
            listOf("git", "ls-remote", "https://github.com/org/repo.git", "main"),
            // scp-style SSH syntax: the `git@` is a user, and SSH keys never travel in argv.
            listOf("git", "clone", "git@github.com:org/repo.git"),
            // An SSH URL whose user is `git`: a name, not a secret.
            listOf("git", "clone", "ssh://git@github.com/org/repo.git"),
            // An `@` in the PATH is not a credential: this pins a tag named `v1`.
            listOf("git", "clone", "https://github.com/org/repo.git@v1"),
            // A port is not a password.
            listOf("git", "clone", "https://github.com:443/org/repo.git"),
        )

        allowed.forEach { args ->
            GitCredentialsApplier.guardProcessBuilderArgs(args)
        }
    }

    @Test
    fun `a credential carried as an http URL USERNAME is refused`() {
        // The token form: no password, and the username is the secret.
        assertRefusedAsUrlCredential("git", "clone", "https://ghp_TOKEN@github.com/org/repo.git")
    }

    @Test
    fun `a credential carried as an http URL userinfo is refused`() {
        assertRefusedAsUrlCredential("git", "clone", "https://user:pass@github.com/org/repo.git")
    }

    @Test
    fun `the URL rule does not depend on the case of the scheme`() {
        assertRefusedAsUrlCredential("git", "clone", "HTTPS://user:pass@github.com/org/repo.git")
        assertRefusedAsUrlCredential("git", "clone", "HtTpS://ghp_TOKEN@github.com/org/repo.git")
    }

    @Test
    fun `a password carried in a NON-http URL is refused`() {
        assertRefusedAsUrlCredential("git", "clone", "ssh://user:pass@github.com/org/repo.git")
    }

    @Test
    fun `the forbidden substrings are refused whatever their case`() {
        listOf(
            listOf("git", "-c", "http.extraHeader=Authorization: Bearer abc", "clone", "https://github.com/org/repo.git"),
            listOf("git", "-c", "http.EXTRAHEADER=Authorization: Bearer abc", "clone", "https://github.com/org/repo.git"),
            listOf("git", "clone", "https://github.com/org/repo.git", "--header", "AUTHORIZATION: Bearer abc"),
        ).forEach { args ->
            val thrown = assertThrows<IllegalArgumentException> {
                GitCredentialsApplier.guardProcessBuilderArgs(args)
            }
            assertTrue(
                thrown.message.orEmpty().contains("Forbidden substring"),
                "the refusal must name the substring rule, but said: ${thrown.message}",
            )
        }
    }

    private fun assertRefusedAsUrlCredential(vararg args: String) {
        val thrown = assertThrows<IllegalArgumentException> {
            GitCredentialsApplier.guardProcessBuilderArgs(args.toList())
        }
        assertTrue(
            thrown.message.orEmpty().contains("embedded credentials"),
            "the refusal must name the URL-credential rule, but said: ${thrown.message}",
        )
    }
}
