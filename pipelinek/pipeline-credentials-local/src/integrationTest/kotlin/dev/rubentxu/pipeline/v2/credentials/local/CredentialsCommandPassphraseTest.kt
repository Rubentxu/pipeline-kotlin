package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The credentials command asks the operator for the passphrase when the
 * environment does not name one.
 *
 * ## The guarantees
 *
 * A command whose environment names no passphrase asks the operator for one,
 * and the passphrase the operator gives is the passphrase the store is opened
 * with: the credential that lands can be read back with that same passphrase.
 * An environment that names one is the automation path and it wins — nobody is
 * asked, because a script that names the passphrase must never stop at a
 * prompt. And a command that can ask but finds no terminal refuses the way it
 * always refused, with the code that says "no passphrase" and without leaving a
 * store behind.
 *
 * ## The observable capacity this serves
 *
 * The operator who runs `credentials add` at a terminal is the one who holds
 * the passphrase. Before this, the only way in was to export it into the
 * environment — the channel the product reserves for automation — and the
 * diagnostic the command printed pointed at a terminal the command never
 * consulted. The operator was sent to a path that did not exist.
 *
 * ## Why this instrument and not another
 *
 * The guarantee joins the argv, the environment, the reader and the file the
 * store writes, so it cannot be settled in the unit lane: that lane does not
 * touch the filesystem. The resolver's own rows cover the resolution order
 * with a reader they inject, and they never reach the command that has to
 * consult it. There is no end-to-end lane here either, and a real terminal
 * would need a PTY — a process — which the policy does not allow.
 *
 * ## What is faked, and what is not
 *
 * The world outside the command is faked: the environment is a map the test
 * owns, the operator's terminal is a reader the test owns, and the value
 * arrives on a stream the test owns. The command, the store and the file are
 * real: the argv is the argv an operator writes, and the oracle is the store
 * read back over the file the command wrote, not the code that wrote it.
 *
 * ## What this does not freeze
 *
 * The wording of the refusal is not frozen. What is frozen is the exit code,
 * whether the operator was asked at all, and the passphrase the store was
 * opened with.
 */
class CredentialsCommandPassphraseTest {

    @TempDir
    lateinit var root: Path

    /** The passphrase the operator gives, owned by this test. */
    private val passphrase = "a passphrase this test owns"

    /** The file the store lives in, named by the environment the command reads. */
    private fun storeFile(): Path = root.resolve("credentials.bin")

    /**
     * The environment the command reads when nothing names the passphrase.
     */
    private fun environment(): Map<String, String> = mapOf(
        CredentialsStorePath.ENV_VAR to storeFile().toString(),
    )

    /**
     * The environment the command reads when the automation path names the
     * passphrase.
     */
    private fun environmentWithPassphrase(): Map<String, String> = environment() + (PASSPHRASE_ENV to passphrase)

    /** What the command did: the exit code the caller sees, and what it said. */
    private data class Answer(val exit: Int, val said: String)

    /**
     * The command, with the world it reads.
     */
    private fun run(
        vararg args: String,
        stdin: String = "",
        environment: Map<String, String>,
        passphraseReader: () -> CharArray?,
    ): Answer {
        val said = ByteArrayOutputStream()
        val exit = MainCredentialsCli.main(
            args = arrayOf(*args),
            out = PrintStream(said, true, Charsets.UTF_8),
            environment = environment,
            stdin = ByteArrayInputStream(stdin.toByteArray(Charsets.UTF_8)),
            passphraseReader = passphraseReader,
        )
        return Answer(exit, said.toString(Charsets.UTF_8))
    }

    /**
     * The store, read back with the passphrase it was sealed with.
     */
    private fun store(sealedWith: String): LocalSecretStore =
        LocalSecretStore(storeFile(), sealedWith.toCharArray())

    @Test
    fun `without a passphrase in the environment, the command asks the operator and the answer opens the store`() {
        var asked = false
        val reader = {
            asked = true
            passphrase.toCharArray()
        }

        val answer = run(
            "add",
            "--from-stdin",
            "alpha",
            stdin = "alpha-value\n",
            environment = environment(),
            passphraseReader = reader,
        )

        assertEquals(0, answer.exit, "the command refused a passphrase the operator gave: ${answer.said}")
        assertTrue(asked, "the operator was never asked for the passphrase")

        // The oracle is the store read back with the passphrase the operator
        // gave: the key they typed is the key the store was sealed with.
        val store = store(passphrase)
        assertEquals(
            listOf(CredentialsId("alpha")),
            store.list(),
            "the store does not hold the credential the command was given",
        )
        val credential = assertInstanceOf(SecretText::class.java, store.get(CredentialsId("alpha")))
        assertArrayEquals(
            "alpha-value".toByteArray(Charsets.UTF_8),
            credential.bytes,
            "the store does not hold the value the command was given",
        )
    }

    @Test
    fun `with a passphrase in the environment, the operator is not asked`() {
        var asked = false
        val reader = {
            asked = true
            "a passphrase the environment did not name".toCharArray()
        }

        val answer = run(
            "add",
            "--from-stdin",
            "alpha",
            stdin = "alpha-value\n",
            environment = environmentWithPassphrase(),
            passphraseReader = reader,
        )

        assertEquals(0, answer.exit, "the command refused a passphrase the environment named: ${answer.said}")
        assertFalse(asked, "the environment names the passphrase, so nobody should be asked")

        // The environment won: the store is sealed with the passphrase the
        // environment named, and not with the one the reader would have given.
        val store = store(passphrase)
        assertEquals(
            listOf(CredentialsId("alpha")),
            store.list(),
            "the store does not hold the credential the command was given",
        )
    }

    @Test
    fun `without a passphrase and without a terminal, the command refuses and leaves no store behind`() {
        val answer = run(
            "add",
            "--from-stdin",
            "alpha",
            stdin = "alpha-value\n",
            environment = environment(),
            passphraseReader = { null },
        )

        assertEquals(2, answer.exit, "a refusal without a passphrase must answer with the code that says so")
        assertTrue(
            answer.said.contains(PASSPHRASE_ENV),
            "the refusal does not name the environment variable that would have carried the passphrase: ${answer.said}",
        )
        assertFalse(
            Files.exists(storeFile()),
            "a request that was refused left a store behind",
        )
    }

    private companion object {
        /** The environment variable the passphrase resolver reads. */
        const val PASSPHRASE_ENV = "PIPELINE_STORE_PASSPHRASE"
    }
}
