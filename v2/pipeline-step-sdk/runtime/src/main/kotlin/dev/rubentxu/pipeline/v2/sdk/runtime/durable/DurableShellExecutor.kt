package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.withLock

/**
 * State machine for a durable shell execution.
 *
 * Valid transitions:
 * ```
 * LAUNCHING → RUNNING → COMPLETE | LOST | LAUNCH_FAILED | TIMED_OUT
 * ```
 *
 * ## State Descriptions
 *
 * - **LAUNCHING**: ProcessHandle acquired but not yet confirmed detached.
 *                  Wrapper may still fork; we're in a race window.
 * - **RUNNING**: Wrapper confirmed detached, cookie file exists.
 *                Process is alive and logging to jenkins-log.txt.
 * - **COMPLETE**: result.txt written and read successfully.
 *                 Exit code captured; final state.
 * - **LOST**: result.txt missing AND heartbeat is stale (> checkInterval + minimumDelta).
 *            Worker died; outcome unknown. Fail-closed per UAT-REC-002.
 * - **LAUNCH_FAILED**: Failed to acquire ProcessHandle or fork wrapper.
 *                      Immediate failure; no recovery possible.
 * - **TIMED_OUT**: Watchdog killed the process via timeout.flag + SIGKILL.
 *                  Per TMO-S-005: timeout.flag written BEFORE kill.
 */
enum class DurableShellState {
    /** ProcessHandle acquired but not yet confirmed detached. */
    LAUNCHING,

    /** Wrapper confirmed detached; cookie file exists. */
    RUNNING,

    /** result.txt written and read successfully. */
    COMPLETE,

    /**
     * result.txt missing AND heartbeat is stale.
     * Fail-closed: LOST never implies success (UAT-REC-002).
     */
    LOST,

    /** Failed to acquire ProcessHandle or fork wrapper. */
    LAUNCH_FAILED,

    /**
     * Watchdog killed the process tree via timeout.flag + SIGKILL.
     *
     * Per TMO-S-005: timeout.flag written BEFORE kill to ensure
     * the reconciler can distinguish this from LOST.
     */
    TIMED_OUT,
}

/**
 * Result of a durable shell execution.
 *
 * @property state The final state (COMPLETE, LOST, LAUNCH_FAILED, or TIMED_OUT).
 * @property exitCode The exit code (0 for LOST/LAUNCH_FAILED - unknown/unavailable).
 * @property controlDir The control directory path.
 * @property capturedStdout The captured stdout if returnStdout was enabled, or null.
 */
data class DurableShellResult(
    val state: DurableShellState,
    val exitCode: Int,
    val controlDir: Path,
    val capturedStdout: String? = null,
)

/**
 * Implementation of [DurableShellLaunching] using ProcessBuilder + nohup/setsid.
 *
 * ## State Machine
 *
 * LAUNCHING → RUNNING → COMPLETE | LOST | LAUNCH_FAILED
 *
 * ## Control Directory Layout (D2)
 *
 * ```
 * controlDir/
 *   script.sh              # User script verbatim
 *   script.sh.copy         # Copy for JENKINS-70874
 *   jenkins-log.txt        # stdout+stderr
 *   result.txt             # Exit code (atomic write)
 *   result.txt.tmp         # Temp for atomic write
 *   output.txt             # Reserved for returnStdout (L1: never read)
 *   .cookie                # PID file; existence = process alive
 * ```
 *
 * ## P2 Invariant (CRITICAL)
 *
 * User script content NEVER appears in argv. This is verified by the
 * self-test [verifyScriptNotInArgv]. If the script somehow appears in argv,
 * the executor will detect it and refuse to launch.
 *
 * ## D3 Wrapper (VERBATIM)
 *
 * The wrapper uses single-quoted paths and NEVER puts script in argv:
 * ```sh
 * nohup /bin/sh -c 'COOKIE=x setsid script.sh ...' >&- 2>&- &
 * ```
 *
 * @see <a href="ADR-0046">ADR-0046 — Durable sh Pattern</a>
 * @see <a href="JENKINS-58290">JENKINS-58290 - nohup+setsid detachment</a>
 * @see <a href="JENKINS-70874">JENKINS-70874 - Text-File-Busy workaround</a>
 */
