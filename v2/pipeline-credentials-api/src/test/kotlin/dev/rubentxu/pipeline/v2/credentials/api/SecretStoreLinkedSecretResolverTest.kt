package dev.rubentxu.pipeline.v2.credentials.api

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialLinkedSecretResolver
import dev.rubentxu.pipeline.v2.domain.credentials.LinkedSecretRef
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * WU-RP-050 (ADR-0098) — tests that pin the contract for the shared
 * [SecretStoreLinkedSecretResolver] adapter.
 *
 * The adapter is the SOLE legitimate non-SPI consumer of
 * [SecretStore.getAsSecretHandle] outside of the implementation
 * ([dev.rubentxu.pipeline.v2.credentials.local.LocalCredentialProvider]).
 * Every other site that needs to resolve a [LinkedSecretRef] from a
 * [SecretStore] must compose this adapter rather than re-implementing
 * the `store.getAsSecretHandle(ref.id)` pattern.
 *
 * Contract:
 *  - [SecretStoreLinkedSecretResolver.resolve] delegates to
 *    [SecretStore.getAsSecretHandle] with the ref's [LinkedSecretRef.credentialsId].
 *  - Errors from the store propagate as-is (typed
 *    [LinkedSecretReferenceNotFoundException] /
 *    [LinkedSecretReferenceTypeMismatchException]).
 *  - The adapter IS-A [CredentialLinkedSecretResolver] (port domain).
 */
@DisplayName("WU-RP-050 SecretStoreLinkedSecretResolver (shared adapter)")
class SecretStoreLinkedSecretResolverTest {

    /**
     * Minimal in-memory [SecretStore] for the tests. Implements all methods
     * of the SPI (required by the Kotlin compiler) but only the methods the
     * adapter needs do anything useful.
     */
    private class InMemorySecretStore(
        private val store: Map<CredentialsId, SecretHandle>,
        private val throwOnGet: Boolean = false,
    ) : SecretStore {
        var getAsSecretHandleCalls: Int = 0
            private set
        val seen: MutableList<CredentialsId> = mutableListOf()

        override fun get(id: CredentialsId): Credential {
            val handle = store[id] ?: throw IllegalStateException("no such credential: ${id.value}")
            // Wrap the bytes as a SecretText credential so the return type matches the SPI.
            return SecretText(id, bytes = handle.bytesView())
        }

        override fun getAsSecretHandle(id: CredentialsId): SecretHandle {
            getAsSecretHandleCalls++
            seen += id
            if (throwOnGet) {
                throw LinkedSecretReferenceNotFoundException(id)
            }
            val handle = store[id] ?: throw LinkedSecretReferenceNotFoundException(id)
            return SecretHandle.secret(handle.bytesView())
        }

        override fun getAsHandle(id: CredentialsId, partName: String): SecretHandle {
            throw UnsupportedOperationException("not needed for this test")
        }

        override fun add(id: CredentialsId, credential: Credential) {
            throw UnsupportedOperationException("not needed for this test")
        }

        override fun put(id: CredentialsId, bytes: ByteArray) {
            throw UnsupportedOperationException("not needed for this test")
        }

        override fun list(): List<CredentialsId> = store.keys.toList()

        override fun remove(id: CredentialsId) {
            throw UnsupportedOperationException("not needed for this test")
        }

        override fun rotate(id: CredentialsId, credential: Credential) {
            throw UnsupportedOperationException("not needed for this test")
        }

        override fun rotateBytes(id: CredentialsId, newBytes: ByteArray) {
            throw UnsupportedOperationException("not needed for this test")
        }

        override fun close() {
            // no-op
        }
    }

    @Test
    fun `resolve delegates to SecretStore getAsSecretHandle with ref credentialsId`() {
        val secretId = CredentialsId("ssh-passphrase")
        val expected = SecretHandle.secret("CANARY_LF0403_SSH".toByteArray())
        val store = InMemorySecretStore(mapOf(secretId to expected))
        val resolver = SecretStoreLinkedSecretResolver(store)

        val result = resolver.resolve(LinkedSecretRef(secretId))

        assertNotNull(result)
        assertEquals(
            "CANARY_LF0403_SSH",
            result.materialize(),
            "WU-RP-050: resolve MUST return the same bytes as SecretStore.getAsSecretHandle",
        )
        assertEquals(1, store.getAsSecretHandleCalls)
        assertEquals(
            secretId,
            store.seen.single(),
            "adapter MUST pass the ref's credentialsId verbatim",
        )
    }

    @Test
    fun `resolve propagates typed errors as-is (fail-closed)`() {
        val store = InMemorySecretStore(store = emptyMap(), throwOnGet = true)
        val resolver = SecretStoreLinkedSecretResolver(store)

        val exception = assertThrows(LinkedSecretReferenceNotFoundException::class.java) {
            resolver.resolve(LinkedSecretRef(CredentialsId("ghost")))
        }
        assertEquals(
            CredentialsId("ghost"),
            exception.referencedId,
            "typed error must carry the referenced credential id (fail-closed with context)",
        )
    }

    @Test
    fun `adapter is-a CredentialLinkedSecretResolver port (hexagonal contract)`() {
        val store = InMemorySecretStore(emptyMap())
        val resolver: CredentialLinkedSecretResolver = SecretStoreLinkedSecretResolver(store)
        assertEquals(true, resolver is CredentialLinkedSecretResolver)
    }

    @Test
    fun `resolve returns fresh SecretHandle per call (SecretStore semantics preserved)`() {
        val secretId = CredentialsId("k")
        val store = InMemorySecretStore(mapOf(secretId to SecretHandle.secret("v".toByteArray())))
        val resolver = SecretStoreLinkedSecretResolver(store)

        val a = resolver.resolve(LinkedSecretRef(secretId))
        val b = resolver.resolve(LinkedSecretRef(secretId))
        assertEquals(a.materialize(), b.materialize())
        assertEquals(2, store.getAsSecretHandleCalls)
    }
}
