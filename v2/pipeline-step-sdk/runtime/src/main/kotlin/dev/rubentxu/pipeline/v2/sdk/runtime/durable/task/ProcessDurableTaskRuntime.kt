package dev.rubentxu.pipeline.v2.sdk.runtime.durable.task

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskRuntime
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.OutputChunk
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionResult
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * The production [DurableTaskRuntime] — the SINGLE authorised home for
 * `ProcessBuilder` in the V2 runtime (M3 gate; LF-0302 implementation +
 * LF-0303 concurrent output pumps + LF-0304 timeout/cancel tree).
 *
 * ## Spec invariants implemented here
 *
 * - **ExecTask preserves argv**: `ProcessBuilder(argv)` with no shell in
 *   between — M3-002 ("argv con spaces/quotes sin shell") holds by
 *   construction.
 * - **ShellScriptTask writes the script to a file** (`script.sh` in the
 *   operation's control dir) and executes `[interpreter.binary, file]`.
 * - **stdout/stderr are drained concurrently** into bounded chunks
 *   (O(chunk) memory, never O(total output)) — two independent pumps
 *   feed the sink while the process lives (LF-0303). Pump threads bridge
 *   to the suspend sink via `runBlocking` per chunk.
 * - **timeout/cancel happen while the process lives** and kill the whole
 *   process tree (`ProcessHandle.descendants()` + `destroyForcibly`),
 *   never just the direct child (LF-0304).
 * - **Durable, atomic result**: the terminal outcome is written to
 *   `result.txt` inside the control dir via temp-file + `ATOMIC_MOVE`,
 *   so a restart can reconcile (never a partial result).
 * - **Redaction before persistence**: env secrets are materialised solely
 *   into the process environment; nothing secret is written to disk.
 *
 * ## Control-dir layout
 *
 * ```
 * controlRoot/<opId>/
 *   script.sh       (ShellScriptTask only; written verbatim)
 *   result.txt      (atomic terminal record: EXIT <code> | TIMED_OUT | CANCELLED)
 * ```
 *
 * ## Termination protocol (single sequence for every path)
 *
 * The process tree is destroyed in the `finally` block while the pump
 * coroutines are still children of the scope. This ordering is what makes
 * cancellation safe: pumps blocked on stream reads are released by the
 * process death (EOF) instead of being awaited while stuck. On timeout
 * the tree is destroyed before the re-`waitFor`; on cancellation the
 * `finally` runs before the scope joins the pumps; on normal exit the
 * `finally` is a no-op.
 */
class ProcessDurableTaskRuntime(
    private val controlRoot: Path,
    private val clock: Clock,
) : DurableTaskRuntime {

    override suspend fun execute(
        request: TaskExecutionRequest,
        outputSink: ExecutionOutputSink,
    ): TaskExecutionResult {
        val controlDir = controlRoot.resolve(request.opId)
        Files.createDirectories(controlDir)
        val startedAt = clock.now().toEpochMilli()

        val argv: List<String> = when (val task = request.task) {
            is TaskSpec.ExecTask -> task.argv
            is TaskSpec.ShellScriptTask -> {
                val scriptFile = controlDir.resolve("script.sh")
                Files.writeString(scriptFile, task.script)
                listOf(task.interpreter.binary, scriptFile.toAbsolutePath().toString())
            }
        }

        val builder = ProcessBuilder(argv)
        val workDir = request.workspaceRoot?.let { Path.of(it) } ?: controlDir.toAbsolutePath()
        builder.directory(workDir.toFile())
        builder.redirectErrorStream(false)
        request.env.forEach { (key, handle) -> builder.environment()[key] = handle.materialize() }

        val process = builder.start()
        var timedOut = false
        var cancelled = false

        val exitCode: Int = try {
            coroutineScope {
                val stdoutPump = async(Dispatchers.IO) {
                    drain(process, TaskStreamKind.STDOUT, outputSink)
                }
                val stderrPump = async(Dispatchers.IO) {
                    drain(process, TaskStreamKind.STDERR, outputSink)
                }

                val exit: Int = try {
                    val timeoutMs = request.timeoutMs
                    if (timeoutMs != null) {
                        val finished = runInterruptible {
                            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                        }
                        if (!finished) {
                            // Watchdog fired while the process lives (LF-0304).
                            timedOut = true
                            destroyTree(process)
                        }
                    }
                    runInterruptible { process.waitFor() }
                    process.exitValue()
                } finally {
                    // Single destruction point: covers timeout, cancellation,
                    // and any pump failure. Normal exits are a no-op here.
                    if (process.isAlive) destroyTree(process)
                }

                // Both pumps drain to EOF after the process is gone.
                stdoutPump.await()
                stderrPump.await()
                exit
            }
        } catch (ce: CancellationException) {
            cancelled = true
            writeAtomicResult(controlDir, "CANCELLED")
            throw ce
        }

        val endedAt = clock.now().toEpochMilli()
        val result = TaskExecutionResult(
            exitCode = exitCode,
            timedOut = timedOut,
            cancelled = cancelled,
            startedAtEpochMs = startedAt,
            endedAtEpochMs = endedAt,
        )
        val record = if (result.timedOut) "TIMED_OUT" else "EXIT ${result.exitCode}"
        writeAtomicResult(controlDir, record)
        return result
    }

    /**
     * Drains one stream into bounded chunks until EOF (LF-0303). The
     * blocking read runs under `runInterruptible` on the IO dispatcher;
     * each chunk is handed to the suspend sink through a `runBlocking`
     * bridge on the pump thread — memory stays O(chunk).
     */
    private suspend fun drain(
        process: Process,
        stream: TaskStreamKind,
        sink: ExecutionOutputSink,
    ) {
        val input = when (stream) {
            TaskStreamKind.STDOUT -> process.inputStream
            TaskStreamKind.STDERR -> process.errorStream
        }
        val buffer = ByteArray(CHUNK_SIZE_BYTES)
        try {
            runInterruptible {
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) {
                        val chunk = when (stream) {
                            TaskStreamKind.STDOUT -> OutputChunk.stdout(buffer.copyOf(read))
                            TaskStreamKind.STDERR -> OutputChunk.stderr(buffer.copyOf(read))
                        }
                        runBlocking { sink.append(chunk) }
                    }
                }
            }
        } catch (_: CancellationException) {
            // Cancellation destroys the process (see execute): the read ends
            // at EOF. Any residual chunk after cancellation is dropped —
            // the run is terminal.
        }
    }

    /** Kills the entire process tree: descendants first, then the child. */
    private fun destroyTree(process: Process) {
        try {
            val handle = process.toHandle()
            handle.descendants().forEach { it.destroyForcibly() }
        } catch (_: java.io.IOException) {
            // Unsupported platform: fall through to direct-child kill.
        }
        process.destroyForcibly()
    }

    /** Atomic durable record (spec: result durable/atómico). Temp + ATOMIC_MOVE. */
    private fun writeAtomicResult(controlDir: Path, record: String) {
        val tmp = controlDir.resolve("result.txt.tmp")
        val target = controlDir.resolve("result.txt")
        Files.writeString(tmp, record + "\n")
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private enum class TaskStreamKind { STDOUT, STDERR }

    private companion object {
        const val CHUNK_SIZE_BYTES = 8 * 1024
    }
}
