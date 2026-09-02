package dev.rubentxu.pipeline.v2.artefacts.local

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.domain.durable.TaskStream
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.ProcessDurableTaskRuntime
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.Collections
import java.util.UUID

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
        val root = pendingFiles.first().second
        try {
            val listContent = pendingFiles.joinToString("\n") { (file, fileRoot) ->
                fileRoot.relativize(file).toString().replace("\\", "/")
            }
            Files.writeString(listFile, listContent)

            // M3: tar runs through the runtime. The output stream is fed
            // chunk-by-chunk to both `output` and `digestSink` (O(chunk)
            // memory — no readAllBytes). exit != 0 fails closed with the
            // accumulated stderr.
            val runtime = ProcessDurableTaskRuntime(
                Files.createTempDirectory("tar-tasks"),
                object : Clock {
                    override fun now(): Instant = Instant.now()
                },
            )
            val stderrChunks: MutableList<dev.rubentxu.pipeline.v2.domain.durable.OutputChunk> =
                Collections.synchronizedList(mutableListOf())
            val argv = listOf(
                "tar",
                "-C", root.toString(),
                "--no-recursion",
                "--files-from", listFile.toString(),
                "-cf", "-",
            )
            val request = TaskExecutionRequest(
                task = TaskSpec.ExecTask(argv = argv),
                runId = RunId("tar-${UUID.randomUUID()}"),
                opId = "tar-${UUID.randomUUID()}",
                timeoutMs = null,
                env = emptyMap(),
            )
            val sink = ExecutionOutputSink { chunk ->
                when (chunk.stream) {
                    TaskStream.STDOUT -> {
                        output.write(chunk.data)
                        digestSink?.update(chunk.data)
                    }
                    TaskStream.STDERR -> stderrChunks.add(chunk)
                }
            }
            val result = runBlocking { runtime.execute(request, sink) }
            if (!result.succeeded) {
                val err = stderrChunks.joinToString("") { it.data.toString(Charsets.UTF_8) }
                throw RuntimeException("tar cf failed (exit ${result.exitCode}${if (result.timedOut) " timed-out" else ""}): $err")
            }
        } finally {
            Files.deleteIfExists(listFile)
        }
    }

    private val pendingFiles = mutableListOf<Pair<Path, Path>>()
}
