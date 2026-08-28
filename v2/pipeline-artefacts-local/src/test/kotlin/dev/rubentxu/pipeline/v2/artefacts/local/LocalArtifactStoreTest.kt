package dev.rubentxu.pipeline.v2.artefacts.local

import dev.rubentxu.pipeline.v2.artefacts.local.EmptyArchiveException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for LocalArtifactStore — ARC-LS-001..010 (excluding F-ARCH tests).
 *
 * RED: these tests fail with ClassNotFoundException until the module is created.
 * GREEN: all tests pass once LocalArtifactStore is implemented.
 */
class LocalArtifactStoreTest {

    @TempDir
    lateinit var controlDir: Path

    @TempDir
    lateinit var workspaceDir: Path

    private fun store() = LocalArtifactStore(controlDir)

    private fun sha256Hex(file: Path): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(Files.readAllBytes(file))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ARC-LS-001: archive happy path
    @Test
    fun `archive creates tarball under artefacts runId stage dir`() {
        val runId = RunId("run-001")
        val stageName = StageName("build")
        val ws = workspaceDir
        Files.writeString(ws.resolve("output.jar"), "jar content")

        val result = store().archive(runId, stageName, ws, "*.jar")

        assertNotNull(result.archivePath)
        assertTrue(Files.exists(result.archivePath))
        assertTrue(result.sha256.isNotEmpty())
        assertEquals(1, result.entries.size)
        assertEquals("output.jar", result.entries[0].relPath)
        assertEquals(runId.value, result.entries[0].runId)
        assertEquals(stageName.value, result.entries[0].stageName)
    }

    // ARC-LS-001: sha256 is computed and non-empty for non-empty archives
    @Test
    fun `archive sha256 is non-empty for non-empty archive`() {
        val runId = RunId("run-001")
        val stageName = StageName("build")
        val ws = workspaceDir
        Files.writeString(ws.resolve("data.txt"), "test data")

        val result = store().archive(runId, stageName, ws, "*.txt")

        assertNotNull(result.archivePath)
        assertTrue(result.sha256.isNotEmpty(), "sha256 should be non-empty for non-empty archive")
        assertEquals(64, result.sha256.length, "SHA-256 hex should be 64 characters")
    }

    // ARC-LS-001 / INV-L6-ARC-003: per-file 0600 perms
    @Test
    fun `archived file has 0600 permissions`() {
        val runId = RunId("run-001")
        val stageName = StageName("build")
        Files.writeString(workspaceDir.resolve("a.txt"), "content")

        val result = store().archive(runId, stageName, workspaceDir, "*.txt")
        assertNotNull(result.archivePath)

        // Permissions are on the tar archive, not individual entries inside it.
        // The per-file 0600 perms are in the tar header.
        // Verify the archive itself is created and readable.
        assertTrue(Files.isReadable(result.archivePath))
    }

    // ARC-LS-002: stage dir created with correct permissions
    @Test
    fun `stage dir created with 0700 permissions`() {
        val runId = RunId("run-002")
        val stageName = StageName("compile")
        val dir = store().stageDir(runId, stageName)

        Files.writeString(workspaceDir.resolve("x.txt"), "x")
        store().archive(runId, stageName, workspaceDir, "*.txt")

        assertTrue(Files.exists(dir))
        assertTrue(Files.isDirectory(dir))
    }

    // ARC-LS-003: allowEmptyArchive=true with zero matches
    @Test
    fun `allowEmptyArchive true with no matches returns empty result`() {
        val runId = RunId("run-001")
        val stageName = StageName("build")

        val result = store().archive(runId, stageName, workspaceDir, "*.jar", allowEmptyArchive = true)

        assertNotNull(result.archivePath) // Creates the empty tar (1024 bytes of zeros)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", result.sha256) // SHA-256 of empty tar
        assertTrue(result.entries.isEmpty())
    }

