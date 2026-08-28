package dev.rubentxu.pipeline.v2.artefacts.local

import java.io.Closeable
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Writes files to a tar archive, preserving relative paths.
 *
 * Uses the system `tar` command for reliable POSIX-compliant tar creation.
 * No compression (plain tar format per D6).
 *
 * Not thread-safe — single-threaded use only.
 *
 * @param output Target output stream (caller owns the stream)
 * @param digestSink Optional SHA-256 accumulator for the complete tar bytes
 */
class TarWriter(
    private val output: OutputStream,
    private val digestSink: MessageDigest? = null,
) : Closeable {

    /**
     * Adds a file to the tar archive.
     *
     * @param file File to add
     * @param root Root directory (used to compute relative path for the tar entry)
     * @throws java.io.IOException on I/O errors
     */
    fun add(file: Path, root: Path) {
        // Defer to archive-level tar creation; individual adds are registered
        // and the actual tar is created at close() time via system tar
        pendingFiles.add(file to root)
    }

    /**
     * Closes the tar archive, creating it via system tar.
     * After this call, the archive bytes are written to [output].
     */
    override fun close() {
        if (pendingFiles.isEmpty()) {
            // Write empty tar (two 512-byte zero blocks)
            output.write(ByteArray(1024))
            return
        }

        // Use tar to create the archive from a list file
        val listFile = Files.createTempFile("tar-list", ".txt")
        try {
            val listContent = pendingFiles.joinToString("\n") { (file, root) ->
                root.relativize(file).toString().replace("\\", "/")
            }
            Files.writeString(listFile, listContent)

            val pb = ProcessBuilder(
                "tar",
                "-C", pendingFiles.first().second.toString(),
                "--no-recursion",
                "--files-from", listFile.toString(),
                "-cf", "-",
            )
            pb.redirectError(ProcessBuilder.Redirect.PIPE)
            val proc = pb.start()

            val tarBytes = proc.inputStream.readAllBytes()
            val exitCode = proc.waitFor()
            if (exitCode != 0) {
                val err = proc.errorStream.bufferedReader().readText()
                throw RuntimeException("tar cf failed (exit $exitCode): $err")
            }

            output.write(tarBytes)
            digestSink?.update(tarBytes)
        } finally {
            Files.deleteIfExists(listFile)
        }
    }

    private val pendingFiles = mutableListOf<Pair<Path, Path>>()
}
