package dev.rubentxu.pipeline.v2.sdk.scm.git

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The credential helper, measured against the git that has to run it.
 *
 * **The guarantee.** A checkout that declares a token puts that token in front of
 * git, and git asks the checkout's helper for it: the token reaches the remote
 * the checkout named, it does NOT reach another remote, and it is written in ONE
 * file.
 *
 * **Why the instrument is the real git.** The helper is not the product; it is a
 * program that git runs. The protocol (key=value lines ended by a blank line),
 * the scope (which request consults the helper) and the reading of the answer
 * are facts about git, and a test that only reads the TEXT of the script would
 * certify that the script says what the test expects — which it does even when
 * the script never answers anybody. The question "does this helper answer git?"
 * has no answer without git.
 *
 * **Why the HOME is isolated.** Without a HOME of its own, the credential
 * machinery of the machine that runs the test answers with the credentials of
 * the operator: `git credential fill` reads `~/.git-credentials` and the
 * operator's own global configuration. The test would then certify somebody
 * else's credential. The environment here is a HOME that holds nothing, a
 * configuration system that is switched off, and no askpass.
 *
 * **Why the credential is named `ci-token`.** The id of a credential is a label,
 * not a host. A test whose credential id were a hostname would pass on a
 * product that reads the host out of the credential id instead of out of the
 * remote the checkout was given.
 */
class GitCredentialHelperProtocolTest {

    @TempDir
    lateinit var temp: Path

    /** How many appliers this test has opened, so their directories keep their names apart. */
    private var appliers = 0

    /** How many environments this test has built, one per instrument run. */
    private var environments = 0

    @Test
    fun `git asks the checkout's helper for the named remote and the token comes back`() {
        val applier = applier()
        applier.apply(tokenBytes(), "https://github.com/org/repo.git")
        val env = gitEnv(applier)

        val answer = gitCredentialFill("https://github.com/org/repo.git", env)

        assertEquals(
            0,
            answer.exitCode,
            "git could not take a credential from the checkout's helper; git said: ${answer.output}",
        )
        assertTrue(
            answer.output.contains("password=$TOKEN"),
            "git asked the checkout's helper and did not get the token; git said: ${answer.output}",
        )
        assertTrue(
            answer.output.contains("username=$USER_NAME"),
            "git asked the checkout's helper and did not get a user name; git said: ${answer.output}",
        )

        // The same question in the shape git asks it: protocol, host and path.
        // The path is a key of the request like any other, and the helper has to
        // answer a request that carries it.
        val asked = gitCredentialFillForHost("github.com", "org/repo.git", env)

        assertEquals(
            0,
            asked.exitCode,
            "git could not take a credential from the checkout's helper when the request carried " +
                "a path; git said: ${asked.output}",
        )
        assertTrue(
            asked.output.contains("password=$TOKEN"),
            "the helper did not answer a request that carried protocol, host and path; git said: " +
                "${asked.output}",
        )

        // The absence, with the continent in front of it: the SAME configuration
        // and the SAME helper that just handed the token over are asked about a
        // remote the checkout never named, and they hand over nothing. Without
        // the first observation this would be a green that says nothing: a
        // helper that answers nobody would pass it too.
        val other = gitCredentialFill("https://gitlab.com/org/repo.git", env)

        assertFalse(
            other.output.contains(TOKEN),
            "the token reached a remote the checkout never named; git said: ${other.output}",
        )
        assertFalse(
            other.output.contains("password="),
            "git was handed a credential for a remote the checkout never named; git said: ${other.output}",
        )

        // The same absence, in the shape git asks the question.
        val otherAsked = gitCredentialFillForHost("gitlab.com", "org/repo.git", env)

        assertFalse(
            otherAsked.output.contains(TOKEN),
            "the token reached a remote the checkout never named, in a request that carried " +
                "protocol, host and path; git said: ${otherAsked.output}",
        )
        assertFalse(
            otherAsked.output.contains("password="),
            "git was handed a credential for a remote the checkout never named; git said: " +
                "${otherAsked.output}",
        )
    }

    @Test
    fun `the scope carries the port the remote names`() {
        val applier = applier()
        applier.apply(tokenBytes(), "https://github.com:8443/org/repo.git")
        val env = gitEnv(applier)

        val answer = gitCredentialFill("https://github.com:8443/org/repo.git", env)

        assertEquals(
            0,
            answer.exitCode,
            "git could not take a credential from the checkout's helper for a remote that names " +
                "a port; git said: ${answer.output}",
        )
        assertTrue(
            answer.output.contains("password=$TOKEN"),
            "the scope of the checkout did not carry the port the remote names, so the token did " +
                "not reach it; git said: ${answer.output}",
        )
    }

