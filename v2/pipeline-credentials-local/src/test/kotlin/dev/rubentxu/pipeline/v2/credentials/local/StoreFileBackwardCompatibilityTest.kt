package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.Certificate
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope
import dev.rubentxu.pipeline.v2.domain.credentials.LinkedSecretRef
import dev.rubentxu.pipeline.v2.domain.credentials.SecretFile
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.domain.credentials.SshPrivateKey
import dev.rubentxu.pipeline.v2.domain.credentials.UsernameColonPassword
import dev.rubentxu.pipeline.v2.domain.credentials.UsernamePassword
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Cross-version compatibility for the store file.
 *
 * The companion fixtures under `src/test/resources/compat` were written by the
 * previous version of `LocalSecretStore`. They pin the one property the
 * refactoring of the writer could silently break: a store written before the
 * change must still load afterwards, byte for byte, including the linked-ref
 * parts whose payload carries no length prefix.
 *
 * Regenerate a fixture with [main] when the format changes deliberately. The
 * reviewer then sees the new bytes in the diff instead of trusting a round-trip
 * test that passes even when both sides moved together.
 */
@DisplayName("Stores written by the previous version still load")
class StoreFileBackwardCompatibilityTest {

    @TempDir
    lateinit var tempDir: Path

    private fun fixture(name: String): ByteArray {
        val resource = javaClass.classLoader.getResourceAsStream("compat/$name")
            ?: error("missing compat fixture 'compat/$name'")
        return resource.use { it.readBytes() }
    }

    private fun copyFixture(name: String, target: String): Path {
        val bytes = fixture(name)
        val path = tempDir.resolve(target)
        Files.write(path, bytes)
        return path
    }

    private fun assertRoundTrips(fixtureName: String, id: CredentialsId, check: (Any) -> Unit) {
        val path = copyFixture(fixtureName, "$fixtureName.bin")
        val store = LocalSecretStore(path, PASS)
        val read = store.get(id)
        assertEquals(id, read.id, "the credential must come back under its own id")
        check(read)
    }

    @Test
    fun `a v1 SecretText fixture still loads`() {
        assertRoundTrips("secret-text-v1.bin", CredentialsId("compat-text")) { read ->
            assertTrue(read is SecretText, "expected SecretText, got ${read::class.simpleName}")
            assertArrayEquals(
                "legacy-value".toByteArray(),
                (read as SecretText).bytes,
                "v1 payload must be byte-identical",
            )
        }
    }

    @Test
    fun `a v2 UsernamePassword fixture still loads`() {
        assertRoundTrips("username-password-v2.bin", CredentialsId("compat-up")) { read ->
            assertTrue(read is UsernamePassword, "expected UsernamePassword")
            val up = read as UsernamePassword
            assertEquals("admin", up.username)
            assertArrayEquals("s3cr3t".toByteArray(), up.password)
        }
    }

    @Test
    fun `a v2 SshPrivateKey fixture with an absent passphrase still loads`() {
        assertRoundTrips("ssh-absent-v2.bin", CredentialsId("compat-ssh-none")) { read ->
            assertTrue(read is SshPrivateKey, "expected SshPrivateKey")
            val ssh = read as SshPrivateKey
            assertEquals("git", ssh.username)
            assertEquals(null, ssh.passphraseRef, "an absent marker must read as no ref")
        }
    }

    @Test
    fun `a v2 SshPrivateKey fixture with a linked passphrase still loads`() {
        assertRoundTrips("ssh-linked-v2.bin", CredentialsId("compat-ssh-ref")) { read ->
            assertTrue(read is SshPrivateKey, "expected SshPrivateKey")
            val ssh = read as SshPrivateKey
            assertEquals("git", ssh.username)
            assertEquals(
                CredentialsId("target-cred"),
                ssh.passphraseRef?.credentialsId,
                "a linked marker must read back as a ref to the stored id",
            )
        }
    }

    @Test
    fun `a v2 Certificate fixture with a linked password still loads`() {
        assertRoundTrips("cert-linked-v2.bin", CredentialsId("compat-cert")) { read ->
            assertTrue(read is Certificate, "expected Certificate")
            val cert = read as Certificate
            assertEquals("leaf", cert.alias)
            assertEquals(
                CredentialsId("target-cred"),
                cert.passwordRef?.credentialsId,
                "a linked marker must read back as a ref to the stored id",
            )
        }
    }

