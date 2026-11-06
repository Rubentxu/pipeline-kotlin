package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * The store that is handed a file it cannot read as what it says it is.
 *
 * ## The guarantee
 * A store file whose body is not the body its header declares is REFUSED,
 * and the refusal says what does not fit. The store does not answer with an
 * empty listing, because a store that holds nothing and a store that cannot
 * be read are two different facts, and answering the second as the first
 * hides a file that has to be looked at.
 *
 * A refusal is also a refusal to WRITE: a store that cannot be read is not
 * published over. The file keeps the bytes it had, byte for byte.
 *
 * ## Why the kind is checked when the file is read
 * An entry carries the kind of the credential it holds, and that kind is
 * what says how its bytes are to be read. An entry whose kind names no
 * credential is a file that cannot be read, and it is refused when the file
 * is read -- not when somebody happens to ask for that credential, which
 * would leave the rest of the store answering as if it were fine.
 *
 * ## Why this test is an INTEGRATION test
 * The unit suite of this module covers the codec's reader with bytes in
 * memory. What no unit test can see is what the STORE does with a file it
 * cannot read: that it refuses, that it does not answer with a listing, and
 * that a mutation over such a file publishes nothing. Those are facts about
 * a real file on disk and about the store that writes it.
 *
 * ## What is faked
 * The world outside the process and nothing else. The store file is a real
 * file in a directory the test owns, which is where the effect that leaves
 * the process goes. The passphrase is a constant this test supplies through
 * the resolver's environment map, so no terminal and no operator is ever
 * asked for one. No clock, no subprocess and no network: the store has none.
 */
class CredentialsStoreFormatRefusalTest {

    /** The home directory the store's path authority resolves against. */
    @TempDir
    lateinit var home: Path

    private val passphrase = "a passphrase this test owns"
    private val credential = CredentialsId("broken")
    private val readable = CredentialsId("readable")

    /** The salt of the store file this test writes. */
    private val salt: ByteArray = ByteArray(LocalSecretStore.SALT_SIZE).also { SecureRandom().nextBytes(it) }

    /** The data key of the store file this test writes. */
    private val dek: ByteArray = ByteArray(LocalSecretStore.DEK_SIZE).also { SecureRandom().nextBytes(it) }

    /** The header of the store file this test writes. */
    private val header: LocalSecretStore.StoreHeader by lazy {
        LocalSecretStore.StoreHeader(
            StoreCodec.magic(),
            StoreCodec.VERSION_CURRENT,
            LocalSecretStore.OWASP_M,
            LocalSecretStore.OWASP_T,
            LocalSecretStore.OWASP_P,
            salt,
            CredentialCrypto.wrapDek(CredentialCrypto.deriveKek(passphrase.toCharArray(), salt), dek),
        )
    }

    /**
     * The store file, as the one authority for the store's location names it.
     */
    private fun storeFile(): Path =
        CredentialsStorePath.resolve(envValue = null, userHome = home)

    /**
     * A store over [storeFile], opened with the passphrase the resolver
     * supplies for the environment this test owns.
     */
    private fun openStore(): LocalSecretStore =
        LocalSecretStore(
            file = storeFile(),
            passphrase = PassphraseResolver.resolve(
                env = mapOf(PASSPHRASE_ENV to passphrase),
                ttyReader = { null },
            ),
        )

    // --- A body that does not fit the version its header declares ------------

    @Test
    fun `a store whose body is not the body its version describes is refused`() {
        writeStoreWithAnEntryWhoseIdLengthIsNoCredential()

        val failure = assertThrows<LocalSecretStore.SecretStoreTamperException> { openStore().list() }

        assertTrue(
            failure.message?.contains("not the body the version") == true,
            "the refusal must say that the body is not the body its version describes; it said: ${failure.message}",
        )
    }

    @Test
    fun `a mutation over a store whose body is not the body its version describes publishes nothing`() {
        writeStoreWithAnEntryWhoseIdLengthIsNoCredential()
        val before = Files.readAllBytes(storeFile())

        val failure = assertThrows<LocalSecretStore.SecretStoreTamperException> {
            openStore().add(credential, SecretText(credential, CredentialScope.GLOBAL, "a secret".toByteArray()))
        }

        assertTrue(
            failure.message?.contains("not the body the version") == true,
            "the refusal must say that the body is not the body its version describes; it said: ${failure.message}",
        )
        assertArrayEquals(
            before,
            Files.readAllBytes(storeFile()),
            "a store that cannot be read was published over anyway",
        )
    }

