package dev.rubentxu.pipeline.v2.credentials.multipart

import dev.rubentxu.pipeline.v2.credentials.api.SecretStoreLinkedSecretResolver
import dev.rubentxu.pipeline.v2.credentials.local.CredentialsStorePath
import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore
import dev.rubentxu.pipeline.v2.credentials.local.PassphraseResolver
import dev.rubentxu.pipeline.v2.credentials.spi.MaterializationKind
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialFilePermissions
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope
import dev.rubentxu.pipeline.v2.domain.credentials.SecretFile
import dev.rubentxu.pipeline.v2.domain.credentials.Zip
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/**
 * The materializer and the store, wired the way the product wires them.
 *
 * ## The guarantee
 * The bytes a credential carries are the bytes the materializer writes, and a
 * credential whose name points outside its own directory is refused rather
 * than written.
 *
 * ## Why this test is an INTEGRATION test and not a unit test
 * Two seams decide this guarantee, and neither of them is visible from one
 * side. The store decides what a credential IS: it serializes the parts,
 * seals them and gives back a credential whose bytes are the ones that were
 * written. The materializer decides where those bytes LAND: it creates the
 * directories, writes the files and sets their permissions.
 *
 * A unit test on either side can only see its own half. A materializer fed
 * a credential built in the test says nothing about a materializer fed a
 * credential that came out of a store, because the round trip through the
 * store's own format is exactly where a credential can lose a part, lose a
 * name or lose its bytes.
 *
 * This test runs both halves for real: the [LocalSecretStore] of the local
 * credentials module, with its own cryptography, and the
 * [CredentialMaterializer] of this module.
 *
 * ## What is faked
 * The world outside the process and nothing else. The store file and the
 * materialization directory live in a directory this test owns, and the
 * materializer's own temporary directory is pointed at it, so every byte the
 * product writes lands where the test can look at it. The passphrase is a
 * constant string this test supplies through the resolver's environment map,
 * so no terminal and no operator is ever asked for one. No clock, no
 * subprocess and no network are involved: neither half has any.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class CredentialMaterializationStoreCompositionTest {

    /** The directory the store and the materialization live in. */
    @TempDir
    lateinit var root: Path

    private lateinit var scratch: Path
    private lateinit var previousTemporaryDirectory: String

    /**
     * Points the materializer's temporary directory at a directory this test
     * owns, so a credential that leaves its directory leaves it somewhere the
     * test can look.
     */
    @BeforeEach
    fun pointTheMaterializerAtTheTestDirectory() {
        previousTemporaryDirectory = requireNotNull(System.getProperty("java.io.tmpdir")) {
            "the runtime names no temporary directory"
        }
        scratch = Files.createDirectories(root.resolve("scratch"))
        System.setProperty("java.io.tmpdir", scratch.toString())
    }

    /** Gives the temporary directory back to the runtime. */
    @AfterEach
    fun restoreTheTemporaryDirectory() {
        System.setProperty("java.io.tmpdir", previousTemporaryDirectory)
    }

    /**
     * The store the materializer reads from, over the file the path
     * authority names.
     */
    private fun openStore(): LocalSecretStore =
        LocalSecretStore(
            file = CredentialsStorePath.resolve(envValue = null, userHome = root),
            passphrase = PassphraseResolver.resolve(
                env = mapOf(PASSPHRASE_ENV to PASSPHRASE),
                ttyReader = { null },
            ),
        )

    @Test
    fun `the archive the materializer writes is the archive the store holds`() {
        val id = CredentialsId("archive")
        val entries = mapOf(
            "config.json" to "{\"mode\":\"strict\"}".toByteArray(),
            "certs/server.pem" to "-----BEGIN KEY-----".toByteArray(),
        )
        val store = openStore()
        store.add(id, Zip(id, CredentialScope.GLOBAL, entries))
        val read = assertInstanceOf(Zip::class.java, store.get(id))
        val materializer = CredentialMaterializer(SecretStoreLinkedSecretResolver(store))
        try {
            val materialized = materializer.materialize(read, MaterializationKind.Zip)
            val directory = requireNotNull(materialized.path) {
                "the materializer named no directory for the archive"
            }

            assertEquals(
                entries.keys,
                filesUnder(directory, directory),
                "the archive was unpacked into names it does not hold",
            )
            for ((name, bytes) in entries) {
                assertArrayEquals(
                    bytes,
                    Files.readAllBytes(directory.resolve(name)),
                    "the entry $name holds bytes the store does not hold",
                )
            }
        } finally {
            materializer.close()
            store.close()
        }
    }

    @Test
    fun `a zip entry that leaves the materialization directory is refused`() {
        val id = CredentialsId("hostile-archive")
        val store = openStore()
        store.add(
            id,
            Zip(id, CredentialScope.GLOBAL, mapOf("../escape.txt" to "owned".toByteArray())),
        )
        val read = assertInstanceOf(Zip::class.java, store.get(id))
        val materializer = CredentialMaterializer(SecretStoreLinkedSecretResolver(store))
        try {
            val refusal = assertThrows(
                ZipEntryEscapesMaterializationException::class.java,
                { materializer.materialize(read, MaterializationKind.Zip) },
                "an entry that leaves the directory was materialized",
            )

            assertEquals("../escape.txt", refusal.entryName)
            assertEquals(id, refusal.credentialsId)
            assertTrue(
                Files.notExists(refusal.resolvedPath),
                "the entry was written where it was refused",
            )
        } finally {
            materializer.close()
            store.close()
        }
    }

    @Test
    fun `the materialized secret file holds the bytes the store holds`() {
        val id = CredentialsId("secret-file")
        val bytes = "the bytes of a secret file".toByteArray()
        val store = openStore()
        store.add(id, SecretFile(id, CredentialScope.GLOBAL, bytes, "notes.txt"))
        val read = assertInstanceOf(SecretFile::class.java, store.get(id))
        val materializer = CredentialMaterializer(SecretStoreLinkedSecretResolver(store))
        try {
            val materialized = materializer.materialize(read, MaterializationKind.SecretFile)
            val path = requireNotNull(materialized.path) {
                "the materializer named no file for the secret"
            }

            assertArrayEquals(
                bytes,
                Files.readAllBytes(path),
                "the materialized file holds bytes the store does not hold",
            )
            assertEquals(
                CredentialFilePermissions.OWNER_READ_WRITE,
                Files.getPosixFilePermissions(path),
                "the materialized file is readable by someone other than its owner",
            )
        } finally {
            materializer.close()
            store.close()
        }
    }

    @Test
    fun `a cleanup that cannot remove the material says so and says nothing of what the material was`() {
        val id = CredentialsId("undeletable")
        val secret = "the bytes of a secret nobody may read again"
        val store = openStore()
        store.add(id, SecretFile(id, CredentialScope.GLOBAL, secret.toByteArray(), "notes.txt"))
        val read = assertInstanceOf(SecretFile::class.java, store.get(id))
        val materializer = CredentialMaterializer(SecretStoreLinkedSecretResolver(store))
        try {
            val materialized = materializer.materialize(read, MaterializationKind.SecretFile)
            requireNotNull(materialized.path) { "the materializer named no file for the secret" }
            // The directory the material lives in stops accepting changes. The file can still be
            // written --its own permissions allow it-- but it can no longer be unlinked, which is
            // what a cleanup that cannot finish looks like.
            Files.setPosixFilePermissions(scratch, PosixFilePermissions.fromString("r-x------"))

            val failure = assertThrows(
                CredentialCleanupException::class.java,
                { materializer.close() },
                "a material that could not be removed was reported as removed",
            )

            assertTrue(
                failure.survivors.isNotEmpty(),
                "the failure names the path whose material could not be removed",
            )
            assertTrue(
                failure.message.orEmpty().contains("may still be readable"),
                "the report does not promise that the material is gone: ${failure.message}",
            )
            assertFalse(
                failure.message.orEmpty().contains(secret),
                "the report does not carry the material it could not remove: ${failure.message}",
            )
        } finally {
            Files.setPosixFilePermissions(scratch, CredentialFilePermissions.OWNER_READ_WRITE_EXECUTE)
            store.close()
        }
    }

    /**
     * The names of the regular files under [directory], relative to [root].
     */
    private fun filesUnder(root: Path, directory: Path): Set<String> {
        val found = mutableSetOf<String>()
        Files.newDirectoryStream(directory).use { entries ->
            for (entry in entries) {
                if (Files.isDirectory(entry)) {
                    found += filesUnder(root, entry)
                } else {
                    found += root.relativize(entry).toString()
                }
            }
        }
        return found
    }

    private companion object {
        /** The environment variable the passphrase resolver reads. */
        const val PASSPHRASE_ENV = "PIPELINE_STORE_PASSPHRASE"

        /** The passphrase this test supplies to the resolver. */
        const val PASSPHRASE = "a passphrase this test owns"
    }
}
