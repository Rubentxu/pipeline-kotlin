package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * The two APIs that take BYTES, over a store that holds a real file.
 *
 * ## The guarantee
 * A byte written into a store is stored the way the store writes a typed
 * credential: in one format, the one the store carries, and with every
 * credential the store held still readable afterwards. A store that holds the
 * legacy format is brought to the current one by that write, and a write that
 * cannot be completed leaves the store exactly as it was.
 *
 * ## Why these two APIs are worth their own tests
 * They are the only way into the store that does NOT go through a typed
 * credential, and the store has two formats on the disk. A write that reads
 * the entries with the reader of one format and writes them back with the
 * writer of the other leaves a file whose header and body disagree, and the
 * credentials it did not touch are the ones that are lost.
 *
 * ## Why this test is an INTEGRATION test
 * The facts are about a FILE that carries a header and its entries together:
 * that the header the store is published with is the header its entries were
 * sealed under, and that the form the entries are written in is the form the
 * reader reads. A unit test would have to fabricate that file, and the
 * fabrication would be a second authority over the format.
 *
 * ## What is faked
 * The world outside the process and nothing else. The store file is a real
 * file in a directory the test owns, which is where the effect that leaves
 * the process goes. The passphrase is a constant this test supplies through
 * the resolver's environment map, so no terminal and no operator is ever
 * asked for one. No clock, no subprocess and no network: the store has none.
 */
class CredentialsStoreByteApiTest {

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
     * The passphrase the store is sealed with, as the resolver hands it over.
     */
    private fun passphraseChars(): CharArray =
        PassphraseResolver.resolve(
            env = mapOf(PASSPHRASE_ENV to passphrase),
            ttyReader = { null },
        )

    /**
     * A store over [storeFile], opened with the passphrase this test owns.
     */
    private fun openStore(): LocalSecretStore =
        LocalSecretStore(file = storeFile(), passphrase = passphraseChars())

    /**
     * A store in the legacy format, holding [entries].
     */
    private fun legacyStore(entries: Map<CredentialsId, ByteArray>) {
        LegacyV1StoreFixture.write(storeFile(), passphraseChars(), entries)
    }

    @Test
    fun `a byte written into a legacy store brings it to the current format and keeps the credentials it held`() {
        val first = CredentialsId("first")
        val second = CredentialsId("second")
        val third = CredentialsId("third")
        legacyStore(
            mapOf(
                first to "first-secret".toByteArray(),
                second to "second-secret".toByteArray(),
            ),
        )
        val store = openStore()

        store.put(third, "third-secret".toByteArray())

        assertEquals(
            2.toShort(),
            LocalSecretStore.readHeader(storeFile()).version,
            "the store was not brought to the format the store writes",
        )
        assertEquals("first-secret", secretOf(store, first), "the store lost a credential it did not touch")
        assertEquals("second-secret", secretOf(store, second), "the store lost a credential it did not touch")
        assertEquals("third-secret", secretOf(store, third), "the byte was not stored")
    }

    @Test
    fun `a byte written into a current store keeps the format and keeps the credentials it held`() {
        val first = CredentialsId("first")
        val second = CredentialsId("second")
        val third = CredentialsId("third")
        val store = openStore()
        store.add(first, SecretText(first, CredentialScope.GLOBAL, "first-secret".toByteArray()))
        store.add(second, SecretText(second, CredentialScope.GLOBAL, "second-secret".toByteArray()))

        store.put(third, "third-secret".toByteArray())

        assertEquals(
            2.toShort(),
            LocalSecretStore.readHeader(storeFile()).version,
            "the store changed the format it carries",
        )
        assertEquals("first-secret", secretOf(store, first), "the store lost a credential it did not touch")
        assertEquals("second-secret", secretOf(store, second), "the store lost a credential it did not touch")
        assertEquals("third-secret", secretOf(store, third), "the byte was not stored")
    }

    @Test
    fun `a byte written into a store that holds nothing creates it in the format the store writes`() {
        val id = CredentialsId("only")
        val store = openStore()

        store.put(id, "a-secret".toByteArray())

        assertEquals(
            2.toShort(),
            LocalSecretStore.readHeader(storeFile()).version,
            "the store was not created in the format the store writes",
        )
        assertEquals("a-secret", secretOf(store, id), "the byte was not stored")
    }

    @Test
    fun `a rotated byte in a legacy store brings it to the current format and keeps the credentials it held`() {
        val first = CredentialsId("first")
        val second = CredentialsId("second")
        legacyStore(
            mapOf(
                first to "first-secret".toByteArray(),
                second to "second-secret".toByteArray(),
            ),
        )
        val store = openStore()

        store.rotateBytes(first, "rotated-secret".toByteArray())

        assertEquals(
            2.toShort(),
            LocalSecretStore.readHeader(storeFile()).version,
            "the store was not brought to the format the store writes",
        )
        assertEquals("rotated-secret", secretOf(store, first), "the rotation did not replace the credential it rotated")
        assertEquals("second-secret", secretOf(store, second), "the store lost a credential it did not touch")
    }