    @Test
    fun `a removal over a store whose body is not the body its version describes publishes nothing`() {
        writeStoreWithAnEntryWhoseIdLengthIsNoCredential()
        val before = Files.readAllBytes(storeFile())

        assertThrows<LocalSecretStore.SecretStoreTamperException> { openStore().remove(credential) }

        assertArrayEquals(
            before,
            Files.readAllBytes(storeFile()),
            "a store that cannot be read was published over anyway",
        )
    }

    // --- An entry whose kind names no credential -------------------------------

    @Test
    fun `a store whose entry carries a kind that names no credential is refused when it is read`() {
        writeStoreWithAnEntryWhoseKindNamesNoCredential()

        val failure = assertThrows<LocalSecretStore.SecretStoreTamperException> { openStore().list() }

        assertTrue(
            failure.message?.contains("names no kind of credential") == true,
            "the refusal must name the kind that names nothing; it said: ${failure.message}",
        )
    }

    @Test
    fun `a store that holds a readable entry and an entry that names no credential does not answer with the readable one`() {
        writeStoreWithAReadableEntryAndAnEntryWhoseKindNamesNoCredential()

        assertThrows<LocalSecretStore.SecretStoreTamperException> { openStore().list() }
    }

    @Test
    fun `a mutation over a store whose entry carries a kind that names no credential publishes nothing`() {
        writeStoreWithAnEntryWhoseKindNamesNoCredential()
        val before = Files.readAllBytes(storeFile())

        assertThrows<LocalSecretStore.SecretStoreTamperException> {
            openStore().add(credential, SecretText(credential, CredentialScope.GLOBAL, "a secret".toByteArray()))
        }

        assertArrayEquals(
            before,
            Files.readAllBytes(storeFile()),
            "a store that cannot be read was published over anyway",
        )
    }

    // --- The files the refusals are about --------------------------------------

    /**
     * Writes a store whose body carries an entry whose length of credential
     * id is one no credential can have.
     *
     * The entry is written by the codec, and then the two bytes that hold
     * the length of the id are made into a length that is not one: the
     * subject of this test is a body that cannot be the body of any
     * version, and that is what those two bytes are for.
     */
    private fun writeStoreWithAnEntryWhoseIdLengthIsNoCredential() {
        val body = entryOfTheCurrentFormat(credential, CredentialKinds.SECRET_TEXT).copyOf()
        body[0] = 0x00
        body[1] = 0x00
        writeStore(body)
    }

    /**
     * Writes a store whose body carries an entry whose kind names no
     * credential.
     *
     * The kind is a value of the format, and the format writes whatever kind
     * it is handed: a kind of 99 is a kind that names nothing, and the
     * reader of the file has to say so.
     */
    private fun writeStoreWithAnEntryWhoseKindNamesNoCredential() {
        writeStore(entryOfTheCurrentFormat(credential, UNKNOWN_KIND))
    }

    /**
     * Writes a store whose body carries a readable entry and an entry whose
     * kind names no credential.
     */
    private fun writeStoreWithAReadableEntryAndAnEntryWhoseKindNamesNoCredential() {
        val body = entryOfTheCurrentFormat(readable, CredentialKinds.SECRET_TEXT) +
            entryOfTheCurrentFormat(credential, UNKNOWN_KIND)
        writeStore(body)
    }

    /**
     * The bytes of an entry of the current format, sealed under its own AAD.
     */
    private fun entryOfTheCurrentFormat(id: CredentialsId, kindId: Short): ByteArray {
        val format = StoreCodec.of(StoreCodec.VERSION_CURRENT)
        val sealed = AeadCipher.encrypt(dek, "a secret".toByteArray(), format.aad(header, id, kindId))
        return format.encode(StoreEntry(id, sealed, kindId))
    }

    /**
     * Writes a store file under the path the store's authority names, with
     * [body] as its body.
     */
    private fun writeStore(body: ByteArray) {
        val file = storeFile()
        file.parent?.let { Files.createDirectories(it) }
        val out = ByteArrayOutputStream()
        StoreCodec.writeHeader(out, header)
        out.write(body)
        Files.write(file, out.toByteArray())
    }

    private companion object {
        /** The environment variable the passphrase resolver reads. */
        const val PASSPHRASE_ENV = "PIPELINE_STORE_PASSPHRASE"

        /** A kind that names no credential. */
        const val UNKNOWN_KIND: Short = 99
    }
}
