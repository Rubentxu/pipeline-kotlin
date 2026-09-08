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

    // ─── EM-7 materialization retention tests ─────────────────────────────────

    @Test
    fun `SSH FILE CERT ZIP bindings retain their materialized paths`(@TempDir tempDir: Path) {
        val capturing = CapturingMaterialization()
        val projector = DefaultCredentialProjector(capturing)

        val ssh = SshPrivateKey(
            id = CredentialsId("ssh-key"),
            username = "git",
            privateKey = "fake-key".toByteArray(),
        )
        val sshResult = projector.project(
            SshUserPrivateKeyBindingSpec(CredentialsId("ssh-key"), "SSH_KEY_FILE"),
            ssh, "run-1",
        )
        assertEquals(1, sshResult.retainedMaterializations.size, "SSH should retain one materialization")
        assertTrue(Files.exists(sshResult.retainedMaterializations.single().path), "SSH path must still exist")

        val file = SecretFile(
            id = CredentialsId("file-key"),
            bytes = "secret".toByteArray(),
        )
        val fileResult = projector.project(
            FileBindingSpec(CredentialsId("file-key"), "SECRET_FILE"),
            file, "run-1",
        )
        assertEquals(1, fileResult.retainedMaterializations.size, "FILE should retain one materialization")
        assertTrue(Files.exists(fileResult.retainedMaterializations.single().path), "FILE path must still exist")

        val cert = Certificate(
            id = CredentialsId("cert-key"),
            keystore = byteArrayOf(0x01),
        )
        val certResult = projector.project(
            CertificateBindingSpec("KEYSTORE", CredentialsId("cert-key")),
            cert, "run-1",
        )
        assertEquals(1, certResult.retainedMaterializations.size, "CERT should retain one materialization")
        assertTrue(Files.exists(certResult.retainedMaterializations.single().path), "CERT path must still exist")

        val zip = Zip(
            id = CredentialsId("zip-key"),
            entries = mapOf("a.txt" to "content".toByteArray()),
        )
        val zipResult = projector.project(
            ZipBindingSpec("ZIP_PATH", CredentialsId("zip-key")),
            zip, "run-1",
        )
        assertEquals(1, zipResult.retainedMaterializations.size, "ZIP should retain one materialization")
        assertTrue(Files.exists(zipResult.retainedMaterializations.single().path), "ZIP path must still exist")
    }

    @Test
    fun `STRING USERNAME_PASSWORD USERNAME_COLON_PASSWORD bindings retain no materializations`(@TempDir tempDir: Path) {
        val projector = DefaultCredentialProjector(CapturingMaterialization())

        val stringResult = projector.project(
            StringBindingSpec(CredentialsId("k"), "VAR"),
            SecretText(CredentialsId("k"), bytes = "secret".toByteArray()),
            "run-1",
        )
        assertTrue(stringResult.retainedMaterializations.isEmpty(), "STRING should retain no materializations")

        val upResult = projector.project(
            UsernamePasswordBindingSpec(CredentialsId("k"), "USER", "PASS"),
            UsernamePassword(CredentialsId("k"), username = "u", password = "p".toByteArray()),
            "run-1",
        )
        assertTrue(upResult.retainedMaterializations.isEmpty(), "USERNAME_PASSWORD should retain no materializations")

        val ucpResult = projector.project(
            UsernameColonPasswordBindingSpec("CREDS", CredentialsId("k")),
            UsernameColonPassword(CredentialsId("k"), user = "u", pass = "p".toByteArray()),
            "run-1",
        )
        assertTrue(ucpResult.retainedMaterializations.isEmpty(), "USERNAME_COLON_PASSWORD should retain no materializations")
    }

    @Test
    fun `ProjectionResult close wipes paths in reverse-LIFO order`(@TempDir tempDir: Path) {
        val capturing = CapturingMaterialization()
        val projector = DefaultCredentialProjector(capturing)

        // Project two file-based bindings
        val ssh = SshPrivateKey(
            id = CredentialsId("ssh-key"),
            username = "git",
            privateKey = "key1".toByteArray(),
        )
        val sshResult = projector.project(
            SshUserPrivateKeyBindingSpec(CredentialsId("ssh-key"), "K1"),
            ssh, "run-1",
        )

        val file = SecretFile(
            id = CredentialsId("file-key"),
            bytes = "key2".toByteArray(),
        )
        val fileResult = projector.project(
            FileBindingSpec(CredentialsId("file-key"), "K2"),
            file, "run-1",
        )

        val sshPath = sshResult.retainedMaterializations.single().path
        val filePath = fileResult.retainedMaterializations.single().path

        assertTrue(Files.exists(sshPath), "SSH path must exist before close")
        assertTrue(Files.exists(filePath), "FILE path must exist before close")

        // Close file result first (LIFO: inner scope closes before outer)
        fileResult.close()
        assertFalse(Files.exists(filePath), "FILE path must be wiped after close")
        assertTrue(Files.exists(sshPath), "SSH path must still exist (not yet closed)")

        // Close ssh result (outer scope)
        sshResult.close()
        assertFalse(Files.exists(sshPath), "SSH path must be wiped after close")

        capturing.close()
    }

    @Test
    fun `ProjectionResult close is idempotent`(@TempDir tempDir: Path) {
        val capturing = CapturingMaterialization()
        val projector = DefaultCredentialProjector(capturing)

        val file = SecretFile(
            id = CredentialsId("file-key"),
            bytes = "secret".toByteArray(),
        )
        val result = projector.project(
            FileBindingSpec(CredentialsId("file-key"), "SECRET_FILE"),
            file, "run-1",
        )
        val path = result.retainedMaterializations.single().path

        // Close multiple times — must not throw
        result.close()
        result.close()
        result.close()

        assertFalse(Files.exists(path), "Path must be absent after first close (and subsequent calls must be no-ops)")
        capturing.close()
    }

    @Test
    fun `ProjectionResult close surfaces wipe failure as WipeException with orphan paths`(@TempDir tempDir: Path) {
        // MaterializedCredentialDomain is now open, so we can subclass it
        // to simulate a wipe failure without needing root privileges.
        val failingPath = tempDir.resolve("unwipeable")
        Files.writeString(failingPath, "secret")
        val failingMaterialization = object : MaterializedCredentialDomain(failingPath, null) {
            override fun close() {
                throw RuntimeException("Simulated wipe failure")
            }
        }

        val result = ProjectionResult(
            bindings = mapOf("VAR" to SecretHandle.masked(failingPath.toString())),
            retainedMaterializations = listOf(failingMaterialization),
        )

        val exception = runCatching { result.close() }.exceptionOrNull()
        assertTrue(exception is ProjectionResult.WipeException, "close() must throw WipeException on wipe failure, got: ${exception?.javaClass?.simpleName}")
        val wipeException = exception as ProjectionResult.WipeException
        assertTrue(failingPath in wipeException.orphanPaths, "Failed path must be in orphan list")
    }
}