    @Test
    fun `a rotated byte in a current store keeps the credentials it held`() {
        val first = CredentialsId("first")
        val second = CredentialsId("second")
        val store = openStore()
        store.add(first, SecretText(first, CredentialScope.GLOBAL, "first-secret".toByteArray()))
        store.add(second, SecretText(second, CredentialScope.GLOBAL, "second-secret".toByteArray()))

        store.rotateBytes(first, "rotated-secret".toByteArray())

        assertEquals(
            2.toShort(),
            LocalSecretStore.readHeader(storeFile()).version,
            "the store changed the format it carries",
        )
        assertEquals("rotated-secret", secretOf(store, first), "the rotation did not replace the credential it rotated")
        assertEquals("second-secret", secretOf(store, second), "the store lost a credential it did not touch")
    }

    @Test
    fun `a rotation of a byte that is not there is refused and writes nothing`() {
        val id = CredentialsId("absent")

        val failure = assertThrows<LocalSecretStore.SecretStoreTamperException> {
            openStore().rotateBytes(id, "a-secret".toByteArray())
        }

        assertTrue(
            failure.message?.contains("not found") == true,
            "the refusal does not say that there is no credential to rotate; it said: ${failure.message}",
        )
        assertTrue(
            openStore().list().isEmpty(),
            "the rotation left a credential in a store that held none",
        )
    }

    @Test
    fun `the mixture add put rotateBytes keeps every credential it did not rotate`() {
        val added = CredentialsId("added")
        val written = CredentialsId("written")
        val store = openStore()

        store.add(added, SecretText(added, CredentialScope.GLOBAL, "added-secret".toByteArray()))
        store.put(written, "written-secret".toByteArray())
        store.rotateBytes(added, "rotated-secret".toByteArray())

        assertEquals("rotated-secret", secretOf(store, added), "the rotation did not replace the credential it rotated")
        assertEquals("written-secret", secretOf(store, written), "the mixture lost a credential it did not touch")
    }

    @Test
    fun `the mixture in the other order keeps every credential it did not rotate`() {
        val written = CredentialsId("written")
        val added = CredentialsId("added")
        val store = openStore()

        store.put(written, "written-secret".toByteArray())
        store.add(added, SecretText(added, CredentialScope.GLOBAL, "added-secret".toByteArray()))
        store.rotateBytes(added, "rotated-secret".toByteArray())

        assertEquals("written-secret", secretOf(store, written), "the mixture lost a credential it did not touch")
        assertEquals("rotated-secret", secretOf(store, added), "the rotation did not replace the credential it rotated")
    }

    @Test
    fun `a store whose version the store does not know is refused and left as it was`() {
        val kept = CredentialsId("kept")
        val store = openStore()
        store.add(kept, SecretText(kept, CredentialScope.GLOBAL, "kept-secret".toByteArray()))
        writeTheVersion(3.toShort())
        val before = Files.readAllBytes(storeFile())

        val failure = assertThrows<LocalSecretStore.SecretStoreTamperException> {
            openStore().put(CredentialsId("other"), "other-secret".toByteArray())
        }

        assertTrue(
            failure.message?.contains("Unsupported store version") == true,
            "the refusal does not name the version the store does not know; it said: ${failure.message}",
        )
        assertArrayEquals(
            before,
            Files.readAllBytes(storeFile()),
            "the store was replaced even though its version was not one the store knows",
        )
    }

    @Test
    fun `a legacy store whose entry does not open is refused and left as it was`() {
        val first = CredentialsId("first")
        val second = CredentialsId("second")
        legacyStore(
            mapOf(
                first to "first-secret".toByteArray(),
                second to "second-secret".toByteArray(),
            ),
        )
        breakTheTagOfTheLastEntry()
        val before = Files.readAllBytes(storeFile())

        val failure = assertThrows<LocalSecretStore.SecretStoreTamperException> {
            openStore().put(CredentialsId("third"), "third-secret".toByteArray())
        }

        assertTrue(
            failure.message?.contains("does not open") == true,
            "the refusal does not say that the entry did not open; it said: ${failure.message}",
        )
        assertArrayEquals(
            before,
            Files.readAllBytes(storeFile()),
            "the store was replaced even though an entry of it did not open",
        )
        assertEquals(
            1.toShort(),
            LocalSecretStore.readHeader(storeFile()).version,
            "the store was left half migrated: it says one format while it holds the body of another",
        )
        assertEquals(
            "first-secret",
            secretOf(openStore(), first),
            "the store that refused the migration cannot be read anymore",
        )
    }

    /**
     * Writes the version the store's header carries, over the file it has.
     */
    private fun writeTheVersion(version: Short) {
        val bytes = Files.readAllBytes(storeFile())
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(4)
        buffer.putShort(version)
        Files.write(storeFile(), bytes)
    }

    /**
     * Breaks the tag of the last entry of the store file.
     *
     * The tag is the last thing the file carries, so the byte that ends the
     * file is the last byte of the last entry's tag. Changing it leaves the
     * file readable as a file: the entry still parses, and it is the seal
     * that refuses to open.
     */
    private fun breakTheTagOfTheLastEntry() {
        val bytes = Files.readAllBytes(storeFile())
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()
        Files.write(storeFile(), bytes)
    }

    /**
     * The secret a credential holds, as text.
     */
    private fun secretOf(store: LocalSecretStore, id: CredentialsId): String {
        val read = assertInstanceOf(SecretText::class.java, store.get(id))
        return String(read.bytes, Charsets.UTF_8)
    }

    private companion object {
        /** The environment variable the passphrase resolver reads. */
        const val PASSPHRASE_ENV = "PIPELINE_STORE_PASSPHRASE"
    }
}
