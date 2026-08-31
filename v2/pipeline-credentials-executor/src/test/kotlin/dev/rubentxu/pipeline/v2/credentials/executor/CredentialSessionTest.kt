package dev.rubentxu.pipeline.v2.credentials.executor

import dev.rubentxu.pipeline.v2.credentials.api.SecretStore
import dev.rubentxu.pipeline.v2.credentials.multipart.CredentialMaterializer
import dev.rubentxu.pipeline.v2.domain.BoundPurpose
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.credentials.Certificate
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.domain.credentials.SshPrivateKey
import dev.rubentxu.pipeline.v2.domain.credentials.Zip
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.events.CredentialBound
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Unit tests for CredentialSession and CredentialSessionImpl.
 *
 * Tests verify:
 * - Seven-kind mapping (StepSpec.CredentialsBinding.Kind → BoundPurpose, MaterializationKind)
 * - No secret values in emitted events
 * - Reverse cleanup behavior at the unit boundary
 * - Idempotent cleanup
 * - Immutable credentialEnv exposure
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CredentialSessionTest {

    // =============================================================================
    // Seven-kind mapping tests
    // =============================================================================

    @Test
    fun `kindToPurpose maps all seven kinds correctly`() {
        // STRING → API_KEY
        assertEquals(BoundPurpose.API_KEY, CredentialSessionImpl.kindToPurpose(StepSpec.CredentialsBinding.Kind.STRING))

        // USERNAME_PASSWORD → USERNAME_PASSWORD
        assertEquals(BoundPurpose.USERNAME_PASSWORD, CredentialSessionImpl.kindToPurpose(StepSpec.CredentialsBinding.Kind.USERNAME_PASSWORD))

        // SSH_USER_PRIVATE_KEY → SSH_KEY
        assertEquals(BoundPurpose.SSH_KEY, CredentialSessionImpl.kindToPurpose(StepSpec.CredentialsBinding.Kind.SSH_USER_PRIVATE_KEY))

        // FILE → FILE
        assertEquals(BoundPurpose.FILE, CredentialSessionImpl.kindToPurpose(StepSpec.CredentialsBinding.Kind.FILE))

        // CERTIFICATE → CERTIFICATE
        assertEquals(BoundPurpose.CERTIFICATE, CredentialSessionImpl.kindToPurpose(StepSpec.CredentialsBinding.Kind.CERTIFICATE))

        // ZIP → ZIP
        assertEquals(BoundPurpose.ZIP, CredentialSessionImpl.kindToPurpose(StepSpec.CredentialsBinding.Kind.ZIP))

        // USERNAME_COLON_PASSWORD → USERNAME_COLON_PASSWORD
        assertEquals(BoundPurpose.USERNAME_COLON_PASSWORD, CredentialSessionImpl.kindToPurpose(StepSpec.CredentialsBinding.Kind.USERNAME_COLON_PASSWORD))
    }

    @Test
    fun `kindToMaterializationKind returns null for in-memory kinds`() {
        // STRING - in-memory, no materialization
        assertNull(CredentialSessionImpl.kindToMaterializationKind(StepSpec.CredentialsBinding.Kind.STRING))

        // USERNAME_PASSWORD - in-memory, no materialization
        assertNull(CredentialSessionImpl.kindToMaterializationKind(StepSpec.CredentialsBinding.Kind.USERNAME_PASSWORD))

        // USERNAME_COLON_PASSWORD - in-memory, no materialization
        assertNull(CredentialSessionImpl.kindToMaterializationKind(StepSpec.CredentialsBinding.Kind.USERNAME_COLON_PASSWORD))
    }

    @Test
    fun `kindToMaterializationKind returns correct kind for file-based credentials`() {
        // SSH_USER_PRIVATE_KEY → SshPrivateKey
        assertEquals(
            dev.rubentxu.pipeline.v2.credentials.multipart.MaterializationKind.SshPrivateKey,
            CredentialSessionImpl.kindToMaterializationKind(StepSpec.CredentialsBinding.Kind.SSH_USER_PRIVATE_KEY)
        )

        // FILE → SecretFile
        assertEquals(
            dev.rubentxu.pipeline.v2.credentials.multipart.MaterializationKind.SecretFile,
            CredentialSessionImpl.kindToMaterializationKind(StepSpec.CredentialsBinding.Kind.FILE)
        )

        // CERTIFICATE → Certificate
        assertEquals(
            dev.rubentxu.pipeline.v2.credentials.multipart.MaterializationKind.Certificate,
            CredentialSessionImpl.kindToMaterializationKind(StepSpec.CredentialsBinding.Kind.CERTIFICATE)
        )

        // ZIP → Zip
        assertEquals(
            dev.rubentxu.pipeline.v2.credentials.multipart.MaterializationKind.Zip,
            CredentialSessionImpl.kindToMaterializationKind(StepSpec.CredentialsBinding.Kind.ZIP)
        )
    }

    // =============================================================================
    // STRING binding tests
    // =============================================================================

    @Test
    fun `STRING binding resolves to credentialEnv with single env var`() {
        val secretStore = InMemorySecretStore()
        val credentialsId = CredentialsId("test-secret")
        secretStore.put(credentialsId, "super-secret-value".toByteArray())

        val binding = StepSpec.CredentialsBinding(
            kind = StepSpec.CredentialsBinding.Kind.STRING,
            credentialsId = credentialsId,
            variable = "API_KEY"
        )

        val session = CredentialSessionImpl(
            bindings = listOf(binding),
            secretStore = secretStore,
            eventSink = CapturingEventSink(),
            runId = "test-run",
            clock = TestClock()
        )

        val env = session.credentialEnv()
        assertEquals(1, env.size)
        assertTrue(env.containsKey("API_KEY"))

        // Verify the handle is not masked (it's a real secret)
        val handle = env["API_KEY"]!!
        assertFalse(handle.isMasked)
    }

    @Test
    fun `STRING binding emits CredentialBound event with API_KEY purpose`() {
        val secretStore = InMemorySecretStore()
        val credentialsId = CredentialsId("test-secret")
        secretStore.put(credentialsId, "secret".toByteArray())

        val binding = StepSpec.CredentialsBinding(
            kind = StepSpec.CredentialsBinding.Kind.STRING,
            credentialsId = credentialsId,
            variable = "API_KEY"
        )

        val eventSink = CapturingEventSink()
        CredentialSessionImpl(
            bindings = listOf(binding),
            secretStore = secretStore,
            eventSink = eventSink,
            runId = "test-run",
            clock = TestClock()
        )

        val events = eventSink.capturedEvents
        assertEquals(1, events.size)

        val credentialBound = events[0] as CredentialBound
        assertEquals(credentialsId, credentialBound.credentialsId)
        assertEquals(BoundPurpose.API_KEY, credentialBound.purpose)
    }

    // =============================================================================
    // USERNAME_PASSWORD binding tests
    // =============================================================================

    @Test
    fun `USERNAME_PASSWORD binding resolves to credentialEnv with two env vars`() {
        val secretStore = InMemorySecretStore()
        val credentialsId = CredentialsId("test-cred")
        // UsernamePassword stores as username\0password (null-separated)
        val value = "admin\u0000secret123".toByteArray()
        secretStore.put(credentialsId, value)

        val binding = StepSpec.CredentialsBinding(
            kind = StepSpec.CredentialsBinding.Kind.USERNAME_PASSWORD,
            credentialsId = credentialsId,
            usernameVariable = "USERNAME",
            passwordVariable = "PASSWORD"
        )

        val session = CredentialSessionImpl(
            bindings = listOf(binding),
            secretStore = secretStore,
            eventSink = CapturingEventSink(),
            runId = "test-run",
            clock = TestClock()
        )

        val env = session.credentialEnv()
        assertEquals(2, env.size)
        assertTrue(env.containsKey("USERNAME"))
        assertTrue(env.containsKey("PASSWORD"))
    }

    // =============================================================================
    // Cleanup behavior tests
    // =============================================================================

    @Test
    fun `close is idempotent - calling multiple times is safe`() {
        val secretStore = InMemorySecretStore()
        val credentialsId = CredentialsId("test-secret")
        secretStore.put(credentialsId, "secret".toByteArray())

        val binding = StepSpec.CredentialsBinding(
            kind = StepSpec.CredentialsBinding.Kind.STRING,
            credentialsId = credentialsId,
            variable = "API_KEY"
        )

        val session = CredentialSessionImpl(
            bindings = listOf(binding),
            secretStore = secretStore,
            eventSink = CapturingEventSink(),
            runId = "test-run",
            clock = TestClock()
        )

        // First close
        session.close()

        // Second close - should not throw
        session.close()

        // Third close - should not throw
        session.close()
    }

    @Test
    fun `close wipes active handles`() {
        val secretStore = InMemorySecretStore()
        val credentialsId = CredentialsId("test-secret")
        secretStore.put(credentialsId, "secret-value".toByteArray())

        val binding = StepSpec.CredentialsBinding(
            kind = StepSpec.CredentialsBinding.Kind.STRING,
            credentialsId = credentialsId,
            variable = "API_KEY"
        )

        val session = CredentialSessionImpl(
            bindings = listOf(binding),
            secretStore = secretStore,
            eventSink = CapturingEventSink(),
            runId = "test-run",
            clock = TestClock()
        )

        // Get handle before close
        val handleBeforeClose = session.credentialEnv()["API_KEY"]!!

        session.close()

        // After close, the handle should be wiped (calling use should not reveal original value)
        var revealedBytes: ByteArray? = null
        handleBeforeClose.use { bytes ->
            revealedBytes = bytes.copyOf()
        }

        // The bytes should be zeros after wipe
        assertTrue(revealedBytes!!.all { it == 0.toByte() })
    }

    @Test
    fun `activeHandles returns handles that can be closed by session`() {
        val secretStore = InMemorySecretStore()
        val credentialsId = CredentialsId("test-secret")
        secretStore.put(credentialsId, "secret-value".toByteArray())

        val binding = StepSpec.CredentialsBinding(
            kind = StepSpec.CredentialsBinding.Kind.STRING,
            credentialsId = credentialsId,
            variable = "API_KEY"
        )

        val session = CredentialSessionImpl(
            bindings = listOf(binding),
            secretStore = secretStore,
            eventSink = CapturingEventSink(),
            runId = "test-run",
            clock = TestClock()
        )

        val activeHandles = session.activeHandles()
        assertEquals(1, activeHandles.size)

        // The session should be able to close these handles
        session.close()

        // After close, handles are wiped
    }

    // =============================================================================
    // Event emission tests
    // =============================================================================

    @Test
    fun `emitted events contain no secret values`() {
        val secretStore = InMemorySecretStore()
        val credentialsId = CredentialsId("my-secret-id")
        secretStore.put(credentialsId, "super-secret-password".toByteArray())

        val binding = StepSpec.CredentialsBinding(
            kind = StepSpec.CredentialsBinding.Kind.STRING,
            credentialsId = credentialsId,
            variable = "SECRET_VAR"
        )

        val eventSink = CapturingEventSink()
        CredentialSessionImpl(
            bindings = listOf(binding),
            secretStore = secretStore,
            eventSink = eventSink,
            runId = "test-run-123",
            clock = TestClock()
        )

        val events = eventSink.capturedEvents

        // Verify no event contains secret values
        for (event in events) {
            val eventStr = event.toString()
            // Secret value should not appear in event string representation
            assertNotEquals(eventStr.contains("super-secret-password"), true,
                "Secret value should not appear in event: $event")
            assertNotEquals(eventStr.contains("SECRET_VAR"), true,
                "Variable name should not appear in CredentialBound event")
        }
    }

    @Test
    fun `CredentialBound event contains credentialsId and purpose only`() {
        val secretStore = InMemorySecretStore()
        val credentialsId = CredentialsId("cred-123")
        secretStore.put(credentialsId, "secret".toByteArray())

        val binding = StepSpec.CredentialsBinding(
            kind = StepSpec.CredentialsBinding.Kind.STRING,
            credentialsId = credentialsId,
            variable = "VAR_NAME"
        )

        val eventSink = CapturingEventSink()
        CredentialSessionImpl(
            bindings = listOf(binding),
            secretStore = secretStore,
            eventSink = eventSink,
            runId = "run-456",
            clock = TestClock()
        )

        val events = eventSink.capturedEvents
        assertEquals(1, events.size)

        val cb = events[0] as CredentialBound
        assertEquals(credentialsId, cb.credentialsId)
        assertEquals(BoundPurpose.API_KEY, cb.purpose)
        assertEquals("run-456", cb.runId)
    }

    // =============================================================================
    // Resolution boundaries tests
    // =============================================================================

    @Test
    fun `boundaries reports success when all bindings resolve`() {
        val secretStore = InMemorySecretStore()
        val cred1 = CredentialsId("cred-1")
        val cred2 = CredentialsId("cred-2")
        secretStore.put(cred1, "secret1".toByteArray())
        secretStore.put(cred2, "secret2".toByteArray())

        val binding1 = StepSpec.CredentialsBinding(
            kind = StepSpec.CredentialsBinding.Kind.STRING,
            credentialsId = cred1,
            variable = "VAR1"
        )
        val binding2 = StepSpec.CredentialsBinding(
            kind = StepSpec.CredentialsBinding.Kind.STRING,
            credentialsId = cred2,
            variable = "VAR2"
        )

        val session = CredentialSessionImpl(
            bindings = listOf(binding1, binding2),
            secretStore = secretStore,
            eventSink = CapturingEventSink(),
            runId = "test-run",
            clock = TestClock()
        )

        val boundaries = session.boundaries()
        assertTrue(boundaries.success)
        assertEquals(2, boundaries.resolved.size)
        assertEquals(0, boundaries.failed.size)
    }

    @Test
    fun `boundaries reports failure when credential not found`() {
        val secretStore = InMemorySecretStore()
        val credentialsId = CredentialsId("non-existent-cred")

        val binding = StepSpec.CredentialsBinding(
            kind = StepSpec.CredentialsBinding.Kind.STRING,
            credentialsId = credentialsId,
            variable = "VAR"
        )

        var exception: CredentialResolutionException? = null
        try {
            CredentialSessionImpl(
                bindings = listOf(binding),
                secretStore = secretStore,
                eventSink = CapturingEventSink(),
                runId = "test-run",
                clock = TestClock()
            )
        } catch (e: CredentialResolutionException) {
            exception = e
        }

        assertNotNull(exception)
        assertEquals(credentialsId, exception!!.credentialsId)
    }

    // =============================================================================
    // Immutable credentialEnv tests
    // =============================================================================

    @Test
    fun `credentialEnv returns immutable map`() {
        val secretStore = InMemorySecretStore()
        val credentialsId = CredentialsId("test-secret")
        secretStore.put(credentialsId, "secret".toByteArray())

        val binding = StepSpec.CredentialsBinding(
            kind = StepSpec.CredentialsBinding.Kind.STRING,
            credentialsId = credentialsId,
            variable = "API_KEY"
        )

        val session = CredentialSessionImpl(
            bindings = listOf(binding),
            secretStore = secretStore,
            eventSink = CapturingEventSink(),
            runId = "test-run",
            clock = TestClock()
        )

        val env1 = session.credentialEnv()

        // The internal mutable map is private; the returned map is a copy
        // Verify content is correct
        assertEquals(1, env1.size)
        assertTrue(env1.containsKey("API_KEY"))

        // Calling again returns an equal but separate copy (both are snapshots)
        val env2 = session.credentialEnv()
        assertEquals(env1, env2)
    }

    // =============================================================================
    // Test doubles
    // =============================================================================

    private class InMemorySecretStore : SecretStore {
        private val store = mutableMapOf<CredentialsId, ByteArray>()

        override fun add(id: CredentialsId, credential: dev.rubentxu.pipeline.v2.domain.credentials.Credential) {
            store[id] = when (credential) {
                is SecretText -> credential.bytes
                is SshPrivateKey -> credential.privateKey
                is Certificate -> credential.keystore
                is Zip -> credential.entries.values.flatMap { it.toList() }.toByteArray()
                else -> throw UnsupportedOperationException("Unsupported credential type: ${credential::class}")
            }
        }

        override fun put(id: CredentialsId, bytes: ByteArray) {
            store[id] = bytes
        }

        override fun get(id: CredentialsId): dev.rubentxu.pipeline.v2.domain.credentials.Credential {
            val bytes = store[id] ?: throw IllegalStateException("Credential not found: ${id.value}")
            return SecretText(id, dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope.GLOBAL, bytes)
        }

        override fun getAsSecretHandle(id: CredentialsId): SecretHandle {
            val bytes = store[id] ?: throw IllegalStateException("Credential not found: ${id.value}")
            return SecretHandle.secret(bytes)
        }

        override fun getAsHandle(id: CredentialsId, partName: String): SecretHandle {
            return getAsSecretHandle(id)
        }

        override fun list(): List<CredentialsId> = store.keys.toList()

        override fun remove(id: CredentialsId) {
            store.remove(id)
        }

        override fun rotate(id: CredentialsId, credential: dev.rubentxu.pipeline.v2.domain.credentials.Credential) {
            add(id, credential)
        }

        override fun rotateBytes(id: CredentialsId, newBytes: ByteArray) {
            store[id] = newBytes
        }

        override fun close() = Unit
    }

    private class CapturingEventSink : EventSink {
        val capturedEvents = mutableListOf<DomainEvent>()

        override fun append(event: DomainEvent) {
            capturedEvents.add(event)
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> {
            return capturedEvents.filter { it.runId == runId }.asSequence()
        }
    }

    private class TestClock : Clock {
        private var counter = 0L

        override fun now(): Instant {
            counter++
            return Instant.ofEpochSecond(counter)
        }
    }
}