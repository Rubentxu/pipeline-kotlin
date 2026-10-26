package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskOutput
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.withLock

/**
 * State machine for a durable shell execution.
 * Valid transitions:
 * ```
 * LAUNCHING → RUNNING → COMPLETE | LOST | LAUNCH_FAILED | TIMED_OUT
 * ```
 * ## State Descriptions
 * - **LAUNCHING**: ProcessHandle acquired but not yet confirmed detached.
 *                  Wrapper may still fork; we're in a race window.
 * - **RUNNING**: Wrapper confirmed detached, cookie file exists.
 *                Process is alive and logging to jenkins-log.txt.
 * - **COMPLETE**: result.txt written and read successfully.
 *                 Exit code captured; final state.
 * - **LOST**: result.txt missing AND heartbeat is stale (> checkInterval + minimumDelta).
 * Worker died; outcome unknown. Fail-closed
 * - **LAUNCH_FAILED**: Failed to acquire ProcessHandle or fork wrapper.
 *                      Immediate failure; no recovery possible.
 * - **TIMED_OUT**: Watchdog killed the process via timeout.flag + SIGKILL.
 * Per: timeout.flag written BEFORE kill
*/
@Deprecated(
    message = "Legacy durable-shell state projection. Consume DurableTaskTerminal at the typed shell seam; this projection is on its way out.",
)
enum class DurableShellState {
    /** ProcessHandle acquired but not yet confirmed detached. */
    LAUNCHING,

    /** Wrapper confirmed detached; cookie file exists. */
    RUNNING,

    /** result.txt written and read successfully. */
    COMPLETE,

    /**
     * result.txt missing AND heartbeat is stale.
     * Fail-closed: LOST never implies success
*/
    LOST,

    /** Failed to acquire ProcessHandle or fork wrapper. */
    LAUNCH_FAILED,

    /**
     * Watchdog killed the process tree via timeout.flag + SIGKILL.
     * Per: timeout.flag written BEFORE kill to ensure
     * the reconciler can distinguish this from LOST.
*/
    TIMED_OUT,
}

/**
 * Result of a durable shell execution.
 * @property state The final state (COMPLETE, LOST, LAUNCH_FAILED, or TIMED_OUT).
 * @property exitCode The exit code (0 for LOST/LAUNCH_FAILED - unknown/unavailable).
 * @property controlDir The control directory path.
 * @property capturedStdout The captured stdout if returnStdout was enabled, or null.
*/
@Deprecated(
    message = "Legacy mixed durable-shell result. Consume DurableTaskTerminal at the typed shell seam; this projection is on its way out.",
)
data class DurableShellResult(
    val state: DurableShellState,
    val exitCode: Int,
    val controlDir: Path,
    val capturedStdout: String? = null,
)

/**
 * The durable shell, using ProcessBuilder + setsid.
 *
 * It used to implement a `DurableShellLaunching` interface that promised fake
 * implementations for unit testing. There were none, and there was no other
 * implementation and no other caller: the interface was a seam with one side
 * of it missing. The executor is now the seam, and a launch returns what it
 * produced ([LaunchedShell]) instead of leaving it in fields for a later,
 * separate step to read back.
 * ## State Machine
 * LAUNCHING → RUNNING → COMPLETE | LOST | LAUNCH_FAILED | TIMED_OUT
 * ## Control Directory Layout
 * The names and paths of the control directory belong to
 * [ShellProtocolLayout]. They are not spelled out here on purpose: a layout
 * written twice — once in prose and once in code — is a layout that can
 * disagree with the code, and the disagreement is silent.
 *  Invariant (CRITICAL)
 * User script content NEVER appears in argv. This holds by construction: the
 * script is written to its own file, the wrapper refers to that file by path
 * only and never embeds its content, and the launch's argv is
 * `[setsid, bash, wrapper.sh]`.
 * ## Jenkins-Faithful Cookie Kill (DEVIATION from old nohup pattern)
 * The kill mechanism mirrors Jenkins (BourneShellScript.java + FileMonitoringTask.stop
 * + ProcessTree):
 * 1. LAUNCH: argv = [setsid, bash, wrapper.sh] — no -c, no -f, no -w.
 *    setsid execs bash in place → JVM child PID == PGID == SID.
 * 2. PIPELINE_OP_COOKIE=__pipeline_protected__ (sentinel) inherited by wrapper + heartbeat.
 * 3. Wrapper OVERWRITES the sentinel with `PIPELINE_OP_COOKIE=<real>` where real = opId
 *    (embedded in file, not argv). The cookie-scan kill uses the REAL cookie, not the sentinel.
 * 4. Watchdog: cookie scan of /proc/<pid>/environ (same-UID), SIGTERM each match,
 *    5s grace (SoftKillWaitSeconds parity), then `kill -9 -<sid>`.
 * 5. Sentinel cookie initially protects wrapper machinery (heartbeat + result-writer);
 *    the wrapper overwrites it before the watchdog scans.
 * Durable sh Pattern</a>
 * @see <a href=""> - nohup+setsid detachment</a>
 * @see <a href=""> - Text-File-Busy workaround</a>
*/
class DurableShellExecutor {

