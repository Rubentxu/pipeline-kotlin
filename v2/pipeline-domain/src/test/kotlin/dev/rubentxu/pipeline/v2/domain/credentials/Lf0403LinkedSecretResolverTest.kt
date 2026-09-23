package dev.rubentxu.pipeline.v2.domain.credentials

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.Certificate
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.credentials.LinkedSecretRef
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.domain.credentials.SshPrivateKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files

/**
 * LF-0403 (ADR-0097) — tests that pin the contract for resolving
 * [LinkedSecretRef] inside [DefaultCredentialProjector]:
 *
 *  - When an SSH [SshPrivateKey.passphraseRef] is present and the binding asks
 *    for `passphraseVariable`, the projector MUST inject the referenced
 *    [SecretText.bytes] (NOT an empty placeholder). Pre-LF-0403 the code
 *    injected `SecretHandle.masked("")` (silent empty string), so SSH handshakes
 *    failed by wrong credentials and the canary never reached the env.
 *  - Same for [Certificate.passwordRef] with `passwordVariable`.
 *  - When the resolver is wired but the referenced credential is absent or
 *    wrong type, the projector propagates the typed exception (fail-closed) —
 *    it does NOT swallow or substitute empty bytes.
 *  - The default [ThrowingCredentialLinkedSecretResolver] is fail-closed: if
 *    a binding asks for a `passphraseVariable` without resolver wiring, the
 *    projector raises a clear error instead of silently injecting `""`.
 *
 * These tests are the RED for the LF-0403 follow-up; the production code
 * currently fails them. GREEN is wired in subsequent commits.
 */
@DisplayName("LF-0403 LinkedSecretRef resolution in DefaultCredentialProjector")
class Lf0403LinkedSecretResolverTest {

    /**
     * In-memory resolver used by tests: maps credential id → SecretText bytes.
     */
    private class InMemoryLinkedSecretResolver(
        private val store: Map<CredentialsId, SecretText>,
    ) : CredentialLinkedSecretResolver {
        var calls: Int = 0
            private set
        val seen: MutableList<CredentialsId> = mutableListOf()

        override fun resolve(ref: LinkedSecretRef): SecretHandle {
            calls++
            seen += ref.credentialsId
            val cred = store[ref.credentialsId]
                ?: throw IllegalStateException(
                    "no such credential: ${ref.credentialsId.value}",
                )
            // Return a fresh handle carrying the plaintext bytes — never masked
            // (this is the canary path; masking would defeat canary tests).
            return SecretHandle.secret(cred.bytes)
        }
    }

    /** Minimal materializer that writes bytes to a temp file and returns it. */
    private class ByteMaterializer : CredentialMaterializationDomain {
        override fun materialize(credential: Credential): MaterializedCredentialDomain {
            val path = when (credential) {
                is SshPrivateKey -> Files.createTempFile("lf0403-ssh-", ".key").also {
                    Files.write(it, credential.privateKey)
                }
                is Certificate -> Files.createTempFile("lf0403-cert-", ".p12").also {
                    Files.write(it, credential.keystore)
                }
                else -> throw IllegalArgumentException("not supported: ${credential::class.simpleName}")
            }
            return MaterializedCredentialDomain(path = path, handle = null)
        }

        override fun close() {
            // no-op; tests use TempDir where applicable
        }
    }

    @Test
    fun `SSH binding injects passphrase bytes from LinkedSecretRef (canary CANARY_LF0403_SSH reaches env)`() {
        val canary = "CANARY_LF0403_SSH"
        val passphraseCredId = CredentialsId("ssh-passphrase")
        val ssh = SshPrivateKey(
            id = CredentialsId("k"),
            username = "git",
            privateKey = "fake-key".toByteArray(),
            passphraseRef = LinkedSecretRef(passphraseCredId),
        )
        val resolver = InMemoryLinkedSecretResolver(
            mapOf(passphraseCredId to SecretText(passphraseCredId, bytes = canary.toByteArray())),
        )
        val projector = DefaultCredentialProjector(
            materialization = ByteMaterializer(),
            linkedSecretResolver = resolver,
        )

        val result = projector.project(
            SshUserPrivateKeyBindingSpec(
                credentialsId = CredentialsId("k"),
                keyFileVariable = "SSH_KEY_FILE",
                passphraseVariable = "SSH_PASS",
                usernameVariable = "SSH_USER",
            ),
            ssh,
            "run-1",
        )

        // The canary MUST reach the env (LF-0403: pre-fix injected "")
        val passHandle = result.bindings["SSH_PASS"]
        assertNotNull(passHandle, "passphraseVariable binding must be present")
        assertEquals(
            canary,
            passHandle!!.materialize(),
            "LF-0403: SSH passphrase MUST contain the referenced SecretText bytes; was empty placeholder",
        )
        assertEquals(1, resolver.calls, "resolver must be called exactly once for one passphraseRef")
        assertEquals(passphraseCredId, resolver.seen.single(), "resolver must receive the passphraseRef.credentialsId")
    }

