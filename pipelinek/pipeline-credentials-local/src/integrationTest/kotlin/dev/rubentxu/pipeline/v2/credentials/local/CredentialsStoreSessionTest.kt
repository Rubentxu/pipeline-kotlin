package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The end of a session of credentials.
 *
 * ## The guarantee
 * A session of credentials ends, and the end is visible in three ways:
 * - the store holds **no key** once an operation ends, and none after the
 *   close -- not even when the operation failed;
 * - the passphrase the store was given is **gone** after the close;
 * - after the close, every operation is **refused**, and the refusal names
 *   that state instead of blaming the passphrase of the operator.
 *
 * ## Why the passphrase is observed from the outside
 * The passphrase is the one secret the caller can see: the store wipes the
 * very array it was given, so the caller holds the proof that it is gone.
 * The keys the store derives are not observable that way, and this test does
 * not pretend otherwise: what it checks is that the store **does not hold**
 * them, which is what [LocalSecretStore.liveSecretBuffers] reports.
 *
 * ## Why this test is an INTEGRATION test
 * The lifecycle of a store is a fact about a store over a real file: the
 * close has to leave the file alone, the refusal has to arrive instead of an
 * answer, and the passphrase has to be the one the file was opened with. None
 * of that exists without the file.
 *
 * ## What is faked
 * The world outside the process and nothing else. The store file is a real
 * file in a directory the test owns. The passphrase is a constant this test
 * supplies through the resolver's environment map, so no terminal and no
 * operator is ever asked for one. No clock, no subprocess and no network: the
 * store has none.
 */
class CredentialsStoreSessionTest {

    /** The home directory the store's path authority resolves against. */
    @TempDir
    lateinit var home: Path

    private val passphrase = "a passphrase this test owns"
    private val id = CredentialsId("session")

    /**
     * The store whose keys are watched while a mutation is in flight.
     *
     * The committer reads the count of the store it belongs to, and the
     * store has to exist before the committer does: the committer is what
     * lets a test look at the store WHILE it is using its keys.
     */
    private lateinit var observedStore: LocalSecretStore

    /** How many secret buffers the store held while the mutation was in flight. */
    private var heldDuringCommit = -1

    /**
     * The store file, as the one authority for the store's location names it.
     */
    private fun storeFile(): Path =
        CredentialsStorePath.resolve(envValue = null, userHome = home)

    /**
     * A store over [storeFile] and the passphrase it was given.
     *
     * The two are returned together because the passphrase is the only secret
     * of this store that the caller can watch: the store wipes the very array
     * it is handed, and this test holds that array.
     */
    private fun openStore(passphraseText: String = passphrase): Pair<LocalSecretStore, CharArray> {
        val key = PassphraseResolver.resolve(
            env = mapOf(PASSPHRASE_ENV to passphraseText),
            ttyReader = { null },
        )
        return LocalSecretStore(file = storeFile(), passphrase = key) to key
    }

    /** A store that already holds one credential. */
    private fun storeHoldingOneCredential(): Pair<LocalSecretStore, CharArray> {
        val (store, key) = openStore()
        store.add(id, SecretText(id, CredentialScope.GLOBAL, "a secret".toByteArray()))
        return store to key
    }

    // --- The store holds no key -----------------------------------------------

    @Test
    fun `a mutation in flight holds the keys it is using`() {
        observedStore = LocalSecretStore(
            file = storeFile(),
            passphrase = PassphraseResolver.resolve(
                env = mapOf(PASSPHRASE_ENV to passphrase),
                ttyReader = { null },
            ),
            committer = object : StoreCommit {
                override fun commit(temporary: Path, target: Path) {
                    heldDuringCommit = observedStore.liveSecretBuffers
                    AtomicStoreCommit.commit(temporary, target)
                }
            },
        )

        observedStore.add(id, SecretText(id, CredentialScope.GLOBAL, "a secret".toByteArray()))

        assertTrue(
            heldDuringCommit > 0,
            "the store held no key while the mutation was in flight: the count cannot see " +
                "what the store holds, and every row that asserts an absence is empty",
        )
    }

    @Test
    fun `the store holds no key once a write ends`() {
        val (store, _) = storeHoldingOneCredential()

        assertEquals(
            0,
            store.liveSecretBuffers,
            "the store kept a key after the write ended",
        )
    }

    @Test
    fun `the store holds no key once a read ends`() {
        val (store, _) = storeHoldingOneCredential()

        store.get(id)

        assertEquals(
            0,
            store.liveSecretBuffers,
            "the store kept a key after the read ended",
        )
    }

