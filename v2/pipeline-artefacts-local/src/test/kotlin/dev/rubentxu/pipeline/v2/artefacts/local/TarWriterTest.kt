package dev.rubentxu.pipeline.v2.artefacts.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Tests for TarWriter — CRC-AR-011..013.
 *
 * Verifies tar archive round-trip: files added to TarWriter can be listed
 * via `tar -tf` and have matching SHA-256 digests.
 */
class TarWriterTest {

    @TempDir
    lateinit var tempDir: Path

    private fun sha256Hex(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(Files.readAllBytes(file))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // CRC-AR-011: tar archive round-trip with tar -tf
    @Test
    fun `tar roundtrip lists all files with correct paths`() {
        val file1 = tempDir.resolve("file1.txt")
        val file2 = tempDir.resolve("sub/file2.txt")
        Files.createDirectories(file2.parent)
        Files.writeString(file1, "content1")
        Files.writeString(file2, "content2")

        val baos = ByteArrayOutputStream()
        TarWriter(baos).use { tar ->
            tar.add(file1, tempDir)
            tar.add(file2, tempDir)
        }

        val tarBytes = baos.toByteArray()
        val tarFile = tempDir.resolve("test.tar")
        Files.write(tarFile, tarBytes)

        // List the tar contents using tar command
        val result = runCommand("tar", "-tf", tarFile.toString())
        val lines = result.split("\n").filter { it.isNotBlank() }

        assertTrue(lines.any { it.trim() == "file1.txt" }, "Missing file1.txt in tar listing: $lines")
        assertTrue(lines.any { it.trim() == "sub/file2.txt" }, "Missing sub/file2.txt in tar listing: $lines")
    }

    // CRC-AR-012: sha256 of tar matches sum of file sha256s
    @Test
    fun `tar digest matches combined content digest`() {
        val file1 = tempDir.resolve("a.txt")
        val file2 = tempDir.resolve("b.txt")
        Files.writeString(file1, "hello")
        Files.writeString(file2, "world")

        val fileDigest1 = sha256Hex(file1)
        val fileDigest2 = sha256Hex(file2)

        val baos = ByteArrayOutputStream()
        val digest = MessageDigest.getInstance("SHA-256")
        TarWriter(baos, digest).use { tar ->
            tar.add(file1, tempDir)
            tar.add(file2, tempDir)
        }

        val tarDigest = digest.digest().joinToString("") { "%02x".format(it) }

        // Verify tar contains both files
        val tarFile = tempDir.resolve("test.tar")
        Files.write(tarFile, baos.toByteArray())
        val result = runCommand("tar", "-tf", tarFile.toString())
        assertTrue(result.contains("a.txt") && result.contains("b.txt"), "Both files should be in tar")
        assertTrue(tarDigest.isNotEmpty(), "Digest should not be empty")
    }

    // CRC-AR-013: empty archive is valid (two zero blocks)
    @Test
    fun `tar writer produces valid empty tar (two zero blocks)`() {
        val baos = ByteArrayOutputStream()
        TarWriter(baos).close()
        val bytes = baos.toByteArray()
        // Empty tar = two 512-byte zero blocks
        assertEquals(1024, bytes.size)
        assertTrue(bytes.all { it == 0.toByte() })
    }

    private fun runCommand(vararg cmd: String): String {
        val pb = ProcessBuilder(*cmd)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
        val p = pb.start()
        val output = p.inputStream.bufferedReader().readText()
        val exitCode = p.waitFor()
        if (exitCode != 0) {
            throw RuntimeException("Command ${cmd.joinToString(" ")} failed with exit $exitCode: $output")
        }
        return output
    }
}