    @Test
    fun `SSH binding with no passphraseRef still produces an empty passphraseVariable handle`() {
        val ssh = SshPrivateKey(
            id = CredentialsId("k"),
            username = "git",
            privateKey = "fake-key".toByteArray(),
            passphraseRef = null,
        )
        val projector = DefaultCredentialProjector(
            materialization = ByteMaterializer(),
            linkedSecretResolver = InMemoryLinkedSecretResolver(emptyMap()),
        )

        val result = projector.project(
            SshUserPrivateKeyBindingSpec(
                credentialsId = CredentialsId("k"),
                keyFileVariable = "SSH_KEY_FILE",
                passphraseVariable = "SSH_PASS",
            ),
            ssh,
            "run-1",
        )

        val passHandle = result.bindings["SSH_PASS"]
        assertNotNull(passHandle)
        // No passphraseRef ⇒ passphraseVariable present but empty (binding shape total).
        assertEquals("", passHandle!!.materialize())
    }

    @Test
    fun `CERTIFICATE binding injects password bytes from LinkedSecretRef (canary CANARY_LF0403_CERT reaches env)`() {
        val canary = "CANARY_LF0403_CERT"
        val passwordCredId = CredentialsId("cert-password")
        val cert = Certificate(
            id = CredentialsId("k"),
            keystore = byteArrayOf(0x01, 0x02, 0x03),
            passwordRef = LinkedSecretRef(passwordCredId),
            alias = "test-alias",
        )
        val resolver = InMemoryLinkedSecretResolver(
            mapOf(passwordCredId to SecretText(passwordCredId, bytes = canary.toByteArray())),
        )
        val projector = DefaultCredentialProjector(
            materialization = ByteMaterializer(),
            linkedSecretResolver = resolver,
        )

        val result = projector.project(
            CertificateBindingSpec(
                keystoreVariable = "KEYSTORE",
                credentialsId = CredentialsId("k"),
                aliasVariable = "ALIAS",
                passwordVariable = "KEYSTORE_PASS",
            ),
            cert,
            "run-1",
        )

        val passHandle = result.bindings["KEYSTORE_PASS"]
        assertNotNull(passHandle, "passwordVariable binding must be present")
        assertEquals(
            canary,
            passHandle!!.materialize(),
            "LF-0403: CERTIFICATE password MUST contain the referenced SecretText bytes; was empty placeholder",
        )
        assertEquals(1, resolver.calls)
        assertEquals(passwordCredId, resolver.seen.single())
    }

    @Test
    fun `SSH binding with passphraseRef and missing resolver wiring throws instead of injecting empty bytes`() {
        // Pre-LF-0403 (and current production code) this test would pass because
        // the binding injected `SecretHandle.masked("")` silently. After the fix,
        // wiring is mandatory: the default ThrowingCredentialLinkedSecretResolver
        // surfaces the missing dependency as a clear error.
        val ssh = SshPrivateKey(
            id = CredentialsId("k"),
            username = "git",
            privateKey = "fake-key".toByteArray(),
            passphraseRef = LinkedSecretRef(CredentialsId("ghost")),
        )
        val projector = DefaultCredentialProjector(
            materialization = ByteMaterializer(),
            // Use the throwing default explicitly.
            linkedSecretResolver = ThrowingCredentialLinkedSecretResolver,
        )

        val exception = assertThrows<IllegalStateException> {
            projector.project(
                SshUserPrivateKeyBindingSpec(
                    credentialsId = CredentialsId("k"),
                    keyFileVariable = "SSH_KEY_FILE",
                    passphraseVariable = "SSH_PASS",
                ),
                ssh,
                "run-1",
            )
        }
        assertTrue(
            exception.message!!.contains("ghost"),
            "error must identify the referenced credential id, got: ${exception.message}",
        )
    }

    @Test
    fun `passphrase and key file produce DIFFERENT handles (no aliasing across SSH bindings)`() {
        val ssh = SshPrivateKey(
            id = CredentialsId("k"),
            username = "git",
            privateKey = "fake-key".toByteArray(),
            passphraseRef = LinkedSecretRef(CredentialsId("pp")),
        )
        val resolver = InMemoryLinkedSecretResolver(
            mapOf(CredentialsId("pp") to SecretText(CredentialsId("pp"), bytes = "phrase".toByteArray())),
        )
        val projector = DefaultCredentialProjector(
            materialization = ByteMaterializer(),
            linkedSecretResolver = resolver,
        )

        val result = projector.project(
            SshUserPrivateKeyBindingSpec(
                credentialsId = CredentialsId("k"),
                keyFileVariable = "SSH_KEY_FILE",
                passphraseVariable = "SSH_PASS",
                usernameVariable = "SSH_USER",
            ),
            ssh,
            "run-1",
        )

        val keyHandle = result.bindings["SSH_KEY_FILE"]!!
        val passHandle = result.bindings["SSH_PASS"]!!
        val userHandle = result.bindings["SSH_USER"]!!
        assertNotEquals(
            keyHandle.materialize(),
            passHandle.materialize(),
            "SSH_KEY_FILE must NOT equal SSH_PASS contents (legacy bug)",
        )
        // username handle remains masked("git"); passphrase handle is secret bytes.
        assertNotEquals(
            userHandle.materialize(),
            passHandle.materialize(),
            "SSH_USER must NOT equal SSH_PASS contents",
        )
    }
}
