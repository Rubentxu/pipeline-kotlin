package dev.rubentxu.pipeline.v2.sdk.scm.git

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The SSH channel, measured against the OpenSSH that consumes it.
 *
 * **The guarantee.** A checkout that declared an SSH key hands OpenSSH that
 * key and the passphrase of that key, in the shape OpenSSH asks for them: the
 * question arrives as an argument, the answer leaves by the standard output,
 * and a question that is not about the passphrase gets no answer at all.
 *
 * **Why the instrument is the real OpenSSH.** The askpass is not the product;
 * it is a program that OpenSSH runs, and how OpenSSH asks and how it reads the
 * answer are facts about OpenSSH. A test that only read the text of the script
 * would certify that the script says what the test expects — which it does
 * even when the script never answers anybody. The question "does OpenSSH read
 * the passphrase from this program?" has no answer without OpenSSH.
 *
 * **Why the question is asked with the real key.** `ssh-keygen -y` is the
 * path that reads a passphrase and opens the key with it, and it asks through
 * the same `ssh_askpass()` that `ssh` uses. It needs no server, no agent and
 * no network, so the event that decides the guarantee is local to OpenSSH.
 *
 * **Why every process is bounded.** A question that nobody answers is a run
 * that never finishes: without a timeout, a broken askpass would hang the
 * suite instead of failing it.
 *
 * **Why the HOME is isolated.** Without a HOME of its own, OpenSSH reads the
 * configuration and the identities of the machine that runs the test: the
 * answer would be the answer of the operator and not of the checkout.
 */
class GitSshChannelTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun `the askpass answers a passphrase question with the passphrase`() {
        val applier = applier()
        applier.applySsh(SSH_KEY.toByteArray(), PASSPHRASE.toByteArray(), "ssh://git@github.com/org/repo.git")

        val answer = askpass(applier, "Enter passphrase for /tmp/.git-ssh-key: ")

