package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.Zip
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The command that imports an archive, over a real store.
 *
 * ## The guarantee
 *
 * An import the command refuses leaves the store as it was: nothing is
 * written, nothing is half-written, and what the operator is told names the
 * ceiling that was crossed without carrying what the credential held. An
 * import the command accepts gives back the credential it was given.
 *
 * ## The capacity this serves
 *
 * The operator of the command cannot see the store's file. The only thing
 * they get to know is what the command said, and the only thing that decides
 * whether a credential is safe to use is whether the store holds it. A
 * command that refuses an import and leaves the credential behind would be a
 * command that says one thing and does another, and the credential it left
 * behind is a secret in a file the operator believes holds nothing.
 *
 * ## Why this is an INTEGRATION test
 *
 * The unit lane of this module covers the rules one by one, with bytes in and
 * a credential out. What no unit test can see is what the store holds after
 * the command refused: the store's medium is a file, and the file is what the
 * oracle reads here. The oracle is the store's own reader over the file the
 * command wrote, not the code that wrote it.
 *
 * ## What is faked, and what is not
 *
 * The world outside the command is faked: the operator's terminal is a stream
 * this test owns, and the environment that names the store and its passphrase
 * is a map this test owns. The command, the store, the file and the archive
 * are real.
 */
class CredentialsImportRefusalTest {

    @TempDir
    lateinit var root: Path

    /** The passphrase the store is sealed with, owned by this test. */
    private val passphrase = "a passphrase this test owns"

    /** The file the store lives in, named by the environment the command reads. */
    private fun storeFile(): Path = root.resolve("credentials.bin")

    /**
     * The environment the command reads: where the store is, and the key that
     * opens it.
     */
    private fun environment(): Map<String, String> = mapOf(
        CredentialsStorePath.ENV_VAR to storeFile().toString(),
        PASSPHRASE_ENV to passphrase,
    )

    /** The store, read back. */
    private fun store(): LocalSecretStore =
        LocalSecretStore(storeFile(), PassphraseResolver.resolve(environment(), ttyReader = { null }))

    /** What the command did: the fact the caller sees, and what it said. */
    private data class Answer(val outcome: CredentialsOutcome, val said: String, val refused: String)

    /**
     * The command, with the world it reads.
     *
     * The argv is the argv an operator writes, the stream is the terminal the
     * operator types into, and the outcome is the fact the caller sees.
     */
    private fun run(vararg args: String, stdin: ByteArray = ByteArray(0)): Answer {
        val said = ByteArrayOutputStream()
        val refused = ByteArrayOutputStream()
        val outcome = MainCredentialsCli.main(
            args = arrayOf(*args),
            out = PrintStream(said, true, Charsets.UTF_8),
            err = PrintStream(refused, true, Charsets.UTF_8),
            environment = environment(),
            stdin = ByteArrayInputStream(stdin),
            passphraseReader = { null },
        )
        return Answer(outcome, said.toString(Charsets.UTF_8), refused.toString(Charsets.UTF_8))
    }

    @Test
    fun `an archive the command refuses leaves the store holding nothing`() {
        // The archive is two kilobytes and the entry it carries is two
        // megabytes: the size of the file says nothing about what it costs,
        // which is why the ceiling is applied to the bytes as they are read.
        val archive = archiveOf("bomb.bin" to ByteArray(2 * 1024 * 1024))

        val answer = run("add", "--kind", "zip", "--from-stdin", "archive", stdin = archive)

        assertEquals(
            CredentialsOutcome.NOT_CARRIED_OUT,
            answer.outcome,
            "a request that is not carried out must not be reported as carried out",
        )
        assertThrows(
            LocalSecretStore.SecretStoreTamperException::class.java,
            { store().get(CredentialsId.from("archive")) },
            "the store must hold no credential after a refused import: a credential the operator " +
                "believes was never written is a secret in a file nobody is looking at",
        )
        assertTrue(
            !Files.exists(storeFile()) || Files.size(storeFile()) == 0L,
            "a refused import must leave no credential behind: the operator asked for a credential " +
                "and got a refusal, and a credential nobody knows about is a secret nobody guards",
        )
    }

    @Test
    fun `the refusal names the ceiling and never the content`() {
        val secret = "the-secret-inside-the-archive"
        val entries = mutableListOf<Pair<String, ByteArray>>("secret.txt" to secret.toByteArray())
        entries += (0 until CredentialImportLimits.DEFAULT_MAX_ENTRIES)
            .map { "entry-$it.txt" to "x".toByteArray() }

        val answer = run(
            "add", "--kind", "zip", "--from-stdin", "archive",
            stdin = archiveOf(*entries.toTypedArray()),
        )

        assertEquals(
            CredentialsOutcome.NOT_CARRIED_OUT,
            answer.outcome,
            "an archive with more entries than the count of the parts can frame is not carried out",
        )
        assertTrue(
            answer.refused.contains("limit"),
            "the refusal must name the ceiling that was crossed: ${answer.refused}",
        )
        assertTrue(
            !answer.refused.contains(secret) && !answer.refused.contains("secret.txt"),
            "the refusal must not carry what the credential held: ${answer.refused}",
        )
    }

    @Test
    fun `an archive the command accepts comes back from the store with its bytes`() {
        val archive = archiveOf(
            "config.json" to "one".toByteArray(),
            "keys/id.pem" to "two".toByteArray(),
        )

        val answer = run("add", "--kind", "zip", "--from-stdin", "archive", stdin = archive)

        assertEquals(
            CredentialsOutcome.ANSWERED,
            answer.outcome,
            "an archive the ceilings allow is a credential the operator asked for: ${answer.refused}",
        )
        val stored = assertInstanceOf(Zip::class.java, store().get(CredentialsId.from("archive")))
        assertEquals(
            listOf("config.json", "keys/id.pem"),
            stored.entries.keys.toList(),
            "the names of the entries are part of the credential",
        )
        assertArrayEquals(
            "two".toByteArray(),
            stored.entries.getValue("keys/id.pem"),
            "the bytes of an entry are the credential; anything else is a credential nobody gave",
        )
    }

    /** An archive whose entries are [entries], each one stored as it is. */
    private fun archiveOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            entries.forEach { (name, payload) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(payload)
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    /**
     * The environment variable that carries the passphrase of the store.
     */
    companion object {
        /** The environment variable that carries the passphrase of the store. */
        const val PASSPHRASE_ENV = "PIPELINE_STORE_PASSPHRASE"
    }
}