    @Test
    fun `a v2 SecretFile fixture with a null name still loads`() {
        assertRoundTrips("secret-file-noname-v2.bin", CredentialsId("compat-file")) { read ->
            assertTrue(read is SecretFile, "expected SecretFile")
            assertArrayEquals("payload".toByteArray(), (read as SecretFile).bytes)
        }
    }

    @Test
    fun `a v2 UsernameColonPassword fixture still loads`() {
        assertRoundTrips("colon-v2.bin", CredentialsId("compat-colon")) { read ->
            assertTrue(read is UsernameColonPassword, "expected UsernameColonPassword")
            val c = read as UsernameColonPassword
            assertEquals("svc", c.user)
            assertArrayEquals("pw".toByteArray(), c.pass)
        }
    }

    @Test
    fun `rewriting a legacy fixture produces a file the new reader accepts`() {
        // The reverse direction: a credential loaded from an old file and
        // written back must remain readable, so a rotation does not silently
        // downgrade or corrupt the entry.
        val path = copyFixture("secret-text-v1.bin", "rewrite.bin")
        val store = LocalSecretStore(path, PASS)
        val read = store.get(CredentialsId("compat-text"))
        val target = tempDir.resolve("rewritten.bin")
        val targetStore = LocalSecretStore(target, PASS)
        targetStore.add(CredentialsId("compat-text"), read)

        val reread = LocalSecretStore(target, PASS).get(CredentialsId("compat-text"))
        assertArrayEquals(
            "legacy-value".toByteArray(),
            (reread as SecretText).bytes,
            "a rewrite must preserve the payload",
        )
    }

    companion object {
        private val PASS = "compat-passphrase".toCharArray()

        /**
         * Regenerates the fixtures. Run by hand, never by the build:
         *
         * ```
         * ./gradlew :pipeline-credentials-local:test --tests '*StoreFileBackwardCompatibilityTest.main*'
         * ```
         */
        @JvmStatic
        fun main(args: Array<String>) {
            val out = Path.of("src/test/resources/compat")
            Files.createDirectories(out)
            val textId = CredentialsId("compat-text")
            val upId = CredentialsId("compat-up")
            val sshNoneId = CredentialsId("compat-ssh-none")
            val sshRefId = CredentialsId("compat-ssh-ref")
            val certId = CredentialsId("compat-cert")
            val fileId = CredentialsId("compat-file")
            val colonId = CredentialsId("compat-colon")
            val tmp = Files.createTempDirectory("compat")
            val target = CredentialsId("target-cred")

            fun write(name: String, init: (LocalSecretStore) -> Unit) {
                val path = tmp.resolve("$name.tmp")
                Files.deleteIfExists(path)
                init(LocalSecretStore(path, PASS))
                Files.copy(path, File(out.toFile(), name).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                println("wrote $name (${Files.size(out.resolve(name))} bytes)")
            }

            // A referenced id must exist, so it is added first in each store.
            write("secret-text-v1.bin") { it.put(textId, "legacy-value".toByteArray()) }
            write("username-password-v2.bin") {
                it.add(upId, UsernamePassword(upId, CredentialScope.GLOBAL, "admin", "s3cr3t".toByteArray()))
            }
            write("ssh-absent-v2.bin") {
                it.add(sshNoneId, SshPrivateKey(sshNoneId, CredentialScope.GLOBAL, "git", KEY, null))
            }
            write("ssh-linked-v2.bin") {
                it.add(target, SecretText(target, CredentialScope.GLOBAL, "shared".toByteArray()))
                it.add(sshRefId, SshPrivateKey(sshRefId, CredentialScope.GLOBAL, "git", KEY, LinkedSecretRef(target)))
            }
            write("cert-linked-v2.bin") {
                it.add(target, SecretText(target, CredentialScope.GLOBAL, "shared".toByteArray()))
                it.add(certId, Certificate(certId, CredentialScope.GLOBAL, "keystore-bytes".toByteArray(), LinkedSecretRef(target), "leaf"))
            }
            write("secret-file-noname-v2.bin") {
                it.add(fileId, SecretFile(fileId, CredentialScope.GLOBAL, "payload".toByteArray(), null))
            }
            write("colon-v2.bin") {
                it.add(colonId, UsernameColonPassword(colonId, CredentialScope.GLOBAL, "svc", "pw".toByteArray()))
            }
        }

        private val KEY = "-----BEGIN PRIVATE KEY-----\nkey\n-----END PRIVATE KEY-----\n".toByteArray()
    }
}
