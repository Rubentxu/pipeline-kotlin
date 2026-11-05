package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.credentials.api.LinkedSecretReferenceNotFoundException
import dev.rubentxu.pipeline.v2.credentials.api.LinkedSecretReferenceTypeMismatchException
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope
import dev.rubentxu.pipeline.v2.domain.credentials.LinkedSecretRef
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.domain.credentials.SshPrivateKey
import dev.rubentxu.pipeline.v2.domain.credentials.UsernamePassword
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * A chain of linked secrets, resolved against a real store file.
 *
 * ## The guarantee
 * Resolving a chain of linked secrets costs what ONE resolution costs. The
 * store is opened once: its file is read once, the passphrase is validated
 * once, the data key is unwrapped once and the entries are parsed once, no
 * matter how many links the chain has. What the chain walks is ONE view of
 * the store, so the answer does not depend on how the store changed while
 * the chain was being walked.
 *
 * The chain still resolves what it always resolved: the value at the end of
 * the chain, and the refusals it always refused -- a chain deeper than the
 * cap, a chain that closes on itself, a reference to a credential the store
 * does not hold, a reference to a credential of a kind that cannot be linked.
 *
 * ## Why this test is an INTEGRATION test and not a unit test
 * The guarantee is about a COST, and a cost is not visible to a unit test of
 * a pure function. The unit tests of this module cover the key derivation,
 * the cipher, the serializer, the part reader and the path authority, each
 * one of them with arguments and return values and no disk.
 *
 * What only the whole store can show is how many times it opens itself. The
 * derivation is Argon2id over nineteen mebibytes, twice, and it costs about
 * fifty milliseconds; the chain used to pay that price once per link, so a
 * chain of fifteen links paid it sixteen times and the cost of the answer
 * grew with the shape of the question. A unit test of the derivation cannot
 * see that, because the derivation is correct every time it runs.
 *
 * The store counts the work it does, and this test reads those counters.
 *
 * ## Why no layer below this one is needed
 * The counters this test reads live in the store itself, at the places where
 * the work happens: the read, the derivation, the unwrap and the parse. A
 * test that watched the store from the outside -- timing it, or watching the
 * file system -- could only see the sum of those four, and the sum is not
 * the guarantee: the guarantee is about the DERIVATION, which is the one of
 * the four that costs fifty milliseconds and the one that the answer's cost
 * is made of. A layer below would also be a layer that can fail for reasons
 * that have nothing to do with this guarantee: a different machine, a
 * different key derivation library, a filesystem that caches.
 *
 * ## What is faked
 * The world outside the process and nothing else. The store file is a real
 * file in a directory the test owns. The passphrase is a constant string
 * this test supplies through the resolver's environment map, so no terminal
 * and no operator is ever asked for one. No clock, no subprocess and no
 * network are involved: the store has none.
 */
class CredentialsStoreChainResolutionTest {

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

    /**
     * A chain of [links] private keys, each one referring to the next, ending
     * in a secret text. The head of the chain is the last link.
     */
    private fun buildChain(store: LocalSecretStore, links: Int): CredentialsId {
        val leaf = CredentialsId("leaf")
        store.add(leaf, SecretText(leaf, CredentialScope.GLOBAL, "the secret".toByteArray()))
        var next = leaf
        for (index in 0 until links) {
            val id = CredentialsId("link-$index")
            store.add(
                id,
                SshPrivateKey(
                    id,
                    CredentialScope.GLOBAL,
                    "operator",
                    "private-key".toByteArray(),
                    LinkedSecretRef(next),
                ),
            )
            next = id
        }
        return next
    }

    @Test
    fun `a chain of linked secrets resolves the value at the end of the chain`() {
        val store = openStore()
        val head = buildChain(store, 4)

        val handle = store.getAsHandle(head, "passphrase")

        assertEquals(
            "the secret",
            handle.use { String(it) },
            "the chain did not resolve the value at its end",
        )
    }

    @Test
    fun `a chain of fifteen links reads the store file once`() {
        val store = openStore()
        val head = buildChain(store, 15)
        val before = store.storeReads.get()

        store.getAsHandle(head, "passphrase")

        assertEquals(
            1,
            store.storeReads.get() - before,
            "the chain read the store file more than once",
        )
    }

