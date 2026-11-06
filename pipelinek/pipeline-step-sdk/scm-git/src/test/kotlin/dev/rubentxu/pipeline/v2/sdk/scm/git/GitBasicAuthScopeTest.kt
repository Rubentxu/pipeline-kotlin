package dev.rubentxu.pipeline.v2.sdk.scm.git

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The Basic channel, measured against the git that consumes it.
 *
 * **The guarantee.** A checkout that declared a user name and a password puts
 * ONE header in front of the remote it named: that header reaches THAT remote
 * and not another one, it carries the credentials the checkout declared, and
 * no configuration that the machine happens to carry adds a second header to
 * the same request.
 *
 * **Why the instrument is the real git.** The scope of a credential is not a
 * fact about this product: it is a PATTERN that git matches against every
 * request it makes. A test that only read the text of the configuration would
 * certify that the file says what the test expects — which it does even when
 * git would match no request with it. The question "does this header reach
 * this remote?" has no answer without git.
 *
 * **Why the host is not a rule of ours.** git's own parser accepts `localhost`
 * and IPv6 literals, and the port belongs to the authority: a remote that
 * names one is a different host as far as git is concerned. A rule here that
 * rejected them would be a second rule about a fact git already owns, and its
 * branch would be reachable and wrong.
 *
 * **Why the configuration of the machine is measured too.** git reads the
 * configuration of the system in addition to the one of the checkout, and a
 * machine-wide `http.extraHeader` is a SECOND header on the same request, from
 * a configuration nobody declared for this checkout.
 *
 * **Why the HOME is isolated.** Without a HOME of its own, git reads the
 * configuration of whoever runs the test, and the header this instrument
 * reports would be a header of the machine and not of the checkout.
 */
class GitBasicAuthScopeTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun `the header reaches the remote the checkout named`() {
        val applier = applier()
        applier.apply(USER, PASSWORD.toByteArray(), "https://github.com/org/repo.git")

        val matched = urlMatch(applier, "https://github.com/org/repo.git")

