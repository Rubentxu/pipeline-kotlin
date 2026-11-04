package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.domain.credentials.UsernamePassword
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The local credential store, over a real file, with its real cryptography.
 *
 * ## The guarantee
 * A credential written to the store is the credential that comes back out of
 * it: the same bytes, under the same identifier, with the same kind. The file
 * that carries it is sealed, and the passphrase that opens it is the only key
 * that does.
 *
 * ## Why this test is an INTEGRATION test and not a unit test
 * The unit tests of this module cover the pure primitives one by one: the
 * key derivation, the cipher, the serializer, the path authority, the
 * passphrase resolver. Each of them passes with arguments and returns values
 * and touches no disk.
 *
 * What no unit test of this module can see is whether those primitives FIT
 * each other over a file. The serializer writes a part format that the store
 * seals and the store opens again; the store derives a key from a salt it
 * wrote into a header and a passphrase it read from somewhere else; the path
 * authority names the file the store creates. A mismatch between any two of
 * them is invisible to every one of them, because each of them is correct on
 * its own.
 *
 * This test wires the real pieces the way the product wires them: the
 * [PassphraseResolver] hands the store its key, the [CredentialsStorePath]
 * names the file, and the [LocalSecretStore] writes and reads it.
 *
 * ## What is faked
 * The world outside the process and nothing else. The store file is a real
 * file in a directory the test owns, which is where the effect that leaves
 * the process goes. The passphrase is a constant string this test supplies
 * through the resolver's environment map, so no terminal and no operator is
 * ever asked for one. No clock, no subprocess and no network are involved:
 * the store has none.
 */
class CredentialsStoreFileRoundTripTest {

    /** The home directory the store's path authority resolves against. */
    @TempDir
    lateinit var home: Path

    private val passphrase = "a passphrase this test owns"

    /**
     * The store file, as the one authority for the store's location names it.
     */
    private fun storeFile(): Path =
        CredentialsStorePath.resolve(envValue = null, userHome = home)

    /**
     * A store over [storeFile], opened with the passphrase the resolver
     * supplies for the environment this test owns.
     */
    private fun openStore(passphraseText: String = passphrase): LocalSecretStore =
        LocalSecretStore(
            file = storeFile(),
            passphrase = PassphraseResolver.resolve(
                env = mapOf(PASSPHRASE_ENV to passphraseText),
                ttyReader = { null },
            ),
        )

    @Test
    fun `the credential the store reads back is the one that was written`() {
        val id = CredentialsId("api-token")
        val secret = "s3cr3t-token-value".toByteArray()
        val store = openStore()

        store.add(id, SecretText(id, CredentialScope.GLOBAL, secret))
        val read = assertInstanceOf(SecretText::class.java, store.get(id))

        assertArrayEquals(
            secret,
            read.bytes,
            "the store read back a secret it does not hold",
        )
    }

    @Test
    fun `a credential with several parts is read back part by part`() {
        val id = CredentialsId("db-password")
        val store = openStore()

        store.add(
            id,
            UsernamePassword(id, CredentialScope.GLOBAL, "deploy", "p4ssw0rd".toByteArray()),
        )
        val read = assertInstanceOf(UsernamePassword::class.java, store.get(id))

        assertEquals(
            "deploy",
            read.username,
            "the username part did not survive the round trip",
        )
        assertArrayEquals(
            "p4ssw0rd".toByteArray(),
            read.password,
            "the password part did not survive the round trip",
        )
    }

    @Test
    fun `the store file is where the store's own path authority names it`() {
        val id = CredentialsId("located")
        val store = openStore()

        store.add(id, SecretText(id, CredentialScope.GLOBAL, "v".toByteArray()))

        assertEquals(
            home.resolve(".pipeline").resolve("credentials.bin"),
            storeFile(),
            "the path authority does not name the file the store writes",
        )
        assertTrue(
            Files.exists(storeFile()),
            "the store wrote no file where its path authority points",
        )
    }

    @Test
    fun `a passphrase that does not open the store is refused`() {
        val id = CredentialsId("guarded")
        val store = openStore()
        store.add(id, SecretText(id, CredentialScope.GLOBAL, "v".toByteArray()))
        store.close()

        val other = LocalSecretStore(
            file = storeFile(),
            passphrase = "a passphrase the store does not know".toCharArray(),
        )

        assertThrows(
            LocalSecretStore.SecretStorePassphraseMismatchException::class.java,
            { other.get(id) },
            "a store that cannot be opened answered with a credential",
        )
    }

    @Test
    fun `the store lists the credentials it holds`() {
        val first = CredentialsId("first")
        val second = CredentialsId("second")
        val store = openStore()

        store.add(first, SecretText(first, CredentialScope.GLOBAL, "one".toByteArray()))
        store.add(second, SecretText(second, CredentialScope.GLOBAL, "two".toByteArray()))

        assertEquals(
            setOf(first, second),
            store.list().toSet(),
            "the store does not list what it holds",
        )
    }

    private companion object {
        /** The environment variable the passphrase resolver reads. */
        const val PASSPHRASE_ENV = "PIPELINE_STORE_PASSPHRASE"
    }
}
