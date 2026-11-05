package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The credentials command acts on the credential it was given.
 *
 * ## The guarantees
 *
 * A credential seeded through the command under one identifier is the credential the command
 * removes when it is given that identifier, and the credential seeded under another identifier
 * survives with its own value. A rotation keeps the identity of the credential it was given and
 * leaves the other one alone: the value that lands is the one that was written, and the value
 * that was there is still there.
 *
 * ## The observable capacity this serves
 *
 * The operator who removes a credential cannot see the store afterwards, so the command's word is
 * the whole of what they get to know. That word has to be about the credential they named. A
 * command that removed another one would say the same thing and leave a secret the operator
 * believes is gone, which is the failure that cannot be taken back: the credential that survives
 * is still there, in the clear, waiting for whoever reads the store next.
 *
 * ## Why this instrument and not another
 *
 * Both guarantees are about the boundary between what the operator wrote and what the store
 * holds. The unit lane cannot reach it: the store's medium is a file, and a unit test does not
 * touch the filesystem. The store's own round trip is certified where the store lives, and it
 * certifies that the bytes come back; what it does not say is that the credential the command
 * removes is the credential the command was given, because the store is driven there directly.
 * The refusals of the command are certified too, and every one of them stops before the store is
 * opened. What was missing is the verb that acts.
 *
 * ## What is faked, and what is not
 *
 * The world outside the command is faked: the operator's terminal is a stream the test owns, and
 * the environment that names the store and the passphrase is a map the test owns. The command,
 * the store and the file are real: the argv is the argv an operator writes, the store is the
 * store the product opens, and the oracle is that store read back over the file the command
 * wrote, not the code that wrote it.
 *
 * ## What this does not freeze
 *
 * The wording of what the command says is not frozen. What is frozen is the outcome, the
 * identifier the store holds, and the bytes the store holds under it.
 */
class CredentialsCommandIdentityTest {

    @TempDir
    lateinit var root: Path

    /** The passphrase the store is sealed with, owned by this test. */
    private val passphrase = "a passphrase this test owns"

    /** The file the store lives in, named by the environment the command reads. */
    private fun storeFile(): Path = root.resolve("credentials.bin")

    /**
     * The environment the command reads: where the store is, and the key that opens it.
     */
    private fun environment(): Map<String, String> = mapOf(
        CredentialsStorePath.ENV_VAR to storeFile().toString(),
        PASSPHRASE_ENV to passphrase,
    )

    /**
     * The store, read back.
     *
     * The oracle is the store's own reader over the file the command wrote. The test does not
     * ask the command what it did: it asks the store what it holds.
     */
    private fun store(): LocalSecretStore =
        LocalSecretStore(storeFile(), PassphraseResolver.resolve(environment(), ttyReader = { null }))

    /** What the command did: the fact the caller sees, and what it said. */
    private data class Answer(val outcome: CredentialsOutcome, val said: String)

    /**
     * The command, with the world it reads.
     *
     * The argv is the argv an operator writes, the stream is the terminal the operator types
     * into, and the outcome is the fact the caller sees. The passphrase comes from the
     * environment here, so the reader that would ask for one at a terminal is never consulted.
     */
    private fun run(vararg args: String, stdin: String = ""): Answer {
        val said = ByteArrayOutputStream()
        val refused = ByteArrayOutputStream()
        val outcome = MainCredentialsCli.main(
            args = arrayOf(*args),
            out = PrintStream(said, true, Charsets.UTF_8),
            err = PrintStream(refused, true, Charsets.UTF_8),
            environment = environment(),
            stdin = ByteArrayInputStream(stdin.toByteArray(Charsets.UTF_8)),
            passphraseReader = { null },
        )
        return Answer(outcome, said.toString(Charsets.UTF_8))
    }

    /**
     * Seeds a credential through the command, the way a script does it.
     */
    private fun seed(id: String, value: String): Answer =
        run("add", "--from-stdin", id, stdin = "$value\n")

    /**
     * The bytes the store holds under an identifier.
     */
    private fun bytesOf(store: LocalSecretStore, id: CredentialsId): ByteArray {
        val credential = assertInstanceOf(SecretText::class.java, store.get(id))
        return credential.bytes
    }

    @Test
    fun `the credential named is the one that is removed, and the other one survives with its own value`() {
        assertEquals(CredentialsOutcome.ANSWERED, seed("alpha", "alpha-value").outcome, "the first credential was not stored")
        assertEquals(CredentialsOutcome.ANSWERED, seed("beta", "beta-value").outcome, "the second credential was not stored")

        // The store holds the two identifiers the command was given, under those identifiers.
        val seeded = store()
        assertEquals(
            setOf(CredentialsId("alpha"), CredentialsId("beta")),
            seeded.list().toSet(),
            "the store does not hold the identifiers the command was given",
        )

        val removed = run("remove", "alpha")
        assertEquals(
            CredentialsOutcome.ANSWERED,
            removed.outcome,
            "the command refused to remove a credential it holds: ${removed.said}",
        )

        val after = store()
        assertFalse(
            after.list().contains(CredentialsId("alpha")),
            "the credential the command named is still in the store",
        )
        assertTrue(
            after.list().contains(CredentialsId("beta")),
            "the command removed a credential it was not given",
        )
        assertArrayEquals(
            "beta-value".toByteArray(Charsets.UTF_8),
            bytesOf(after, CredentialsId("beta")),
            "the credential that survived holds another credential's value",
        )
        // And the credential the store hands back is identified by the identifier it was
        // seeded with, which is the one the operator wrote.
        assertEquals(
            CredentialsId("beta"),
            after.get(CredentialsId("beta")).id,
            "the credential the store hands back is not identified by the identifier it was seeded with",
        )
    }

    @Test
    fun `a rotation keeps the identity of the credential it was given, and leaves the other one alone`() {
        assertEquals(CredentialsOutcome.ANSWERED, seed("alpha", "alpha-value").outcome, "the first credential was not stored")
        assertEquals(CredentialsOutcome.ANSWERED, seed("beta", "beta-value").outcome, "the second credential was not stored")

        val rotated = run("rotate", "--from-stdin", "alpha", stdin = "rotated-value\n")
        assertEquals(
            CredentialsOutcome.ANSWERED,
            rotated.outcome,
            "the command refused to rotate a credential it holds: ${rotated.said}",
        )

        val store = store()
        assertEquals(
            setOf(CredentialsId("alpha"), CredentialsId("beta")),
            store.list().toSet(),
            "the rotation changed which identifiers the store holds",
        )
        assertArrayEquals(
            "rotated-value".toByteArray(Charsets.UTF_8),
            bytesOf(store, CredentialsId("alpha")),
            "the value the command wrote did not land in the credential it named",
        )
        assertArrayEquals(
            "beta-value".toByteArray(Charsets.UTF_8),
            bytesOf(store, CredentialsId("beta")),
            "the rotation touched a credential it was not given",
        )
    }

    private companion object {
        /** The environment variable the passphrase resolver reads. */
        const val PASSPHRASE_ENV = "PIPELINE_STORE_PASSPHRASE"
    }
}
