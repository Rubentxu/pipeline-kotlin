package dev.rubentxu.pipeline.v2.sdk.runtime.durable.task

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskRuntime
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.OutputChunk
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionResult
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.domain.durable.TaskStream
import dev.rubentxu.pipeline.v2.domain.durable.TaskTerminalDecision
import dev.rubentxu.pipeline.v2.domain.durable.decideTaskTerminal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.CONTROLLER_SECRET_VARIABLES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
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
 * Feed the sink while the process lives, through a window of
 *   [CHANNEL_CAPACITY] chunks that is the only thing the pump retains.
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
        // The child's environment starts as the controller's own. The controller's credential
        // material is removed before the caller's variables are applied: a credential the
        // pipeline DECLARES still reaches the child (it is applied after this removal, and
        // therefore wins), but the passphrase the controller resolved for its own store does
        // not cross by inheritance.
        CONTROLLER_SECRET_VARIABLES.forEach { builder.environment().remove(it) }
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
                // but the pump's thread is never held by the hand-off and the
                // durable outcome is unaffected. Memory stays O(capacity).
                val chunkChannel = kotlinx.coroutines.channels.Channel<OutputChunk>(
                    capacity = CHANNEL_CAPACITY
                )
                val renderer = launch(Dispatchers.IO) {
                    for (chunk in chunkChannel) {
                        outputSink.append(chunk)
                    }
                }
                val stdoutPump = async(Dispatchers.IO) {
                    TaskOutputPump(TaskStream.STDOUT, ExecutionOutputSink { chunkChannel.send(it) })
                        .drain(process.inputStream)
                }
                val stderrPump = async(Dispatchers.IO) {
                    TaskOutputPump(TaskStream.STDERR, ExecutionOutputSink { chunkChannel.send(it) })
                        .drain(process.errorStream)
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

    private companion object {
        /**
         * How many chunks the window between the child's pipe and the sink holds.
         *
         * The window is the ONLY thing that bounds what one execution retains, so its size IS
         * the memory cost of the pump: what is in flight is this many chunks plus whatever the
         * operating system's pipe holds for the child (64 KiB on this machine). Measured here
         * (JDK 21) with a child writing 64 KiB blocks and a sink that takes 2 ms per chunk,
         * against a 32 MiB burst read by a fast sink. The in-flight figures are quantised by
         * the child's own block, and the throughput at the two ends is the median of four runs
         * each; the spread inside a group is about 7%.
         *
         * ```text
         * capacity   window    in flight   throughput
         *       1     8 KiB      80 KiB     118.1 MiB/s
         *       2    16 KiB      80 KiB     129.0 MiB/s
         *       4    32 KiB      64 KiB     133.9 MiB/s
         *       8    64 KiB      88 KiB     156.9 MiB/s
         *      16   128 KiB     152 KiB     180.3 MiB/s
         *      32   256 KiB     288 KiB     177.8 MiB/s
         *      64   512 KiB     560 KiB     190.5 MiB/s
         *     256     2 MiB     2.04 MiB    188.2 MiB/s
         *    1024     8 MiB     8.04 MiB    190.5 MiB/s
         *    4096    32 MiB    32.06 MiB    185.5 MiB/s
         * ```
         *
         * **Why 16.** Below it the window is too small to keep the pump and the renderer from
         * taking turns: the throughput falls because a chunk waits for the renderer on every
         * step. From 16 upwards the throughput is the same within the noise of the measurement
         * — 16 and 4096 differ by 2% — while the memory grows with the capacity for nothing.
         * Sixteen chunks of 8 KiB are 128 KiB, the same order as the window the durable shell's
         * own pump uses ([dev.rubentxu.pipeline.v2.sdk.runtime.durable.TRANSCRIPT_LIVE_WINDOW_BYTES]),
         * and it makes the memory of a running task a BOUNDED WINDOW instead of a reservoir.
         *
         * **What the number buys and what it costs.** It buys about 200 times less memory
         * retained per running task — 32 MiB down to 152 KiB in flight, the pipe included. It
         * costs the child the slack of a reservoir: with a sink slower than the child, the child
         * now feels the backpressure after 128 KiB instead of after 32 MiB. That is the trade
         * the memory invariant asks for: the child's output is throttled to what the sink can
         * take, and the runtime holds a window, not the whole output.
         */
        const val CHANNEL_CAPACITY = 16
    }
}
