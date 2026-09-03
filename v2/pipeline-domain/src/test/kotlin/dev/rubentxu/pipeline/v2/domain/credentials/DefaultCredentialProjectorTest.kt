package dev.rubentxu.pipeline.v2.domain.credentials

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.Certificate
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.credentials.SecretFile
import dev.rubentxu.pipeline.v2.domain.credentials.SshPrivateKey
import dev.rubentxu.pipeline.v2.domain.credentials.Zip
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * LF-0403 unit tests for [DefaultCredentialProjector].
 *
 * Covers each binding kind:
 *  - STRING: extracts inner bytes (no envelope, no NUL).
 *  - USERNAME_PASSWORD: two distinct handles for username + password.
 *  - USERNAME_COLON_PASSWORD: joined bytes, no envelope.
 *  - SSH / FILE / CERT / ZIP: each materializes through the port and returns the
 *    correct (var, handle) pairs.
 */
@DisplayName("DefaultCredentialProjector per-kind projections")
class DefaultCredentialProjectorTest {

    private class CapturingMaterialization(
        private val pathByCredential: MutableMap<CredentialsId, Path> = mutableMapOf(),
    ) : CredentialMaterializationDomain {
        var materializeCalls = 0
            private set

        override fun materialize(credential: Credential): MaterializedCredentialDomain {
            materializeCalls++
            val path = when (credential) {
                is SecretFile -> Files.createTempFile("unit-secret-", ".bin").also {
                    Files.write(it, credential.bytes)
                    pathByCredential[credential.id] = it
                }
                is SshPrivateKey -> Files.createTempFile("unit-ssh-", ".key").also {
                    Files.write(it, credential.privateKey)
                    pathByCredential[credential.id] = it
                }
                is Certificate -> Files.createTempFile("unit-cert-", ".p12").also {
                    Files.write(it, credential.keystore)
                    pathByCredential[credential.id] = it
                }
                is Zip -> Files.createTempDirectory("unit-zip-").also { dir ->
                    credential.entries.forEach { (name, bytes) ->
                        val entryPath = dir.resolve(name)
                        Files.createDirectories(entryPath.parent)
                        Files.write(entryPath, bytes)
                    }
                    pathByCredential[credential.id] = dir
                }
                else -> throw IllegalArgumentException("Cannot materialize ${credential::class.simpleName}")
            }
            return MaterializedCredentialDomain(path = path, handle = null)
        }

        override fun close() {
            for (path in pathByCredential.values) {
                runCatching {
                    if (Files.isDirectory(path)) {
                        Files.walk(path).use { stream ->
                            stream.sorted(Comparator.reverseOrder()).forEach { p ->
                                runCatching { Files.deleteIfExists(p) }
                            }
                        }
                    } else {
                        Files.deleteIfExists(path)
                    }
                }
            }
            pathByCredential.clear()
        }
    }

    @Test
    fun `STRING returns the credential bytes verbatim with no envelope`() {
        val text = SecretText(CredentialsId("k"), bytes = "secret-value".toByteArray())
        val projector = DefaultCredentialProjector(CapturingMaterialization())

        val result = projector.project(StringBindingSpec(CredentialsId("k"), "API_KEY"), text, "run-1")

        assertEquals(1, result.bindings.size)
        val handle = result.bindings["API_KEY"]
        assertNotNull(handle)
        assertEquals("secret-value", handle!!.materialize())
        assertFalse(
            handle.bytesView().contains(0.toByte()),
            "LF-0403: STRING projection must NOT contain NUL bytes (envelope stripped)",
        )
    }

    @Test
    fun `USERNAME_PASSWORD returns two different handles for username and password`() {
        val up = UsernamePassword(
            CredentialsId("k"),
            username = "admin",
            password = "p@ssw0rd".toByteArray(),
        )
        val projector = DefaultCredentialProjector(CapturingMaterialization())

        val result = projector.project(
            UsernamePasswordBindingSpec(CredentialsId("k"), "DB_USER", "DB_PASS"),
            up,
            "run-1",
        )

        val userHandle = result.bindings["DB_USER"]
        val passHandle = result.bindings["DB_PASS"]
        assertNotNull(userHandle)
        assertNotNull(passHandle)
        assertEquals("admin", userHandle!!.materialize())
        assertEquals("p@ssw0rd", passHandle!!.materialize())
    }