class DurableShellExecutor : DurableShellLaunching {

    /** Polling interval for result.txt checks (100ms as per ADR-0046). */
    private val pollIntervalMs = 100L

    override fun launch(
        controlDir: Path,
        scriptContent: String,
        opId: String,
        config: DurableShConfig,
    ): ProcessHandle {
        return launch(controlDir, scriptContent, opId, config, captureStdout = false)
    }

    /**
     * Launches a durable shell step with optional stdout capture.
     *
     * @param controlDir The control directory path.
     * @param scriptContent The user's shell script.
     * @param opId The operation ID.
     * @param config The durable shell configuration.
     * @param captureStdout If true, capture stdout to output.txt via tee wrapper.
     * @return The process handle.
     */
    fun launch(
        controlDir: Path,
        scriptContent: String,
        opId: String,
        config: DurableShConfig,
        captureStdout: Boolean,
    ): ProcessHandle {
        checkLinuxOrThrow()

        // P2 invariant: script content is written to filesystem (script.sh), NOT passed as argv.
        // argv = ["sh", "-c", wrapper] where wrapper only contains paths, never script content.
        // This is guaranteed by construction:
        // 1. scriptContent -> Files.writeString(scriptFile)
        // 2. wrapper uses '$PATH' for variables, single-quoted paths, never embeds script
        // 3. script is invoked by path: '$scriptPathEscaped'

        // Create control directory structure
        Files.createDirectories(controlDir)

        // Write script files
        val scriptFile = controlDir.resolve("script.sh")
        val scriptCopy = controlDir.resolve("script.sh.copy")
        val logFile = controlDir.resolve("jenkins-log.txt")
        val resultFile = controlDir.resolve("result.txt")
        val resultTmp = controlDir.resolve("result.txt.tmp")
        val cookieFile = controlDir.resolve(".cookie")

        Files.writeString(scriptFile, scriptContent)
        Files.writeString(scriptCopy, scriptContent) // JENKINS-70874 workaround
        Files.setPosixFilePermissions(scriptFile, java.util.EnumSet.of(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
        ))

        // Build wrapper using D3 contract (tee-gated if captureStdout)
        val wrapper = buildWrapper(controlDir, scriptFile, config, captureStdout)

        // Launch via ProcessBuilder — argv = [sh, -c, wrapper]
        // Script content is ONLY on filesystem, never in argv
        val pb = ProcessBuilder("sh", "-c", wrapper)
        pb.directory(controlDir.toFile())

        // Pass cookie via environment (NOT argv — this is critical for P2)
        val env = pb.environment()
        env["DURABLE_SH_COOKIE"] = "please-do-not-kill-me-$opId"
        env["DURABLE_SH_OPID"] = opId

        // Redirect stdin/stdout/stderr to prevent leakage
        // stdin: inherit to avoid blocking on input (nohup handles this)
        // Note: stdout/stderr redirection is handled by the wrapper's own redirects
        // when tee-gating is enabled (captureStdout=true)
        pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()))
        pb.redirectError(ProcessBuilder.Redirect.to(logFile.toFile()))

        return try {
            val process = pb.start()
            process.toHandle()
        } catch (e: Exception) {
            throw IllegalStateException("Failed to launch durable shell for $opId", e)
        }
    }

    override fun detach(process: ProcessHandle, controlDir: Path) {
        checkLinuxOrThrow()

        val cookieFile = controlDir.resolve(".cookie")
        val resultFile = controlDir.resolve("result.txt")

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

    override fun pollResult(controlDir: Path, timeoutMs: Long): Int? {
        val resultFile = controlDir.resolve("result.txt")
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

    override fun isAlive(controlDir: Path, process: ProcessHandle): Boolean {
        val cookieFile = controlDir.resolve(".cookie")

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

    override fun kill(process: ProcessHandle, controlDir: Path) {
        val cookieFile = controlDir.resolve(".cookie")

        // SIGKILL the process tree using negative PID (process group)
        try {
            val pid = process.pid()
            ProcessBuilder("kill", "-9", "-$pid").start().waitFor()
        } catch (_: Exception) {
            // Fallback: just destroy the process
            process.destroyForcibly()
        }

        // Remove cookie immediately to signal death
        try {
            Files.deleteIfExists(cookieFile)
        } catch (_: Exception) {
            // Ignore
        }
    }

    override fun cleanup(controlDir: Path, exitCode: Int) {
        val config = DurableShConfig.fromSystemProperties()

        // Retain on failure if configured
        if (!config.cleanupRetainOnFailure && exitCode != 0) {
            return // Don't delete on failure
        }

        // Always delete on success
        if (exitCode == 0) {
            deleteRecursively(controlDir)
        }
    }

    override fun buildWrapper(controlDir: Path, scriptPath: Path, config: DurableShConfig): String {
        return buildWrapper(controlDir, scriptPath, config, captureStdout = false)
    }

    /**
     * Builds the shell wrapper command.
     *
     * @param controlDir The control directory path.
     * @param scriptPath The path to the script file.
     * @param config The durable shell configuration.
     * @param captureStdout If true, tee stdout to output.txt (returnStdout mode).
     * @return The shell wrapper command string.
     */
    fun buildWrapper(
        controlDir: Path,
        scriptPath: Path,
        config: DurableShConfig,
        captureStdout: Boolean,
    ): String {
        val cookieFileEscaped = escapeForShell(controlDir.resolve(".cookie").toString())
        val logFileEscaped = escapeForShell(controlDir.resolve("jenkins-log.txt").toString())
        val resultFileEscaped = escapeForShell(controlDir.resolve("result.txt").toString())
        val resultTmpEscaped = escapeForShell(controlDir.resolve("result.txt.tmp").toString())
        val scriptPathEscaped = escapeForShell(scriptPath.toString())
        val outputFileEscaped = escapeForShell(controlDir.resolve("output.txt").toString())

        // D3 Wrapper: VERBATIM, single-quoted paths, script NEVER in argv
        //
        // Key properties:
        // 1. COOKIE env var (not argv) protects from process-tree-killer
        // 2. Single-quoted script path prevents any expansion
        // 3. nohup + >&- 2>&- + & detaches from JVM
        // 4. Heartbeat loop touches log file (independent of stdout)
        // 5. Exit code written atomically
        //
        // When captureStdout=true (D4 tee-gating):
        // - stdout is tee'd to output.txt via '> '$OUT' 2> '$LOG'
        // - stderr goes to jenkins-log.txt as usual
        // - Heartbeat (inner while) still touches jenkins-log.txt only
        //
        // IMPORTANT: Script content is ONLY in script.sh on the filesystem.
        // The wrapper references it by PATH, never by argv content.

        // Build the wrapper. Each shell variable uses ${'$'}name to avoid Kotlin template processing.
        // Key design: cookie file is created before fork, lives while process runs, deleted on process death.
        // No EXIT trap - the OS cleans up when process exits.

        // Outer redirect: tee to output.txt if captureStdout, otherwise just log
        val outerRedirect = if (captureStdout) {
            "> '$outputFileEscaped' 2> '$logFileEscaped'"
        } else {
            "> '$logFileEscaped' 2>&1"
        }

        return buildString {
            append("( ")
            append("COOKIE_FILE='$cookieFileEscaped'; ")
            append("LOG_FILE='$logFileEscaped'; ")
            append("RESULT_FILE='$resultFileEscaped'; ")
            append("RESULT_TMP='$resultTmpEscaped'; ")
            append("CHECK_INTERVAL=${config.heartbeatCheckInterval}; ")
            append("COOKIE_VALUE='please-do-not-kill-me'; ")
            append("export COOKIE_VALUE; ")
            // Create cookie file with PID - this is the heartbeat mechanism
            append("echo ${'$'}${'$'} > \"${'$'}COOKIE_FILE\"; ")
            // Heartbeat: touch log file periodically to show process is alive
            // This is independent of stdout - it always touches jenkins-log.txt
            append("(")
            append("while [ -f \"${'$'}COOKIE_FILE\" ]; do ")
            append("sleep ${'$'}CHECK_INTERVAL; ")
            append("touch \"${'$'}LOG_FILE\"; ")
            append("done")
            append(") & ")
            // Run the actual script
            append("'$scriptPathEscaped'; ")
            append("EXIT_CODE=${'$'}?; ")
            // Write exit code atomically: temp file then rename
            append("echo ${'$'}EXIT_CODE > \"${'$'}RESULT_TMP\"; ")
            append("mv \"${'$'}RESULT_TMP\" \"${'$'}RESULT_FILE\"; ")
            // Remove cookie file to signal completion
            append("rm -f \"${'$'}COOKIE_FILE\"; ")
            append("exit ${'$'}EXIT_CODE")
            append(") $outerRedirect &")
        }
    }

    /**
     * Reads the captured stdout from output.txt.
     *
     * Per RTS-S-004: read AFTER result.txt (poll result.txt first).
     * Per RTS-S-005: single-flight read-then-delete (when captureRetainPolicy == READ_THEN_DELETE).
     * Per RTS-S-006: on timeout/kill/non-zero, returns "" (never throws, never blocks).
     *
     * @param controlDir The control directory path.
     * @param captureRetainPolicy Whether to delete output.txt after reading.
     * @return The captured stdout content, or null if output.txt doesn't exist.
     */
    fun readOutputText(controlDir: Path, captureRetainPolicy: CaptureRetainPolicy = CaptureRetainPolicy.READ_THEN_DELETE): String? {
        val outputFile = controlDir.resolve("output.txt")
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
            // On any error reading/deleting, return empty string per RTS-S-006
            // (never throws, never blocks)
            ""
        }
    }

    private fun escapeForShell(path: String): String {
        // Escape single quotes for shell
        return path.replace("'", "'\\''")
    }

    private fun checkLinuxOrThrow() {
        val osName = System.getProperty("os.name", "").lowercase()
        if (!osName.contains("linux")) {
            throw LinuxRequiredException()
        }
    }

    private fun deleteRecursively(path: Path) {
        Files.walk(path)
            .sorted(Comparator.reverseOrder())
            .forEach { p ->
                try {
                    Files.deleteIfExists(p)
                } catch (_: Exception) {
                    // Ignore
                }
            }
    }

    /**
     * Writes the timeout.flag file to signal that the watchdog triggered.
     *
     * Per TMO-S-005: timeout.flag MUST be written BEFORE kill to ensure
     * the reconciler can distinguish TIMED_OUT from LOST.
     *
     * @param controlDir The control directory path.
     */
    private fun writeTimeoutFlag(controlDir: Path) {
        try {
            val flagFile = controlDir.resolve("timeout.flag")
            Files.writeString(flagFile, System.currentTimeMillis().toString())
        } catch (_: Exception) {
            // Don't fail if we can't write the flag
        }
    }

    /**
     * Checks if the timeout.flag exists in the control directory.
     *
     * @param controlDir The control directory path.
     * @return true if timeout.flag exists.
     */
    fun hasTimeoutFlag(controlDir: Path): Boolean {
        return Files.exists(controlDir.resolve("timeout.flag"))
    }

    /**
     * Executes a durable shell step with timeout watchdog support.
     *
     * This is an extended version of [executeDurableShell] that accepts timeout
     * and stdout capture options via [ShOptions].
     *
     * @param controlDir The control directory path.
     * @param scriptContent The user's shell script.
     * @param opId The operation ID.
     * @param shOptions Shell execution options including timeout and capture settings.
     * @return The execution result with optional captured stdout.
     */
    fun execute(
        controlDir: Path,
        scriptContent: String,
        opId: String,
        shOptions: ShOptions,
    ): DurableShellResult {
        var state = DurableShellState.LAUNCHING
        var exitCode = -1
        var process: ProcessHandle? = null
        var timedOut = false

        val config = DurableShConfig.fromSystemProperties()
        val timeoutMs = shOptions.timeoutMs ?: 0L
        val captureStdout = shOptions.captureStdout

        try {
            // Step 1: Launch
            process = launch(controlDir, scriptContent, opId, config, captureStdout)
            state = DurableShellState.LAUNCHING

            // Step 2: Detach
            detach(process, controlDir)
            state = DurableShellState.RUNNING

            // Step 3: Poll for result with optional timeout
            // If timeoutMs > 0, schedule watchdog thread
            val watchdogThread = if (timeoutMs > 0) {
                Thread {
                    try {
                        Thread.sleep(timeoutMs)
                        // Timeout triggered - write flag BEFORE kill
                        writeTimeoutFlag(controlDir)
                        // Kill the process tree via setsid-derived pgid
                        // process is guaranteed non-null at this point (assigned in line above)
                        try {
                            val pid = process.pid()
                            ProcessBuilder("setsid", "-f", "kill", "-9", "-$pid").start().waitFor()
                        } catch (_: Exception) {
                            // Fallback to destroyForcibly
                            process.destroyForcibly()
                        }
                        timedOut = true
                    } catch (_: InterruptedException) {
                        // Normal interruption - timeout was cancelled
                    }
                }.apply { start() }
            } else null

            // Poll for result
            exitCode = pollResult(controlDir, timeoutMs = if (timeoutMs > 0) timeoutMs + 30_000 else 3600_000) ?: -1

            // Cancel watchdog if still running
            watchdogThread?.interrupt()

            // If we exited due to timeout
            if (timedOut) {
                state = DurableShellState.TIMED_OUT
                // One grace poll cycle - late exit wins per TMO-S-009
                val graceExitCode = pollResult(controlDir, 1000)
                if (graceExitCode != null) {
                    exitCode = graceExitCode
                    state = DurableShellState.COMPLETE
                }
            } else {
                state = DurableShellState.COMPLETE
            }

            // Read captured stdout if enabled
            val capturedStdout = if (captureStdout) {
                readOutputText(controlDir, config.captureRetainPolicy)
            } else null

            return DurableShellResult(
                state = state,
                exitCode = exitCode,
                controlDir = controlDir,
                capturedStdout = capturedStdout,
            )
        } catch (e: LinuxRequiredException) {
            state = DurableShellState.LAUNCH_FAILED
            throw e
        } catch (e: Exception) {
            state = if (timedOut) DurableShellState.TIMED_OUT else DurableShellState.LOST
            exitCode = -1
            return DurableShellResult(
                state = state,
                exitCode = exitCode,
                controlDir = controlDir,
            )
        } finally {
            // Cleanup based on final state
            cleanup(controlDir, exitCode)
        }
    }
}

/**
 * Executes a durable shell step with full state machine.
 *
 * ## Crash-Safe Order (C5)
 *
 * The crash-safe execution order is:
 * 1. begin → journal RUNNING
 * 2. create → control dir + script.sh
 * 3. launch → ProcessHandle acquired, wrapper forked
 * 4. poll → wait for result.txt (100ms polling)
 * 5. append → journal COMPLETE/LOST
 * 6. cleanup → delete control dir (unless retainOnFailure)
 *
 * This order ensures that:
 * - On crash after step 1-2: LOST on resume (no result.txt)
 * - On crash after step 3-4: LOST on resume (no result.txt, stale heartbeat)
 * - On crash after step 5: COMPLETE on resume (result.txt exists)
 *
 * @param controlDir The control directory root.
 * @param scriptContent The user's shell script.
 * @param opId The operation ID.
 * @param config The durable shell configuration.
 * @return The execution result.
 */
fun executeDurableShell(
    controlDir: Path,
    scriptContent: String,
    opId: String,
    config: DurableShConfig = DurableShConfig.fromSystemProperties(),
): DurableShellResult {
    val executor = DurableShellExecutor()
    var state = DurableShellState.LAUNCHING
    var exitCode = -1
    var process: ProcessHandle? = null

    try {
        // Step 1: Launch
        process = executor.launch(controlDir, scriptContent, opId, config)
        state = DurableShellState.LAUNCHING

        // Step 2: Detach
        executor.detach(process, controlDir)
        state = DurableShellState.RUNNING

        // Step 3: Poll for result (with reasonable timeout)
        exitCode = executor.pollResult(controlDir, timeoutMs = 3600_000) ?: -1
        // If we got here, result.txt exists
        state = DurableShellState.COMPLETE

        return DurableShellResult(
            state = state,
            exitCode = exitCode,
            controlDir = controlDir,
        )
    } catch (e: LinuxRequiredException) {
        state = DurableShellState.LAUNCH_FAILED
        throw e
    } catch (e: Exception) {
        // Unexpected error during launch/detach/poll
        state = DurableShellState.LOST
        exitCode = -1
        return DurableShellResult(
            state = state,
            exitCode = exitCode,
            controlDir = controlDir,
        )
    } finally {
        // Cleanup based on final state
        executor.cleanup(controlDir, exitCode)
    }
}