    /** Polling interval for result.txt checks (100ms) */
    private val pollIntervalMs = 100L


    /**
     * Launches a durable shell step with optional stdout capture.
     *
     * The launch itself belongs to [ShellProcessLauncher]: the argv, the environment and the
     * routing of the child's two output descriptors are that component's work. This method is the
     * façade's seam onto it, and it keeps ONE thing here that the launcher must not know: the
     * agent's rendezvous. The rendezvous IS a component now — [AgentRendezvous] — and it still
     * arrives as a callback, so the launcher decides WHEN it runs (between the builder's
     * configuration and the child's start, because opening a FIFO for write blocks until a reader
     * appears) without knowing WHAT the agent is.
     *
     * @param controlDir The control directory path.
     * @param scriptContent The user's shell script.
     * @param opId The operation ID.
     * @param config The durable shell configuration.
     * @param request What the execution asked for: the environment, the sandbox, the workspace
     *   root and whether stdout is captured. The launch takes the REQUEST and not its pieces, so
     *   it cannot be called with an environment from one execution and a sandbox from another.
     * @return The launch: the child AND the transcript state that belongs to it.
*/
    private fun launch(
        controlDir: Path,
        scriptContent: String,
        opId: String,
        config: DurableShConfig,
        request: DurableShellExecutionRequest,
        // Wrap factory applied to EACH child's
        // stream BEFORE any byte reaches its sink. The factory must return a
        // chunk-boundary-safe redacting stream (StreamingRedactor).
        // Null = explicit legacy/test composition (raw transcript, unredacted).
        transcriptRedactor: ((java.io.InputStream) -> java.io.InputStream)? = null,
        // One sink per channel. Null entries mean "this composition has no destination
        // for that channel", which the launch treats as "pump it to the control-dir file" rather
        // than as a request to discard it — a missing sink must never become a dropped byte.
        channelSinks: Map<ProcessOutputChannel, ProcessOutputSink> = emptyMap(),
    ): LaunchedShell = ShellProcessLauncher(opId = opId, config = config).launch(
        controlDir = controlDir,
        scriptContent = scriptContent,
        request = request,
        transcriptRedactor = transcriptRedactor,
        channelSinks = channelSinks,
        attachRendezvous = { protocol ->
            AgentRendezvous(opId = opId, config = config).rendezvous(protocol)
        },
    )


    /**
     * Checks if the given PID is a session leader (PID == PGID == SID).
     * Used for post-launch verification of the Jenkins-faithful launch pattern.
     * @param pid The process ID to check.
     * @return true if the process is a session leader.
*/
    private fun isSessionLeader(pid: Long): Boolean {
        return try {
            val statFile = java.io.File("/proc/$pid/stat")
            if (!statFile.exists()) return false
            val content = statFile.readText()
            // stat format: pid (comm) state ppid pgrp session tpgid...
            // Extract ppid (field 4), session (field 5), pgrp (field 6)
            // We need PID == PGID == SID
            val pidLong = pid
            // Parse the stat content - comm is in parentheses and may contain spaces
            val lastParen = content.lastIndexOf(')')
            if (lastParen < 0) return false
            val afterComm = content.substring(lastParen + 1).trim()
            val fields = afterComm.split(Regex("\\s+"))
            if (fields.size < 6) return false
            val ppid = fields[1].toLong()
            val session = fields[2].toLong()
            val pgrp = fields[3].toLong()
            // Session leader: PID == Session == PGID
            // But we only have PID, and we know from /proc/<pid>/stat that:
            // field 5 (session) should equal field 6 (pgrp) for a session leader
            session == pgrp && session == pidLong
        } catch (_: Exception) {
            false
        }
    }

