package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
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
 * The store that is upgraded from the v1 format to the v2 one.
 *
 * ## The guarantee
 * A store written in the v1 format and then upgraded keeps every entry it
 * held: the ones it had and the one the upgrade added. An upgrade that
 * cannot be completed leaves the store exactly as it was, and says which
 * entry refused to open.
 *
 * ## Why the version travels in the AAD
 * The AAD of an entry carries the version of the header the entry lives
 * under. An entry sealed under a v1 header and published under a v2 header
 * cannot be opened by anybody, and the store would answer with a diagnostic
 * that blames tampering for a mistake of its own.
 *
 * ## Why the secret travels in another form
 * A v1 entry holds the secret itself. A v2 entry holds the credential in the
 * canonical part form its reader deserializes. An upgrade that moves the
 * bytes without moving the form leaves an entry that opens and says
 * something else than what it said.
 *
 * ## Why this test is an INTEGRATION test
 * The unit suite of this module covers the pure primitives one by one. What
 * no unit test can see is whether the header a store is PUBLISHED with and
 * the header its entries are SEALED under are the same header, and whether
 * the form the entries are written in is the form the reader reads. Both
 * are facts about a file that carries the header and the entries together,
 * and they only exist when the real store writes a real file and the real
 * reader opens it again.
 *
 * ## What is faked
 * The world outside the process and nothing else. The store file is a real
 * file in a directory the test owns, which is where the effect that leaves
 * the process goes. The passphrase is a constant this test supplies through
 * the resolver's environment map, so no terminal and no operator is ever
 * asked for one. No clock, no subprocess and no network: the store has none.
 */
class CredentialsStoreMigrationTest {

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
    private fun openStore(): LocalSecretStore =
        LocalSecretStore(
            file = storeFile(),
            passphrase = PassphraseResolver.resolve(
                env = mapOf(PASSPHRASE_ENV to passphrase),
                ttyReader = { null },
            ),
        )

    @Test
    fun `a store written in v1 and upgraded by add holds every entry readable afterwards`() {
        val first = CredentialsId("first")
        val second = CredentialsId("second")
        val third = CredentialsId("third")
        v1Store(listOf(first, second))

        val upgrading = openStore()
        upgrading.add(third, SecretText(third, CredentialScope.GLOBAL, "three".toByteArray()))
        upgrading.close()

        val reopened = openStore()
        assertEquals("first", secretOf(reopened, first), "the entry the store already had did not survive the upgrade")
        assertEquals("second", secretOf(reopened, second), "the entry the store already had did not survive the upgrade")
        assertEquals("three", secretOf(reopened, third), "the entry the upgrade added did not survive it")
        assertEquals(
            2.toShort(),
            LocalSecretStore.readHeader(storeFile()).version,
            "the store was not upgraded to v2",
        )
    }

    @Test
    fun `a store written in v1 and upgraded by rotate holds every entry readable afterwards`() {
        val first = CredentialsId("first")
        val second = CredentialsId("second")
        v1Store(listOf(first, second))

        val upgrading = openStore()
        upgrading.rotate(first, SecretText(first, CredentialScope.GLOBAL, "rotated".toByteArray()))
        upgrading.close()

        val reopened = openStore()
        assertEquals("rotated", secretOf(reopened, first), "the rotated entry did not survive the upgrade")
        assertEquals("second", secretOf(reopened, second), "the entry the store already had did not survive the upgrade")
        assertEquals(
            2.toShort(),
            LocalSecretStore.readHeader(storeFile()).version,
            "the store was not upgraded to v2",
        )
    }

    @Test
    fun `an upgrade that cannot open the v1 store leaves the store exactly as it was`() {
        val first = CredentialsId("first")
        val second = CredentialsId("second")
        v1Store(listOf(first, second))
        breakTheTagOfTheLastEntry()
        val before = Files.readAllBytes(storeFile())
        val third = CredentialsId("third")

        val failure = assertThrows<LocalSecretStore.SecretStoreTamperException> {
            openStore().add(third, SecretText(third, CredentialScope.GLOBAL, "three".toByteArray()))
        }

        assertTrue(
            failure.message?.contains("does not open") == true,
            "the refusal does not say that the entry did not open; it said: ${failure.message}",
        )
        assertArrayEquals(
            before,
            Files.readAllBytes(storeFile()),
            "an upgrade that failed replaced the store anyway",
        )
        assertEquals(
            1.toShort(),
            LocalSecretStore.readHeader(storeFile()).version,
            "the store was left half upgraded: it says v2 while it holds a v1 body",
        )
        val reopened = openStore()
        assertEquals("first", secretOf(reopened, first), "the store that refused the upgrade cannot be read anymore")
    }

    /**
     * A store in the legacy format, holding [ids] with the bytes of their own
     * name as the secret.
     *
     * The store writes one format; the fixture writes the other one, which
     * the store still opens and migrates.
     */
    private fun v1Store(ids: List<CredentialsId>) {
        LegacyV1StoreFixture.write(
            storeFile(),
            PassphraseResolver.resolve(
                env = mapOf(PASSPHRASE_ENV to passphrase),
                ttyReader = { null },
            ),
            ids.associateWith { it.value.toByteArray() },
        )
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
