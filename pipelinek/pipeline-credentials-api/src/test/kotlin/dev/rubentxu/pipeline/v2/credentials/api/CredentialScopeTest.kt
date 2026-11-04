package dev.rubentxu.pipeline.v2.credentials.api

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.NullEventSink
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The scope that resolves a credential, and the surface it offers to whoever builds one.
 *
 * ## What is guaranteed here
 *
 * Two facts, and they answer two different questions:
 *
 *  - the scope resolves the credential the CALLER NAMES. It holds no list of the
 *    credentials that are active in it, so there is nothing to keep in sync and nothing
 *    that can drift away from what the store actually holds;
 *  - the constructor does not take such a list. That is the PUBLISHED surface: the class
 *    is in a module whose ABI is frozen, so the shape of the constructor is part of the
 *    contract with whoever compiled against it.
 *
 * ## Why this instrument
 *
 * The scope is a pure decision over a store, a sink, a clock and a run id: it reads the
 * credential when it is asked for, emits an audit event and wipes the handles on close.
 * A row here therefore needs no filesystem, no process and no clock beyond a fixed
 * instant — and the class had NO row at all before this one, because nothing in the
 * tree built a scope: the surface it exposes was public and unexercised.
 *
 * ## What this does not freeze
 *
 * The wording of the audit events, and the order in which the scope resolves and
 * reports. The rows check what a caller observes: the variable name, the bytes, and the
 * shape of the constructor.
 */
class CredentialScopeTest {

    @Test
    fun `the scope resolves the credential the caller names`() {
        val id = CredentialsId("db-credential")
        val scope = CredentialScope(
            store = SingleSecretStore(id, "s3cret-value".toByteArray()),
            eventSink = NullEventSink,
            clock = FixedClock,
            runId = "run-1",
        )

        val bound = scope.env(CredentialsBinding.string(id, "DB_PASS"))

        assertEquals(
            listOf("DB_PASS"),
            bound.keys.toList(),
            "the binding's variable name is the environment variable the step receives",
        )
        assertArrayEquals(
            "s3cret-value".toByteArray(),
            bound.getValue("DB_PASS").peek { bytes -> bytes },
            "the handle carries the credential the store holds, not another one",
        )
    }

    @Test
    fun `no constructor of the scope takes a list of the credentials that are active`() {
        val parameters = CredentialScope::class.java.constructors
            .flatMap { constructor -> constructor.parameterTypes.toList() }

        assertTrue(
            parameters.none { List::class.java.isAssignableFrom(it) },
            "the scope's constructor still asks for a list of active credentials: $parameters",
        )
    }
}

/**
 * A store that holds ONE credential and answers nothing else.
 *
 * The scope only ever asks for a credential by identifier, so that is the one method
 * this fake answers; the rest of the interface is the storage surface, and a fake that
 * implemented it would be a second store.
 */
private class SingleSecretStore(
    private val id: CredentialsId,
    private val secret: ByteArray,
) : SecretStore {

    override fun getAsSecretHandle(id: CredentialsId): SecretHandle {
        if (id != this.id) {
            error("the store holds no credential named '${id.value}'")
        }
        return SecretHandle.secret(secret.copyOf())
    }

    override fun add(id: CredentialsId, credential: Credential) = unsupported()

    override fun put(id: CredentialsId, bytes: ByteArray) = unsupported()

    override fun get(id: CredentialsId): Credential = unsupported()

    override fun getAsHandle(id: CredentialsId, partName: String): SecretHandle = unsupported()

    override fun list(): List<CredentialsId> = unsupported()

    override fun remove(id: CredentialsId) = unsupported()

    override fun rotate(id: CredentialsId, credential: Credential) = unsupported()

    override fun rotateBytes(id: CredentialsId, newBytes: ByteArray) = unsupported()

    override fun close() = Unit

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("this store holds one credential and nothing else")
}

/** A clock that never moves: the row is about the credential, not about the instant. */
private object FixedClock : Clock {
    override fun now(): Instant = Instant.parse("2026-01-01T00:00:00Z")
}