    fun detach(process: ProcessHandle, controlDir: Path) {
        requireLinuxHost()

        val protocol = ShellProtocolLayout(controlDir)
        val cookieFile = protocol.cookieFile
        val resultFile = protocol.resultFile

        // The wrapper handles setsid internally via:
        // `nohup /bin/sh -c '...' >&- 2>&- &`
        // This detaches from the JVM process group.

        // For safety, also use ProcessBuilder to explicitly setsid if available
        try {
            ProcessBuilder("setsid", "-f", "true").start().waitFor()
        } catch (_: Exception) {
            // setsid may not be available; wrapper handles detachment
        }

        // Wait a brief moment for the wrapper to initialize
        Thread.sleep(100)

        // Check if script already finished (for fast-executing scripts)
        // If result.txt exists, the script completed and we don't need to check cookie
        if (Files.exists(resultFile)) {
            return
        }

        // Verify cookie exists - indicates the wrapper process is still running
        // If cookie doesn't exist and result doesn't exist, the wrapper failed to start
        if (!Files.exists(cookieFile)) {
            throw IllegalStateException("Cookie file not created; wrapper may have failed to start")
        }
    }

    fun pollResult(controlDir: Path, timeoutMs: Long): Int? {
        val resultFile = ShellProtocolLayout(controlDir).resultFile
        val deadline = if (timeoutMs > 0) System.currentTimeMillis() + timeoutMs else Long.MAX_VALUE

        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(resultFile)) {
                return try {
                    Files.readString(resultFile).trim().toInt()
                } catch (e: Exception) {
                    null
                }
            }
            Thread.sleep(pollIntervalMs)
        }
        return null
    }

    fun isAlive(controlDir: Path, process: ProcessHandle): Boolean {
        val cookieFile = ShellProtocolLayout(controlDir).cookieFile

        // Check 1: cookie file exists
        if (!Files.exists(cookieFile)) {
            return false
        }

        // Check 2: PID still running
        return try {
            process.isAlive
        } catch (_: Exception) {
            false
        }
    }

    fun cleanup(controlDir: Path, exitCode: Int) {
        cleanup(controlDir, exitCode, keepTranscriptLog = false)
    }

    /**
     * When [keepTranscriptLog] is set (plain JENKINS_LOG
     * projection), a successful cleanup deletes everything in the control dir
     * EXCEPT console.log, which the production consumer streams lazily and then
     * deletes itself. Capture-mode callers keep the default (full delete).
*/
    fun cleanup(controlDir: Path, exitCode: Int, keepTranscriptLog: Boolean) {
        val config = DurableShConfig.fromSystemProperties()

        // Retain on failure if configured
        if (!config.cleanupRetainOnFailure && exitCode != 0) {
            return // Don't delete on failure
        }

        // Always delete on success
        if (exitCode == 0) {
            // In plain (JENKINS_LOG) projection the
            // terminal does not materialise the transcript, so the production
            // consumer (ShExecution.ingestTranscriptIntoOutputPlane) streams it
            // from console.log AFTER this method returns. Deleting console.log here
            // would race the consumer and silently drop observability. The
            // consumer deletes it in its finally once streaming completes.
            // The method used to be named `emitTranscriptStreaming` here, which
            // never existed: a grep for it found only this comment, so the
            // rationale for keeping console.log pointed at a phantom consumer.
            if (keepTranscriptLog) {
                // `use` closes the walk's directory handle. This runs once per shell step, so
                // a bare chain leaks a descriptor per step for the life of the JVM.
                Files.walk(controlDir).use { walk ->
                    walk.sorted(Comparator.reverseOrder())
                        .forEach { p ->
                            try {
                                if (p.fileName?.toString() != DurableShellFiles.CONSOLE_LOG) {
                                    Files.deleteIfExists(p)
                                }
                            } catch (_: Exception) {
                                // Ignore
                            }
                        }
                }
            } else {
                deleteRecursively(controlDir)
            }
        }
    }

    /**
     * The legacy wrapper builder, kept as this executor's compatible façade.
     *
     * It renders the same wrapper the launch does, and it binds the protocol's paths through
     * [ShellProtocolLayout] just like the launch, so it cannot publish the exit code anywhere
     * the executor does not poll for it. It takes the script path as an argument because the
     * caller's own protocol may place the script elsewhere.
*/
    fun buildWrapper(controlDir: Path, scriptPath: Path, config: DurableShConfig): String {
        val protocol = ShellProtocolLayout(controlDir)
        return ShellLaunchPreparation(
            protocol = protocol,
            opId = "legacy",
            heartbeatCheckInterval = config.heartbeatCheckInterval,
            pumpedViaAgent = false,
        ).wrapperContent(scriptPath)
    }

    /**
     * Reads the captured stdout from output.txt.
     * Per: read AFTER result.txt (poll result.txt first)
     * Per: single-flight read-then-delete (when captureRetainPolicy == READ_THEN_DELETE)
     * Per: on timeout/kill/non-zero, returns "" (never throws, never blocks)
     * @param controlDir The control directory path.
     * @param captureRetainPolicy Whether to delete output.txt after reading.
     * @return The captured stdout content, or null if output.txt doesn't exist.
*/
    fun readOutputText(controlDir: Path, captureRetainPolicy: CaptureRetainPolicy = CaptureRetainPolicy.READ_THEN_DELETE): String? {
        val outputFile = ShellProtocolLayout(controlDir).outputFile
        if (!Files.exists(outputFile)) {
            return null
        }
        return try {
            val content = Files.readString(outputFile).trim()
            // Single-flight delete on success when policy is READ_THEN_DELETE
            if (captureRetainPolicy == CaptureRetainPolicy.READ_THEN_DELETE) {
                Files.deleteIfExists(outputFile)
            }
            content
        } catch (_: Exception) {
            // On any error reading/deleting, return empty string
            // (never throws, never blocks)
            ""
        }
    }

    private fun deleteRecursively(path: Path) {
        Files.walk(path).use { walk ->
            walk.sorted(Comparator.reverseOrder())
                .forEach { p ->
                    try {
                        Files.deleteIfExists(p)
                    } catch (_: Exception) {
                        // Ignore
                    }
                }
        }
    }

    /**
     * Executes a durable shell step with timeout watchdog support.
     * This is an extended version of [executeDurableShell] that accepts timeout
     * and stdout capture options via [ShOptions].
     * @param controlDir The control directory path.
     * @param scriptContent The user's shell script.
     * @param opId The operation ID.
     * @param shOptions Shell execution options including timeout and capture settings.
     * @return The execution result with optional captured stdout.
*/
    @Deprecated(
        message = "Legacy durable-shell projection. Use executeTerminal() and consume DurableTaskTerminal; this projection is on its way out.",
        replaceWith = ReplaceWith("executeTerminal(controlDir, scriptContent, opId, shOptions)"),
    )
    fun execute(
        controlDir: Path,
        scriptContent: String,
        opId: String,
        shOptions: ShOptions,
    ): DurableShellResult = executeTerminal(
        controlDir = controlDir,
        scriptContent = scriptContent,
        opId = opId,
        shOptions = shOptions,
    ).toLegacyShellResult(controlDir)

    /**
     * Canonical durable-shell execution seam. The closed return type permits
     * only terminal outcomes; lifecycle snapshots never escape this module.
*/
    fun executeTerminal(
        controlDir: Path,
        scriptContent: String,
        opId: String,
        shOptions: ShOptions,
        config: DurableShConfig = DurableShConfig.fromSystemProperties(),
        transcriptRedactor: ((java.io.InputStream) -> java.io.InputStream)? = null,
        channelSinks: Map<ProcessOutputChannel, ProcessOutputSink> = emptyMap(),
    ): DurableTaskTerminal = executeCore(
        controlDir = controlDir,
        scriptContent = scriptContent,
        opId = opId,
        config = config,
        channelSinks = channelSinks,
        transcriptRedactor = transcriptRedactor,
        request = DurableShellExecutionRequest(
            timeoutMs = shOptions.timeoutMs ?: 0L,
            env = shOptions.env,
            sandbox = shOptions.sandbox,
            workspaceRoot = shOptions.workspaceRoot,
            captureStdout = shOptions.captureStdout,
            outputProjection = if (shOptions.captureStdout) {
                DurableShellOutputProjection.CAPTURE_FILE
            } else {
                DurableShellOutputProjection.JENKINS_LOG
            },
            timeoutCompletion = DurableShellTimeoutCompletion.KILL_RESULT,
        ),
    )

    /**
     * Compatibility implementation detail retained while [PipelineRun] still
     * consumes the previous string/status flow. It projects the typed terminal
     * produced by the same core while retaining the legacy watchdog policy.
*/
    internal fun executeLegacyProjection(
        controlDir: Path,
        scriptContent: String,
        opId: String,
        config: DurableShConfig,
        timeoutMs: Long,
        env: Map<String, SecretHandle>,
        sandbox: SandboxConfig,
        workspaceRoot: Path?,
    ): DurableShellResult = executeCore(
        controlDir = controlDir,
        scriptContent = scriptContent,
        opId = opId,
        config = config,
        request = DurableShellExecutionRequest(
            timeoutMs = timeoutMs,
            env = env,
            sandbox = sandbox,
            workspaceRoot = workspaceRoot,
            captureStdout = false,
            outputProjection = DurableShellOutputProjection.JENKINS_LOG,
            timeoutCompletion = DurableShellTimeoutCompletion.WATCHDOG_ATTEMPT,
        ),
    ).toLegacyShellResult(controlDir)

    private fun executeCore(
        controlDir: Path,
        scriptContent: String,
        opId: String,
        config: DurableShConfig,
        request: DurableShellExecutionRequest,
        transcriptRedactor: ((java.io.InputStream) -> java.io.InputStream)? = null,
        channelSinks: Map<ProcessOutputChannel, ProcessOutputSink> = emptyMap(),
    ): DurableTaskTerminal {
        var exitCode = -1
        var process: ProcessHandle? = null
        var launched = false
        // The watch: the deadline of this execution and the request to stop it. It owns the two
        // marks of an expired execution — the latch it publishes and the flag it persists — and
        // the consequences of an expiry.
        val watchdog = ShellWatchdog(
            controlDir = controlDir,
            opId = opId,
            timeoutMs = request.timeoutMs,
            completion = request.timeoutCompletion,
        )
        // The launch: the child AND the transcript state that belongs to it. The verdict on the
        // transcript is computed from THIS launch's pumps and sinks, so there is no ambient state
        // left for two executions to exchange.
        var launchedShell: LaunchedShell? = null

        try {
            // Step 1: Launch
            // Environment is injected via pb.environment.putAll in launch
            val shell = launch(
                controlDir,
                scriptContent,
                opId,
                config,
                request,
                transcriptRedactor,
                channelSinks,
            )
            launchedShell = shell
            process = shell.handle
            launched = true

            // Step 2: Detach
            detach(process, controlDir)

            // Step 3: Arm the watch and poll for the result.
            // The watch is armed AFTER the detach: it asks for the child's death, and the child
            // must already be detached for that to mean anything.
            watchdog.arm(process)

            // Poll in one interval slices so a watchdog kill does not leave us
            // waiting for a result.txt that the killed wrapper cannot publish.
            val pollDeadline = System.currentTimeMillis() + watchdog.pollDeadlineMs()
            while (System.currentTimeMillis() < pollDeadline && !watchdog.fired()) {
                val result = pollResult(controlDir, pollIntervalMs)
                if (result != null) {
                    exitCode = result
                    break
                }
            }

            // TMO race guard: under CPU load the watch's kill lands AFTER pollResult already
            // read a (misleading) wrapper exit code — the killed script's bash continued, echoed
            // `done` and wrote result.txt 0 just before the SIGKILL, while the watch had not yet
            // published. Without this bounded settle the terminal is classified Exited(0) →
            // success, defeating FAILED_TIMEOUT. The watch is given a bounded window to publish
            // its latch before classifying.
            watchdog.settle()

            // The persisted flag file is the AUTHORITATIVE timeout marker (written BEFORE the
            // kill). The latch depends on the watch thread's scheduling AND on the kill's
            // completion (KILL_RESULT), so on a loaded 2-core runner it can lose the race against
            // a wrapper that writes result.txt after its child was killed. If the flag file
            // exists, the run timed out regardless of the exit code.
            val timedOut = watchdog.timedOut()

            // Cancel the watch if it is still running
            watchdog.stop()

            // If we exited due to timeout
            if (timedOut) {
                // One grace poll cycle - late exit wins
                val graceExitCode = pollResult(controlDir, watchdog.graceWindowMs())
                if (graceExitCode != null) {
                    exitCode = graceExitCode
                }
            }

            // Drain the transcript pumps BEFORE projecting, so the last sanitized bytes have reached
            // the durable authority before the step is reported.
            // Every channel's pump is drained, not just one. Joining a single pump would
            // let stderr still be in flight when the step is reported, and a reader tailing the
            // stderr stream would see the step finish before its last bytes arrive.
            // `drainTranscriptPumps` is not `forEach { join }`. It waits AND then asks whether
            // the wait succeeded, because a join that times out returns exactly like one that
            // completed and the difference between them is the whole difference between a complete
            // transcript and a plausible one.
            // A persistence or drain refusal is a step failure, not a warning. The bytes left the
            // pipe when the pump read them, so a transcript that is short, ordered and plausible is
            // the worst possible outcome; it has to be reported rather than returned.
            // The verdict is computed from THIS launch: its pumps are drained first, and a refusal
            // from them subsumes whatever its sinks recorded in the meantime.
            transcriptVerdict(shell, config)?.let { refusal ->
                // Exhaustive over the closed set: a write that threw and a pump that never finished
                // are different facts, and the record says which one occurred. There is no `else`
                // and no fabricated Throwable for the case that has no throwable.
                val causeToken = when (refusal) {
                    is ProcessOutputRefusal.WriteFailed -> refusal.cause.javaClass.simpleName
                    is ProcessOutputRefusal.DrainIncomplete -> when (refusal.cause) {
                        // The token of a stuck child is the one that was already published; the
                        // drain that ran out of time is a different fact and gets its own.
                        TranscriptDrainCause.STUCK_CHILD -> "transcript-pump-still-alive"
                        TranscriptDrainCause.OUT_OF_TIME -> "transcript-drain-out-of-time"
                    }
                }
                return DurableTaskTerminal.LaunchFailed(
                    dev.rubentxu.pipeline.v2.domain.durable.FailureRecord(
                        code = OUTPUT_PERSISTENCE_REFUSED,
                        kind = dev.rubentxu.pipeline.v2.domain.FailureKind.INFRASTRUCTURE,
                        message = "process output could not be persisted: ${refusal.detail}",
                        origin = dev.rubentxu.pipeline.v2.domain.durable.FailureOrigin.DURABLE_TASK,
                        // Not retryable: the bytes are gone from the pipe, so a retry would have to
                        // re-run the child to produce a transcript that is complete rather than
                        // short. That is the step's call, not this seam's.
                        retryable = false,
                        operationId = opId,
                        details = mapOf("cause" to causeToken),
                    ),
                )
            }

            // The terminal carries NO console transcript. The bytes belong to the Output
            // Plane and are read through OutputReadPort; a terminal that carried them would be a
            // second copy of an observable authority, and in-memory at that. capturedStdout stays
            // exactly what it always claimed to be: the typed VALUE requested by returnStdout,
            // present only in capture mode.
            val capturedStdout = when (request.outputProjection) {
                DurableShellOutputProjection.CAPTURE_FILE -> readOutputText(controlDir, config.captureRetainPolicy)
                DurableShellOutputProjection.JENKINS_LOG -> null
            }

            // When the watchdog triggered, the
            // terminal is a timeout REGARDLESS of the wrapper exit code. The race
            // evidence: the killed child's surviving bash wrote result.txt=0 after
            // the SIGKILL; classifying Exited(0) marked the run success AND the
            // success-path cleanup deleted the control dir (removing the
            // authoritative timeout.flag). A wrapper exit code published after its
            // child was killed is not a legitimate completion; timeout abort wins.
            return if (timedOut) {
                watchdog.timeoutTerminal()
            } else {
                DurableTaskTerminal.Exited(
                    exitCode = exitCode,
                    output = DurableTaskOutput(
                        controlDir = controlDir.toString(),
                        capturedStdout = capturedStdout,
                    ),
                )
            }
        } catch (e: LinuxRequiredException) {
            throw e
        } catch (e: Exception) {
            return when {
                !launched -> DurableTaskTerminal.LaunchFailed(e.launchFailureRecord(opId, controlDir))
                // The same authority the terminal uses: an execution that expired and then failed
                // is a timeout, not a lost task. The reconciler reads the same flag, so the
                // terminal agrees with it instead of contradicting it.
                watchdog.timedOut() -> watchdog.timeoutTerminal()
                else -> DurableTaskTerminal.Lost(
                    lostFailureRecord(opId, controlDir, e.message ?: "durable task could not be reconciled"),
                )
            }
        } finally {
            // Release the warmup fds so the agent can
            // see EOF. The child has already exited (pollResult returned
            // before we got here, or the failure path took over), so the
            // child's O_WRONLY is already closed; the warmup is the only
            // writer on the FIFO. Closing it leaves 0 writers, the agent's
            // read returns EOF, and the agent exits cleanly.
            // `kill -9` of the runtime (the spike test) skips this finally
            // block. The OS releases the warmup fds on JVM death, so the
            // invariant still holds: at the moment the runtime dies the
            // child is alive (the test holds GO until after the kill), the
            // child becomes the only writer, and the agent's read continues
            // until the child exits and the kernel closes its O_WRONLY.
            launchedShell?.agentWarmup?.forEach { runCatching { it.close() } }
            // Cleanup based on final state. A watchdog timeout is a failure, not a
            // success: the surviving wrapper can publish exit code 0 after its child
            // was SIGKILLed, so cleanup MUST NOT read that code as success and
            // delete the control dir (which would erase the authoritative
            // timeout.flag). The decision is the watch's, and it follows the same
            // authority the terminal followed.
            cleanup(
                controlDir,
                watchdog.cleanupExitCode(exitCode),
                keepTranscriptLog = watchdog.keepsTranscriptLog(exitCode, request.outputProjection),
            )
        }
    }

}