    @Test
    fun `a chain of fifteen links validates the passphrase once`() {
        val store = openStore()
        val head = buildChain(store, 15)
        val before = store.passphraseValidations.get()

        store.getAsHandle(head, "passphrase")

        assertEquals(
            1,
            store.passphraseValidations.get() - before,
            "the chain validated the passphrase more than once",
        )
    }

    @Test
    fun `a chain of fifteen links unwraps the data key once`() {
        val store = openStore()
        val head = buildChain(store, 15)
        val before = store.keyUnwraps.get()

        store.getAsHandle(head, "passphrase")

        assertEquals(
            1,
            store.keyUnwraps.get() - before,
            "the chain unwrapped the data key more than once",
        )
    }

    @Test
    fun `a chain of fifteen links parses the entries once`() {
        val store = openStore()
        val head = buildChain(store, 15)
        val before = store.entryParses.get()

        store.getAsHandle(head, "passphrase")

        assertEquals(
            1,
            store.entryParses.get() - before,
            "the chain parsed the entries more than once",
        )
    }

    @Test
    fun `a chain one link longer than the depth cap is refused`() {
        val store = openStore()
        val head = buildChain(store, 16)

        val failure = assertThrows(LocalSecretStore.SecretStoreTamperException::class.java) {
            store.getAsHandle(head, "passphrase")
        }

        assertEquals(
            "Linked secret reference chain exceeds maximum depth of 16",
            failure.message,
            "the chain was refused for a reason other than its depth",
        )
    }

    @Test
    fun `a chain that closes on itself is refused`() {
        val store = openStore()
        val first = CredentialsId("first")
        val second = CredentialsId("second")
        store.add(
            first,
            SshPrivateKey(first, CredentialScope.GLOBAL, "operator", "key".toByteArray(), LinkedSecretRef(second)),
        )
        store.add(
            second,
            SshPrivateKey(second, CredentialScope.GLOBAL, "operator", "key".toByteArray(), LinkedSecretRef(first)),
        )

        val failure = assertThrows(LocalSecretStore.SecretStoreTamperException::class.java) {
            store.getAsHandle(first, "passphrase")
        }

        assertTrue(
            failure.message?.contains("Circular linked secret reference detected") == true,
            "the chain that closes on itself was refused for another reason: ${failure.message}",
        )
    }

    @Test
    fun `a chain that refers to a credential the store does not hold is refused`() {
        val store = openStore()
        val head = CredentialsId("head")
        store.add(
            head,
            SshPrivateKey(
                head,
                CredentialScope.GLOBAL,
                "operator",
                "key".toByteArray(),
                LinkedSecretRef(CredentialsId("absent")),
            ),
        )

        assertThrows(LinkedSecretReferenceNotFoundException::class.java) {
            store.getAsHandle(head, "passphrase")
        }
    }

    @Test
    fun `a chain that refers to a credential of a kind that cannot be linked is refused`() {
        val store = openStore()
        val target = CredentialsId("target")
        store.add(target, UsernamePassword(target, CredentialScope.GLOBAL, "operator", "password".toByteArray()))
        val head = CredentialsId("head")
        store.add(
            head,
            SshPrivateKey(
                head,
                CredentialScope.GLOBAL,
                "operator",
                "key".toByteArray(),
                LinkedSecretRef(target),
            ),
        )

        assertThrows(LinkedSecretReferenceTypeMismatchException::class.java) {
            store.getAsHandle(head, "passphrase")
        }
    }

    @Test
    fun `a store that does not hold the credential says so even when the passphrase is wrong`() {
        val store = openStore()
        store.add(
            CredentialsId("held"),
            SecretText(CredentialsId("held"), CredentialScope.GLOBAL, "v".toByteArray()),
        )
        val wrong = openStore("a passphrase the store does not know")

        val failure = assertThrows(LocalSecretStore.SecretStoreTamperException::class.java) {
            wrong.getAsSecretHandle(CredentialsId("absent"))
        }

        assertEquals(
            "Credential not found: absent",
            failure.message,
            "the store did not answer that the credential is not there",
        )
    }

    private companion object {
        /** The environment variable the passphrase resolver reads. */
        const val PASSPHRASE_ENV = "PIPELINE_STORE_PASSPHRASE"
    }
}
