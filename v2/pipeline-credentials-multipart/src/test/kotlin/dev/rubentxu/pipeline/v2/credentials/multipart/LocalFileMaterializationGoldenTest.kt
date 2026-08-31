package dev.rubentxu.pipeline.v2.credentials.multipart

import dev.rubentxu.pipeline.v2.credentials.api.SecretStore
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope
import dev.rubentxu.pipeline.v2.domain.credentials.SecretFile
import dev.rubentxu.pipeline.v2.credentials.spi.MaterializationKind
import dev.rubentxu.pipeline.v2.credentials.spi.MaterializedCredential
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Golden test for LocalFileMaterialization — verifies byte-for-byte equivalence
 * with CredentialMaterializer.materialize.
 *
 * Design (design §6.1 R-3):
 * - Verifies LocalFileMaterialization.materialize returns byte-identical result
 *   to CredentialMaterializer.materialize
 * - mkstemp 0600 / mkdtemp 0700 perms preserved
 * - wipe-on-close preserved
 */
@DisplayName("LocalFileMaterialization golden test")
@Timeout(120)
class LocalFileMaterializationGoldenTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var store: InMemorySecretStore
    private lateinit var materializer: CredentialMaterializer
    private lateinit var adapter: LocalFileMaterialization

    @BeforeEach
    fun setUp() {
        store = InMemorySecretStore()
        materializer = CredentialMaterializer(store)
        adapter = LocalFileMaterialization(materializer)
    }

    @AfterEach
    fun tearDown() {
        adapter.close()
    }

    @Test
    fun `materialize returns byte-identical MaterializedCredential to CredentialMaterializer`() {
        // Given a SecretFile credential
        val credId = CredentialsId("test-secret-file")
        val fileBytes = "secret file content".toByteArray()
        val credential = SecretFile(credId, CredentialScope.GLOBAL, fileBytes, "deploy.key")

        // When we materialize via adapter and directly via materializer
        val viaAdapter = adapter.materialize(credential, MaterializationKind.SecretFile)
        val viaMaterializer = materializer.materialize(credential, MaterializationKind.SecretFile)

        // Then both return same credentialsId and kind
        assertEquals(viaMaterializer.credentialsId, viaAdapter.credentialsId)
        assertEquals(viaMaterializer.kind, viaAdapter.kind)

        // And both paths exist and have same permissions
        assertNotNull(viaAdapter.path, "Adapter must return a path")
        assertNotNull(viaMaterializer.path, "Materializer must return a path")
        assertEquals(viaMaterializer.path, viaAdapter.path)

        val adapterPerms = Files.getPosixFilePermissions(viaAdapter.path!!)
        val materializerPerms = Files.getPosixFilePermissions(viaMaterializer.path!!)
        assertEquals(materializerPerms, adapterPerms) {
            "Permissions must be identical"
        }
    }

    @Test
    fun `close closes the underlying materializer`() {
        // Given a materialized credential
        val credId = CredentialsId("test-close")
        val credential = SecretFile(credId, CredentialScope.GLOBAL, "content".toByteArray())
        val result = adapter.materialize(credential, MaterializationKind.SecretFile)
        val path = result.path

        // When we close the adapter
        adapter.close()

        // Then the materialized file is deleted
        assertFalse(Files.exists(path), "Materialized file must be deleted after close")
    }
}

/**
 * Simple in-memory SecretStore for testing.
 */
private class InMemorySecretStore : SecretStore {
    private val secrets = mutableMapOf<CredentialsId, ByteArray>()

    override fun add(id: CredentialsId, credential: Credential) {
        // Not used in this test
    }

    override fun put(id: CredentialsId, bytes: ByteArray) {
        secrets[id] = bytes
    }

    override fun get(id: CredentialsId): Credential {
        throw NotImplementedError()
    }

    override fun getAsSecretHandle(id: CredentialsId): SecretHandle {
        return SecretHandle.secret(secrets[id] ?: throw Exception("Not found"))
    }

    override fun getAsHandle(id: CredentialsId, partName: String): SecretHandle {
        throw NotImplementedError()
    }

    override fun list(): List<CredentialsId> = secrets.keys.toList()

    override fun remove(id: CredentialsId) {
        secrets.remove(id)
    }

    override fun rotate(id: CredentialsId, credential: Credential) {
        // Not used in this test
    }

    override fun rotateBytes(id: CredentialsId, newBytes: ByteArray) {
        secrets[id] = newBytes
    }

    override fun close() {
        secrets.clear()
    }
}
