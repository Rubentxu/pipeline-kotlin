package dev.rubentxu.pipeline.v2.sdk.runtime.durable.task

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskRuntime
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.OutputChunk
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionResult
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.domain.durable.TaskTerminalDecision
import dev.rubentxu.pipeline.v2.domain.durable.decideTaskTerminal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * The production [DurableTaskRuntime] — the adapter of that port, and the executor of GENERIC
 * DURABLE TASKS.
 *
 * ## What this owns
 *
 * The execution of a [TaskSpec]: an argv ([TaskSpec.ExecTask]) or a script written to a file
 * ([TaskSpec.ShellScriptTask]), with the concurrent output pumps, the deadline that fires while
 * the process lives and the tree kill that ends it. This is the ONE site that owns the port's
 * invariants.
 *
 * ## What this is NOT
 *
 * It is NOT the only place where the product creates a process, and it used to claim it was: this
 * KDoc said this was "the SINGLE authorised home for `ProcessBuilder` in the runtime". That claim
 * was false — the durable shell's launcher, the rendezvous's host and the watchdog's signals
 * create processes too — and it was false in the direction that matters, because it hid WHY there
 * are several mechanisms.
 *
 * The product reaches for a process in FOUR places, and each of the other three exists for a
 * reason this class does not cover:
 *
 *  - the durable shell's launcher, whose child leads its own session and carries its own protocol;
 *  - the rendezvous's host, which creates the FIFOs and the auxiliary agent's JVM;
 *  - the watchdog, which creates no child and only sends `kill` signals.
 *
 * ## Why the two terminations differ, on purpose
 *
 * This class kills the tree it can SEE: the child is a direct child of this JVM, so
 * `ProcessHandle.descendants()` is the tree and `destroyForcibly` on each is the kill. The durable
 * shell's child leads a session of its own, so its tree cannot be reached that way; there the unit
 * of termination is the SESSION and the tree is discovered by the operation cookie in the
 * environment. Two mechanisms, because two different process topologies — not two implementations
 * of one.
 *
 * ## Spec invariants implemented here
 * - **ExecTask preserves argv**: `ProcessBuilder(argv)` with no shell in
 * Between — ("argv con spaces/quotes sin shell") holds by
 *   construction.
 * - **ShellScriptTask writes the script to a file** (`script.sh` in the
 *   operation's control dir) and executes `[interpreter.binary, file]`.
 * - **stdout/stderr are drained concurrently** into bounded chunks
 *   (O(chunk) memory, never O(total output)) — two independent pumps
 * Feed the sink while the process lives. Pump threads bridge
 *   to the suspend sink via `runBlocking` per chunk.
 * - **timeout/cancel happen while the process lives** and kill the whole
 *   process tree (`ProcessHandle.descendants()` + `destroyForcibly`),
 * Never just the direct child
 * - **Durable, atomic result**: the terminal outcome is written to
 *   `result.txt` inside the control dir via temp-file + `ATOMIC_MOVE`,
 *   so a restart can reconcile (never a partial result).
 * - **Redaction before persistence**: env secrets are materialised solely
 *   into the process environment; nothing secret is written to disk.
 * ## Control-dir layout
 * ```
 * controlRoot/<opId>/
 *   script.sh       (ShellScriptTask only; written verbatim)
 *   result.txt      (atomic terminal record: EXIT <code> | TIMED_OUT | CANCELLED)
 * ```
 * ## Termination protocol (single sequence for every path)
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
                // Sink invocation is decoupled from the pump
                // threads. Pumps enqueue chunks (suspend, bounded: co-operate
                // with a slow sink at capacity instead of dropping — the
                // pipeline output is lossless) but the sink call itself runs
                // on a dedicated renderer coroutine. A slow consumer slows
                // the render, and past capacity the producers co-operate,
                // but pump threads are never blocked in runBlocking and the
                // durable outcome is unaffected. Memory stays O(capacity).
                val chunkChannel = kotlinx.coroutines.channels.Channel<OutputChunk>(
                    capacity = 4_096
                )
                val renderer = launch(Dispatchers.IO) {
                    for (chunk in chunkChannel) {
                        outputSink.append(chunk)
                    }
                }
                suspend fun sink(chunk: OutputChunk) = chunkChannel.send(chunk)
                val stdoutPump = async(Dispatchers.IO) {
                    drain(process, TaskStreamKind.STDOUT, ::sink)
                }
                val stderrPump = async(Dispatchers.IO) {
                    drain(process, TaskStreamKind.STDERR, ::sink)
                }

                val exit: Int = try {
                    val timeoutMs = request.timeoutMs
                    if (timeoutMs != null) {
                        val finished = runInterruptible {
                            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                        }
                        if (!finished) {
                            // Watchdog fired while the process lives
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
                chunkChannel.close() // EOF: renderer finishes after last chunk
                renderer.join()
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
        val record = when (val decision = decideTaskTerminal(result)) {
            // TimedOut: watchdog killed the process tree after TaskExecutionRequest.timeoutMs.
            is TaskTerminalDecision.TimedOut -> "TIMED_OUT"
            // Cancelled is structurally unreachable at this site: the catch block above writes
            // "CANCELLED" and rethrows, so by construction result.cancelled is false here. The
            // explicit error is the audit-trail witness for that invariant: any future refactor
            // that drops the catch's CANCELLED write will hit it.
            is TaskTerminalDecision.Cancelled -> error(
                "cancellation reached writeAtomicResult; the catch block above should have written CANCELLED"
            )
            is TaskTerminalDecision.Exited -> "EXIT ${decision.exitCode}"
        }
        writeAtomicResult(controlDir, record)
        return result
    }

    /**
     * Drains one stream into bounded chunks until EOF. The
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