        assertEquals(
            0,
            matched.exitCode,
            "the header the checkout wrote does not reach the remote it named, so the remote " +
                "would be asked for a password it never gets; git said: ${matched.output}",
        )
        assertTrue(
            matched.output.contains("Authorization: Basic"),
            "git matched the scope but did not return the header; git said: ${matched.output}",
        )
        assertEquals(
            "$USER:$PASSWORD",
            decodedCredentials(matched.output),
            "the header carries something other than the credentials the checkout declared",
        )
    }

    @Test
    fun `the header reaches a remote whose host is localhost`() {
        val applier = applier()
        applier.apply(USER, PASSWORD.toByteArray(), "https://localhost:8443/org/repo.git")

        val matched = urlMatch(applier, "https://localhost:8443/org/repo.git")

        assertEquals(
            0,
            matched.exitCode,
            "the header the checkout wrote does not reach a remote whose host is localhost, so " +
                "the remote would be asked for a password it never gets; git said: ${matched.output}",
        )
        assertTrue(
            matched.output.contains("Authorization: Basic"),
            "git matched the scope but did not return the header; git said: ${matched.output}",
        )
    }

    @Test
    fun `the header reaches a remote whose host is an IPv6 literal`() {
        val applier = applier()
        applier.apply(USER, PASSWORD.toByteArray(), "https://[::1]:8443/org/repo.git")

        val matched = urlMatch(applier, "https://[::1]:8443/org/repo.git")

        assertEquals(
            0,
            matched.exitCode,
            "the header the checkout wrote does not reach a remote whose host is an IPv6 " +
                "literal, so the remote would be asked for a password it never gets; git said: " +
                "${matched.output}",
        )
        assertTrue(
            matched.output.contains("Authorization: Basic"),
            "git matched the scope but did not return the header; git said: ${matched.output}",
        )
    }

    @Test
    fun `the header does not reach another remote`() {
        val applier = applier()
        applier.apply(USER, PASSWORD.toByteArray(), "https://github.com/org/repo.git")

        // The continent, first: the SAME configuration that is about to be
        // asked about another remote does reach its own. Without this, the
        // absence below would be a green that says nothing: a configuration
        // that reached nobody would pass it too.
        val own = urlMatch(applier, "https://github.com/org/repo.git")
        assertEquals(
            0,
            own.exitCode,
            "the header does not even reach the remote the checkout named, so its absence for " +
                "another remote would prove nothing; git said: ${own.output}",
        )

        val other = urlMatch(applier, "https://gitlab.com/org/repo.git")

        assertEquals(
            1,
            other.exitCode,
            "the header the checkout wrote reaches a remote the checkout never named; git said: " +
                "${other.output}",
        )
        assertFalse(
            other.output.contains("Authorization"),
            "git was handed a credential for a remote the checkout never named; git said: " +
                "${other.output}",
        )
    }

    @Test
    fun `the configuration of the machine is not read`() {
        // A machine that carries a header of its own: the checkout did not
        // declare it, and it would be a second header on the same request.
        val machine = temp.resolve("machine.cfg")
        Files.writeString(
            machine,
            "[http \"https://github.com\"]\n    extraHeader = Authorization: Basic MACHINE\n",
        )
        val applier = applier()
        applier.apply(USER, PASSWORD.toByteArray(), "https://github.com/org/repo.git")

        val listed = git(
            listOf("config", "--list", "--show-origin"),
            applier.buildEnv() + mapOf("GIT_CONFIG_SYSTEM" to machine.toString()),
        )

        assertEquals(
            0,
            listed.exitCode,
            "git refused to list the configuration it reads; git said: ${listed.output}",
        )
        assertFalse(
            listed.output.contains("MACHINE"),
            "the configuration of the machine is read by this checkout, so a machine-wide header " +
                "joins the one the checkout declared on the same request; git said: " +
                "${listed.output}",
        )
        assertTrue(
            listed.output.contains("Authorization: Basic"),
            "the configuration of the checkout is not the one git reads; git said: ${listed.output}",
        )
    }

    @Test
    fun `a remote that cannot authenticate with a password gets no header and no copy of it`() {
        val applier = applier()
        applier.apply(USER, PASSWORD.toByteArray(), "ssh://git@github.com/org/repo.git")

        assertNull(
            applier.gitConfigFilePath(),
            "the record points at a configuration that carries a header, and the remote cannot " +
                "authenticate with one",
        )
        val root = Path.of(applier.answerFilePath()).parent
        val config = root.resolve(".gitconfig")
        assertFalse(
            Files.readString(config).contains("extraHeader"),
            "the configuration of a remote that cannot use a password carries a header anyway; " +
                "the file says: ${Files.readString(config)}",
        )
        assertTrue(
            filesHolding(root, PASSWORD).isEmpty(),
            "the password of a remote that cannot use it is on disk anyway",
        )
    }

    /**
     * The header git returns for [url], read from the configuration of the
     * checkout alone.
     *
     * `git config --file` reads THAT file and no other, so the answer cannot
     * be polluted by the configuration of the machine that runs the test.
     */
    private fun urlMatch(applier: GitCredentialsApplier, url: String): GitResult {
        val config = Path.of(
            requireNotNull(applier.gitConfigFilePath()) {
                "the checkout wrote no configuration that carries a header"
            },
        )
        return git(
            listOf(
                "config",
                "--file", config.toString(),
                "--get-urlmatch", "http.extraHeader",
                url,
            ),
            emptyMap(),
        )
    }

    /** The credentials the header carries, decoded the way a server decodes them. */
    private fun decodedCredentials(header: String): String {
        val encoded = header.substringAfter("Basic ").trim().lines().first()
        return String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
    }

    /** An applier over a directory of its own. */
    private fun applier(name: String = "applier"): GitCredentialsApplier {
        val root = Files.createDirectories(temp.resolve(name))
        return GitCredentialsApplier(tempDir = root)
    }

    /**
     * A HOME of its own, with nothing in it.
     *
     * Without it, git reads the configuration of whoever runs the test, and the
     * answer this instrument gives is the answer of the machine and not of the
     * checkout.
     */
    private fun home(): Path = Files.createDirectories(temp.resolve("home"))

    /**
     * Runs the real git, because the question "does git accept this?" has no
     * answer without git.
     *
     * The output is collected in a FILE and read after git ends: reading it
     * from a pipe while git runs would block the test before the deadline
     * could fire.
     */
    private fun git(args: List<String>, env: Map<String, String>): GitResult {
        val collected = Files.createTempFile(temp, "git", ".out")
        val builder = ProcessBuilder(listOf("git") + args)
            .redirectErrorStream(true)
            .redirectOutput(collected.toFile())
        builder.directory(temp.toFile())
        // The configuration of the machine that runs the test stays out of the
        // way: a HOME of its own, and no askpass of the operator that could
        // answer a question of ours.
        builder.environment().remove("GIT_ASKPASS")
        builder.environment().remove("SSH_ASKPASS")
        builder.environment().put("HOME", home().toString())
        builder.environment().put("XDG_CONFIG_HOME", home().toString())
        builder.environment().put("GIT_TERMINAL_PROMPT", "0")
        builder.environment().putAll(env)
        val process = builder.start()
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("git did not finish in $TIMEOUT_SECONDS seconds")
        }
        return GitResult(process.exitValue(), Files.readString(collected))
    }

    /** The files of [root] whose bytes carry [needle]. */
    private fun filesHolding(root: Path, needle: String): List<Path> {        val directory = Files.newDirectoryStream(root)
        directory.use { files ->
            return files
                .filter { Files.isRegularFile(it) }
                .filter { Files.readAllBytes(it).toString(Charsets.ISO_8859_1).contains(needle) }
                .toList()
        }
    }

    private data class GitResult(val exitCode: Int, val output: String)

    private companion object {
        /** A user name that is not a user name: it never leaves this machine. */
        const val USER = "ci-user"

        /** A password that is not a password: it never leaves this machine. */
        const val PASSWORD = "ci-password"

        /** How long git is given to answer. */
        const val TIMEOUT_SECONDS = 30L
    }
}