/**
 * Executes a durable shell step with full state machine.
 * ## Crash-Safe Order
 * The crash-safe execution order is:
 * 1. begin → journal RUNNING
 * 2. create → control dir + script.sh
 * 3. launch → ProcessHandle acquired, wrapper forked
 * 4. poll → wait for result.txt (100ms polling)
 * 5. append → journal COMPLETE/LOST
 * 6. cleanup → delete control dir (unless retainOnFailure)
 * This order ensures that:
 * - On crash after step 1-2: LOST on resume (no result.txt)
 * - On crash after step 3-4: LOST on resume (no result.txt, stale heartbeat)
 * - On crash after step 5: COMPLETE on resume (result.txt exists)
 * @param controlDir The control directory root.
 * @param scriptContent The user's shell script.
 * @param opId The operation ID.
 * @param config The durable shell configuration.
 * @param timeoutMs Timeout in milliseconds (0 = no timeout,)
 * @param env Environment variables to inject via pb.environment.putAll (env via env map, NOT argv)
 * @param sandbox Sandbox profile for cwd flip and env filtering.
 * @param workspaceRoot Root directory for the stage workspace (cwd flip). If null, defaults to controlDir
 * @return The execution result.
*/
@Deprecated(
    message = "Legacy String status flow. Use DurableShellExecutor.executeTerminal and consume the typed terminal result.",
    replaceWith = ReplaceWith(
        "DurableShellExecutor().executeTerminal(controlDir, scriptContent, opId, ShOptions(timeoutMs = timeoutMs, env = env, sandbox = sandbox, workspaceRoot = workspaceRoot ?: controlDir))",
    ),
)
fun executeDurableShell(
    controlDir: Path,
    scriptContent: String,
    opId: String,
    config: DurableShConfig = DurableShConfig.fromSystemProperties(),
    timeoutMs: Long = 0L,
    env: Map<String, SecretHandle> = emptyMap(),
    sandbox: SandboxConfig = SandboxConfig.NONE,
    workspaceRoot: Path? = null,
): DurableShellResult {
    val executor = DurableShellExecutor()
    return executor.executeLegacyProjection(
        controlDir = controlDir,
        scriptContent = scriptContent,
        opId = opId,
        config = config,
        timeoutMs = timeoutMs,
        env = env,
        sandbox = sandbox,
        workspaceRoot = workspaceRoot,
    )
}