    @Test
    fun `the store holds no key when a read fails`() {
        val (store, _) = storeHoldingOneCredential()

        assertThrows(LocalSecretStore.SecretStoreTamperException::class.java) {
            store.get(CredentialsId("a credential the store does not hold"))
        }

        assertEquals(
            0,
            store.liveSecretBuffers,
            "the store kept a key after a read failed",
        )
    }

    @Test
    fun `the store holds no key when the opening fails`() {
        val (holding, _) = storeHoldingOneCredential()
        holding.close()
        val (store, _) = openStore("a passphrase the store does not know")

        assertThrows(LocalSecretStore.SecretStorePassphraseMismatchException::class.java) {
            store.get(id)
        }

        assertEquals(
            0,
            store.liveSecretBuffers,
            "the store kept the key it derived to try to open",
        )
    }

    @Test
    fun `the store holds no key when the passphrase check fails`() {
        val (holding, _) = storeHoldingOneCredential()
        holding.close()
        val (store, _) = openStore("a passphrase the store does not know")

        assertThrows(LocalSecretStore.SecretStorePassphraseMismatchException::class.java) {
            store.verifyPassphrase()
        }

        assertEquals(
            0,
            store.liveSecretBuffers,
            "the store kept the key it derived to try to open",
        )
    }

    @Test
    fun `the store holds no key after the close`() {
        val (store, _) = storeHoldingOneCredential()

        store.close()

        assertEquals(
            0,
            store.liveSecretBuffers,
            "the store kept a key after the close",
        )
    }

    // --- The close ------------------------------------------------------------

    @Test
    fun `the close wipes the passphrase the store was given`() {
        val (store, key) = storeHoldingOneCredential()

        store.close()

        assertTrue(
            key.all { it == '\u0000' },
            "the passphrase the store was given is still readable after the close",
        )
    }

    @Test
    fun `closing twice is not an error`() {
        val (store, key) = storeHoldingOneCredential()

        store.close()
        store.close()

        assertTrue(
            key.all { it == '\u0000' },
            "the second close let the passphrase back",
        )
    }

    @Test
    fun `a refused operation after the close leaves the file as it was`() {
        val (store, _) = storeHoldingOneCredential()
        val before = Files.readAllBytes(storeFile())
        store.close()

        assertThrows(LocalSecretStore.SecretStoreClosedException::class.java) {
            store.add(id, SecretText(id, CredentialScope.GLOBAL, "another secret".toByteArray()))
        }

        assertArrayEquals(
            before,
            Files.readAllBytes(storeFile()),
            "a closed store wrote to its file",
        )
    }

    // --- The use after the close ----------------------------------------------

    @Test
    fun `a read after the close is refused`() {
        val (store, _) = storeHoldingOneCredential()
        store.close()

        val failure = assertThrows(LocalSecretStore.SecretStoreClosedException::class.java) {
            store.get(id)
        }

        assertTrue(
            failure.message?.contains("closed") == true,
            "the refusal must name the state the store is in; it said: ${failure.message}",
        )
    }

    @Test
    fun `the listing after the close is refused`() {
        val (store, _) = storeHoldingOneCredential()
        store.close()

        assertThrows(
            LocalSecretStore.SecretStoreClosedException::class.java,
            { store.list() },
            "a closed store answered what it holds",
        )
    }

    @Test
    fun `a store that was opened before the close does not answer after it`() {
        val (store, _) = storeHoldingOneCredential()
        store.list()
        store.close()

        assertThrows(
            LocalSecretStore.SecretStoreClosedException::class.java,
            { store.list() },
            "a closed store that was open before it answered what it holds",
        )
    }

    @Test
    fun `the passphrase check after the close is refused`() {
        val (store, _) = storeHoldingOneCredential()
        store.close()

        assertThrows(
            LocalSecretStore.SecretStoreClosedException::class.java,
            { store.verifyPassphrase() },
            "a closed store said that its passphrase was right",
        )
    }

    @Test
    fun `a write after the close is refused`() {
        val (store, _) = storeHoldingOneCredential()
        store.close()

        assertThrows(
            LocalSecretStore.SecretStoreClosedException::class.java,
            { store.put(id, "a secret".toByteArray()) },
            "a closed store accepted a secret",
        )
    }

    @Test
    fun `the refusal after the close names no secret`() {
        val (store, _) = storeHoldingOneCredential()
        store.close()

        val failure = assertThrows(LocalSecretStore.SecretStoreClosedException::class.java) {
            store.get(id)
        }

        assertFalse(
            failure.message?.contains(passphrase) == true,
            "the refusal named the passphrase of the operator",
        )
    }

    private companion object {
        /** The environment variable the passphrase resolver reads. */
        const val PASSPHRASE_ENV = "PIPELINE_STORE_PASSPHRASE"
    }
}