        assertEquals(
            0,
            answer.exitCode,
            "the askpass of the checkout did not answer the question; it said: ${answer.output}",
        )
        assertEquals(
            PASSPHRASE,
            answer.output.trim(),
            "the askpass answered something other than the passphrase the checkout declared",
        )
    }

    @Test
    fun `the askpass refuses a question that is not about the passphrase`() {
        val applier = applier()
        applier.applySsh(SSH_KEY.toByteArray(), PASSPHRASE.toByteArray(), "ssh://git@github.com/org/repo.git")

        val answer = askpass(applier, "Password: ")

        assertNotEquals(
            0,
            answer.exitCode,
            "the askpass answered a question it cannot answer, and a checkout that holds a " +
                "passphrase would hand it to whoever asked",
        )
        assertFalse(
            answer.output.contains(PASSPHRASE),
            "the askpass handed the passphrase to a question that was not about it",
        )
    }

    @Test
    fun `OpenSSH reads the passphrase of the declared key from the askpass of the checkout`() {
        val key = encryptedKey()
        val applier = applier()
        applier.applySsh(Files.readAllBytes(key), PASSPHRASE.toByteArray(), "ssh://git@github.com/org/repo.git")

        val result = run(
            listOf("ssh-keygen", "-y", "-f", requireNotNull(applier.sshKeyPath())),
            applier.buildEnv(),
        )

        assertEquals(
            0,
            result.exitCode,
            "OpenSSH could not open the key the checkout declared with the passphrase the " +
                "checkout declared; it said: ${result.output}",
        )
        assertTrue(
            result.output.trim().startsWith("ssh-ed25519"),
            "OpenSSH did not open the key; it said: ${result.output}",
        )
    }

    @Test
    fun `the ssh command names the declared key and offers no other`() {
        val applier = applier()
        applier.applySsh(SSH_KEY.toByteArray(), null, "ssh://git@github.com/org/repo.git")

        val command = requireNotNull(applier.buildEnv()["GIT_SSH_COMMAND"]) {
            "the ssh transport must put GIT_SSH_COMMAND in the environment it hands over"
        }

        assertTrue(
            command.contains("-i \"${applier.sshKeyPath()}\""),
            "the ssh command does not name the key the checkout declared, so the checkout would " +
                "authenticate with whatever the machine happens to have; the command was: " +
                "$command",
        )
        assertTrue(
            command.contains("IdentitiesOnly=yes"),
            "the ssh command offers the identities of the machine as well as the declared key; " +
                "the command was: $command",
        )
    }

    @Test
    fun `the passphrase is written in one file and the checkout destroys it`() {
        val applier = applier()
        applier.applySsh(SSH_KEY.toByteArray(), PASSPHRASE.toByteArray(), "ssh://git@github.com/org/repo.git")
        val root = Path.of(applier.answerFilePath()).parent

        val holders = filesHolding(root, PASSPHRASE)
        assertEquals(
            1,
            holders.size,
            "the passphrase is written in ${holders.size} files: $holders. A secret that lives " +
                "in two places is two places to wipe, and one of them may outlive the checkout.",
        )

        // A SECOND NAME for the same bytes. Unlinking a file destroys its NAME, not
        // its CONTENT: the passphrase stays in the blocks of the device, and
        // whoever can read those blocks reads the secret. A hard link is how
        // this test tells destroying the bytes from renaming them away: the link
        // outlives the checkout's own name, so what it reads after the checkout
        // ends is the CONTENT of the file the checkout held.
        val witness = temp.resolve("witness-of-the-passphrase")
        Files.createLink(witness, holders.first())

        applier.close()

        assertTrue(
            Files.exists(witness),
            "the witness of the passphrase file is gone, so the checkout destroyed the name and " +
                "not the bytes, and this row cannot tell one from the other",
        )
        assertFalse(
            Files.readString(witness).contains(PASSPHRASE),
            "the bytes of the passphrase are still readable after the checkout ended: the " +
                "checkout unlinked the file instead of destroying what was in it",
        )
        assertFalse(
            Files.exists(root),
            "the credential directory is still on disk after the checkout ended, and it holds " +
                "the passphrase and the private key",
        )
    }

    @Test
    fun `a remote that cannot use a key gets neither key nor passphrase`() {
        val applier = applier()
        applier.applySsh(SSH_KEY.toByteArray(), PASSPHRASE.toByteArray(), "https://github.com/org/repo.git")
        val root = Path.of(applier.answerFilePath()).parent

        assertNull(
            applier.sshKeyPath(),
            "the record points at a key for a remote that cannot authenticate with one",
        )
        assertTrue(
            filesHolding(root, PASSPHRASE).isEmpty(),
            "the passphrase of a remote that cannot use it is on disk anyway",
        )
        assertTrue(
            filesHolding(root, SSH_KEY).isEmpty(),
            "the private key of a remote that cannot use it is on disk anyway",
        )
    }

    /**
     * Runs the askpass of the checkout the way OpenSSH runs it: with the
     * question as the first argument, and nothing on the standard input.
     */
    private fun askpass(applier: GitCredentialsApplier, prompt: String): ProcessResult {
        val script = requireNotNull(applier.buildEnv()["SSH_ASKPASS"]) {
            "the ssh transport must put SSH_ASKPASS in the environment it hands over"
        }
        return run(listOf(script, prompt), emptyMap())
    }

    /** A private key with a passphrase, made by the OpenSSH that will have to open it. */
    private fun encryptedKey(): Path {
        val key = temp.resolve("encrypted-key")
        val result = run(
            listOf("ssh-keygen", "-q", "-t", "ed25519", "-N", PASSPHRASE, "-f", key.toString()),
            emptyMap(),
        )
        assertEquals(
            0,
            result.exitCode,
            "the key this test needs could not be made; ssh-keygen said: ${result.output}",
        )
        return key
    }

    /** An applier over a directory of its own. */
    private fun applier(name: String = "applier"): GitCredentialsApplier {
        val root = Files.createDirectories(temp.resolve(name))
        return GitCredentialsApplier(tempDir = root)
    }

    /**
     * A HOME of its own, with nothing in it.
     *
     * Without it, OpenSSH reads the configuration and the identities of whoever
     * runs the test, and the answer this instrument gives is the answer of the
     * machine and not of the checkout.
     */
    private fun home(): Path = Files.createDirectories(temp.resolve("home"))

    /**
     * Runs a program and waits for it, with a deadline.
     *
     * The deadline is the point of this helper: a program that asks a question
     * nobody answers waits for ever, and a test that waits for ever is a test
     * that never fails.
     *
     * The output is collected in a FILE and read AFTER the program ends, and
     * not read from a pipe while it runs: a program that waits for an answer
     * keeps its pipe open, so reading the pipe first would block the test
     * BEFORE the deadline could fire. That is the difference between a test
     * that fails and a suite that hangs.
     */
    private fun run(args: List<String>, env: Map<String, String>): ProcessResult {
        val collected = Files.createTempFile(temp, "program", ".out")
        val builder = ProcessBuilder(args)
            .redirectErrorStream(true)
            .redirectOutput(collected.toFile())
        builder.directory(temp.toFile())
        // The environment of the machine that runs the test stays out of the way:
        // a HOME of its own, and no askpass of the operator that could answer a
        // question that belongs to this checkout.
        builder.environment().remove("GIT_ASKPASS")
        builder.environment().remove("SSH_ASKPASS")
        builder.environment().put("HOME", home().toString())
        builder.environment().put("XDG_CONFIG_HOME", home().toString())
        builder.environment().putAll(env)
        val process = builder.start()
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS)
            return ProcessResult(
                TIMED_OUT,
                "the program did not finish in $TIMEOUT_SECONDS seconds: " +
                    Files.readString(collected),
            )
        }
        return ProcessResult(process.exitValue(), Files.readString(collected))
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

    private data class ProcessResult(val exitCode: Int, val output: String)

    private companion object {
        /** A key that is not a key: the checkout only carries its bytes. */
        const val SSH_KEY = "ci-private-key"

        /**
         * A passphrase that is not a passphrase.
         *
         * It does NOT contain the word "passphrase": the askpass names the
         * kind of question it answers, and a value that carried the word would
         * make the SCRIPT a second holder of the secret.
         */
        const val PASSPHRASE = "ci-secret-42"

        /** How long a program is given to finish. */
        const val TIMEOUT_SECONDS = 30L

        /** The exit code a program that had to be killed reports. */
        const val TIMED_OUT = -1

        /** How long a killed program is given to die. */
        const val KILL_GRACE_SECONDS = 5L
    }
}