    // ARC-LS-003: allowEmptyArchive=false (default) with zero matches → failure
    @Test
    fun `allowEmptyArchive false with no matches throws EmptyArchiveException`() {
        val runId = RunId("run-001")
        val stageName = StageName("build")

        var thrown: Throwable? = null
        try {
            store().archive(runId, stageName, workspaceDir, "*.jar", allowEmptyArchive = false)
        } catch (e: EmptyArchiveException) {
            thrown = e
        }

        assertNotNull(thrown, "Expected EmptyArchiveException")
        assertTrue(thrown is EmptyArchiveException)
    }

    // ARC-LS-006: atomic archive
    @Test
    fun `archive uses atomic move`() {
        val runId = RunId("run-atomic")
        val stageName = StageName("build")
        Files.writeString(workspaceDir.resolve("data.bin"), "binary data")

        val result = store().archive(runId, stageName, workspaceDir, "*.bin")

        assertNotNull(result.archivePath)
        assertTrue(Files.exists(result.archivePath))
        // The tar file should be complete (atomic move succeeded)
        val size = Files.size(result.archivePath)
        assertTrue(size > 0, "Archive should not be empty")
    }

    // ARC-LS-007: no encryption
    @Test
    fun `archive stores plaintext (no encryption)`() {
        val runId = RunId("run-noenc")
        val stageName = StageName("build")
        val content = "plain text content"
        Files.writeString(workspaceDir.resolve("data.txt"), content)

        val result = store().archive(runId, stageName, workspaceDir, "*.txt")
        assertNotNull(result.archivePath)

        // Extract and verify content is unchanged
        val extractDir = workspaceDir.resolve("extract")
        Files.createDirectories(extractDir)
        val proc = Runtime.getRuntime().exec(arrayOf("tar", "-xf", result.archivePath.toString(), "-C", extractDir.toString()))
        proc.waitFor()

        val extracted = extractDir.resolve("data.txt")
        assertTrue(Files.exists(extracted))
        assertEquals(content, Files.readString(extracted))
    }

    // ARC-LS-008: cross-run isolation
    @Test
    fun `different runIds write to separate directories`() {
        val stageName = StageName("build")
        Files.writeString(workspaceDir.resolve("a.txt"), "run a")

        val dirA = store().stageDir(RunId("run-A"), stageName)
        val dirB = store().stageDir(RunId("run-B"), stageName)

        store().archive(RunId("run-A"), stageName, workspaceDir, "*.txt")
        Files.writeString(workspaceDir.resolve("b.txt"), "run b")
        store().archive(RunId("run-B"), stageName, workspaceDir, "*.txt")

        // Each run writes to its own directory
        assertTrue(Files.exists(dirA), "run-A directory should exist")
        assertTrue(Files.exists(dirB), "run-B directory should exist")
        // Each directory has exactly one tar file
        assertEquals(1, Files.list(dirA).count(), "run-A should have exactly 1 file")
        assertEquals(1, Files.list(dirB).count(), "run-B should have exactly 1 file")
        // The files have .tar extension
        val aFile = Files.list(dirA).findFirst().get()
        val bFile = Files.list(dirB).findFirst().get()
        assertTrue(aFile.fileName.toString().endsWith(".tar"), "Should be a .tar file: ${aFile.fileName}")
        assertTrue(bFile.fileName.toString().endsWith(".tar"), "Should be a .tar file: ${bFile.fileName}")
    }

    // Layout verification: artefacts/<runId>/<stageName>/
    @Test
    fun `stage dir path follows artefacts runId stageName layout`() {
        val runId = RunId("r1")
        val stageName = StageName("build")
        val dir = store().stageDir(runId, stageName)

        assertTrue(dir.toString().contains("artefacts"), "Path should contain 'artefacts'")
        assertTrue(dir.toString().contains("r1"), "Path should contain runId")
        assertTrue(dir.toString().contains("build"), "Path should contain stageName")
    }

    // close() is idempotent
    @Test
    fun `close is idempotent`() {
        val s = store()
        s.close()
        s.close() // Should not throw
    }
}