    @Test
    fun `the token is written in one file only`() {
        val applier = applier()
        applier.apply(tokenBytes(), "https://github.com/org/repo.git")

        val holders = filesHolding(applierRoot(applier), TOKEN)

        assertEquals(
            1,
            holders.size,
            "the token is written in ${holders.size} files: $holders. A secret that lives in " +
                "two places is two places to wipe, and one of them may outlive the checkout.",
        )
    }

    @Test
    fun `a remote that cannot authenticate with the token leaves no token on disk`() {
        val overHttp = applier("applier-http")
        overHttp.apply(tokenBytes(), "https://github.com/org/repo.git")
        assertEquals(
            1,
            filesHolding(applierRoot(overHttp), TOKEN).size,
            "the same token IS written for a remote that can authenticate with it, so its absence " +
                "for the other remote is a fact about the remote and not about the token",
        )

        val overSsh = applier("applier-ssh")
        overSsh.apply(tokenBytes(), "ssh://git@github.com/org/repo.git")

        val holders = filesHolding(applierRoot(overSsh), TOKEN)
        assertTrue(
            holders.isEmpty(),
            "the token of a remote that cannot use it is on disk anyway: $holders",
        )
    }

    /**
     * An applier whose credential is the token, over a directory of its own.
     *
     * The credential id is a LABEL (`ci-token`), never a host: a product that
     * read the host out of the credential id would pass a test whose id was a
     * hostname, and fail in production.
     */
    private fun applier(name: String = "applier-${appliers++}"): GitCredentialsApplier {
        val root = Files.createDirectories(temp.resolve(name))
        return GitCredentialsApplier(tempDir = root)
    }

    /**
     * The token, as the bytes the caller resolved before handing it over.
     *
     * The credential is resolved by the CALLER, not by the applier: a token is
     * one secret, and a credential that is more than one secret cannot be
     * projected into a single handle by any store.
     */
    private fun tokenBytes(): ByteArray = TOKEN.toByteArray(Charsets.UTF_8)

    /** The directory an applier of this test owns, so the test can look inside it. */
    private fun applierRoot(applier: GitCredentialsApplier): Path =
        Path.of(applier.answerFilePath()).parent

    /**
     * The environment the credential machinery runs with.
     *
     * The HOME holds nothing, the system configuration is switched off, and the
     * terminal is told not to prompt: without those three, the instrument can
     * answer with the credentials of the machine that runs it, and a run that
     * waits for a prompt is a run that never finishes.
     */
    private fun gitEnv(applier: GitCredentialsApplier): Map<String, String> {
        val home = Files.createDirectories(temp.resolve("home-${environments++}"))
        return applier.buildEnv() + mapOf(
            "HOME" to home.toString(),
            "XDG_CONFIG_HOME" to home.toString(),
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_TERMINAL_PROMPT" to "0",
        )
    }

    /**
     * Asks git for the credential of [url], the way a checkout asks git to fetch.
     *
     * The askpass of the machine that runs the test is removed from the
     * environment: an askpass would answer the request that has no credential,
     * and the answer would not be the checkout's.
     */
    private fun gitCredentialFill(url: String, env: Map<String, String>): GitResult =
        gitCredentialFillFor("url=$url\n\n", env)

    /**
     * Asks git for the credential of a host, in the shape git asks it: the
     * request carries the protocol, the host and the path, and ends with a
     * blank line.
     */
    private fun gitCredentialFillForHost(host: String, path: String, env: Map<String, String>): GitResult =
        gitCredentialFillFor("protocol=https\nhost=$host\npath=$path\n\n", env)

    /** Asks git for a credential, with the request the test writes on its standard input. */
    private fun gitCredentialFillFor(request: String, env: Map<String, String>): GitResult {
        val builder = ProcessBuilder(listOf("git", "credential", "fill")).redirectErrorStream(true)
        val environment = builder.environment()
        environment.remove("GIT_ASKPASS")
        environment.remove("SSH_ASKPASS")
        environment.putAll(env)
        val process = builder.start()
        process.outputStream.use { it.write(request.toByteArray(Charsets.UTF_8)) }
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw AssertionError("git credential fill did not finish in $TIMEOUT_SECONDS seconds")
        }
        return GitResult(process.exitValue(), output)
    }

    /** The files of [root] whose bytes carry [needle]. */
    private fun filesHolding(root: Path, needle: String): List<Path> {
        val directory = Files.newDirectoryStream(root)
        directory.use { files ->
            return files
                .filter { Files.isRegularFile(it) }
                .filter { Files.readAllBytes(it).toString(Charsets.ISO_8859_1).contains(needle) }
                .toList()
        }
    }

    private data class GitResult(val exitCode: Int, val output: String)

    private companion object {
        /** A token that is not a token: it never leaves this machine. */
        const val TOKEN = "ghp_TESTTOKEN_0123456789"

        /** The user name git is told to authenticate with. */
        const val USER_NAME = "x-access-token"

        /** How long the credential machinery is given to answer. */
        const val TIMEOUT_SECONDS = 30L
    }
}