    @Test
    fun `USERNAME_COLON_PASSWORD joins user and password with a colon, no envelope`() {
        val ucp = UsernameColonPassword(
            CredentialsId("k"),
            user = "admin",
            pass = "secret123".toByteArray(),
        )
        val projector = DefaultCredentialProjector(CapturingMaterialization())

        val result = projector.project(
            UsernameColonPasswordBindingSpec("U_P", CredentialsId("k")),
            ucp,
            "run-1",
        )

        val handle = result.bindings["U_P"]
        assertNotNull(handle)
        assertEquals("admin:secret123", handle!!.materialize())
        assertFalse(
            handle.bytesView().contains(0.toByte()),
            "LF-0403: USERNAME_COLON_PASSWORD must NOT contain NUL bytes (no envelope)",
        )
    }

    @Test
    fun `SSH key file binding injects keyFileVariable as masked path`() {
        val ssh = SshPrivateKey(
            id = CredentialsId("k"),
            username = "git",
            privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nfake\n-----END OPENSSH PRIVATE KEY-----\n".toByteArray(),
        )
        val projector = DefaultCredentialProjector(CapturingMaterialization())

        val result = projector.project(
            SshUserPrivateKeyBindingSpec(CredentialsId("k"), "SSH_KEY_FILE"),
            ssh,
            "run-1",
        )

        val handle = result.bindings["SSH_KEY_FILE"]
        assertNotNull(handle)
        assertTrue(
            handle!!.materialize().startsWith("/"),
            "SSH key path must be absolute: ${handle.materialize()}",
        )
        assertTrue(handle.isMasked, "file paths must be masked (not subject to redaction)")
    }

    @Test
    fun `SSH binding injects THREE distinct handles (key + passphrase + username)`() {
        val ssh = SshPrivateKey(
            id = CredentialsId("k"),
            username = "git",
            privateKey = "fake-key".toByteArray(),
            passphraseRef = null,
        )
        val projector = DefaultCredentialProjector(CapturingMaterialization())

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

        val keys = result.bindings.keys
        assertTrue(keys.containsAll(listOf("SSH_KEY_FILE", "SSH_PASS", "SSH_USER")))
        assertEquals("git", result.bindings["SSH_USER"]!!.materialize())
        // The fix: the three variables do NOT share the same handle.
        val keyHandle = result.bindings["SSH_KEY_FILE"]!!
        val passHandle = result.bindings["SSH_PASS"]!!
        val userHandle = result.bindings["SSH_USER"]!!
        assertFalse(
            keyHandle === passHandle && passHandle === userHandle,
            "LF-0403: SSH binding must inject THREE different handles",
        )
    }

    @Test
    fun `FILE binding injects the materialized secret file path as masked handle`() {
        val sf = SecretFile(
            id = CredentialsId("k"),
            bytes = "secret-file-content".toByteArray(),
        )
        val projector = DefaultCredentialProjector(CapturingMaterialization())

        val result = projector.project(FileBindingSpec(CredentialsId("k"), "SECRET_FILE"), sf, "run-1")

        val handle = result.bindings["SECRET_FILE"]
        assertNotNull(handle)
        assertTrue(handle!!.materialize().startsWith("/"), "File path must be absolute")
        assertTrue(handle.isMasked)
    }

    @Test
    fun `CERTIFICATE binding injects keystore path as masked handle`() {
        val cert = Certificate(
            id = CredentialsId("k"),
            keystore = byteArrayOf(0x01, 0x02, 0x03),
            passwordRef = null,
            alias = "test-alias",
        )
        val projector = DefaultCredentialProjector(CapturingMaterialization())

        val result = projector.project(
            CertificateBindingSpec(
                keystoreVariable = "KEYSTORE",
                credentialsId = CredentialsId("k"),
                aliasVariable = "ALIAS",
            ),
            cert,
            "run-1",
        )

        val keystoreHandle = result.bindings["KEYSTORE"]
        val aliasHandle = result.bindings["ALIAS"]
        assertNotNull(keystoreHandle)
        assertNotNull(aliasHandle)
        assertTrue(keystoreHandle!!.materialize().startsWith("/"))
        assertTrue(keystoreHandle.isMasked)
        assertEquals("test-alias", aliasHandle!!.materialize())
    }

    @Test
    fun `ZIP binding injects the extracted directory path as masked handle`(@TempDir tempDir: Path) {
        val zip = Zip(
            id = CredentialsId("k"),
            entries = mapOf("config.json" to """{"a":1}""".toByteArray()),
        )
        val projector = DefaultCredentialProjector(CapturingMaterialization())

        val result = projector.project(ZipBindingSpec("ZIP_PATH", CredentialsId("k")), zip, "run-1")

        val handle = result.bindings["ZIP_PATH"]
        assertNotNull(handle)
        assertTrue(handle!!.materialize().startsWith("/"))
        assertTrue(handle.isMasked)
    }
}
