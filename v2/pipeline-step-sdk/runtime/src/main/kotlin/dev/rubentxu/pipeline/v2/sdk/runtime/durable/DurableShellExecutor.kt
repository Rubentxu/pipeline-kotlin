package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskOutput
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionRecord
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
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
@Deprecated(
    message = "Legacy durable-shell state projection. Consume DurableTaskTerminal at the typed shell seam; removal is scheduled for EM-10.",
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
@Deprecated(
    message = "Legacy mixed durable-shell result. Consume DurableTaskTerminal at the typed shell seam; removal is scheduled for EM-10.",
)
data class DurableShellResult(
    val state: DurableShellState,
    val exitCode: Int,
    val controlDir: Path,
    val capturedStdout: String? = null,
)

/**
 * Implementation of [DurableShellLaunching] using ProcessBuilder + setsid.
 *
 * ## State Machine
 *
 * LAUNCHING → RUNNING → COMPLETE | LOST | LAUNCH_FAILED | TIMED_OUT
 *
 * ## Control Directory Layout (D2)
 *
 * ```
 * controlDir/
 *   script.sh              # User script verbatim
 *   script.sh.copy         # Copy for JENKINS-70874
 *   wrapper.sh             # Generated; embeds real cookie value (never in argv)
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
 * ## Jenkins-Faithful Cookie Kill (DEVIATION from old nohup pattern)
 *
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
 *
 * @see <a href="ADR-0046">ADR-0046 — Durable sh Pattern</a>
 * @see <a href="JENKINS-58290">JENKINS-58290 - nohup+setsid detachment</a>
 * @see <a href="JENKINS-70874">JENKINS-70874 - Text-File-Busy workaround</a>
 */
class DurableShellExecutor : DurableShellLaunching {

    /** Polling interval for result.txt checks (100ms as per ADR-0046). */
    private val pollIntervalMs = 100L

    /** Sentinel cookie value — inherited by wrapper + heartbeat, distinguishes wrapper machinery. */
    private val SENTINEL_COOKIE = "__pipeline_protected__"

    /** Path to setsid (resolved once at startup). */
    private val setsidPath: String by lazy {
        resolveCommandPath("setsid") ?: throw IllegalStateException("setsid not found on PATH")
    }

    /** Path to bash (resolved once at startup). */
    private val bashPath: String by lazy {
        resolveCommandPath("bash") ?: throw IllegalStateException("bash not found on PATH")
    }

    /**
     * Resolves the absolute path of a command using `command -v`.
     * Returns null if the command is not found.
     */
    private fun resolveCommandPath(command: String): String? {
        return try {
            val pb = ProcessBuilder("command", "-v", command)
            pb.redirectError(ProcessBuilder.Redirect.DISCARD)
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            if (exitCode == 0 && output.isNotEmpty()) output else null
        } catch (_: Exception) {
            null
        }
    }

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
     * ## Jenkins-Faithful Launch Pattern
     *
     * argv = [setsid, bash, wrapper.sh] — no -c, no -f, no -w.
     * setsid execs bash in place → JVM child PID == PGID == SID.
     *
     * The wrapper.sh file embeds the real cookie value (opId).
     * PIPELINE_OP_COOKIE=__pipeline_protected__ sentinel is set in pb.environment()
     * and inherited by the wrapper + heartbeat processes. The sentinel protects
     * wrapper machinery from the cookie-scan kill.
     *
     * @param controlDir The control directory path.
     * @param scriptContent The user's shell script.
     * @param opId The operation ID.
     * @param config The durable shell configuration.
     * @param captureStdout If true, capture stdout to output.txt via tee wrapper.
     * @param env Environment variables to inject via pb.environment().putAll (P2: env via env map, NOT argv).
     * @return The process handle.
     */
    fun launch(
        controlDir: Path,
        scriptContent: String,
        opId: String,
        config: DurableShConfig,
        captureStdout: Boolean,
        env: Map<String, SecretHandle> = emptyMap(),
        workspaceRoot: Path? = null,
        sandbox: SandboxConfig = SandboxConfig.NONE,
        // WU-LPR-011R2 (Gate-1 at-rest closure): wrap factory applied to the child's
        // merged output stream BEFORE any byte reaches `console.log`. The factory
        // must return a chunk-boundary-safe redacting stream (StreamingRedactor).
        // Null = explicit legacy/test composition (raw transcript, unredacted).
        transcriptRedactor: ((java.io.InputStream) -> java.io.InputStream)? = null,
    ): ProcessHandle {
        checkLinuxOrThrow()

        // P2 invariant: script content is written to filesystem (script.sh), NOT passed as argv.
        // This is guaranteed by construction:
        // 1. scriptContent -> Files.writeString(scriptFile)
        // 2. wrapper.sh references script by path only, never embeds content
        // 3. argv = [setsid, bash, wrapper.sh] — script content never in argv

        // Create control directory structure
        Files.createDirectories(controlDir)

        // Write script files
        val scriptFile = controlDir.resolve("script.sh")
        val scriptCopy = controlDir.resolve("script.sh.copy")
        val logFile = DurableShellFiles.consoleLog(controlDir)
        val resultFile = controlDir.resolve("result.txt")
        val resultTmp = controlDir.resolve("result.txt.tmp")
        val cookieFile = controlDir.resolve(".cookie")
        val wrapperFile = controlDir.resolve("wrapper.sh")

        Files.writeString(scriptFile, scriptContent)
        Files.writeString(scriptCopy, scriptContent) // JENKINS-70874 workaround
        Files.setPosixFilePermissions(scriptFile, java.util.EnumSet.of(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
        ))

        // Build wrapper using D3 contract (tee-gated if captureStdout)
        // The real cookie value (opId) is embedded in the wrapper file content
        val wrapperContent = buildWrapperContent(controlDir, scriptFile, config, captureStdout, opId, transcriptRedactor != null)
        Files.writeString(wrapperFile, wrapperContent)
        Files.setPosixFilePermissions(wrapperFile, java.util.EnumSet.of(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
        ))

        // Jenkins-faithful launch: setsid creates new session+process group.
        // argv = [setsid, bash, wrapper.sh] — no -c, no inline content in argv.
        // setsid forks; parent exits immediately; child (bash) becomes session leader.
        // The ProcessHandle points to the setsid parent (exits quickly).
        // The real target (bash/wrapper) is found via cookie-scan.
        val pb = ProcessBuilder("setsid", "bash", wrapperFile.toString())
        pb.directory((workspaceRoot ?: controlDir).toFile())

        // PIPELINE_OP_COOKIE=sentinel inherited by wrapper + heartbeat (protects wrapper machinery)
        val pbEnv = pb.environment()
        pbEnv["PIPELINE_OP_COOKIE"] = SENTINEL_COOKIE
        // Internal durable vars
        pbEnv["DURABLE_SH_COOKIE"] = "please-do-not-kill-me-$opId"
        pbEnv["DURABLE_SH_OPID"] = opId

        // ML-R3: Sandbox profile integration (DEC-1 cwd + DEC-2 deny-list + DEC-3 PATH normalize)
        // Profile branch: LOCAL applies deny-list + PATH normalize; NONE is pass-through.
        // OS would throw at factory (CLI rejects it), so reaching here means NONE or LOCAL.
        if (sandbox.profile == SandboxProfile.LOCAL) {
            val pbEnvFiltered = pbEnv.applyDenyList(sandbox.allowExtra)
            val javaHome = pbEnvFiltered["JAVA_HOME"]
            val m2Home = pbEnvFiltered["M2_HOME"]
            pbEnv.clear()
            pbEnv.putAll(pbEnvFiltered.normalizePath(sandbox.pathKeep, javaHome, m2Home))
        }

        // User-provided env injected here (WS-S-005: env via pb.environment() ONLY)
        // WS-S-022: coerce SecretHandle to String at pb.environment() putAll
        if (env.isNotEmpty()) {
            // F-D1: Apply EnvModel transformations (PATH prepend, PATH+= handling) before materialization.
            // This ensures JAVA_HOME/bin and M2_HOME/bin are prepended to PATH, and PATH+=
            // entries are properly handled. Without this call, the PATH manipulation is skipped.
            val transformedEnv = EnvModel.apply(env)
            // Coerce SecretHandle to String at the single choke point
            val coercedEnv: Map<String, String> = transformedEnv.mapValues { entry -> entry.value.materialize() }
            pbEnv.putAll(coercedEnv)

            // SB-S-005 regression fix (T-02): re-normalize PATH after user env merge.
            // User-provided PATH overrides the sandbox-normalized PATH if it contains
            // entries outside the keep-set. Re-apply normalization to ensure sandbox
            // filtering is enforced on user-provided PATH values.
            if (coercedEnv.containsKey("PATH") && sandbox.profile == SandboxProfile.LOCAL) {
                val userPath = coercedEnv["PATH"] ?: ""
                val normalizedUserPath = mapOf("PATH" to userPath).normalizePath(
                    sandbox.pathKeep,
                    pbEnv["JAVA_HOME"],
                    pbEnv["M2_HOME"]
                )["PATH"] ?: ""
                if (normalizedUserPath != userPath) {
                    pbEnv["PATH"] = normalizedUserPath
                }
            }

            // WS-S-023: wipe handles after putAll
            // WS-S-024: wipe failure addsSuppressed but does NOT prevent step completion
            // NOTE (M4): wipe intentionally OMITTED. WithCredentialsExecutor shares the
            // env map across multiple inner sh invocations (outer sh → nested
            // withCredentials → restored sh). Wiping here would corrupt the bytes for
            // the third sh. The wipe is performed by BoundCredentials.close() instead.
        }

        // Redirect stdin to /dev/null to prevent blocking on input
        // LB-02 / S6.8 (mode-aware projection): the durable file separation depends on the Sh
        // invocation mode.
        //   plain (captureStdout=false): stdout+stderr merged via ONE file descriptor into the
        //     durable console transcript (console.log). redirectErrorStream(true) gives a single
        //     O_TRUNC open, so no stream is lost or duplicated.
        //   captureStdout=true: stdout goes to the separate stdout value file (output.txt) and
        //     stderr to console.log (durable console transcript). No fusion: the typed value must
        //     never be polluted by stderr.
        // console.log always has a single writer per launch.
        //
        // WU-LPR-011R2 (Gate-1 at-rest closure): when a transcript redactor is active, the
        // console transcript is NO LONGER a ProcessBuilder redirect target. The redirected
        // stream is pumped through the redactor by a runtime-owned pump thread so ONLY
        // already-redacted bytes reach `console.log` — redaction happens BEFORE persistence,
        // not during cleanup. The typed value channel (output.txt) is unaffected: capture-mode
        // stdout remains an exact redirect (typed values are never scrubbed).
        pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        if (captureStdout) {
            val stdoutCapture = controlDir.resolve("output.txt")
            pb.redirectOutput(ProcessBuilder.Redirect.to(stdoutCapture.toFile()))
            if (transcriptRedactor != null) {
                pb.redirectError(ProcessBuilder.Redirect.PIPE)
            } else {
                pb.redirectError(ProcessBuilder.Redirect.to(logFile.toFile()))
            }
        } else {
            pb.redirectErrorStream(true)
            if (transcriptRedactor != null) {
                pb.redirectOutput(ProcessBuilder.Redirect.PIPE)
            } else {
                pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()))
            }
        }
        // Pre-create console.log so the wrapper heartbeat `touch` and external
        // observers always see the transcript file, and so the pump owns the ONLY
        // writer handle from the very first byte.
        if (transcriptRedactor != null && !captureStdout) {
            Files.newOutputStream(logFile).use { /* create/truncate */ }
        }

            return try {
            val process = pb.start()
            // WU-LPR-011R2: drain the redirected stream through the redactor into
            // console.log on a daemon pump. Redaction happens BEFORE persistence:
            // at any instant the on-disk transcript contains only sanitized bytes,
            // and a JVM crash can leave at most a partial SANITIZED transcript.
            if (transcriptRedactor != null) {
                val raw: java.io.InputStream = if (captureStdout) process.errorStream else process.inputStream
                val sink = java.io.BufferedOutputStream(java.nio.file.Files.newOutputStream(logFile))
                val pump = Thread {
                    val redacted = transcriptRedactor(raw)
                    try {
                        redacted.copyTo(sink, 8192)
                    } catch (_: Exception) {
                        // Child killed / pipe broken mid-flight: keep whatever
                        // sanitized bytes were already persisted.
                    } finally {
                        // Close the redacting stream FIRST: its pending buffer
                        // (EOF drain) is what flushes the final sanitized bytes.
                        try { redacted.close() } catch (_: Exception) {}
                        try { sink.flush() } catch (_: Exception) {}
                        try { sink.close() } catch (_: Exception) {}
                    }
                }
                pump.isDaemon = true
                pump.name = "durable-sh-transcript-pump-$opId"
                pump.start()
                lastTranscriptPump = pump
            }
            val handle = process.toHandle()

            // Advisory session-leader verification (can be non-fatal if setsid forks).
            // The real kill mechanism uses cookie-scan (Jenkins pattern), not PGID.
            // The session-leader check is informative only; if it fails, we continue
            // with the cookie-scan kill which will still work correctly.
            try {
                if (!isSessionLeader(handle.pid())) {
                    // Log but don't fail — cookie scan will handle the kill correctly
                    System.err.println(
                        "[DurableShellExecutor] Warning: PID ${handle.pid()} is not a session leader. " +
                        "Cookie-scan kill will be used for timeout termination."
                    )
                }
            } catch (_: Exception) {
                // Non-fatal: continue even if verification fails
            }

            handle
        } catch (e: Exception) {
            throw IllegalStateException("Failed to launch durable shell for $opId", e)
        }
    }

    /**
     * Checks if the given PID is a session leader (PID == PGID == SID).
     * Used for post-launch verification of the Jenkins-faithful launch pattern.
     *
     * @param pid The process ID to check.
     * @return true if the process is a session leader.
     */
    private fun isSessionLeader(pid: Long): Boolean {
        return try {
            val statFile = java.io.File("/proc/$pid/stat")
            if (!statFile.exists()) return false
            val content = statFile.readText()
            // stat format: pid (comm) state ppid pgrp session tpgid ...
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
        cleanup(controlDir, exitCode, keepTranscriptLog = false)
    }

    /**
     * WU-RP-044 (M5 RSS debt): when [keepTranscriptLog] is set (plain JENKINS_LOG
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
            // WU-RP-044 (M5 RSS debt): in plain (JENKINS_LOG) projection the
            // terminal does not materialise the transcript, so the production
            // consumer (ShExecution.emitTranscriptStreaming) streams it from
            // console.log AFTER this method returns. Deleting console.log here
            // would race the consumer and silently drop observability. The
            // consumer deletes it in its finally once streaming completes.
            if (keepTranscriptLog) {
                Files.walk(controlDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach { p ->
                        try {
                            if (p.fileName?.toString() != DurableShellFiles.CONSOLE_LOG) {
                                Files.deleteIfExists(p)
                            }
                        } catch (_: Exception) {
                            // Ignore
                        }
                    }
            } else {
                deleteRecursively(controlDir)
            }
        }
    }

    /**
     * Builds the wrapper script content that is written to wrapper.sh.
     *
     * ## Jenkins-Faithful Wrapper
     *
     * The real cookie value (opId) is embedded in the file content as:
     * `PIPELINE_OP_COOKIE=<opId>`
     *
     * This allows the watchdog to identify victim processes by scanning
     * /proc/<pid>/environ for PIPELINE_OP_COOKIE=<real>, while the sentinel
     * PIPELINE_OP_COOKIE=__pipeline_protected__ protects the wrapper machinery
     * (heartbeat + result-writer) from being killed.
     *
     * @param controlDir The control directory path.
     * @param scriptPath The path to the script file.
     * @param config The durable shell configuration.
     * @param captureStdout If true, tee stdout to output.txt (returnStdout mode).
     * @param opId The operation ID (embedded as the real cookie value).
     * @return The shell wrapper script content.
     */
    private fun buildWrapperContent(
        controlDir: Path,
        scriptPath: Path,
        config: DurableShConfig,
        captureStdout: Boolean,
        opId: String,
        transcriptRedacted: Boolean = false,
    ): String {
        val cookieFileEscaped = escapeForShell(controlDir.resolve(".cookie").toString())
        val logFileEscaped = escapeForShell(DurableShellFiles.consoleLog(controlDir).toString())
        val resultFileEscaped = escapeForShell(controlDir.resolve("result.txt").toString())
        val resultTmpEscaped = escapeForShell(controlDir.resolve("result.txt.tmp").toString())
        val scriptPathEscaped = escapeForShell(scriptPath.toString())
        val outputFileEscaped = escapeForShell(controlDir.resolve("output.txt").toString())

        // D3 Wrapper: VERBATIM, single-quoted paths, script NEVER in argv
        //
        // Key properties:
        // 1. PIPELINE_OP_COOKIE=<real> (embedded in file, NOT argv) identifies victims
        // 2. COOKIE_FILE protects wrapper machinery from cookie-scan kill
        // 3. Single-quoted script path prevents any expansion
        // 4. Heartbeat loop touches log file (independent of stdout)
        // 5. Exit code written atomically
        //
        // When captureStdout=true (D4 tee-gating):
        // - stdout is tee'd to output.txt via '> '$OUT' 2> '$LOG'
        // - stderr goes to jenkins-log.txt as usual
        // - Heartbeat (inner while) still touches jenkins-log.txt only

        // Outer redirect: tee to output.txt if captureStdout, otherwise just log
        // WU-LPR-011R2: when the transcript is redacted by the runtime pump, the
        // wrapper MUST NOT open its own console.log handle. It inherits the PIPE
        // and the pump is the single writer of only redacted bytes. For capture
        // mode, stdout still tee's to output.txt; stderr inherits the pipe.
        val outerRedirect = when {
            transcriptRedacted && captureStdout -> "> '$outputFileEscaped'"
            transcriptRedacted -> ""
            captureStdout -> "> '$outputFileEscaped' 2> '$logFileEscaped'"
            else -> "> '$logFileEscaped' 2>&1"
        }

        // The real cookie value is embedded in the file (not argv)
        // This is the value the watchdog looks for when scanning /proc/<pid>/environ
        return buildString {
            append("#!/bin/bash\n")
            // Embed the real cookie — watchdog scans /proc/PID/environ for this value
            append("export PIPELINE_OP_COOKIE='$opId'\n")
            append("COOKIE_FILE='$cookieFileEscaped'; ")
            append("LOG_FILE='$logFileEscaped'; ")
            append("RESULT_FILE='$resultFileEscaped'; ")
            append("RESULT_TMP='$resultTmpEscaped'; ")
            append("CHECK_INTERVAL=${config.heartbeatCheckInterval}; ")
            // Create cookie file with PID - this is the heartbeat mechanism
            append("echo \$\$ > \"\$COOKIE_FILE\"; ")
            // Heartbeat: touch log file periodically to show process is alive
            // WU-LPR-011R2: capture the heartbeat PID. When the transcript is pumped
            // through the redaction pipe, the heartbeat subshell holds the pipe's write
            // end; if left running it delays EOF (and thus the final redactor drain)
            // by up to CHECK_INTERVAL. The wrapper kills it explicitly at exit.
            append("HB_PID=\$!; ")
            // Run the actual script
            append("'$scriptPathEscaped'; ")
            append("EXIT_CODE=\$?; ")
            // Write exit code atomically: temp file then rename
            append("echo \$EXIT_CODE > \"\$RESULT_TMP\"; ")
            append("mv \"\$RESULT_TMP\" \"\$RESULT_FILE\"; ")
            // Remove cookie file to signal completion
            append("rm -f \"\$COOKIE_FILE\"; ")
            append("kill \$HB_PID 2>/dev/null; ")
            append("exit \$EXIT_CODE")
        }
    }

    /**
     * Legacy wrapper builder for compatibility (used by tests).
     * @deprecated Use buildWrapperContent instead for Jenkins-faithful pattern.
     */
    override fun buildWrapper(controlDir: Path, scriptPath: Path, config: DurableShConfig): String {
        return buildWrapperContent(controlDir, scriptPath, config, captureStdout = false, opId = "legacy")
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

    /**
     * Jenkins-faithful cookie-scan kill.
     *
     * Scans /proc/<pid>/environ for processes matching PIPELINE_OP_COOKIE=<real>
     * (same UID; excludes the JVM itself), sends SIGTERM to each via ProcessHandle.destroy(),
     * waits up to 5000ms (SoftKillWaitSeconds parity), then escalates to
     * `kill -9 -<sid>` (sid = session ID, stable).
     *
     * DEVIATION from Jenkins core: Jenkins has no SIGKILL escalation (no determinism
     * requirement); our FAILED_TIMEOUT state requires definitive process-tree kill.
     *
     * @param process The process handle (may be dead parent sh; the real process is found via cookie scan).
     * @param opId The operation ID (real cookie value).
     * @return true if kill was triggered (always returns true if called).
     */
    internal fun killWithCookieScan(process: ProcessHandle, opId: String): Boolean {
        val jvmPid = ProcessHandle.current().pid()

        // Step 1: Find all processes with the matching cookie
        val matchingPids = findCookieProcesses(opId, jvmPid)
        if (matchingPids.isEmpty()) {
            return false
        }

        // Get the SID from any matching process's session
        // kill -TERM -<sid> sends SIGTERM to all processes in the session
        var targetSid: Long? = null
        for (pid in matchingPids) {
            try {
                val stat = java.io.File("/proc/$pid/stat").readText()
                val lastParen = stat.lastIndexOf(')')
                if (lastParen < 0) continue
                val afterComm = stat.substring(lastParen + 1).trim()
                val fields = afterComm.split(Regex("\\s+"))
                if (fields.size < 4) continue
                val sid = fields[3].toLongOrNull() ?: continue
                targetSid = sid
                break
            } catch (_: Exception) {
                continue
            }
        }
        if (targetSid == null) targetSid = matchingPids.first()

        // Step 2: SIGTERM to the session (all processes in the session)
        try {
            ProcessBuilder("kill", "-TERM", "-$targetSid").start().waitFor()
        } catch (_: Exception) {
            try {
                ProcessBuilder("kill", "-KILL", "-$targetSid").start().waitFor()
            } catch (_: Exception) {}
        }

        // Step 3: Grace poll up to 5000ms
        var allGone = matchingPids.all { pid ->
            try {
                ProcessHandle.of(pid).map { !it.isAlive }.orElse(true)
            } catch (_: Exception) { true }
        }

        if (!allGone) {
            Thread.sleep(500)
        }

        allGone = matchingPids.all { pid ->
            try {
                ProcessHandle.of(pid).map { !it.isAlive }.orElse(true)
            } catch (_: Exception) { true }
        }

        // Step 4: SIGKILL escalation if still alive
        if (!allGone) {
            try {
                ProcessBuilder("kill", "-9", "-$targetSid").start().waitFor()
            } catch (_: Exception) {
                for (pid in matchingPids) {
                    try {
                        ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
                    } catch (_: Exception) {}
                }
            }

            try {
                val deadline = System.currentTimeMillis() + 3000L
                while (matchingPids.any { pid ->
                    try { ProcessHandle.of(pid).map { it.isAlive }.orElse(false) } catch (_: Exception) { false }
                } && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100)
                }
            } catch (_: Exception) {}

            try {
                val check = ProcessBuilder("kill", "-0", "-$targetSid").start().waitFor()
                if (check == 0) {
                    val survivors = findCookieProcesses(opId, jvmPid)
                    for (pid in survivors) {
                        try {
                            ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        return true
    }

    /**
     * Finds PIDs of processes whose /proc/<pid>/environ contains PIPELINE_OP_COOKIE=<real>.
     *
     * @param realCookie The real cookie value (opId) to match.
     * @param jvmPid The JVM's own PID to exclude from the scan.
     * @return List of matching PIDs.
     */
    private fun findCookieProcesses(realCookie: String, jvmPid: Long): List<Long> {
        val matching = mutableListOf<Long>()
        val procDir = java.io.File("/proc")

        try {
            // Get our own UID from /proc/self/status (format: "Uid:\t<uid>\t<euid>\t<suid>\t<fsuid>")
            val selfUid = java.io.File("/proc/self/status").readText().lines()
                .mapNotNull { line -> if (line.startsWith("Uid:")) line.split("\t")[1].toLongOrNull() else null }
                .firstOrNull() ?: return matching

            val entries = procDir.listFiles() ?: return matching
            for (entry in entries) {
                if (!entry.isDirectory) continue
                val pidStr = entry.name
                val pid = pidStr.toLongOrNull() ?: continue

                // Exclude the JVM itself
                if (pid == jvmPid) continue

                // Only scan processes of the same UID (from /proc/<pid>/status)
                val targetUid = try {
                    java.io.File("/proc/$pidStr/status").readText().lines()
                        .mapNotNull { line -> if (line.startsWith("Uid:")) line.split("\t")[1].toLongOrNull() else null }
                        .firstOrNull()
                } catch (_: Exception) {
                    continue
                }
                if (targetUid != selfUid) continue

                // Read /proc/<pid>/environ and scan for the cookie
                try {
                    val environFile = java.io.File("/proc/$pidStr/environ")
                    if (!environFile.exists() || !environFile.canRead()) continue
                    val environBytes = environFile.readBytes()
                    val environContent = String(environBytes, java.nio.charset.StandardCharsets.UTF_8)
                    // /proc/<pid>/environ is null-separated
                    val envPairs = environContent.split('\u0000')
                    for (pair in envPairs) {
                        if (pair.startsWith("PIPELINE_OP_COOKIE=")) {
                            val value = pair.substring("PIPELINE_OP_COOKIE=".length)
                            if (value == realCookie) {
                                matching.add(pid)
                                break
                            }
                        }
                    }
                } catch (_: Exception) {
                    continue
                }
            }
        } catch (_: Exception) {
            // Fallback: return empty
        }

        return matching
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
            Files.writeString(controlDir.resolve("timeout.flag"), System.currentTimeMillis().toString())
        } catch (_: Exception) {
            // The watchdog must still attempt process cleanup if its marker cannot be persisted.
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
    @Deprecated(
        message = "Legacy durable-shell projection. Use executeTerminal() and consume DurableTaskTerminal; removal is scheduled for EM-10.",
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
    ): DurableTaskTerminal = executeCore(
        controlDir = controlDir,
        scriptContent = scriptContent,
        opId = opId,
        config = config,
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
     * consumes the pre-EM-1 string/status flow. It projects the typed terminal
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

    /**
     * WU-LPR-011R2: the transcript pump created by the most recent [launch] call
     * (single-threaded launch discipline: one launch per executeCore). Used by
     * executeCore to drain the pump before projecting the persisted transcript.
     */
    @Volatile
    private var lastTranscriptPump: Thread? = null

    private fun consumeLastTranscriptPump(@Suppress("UNUSED_PARAMETER") handle: ProcessHandle): Thread? {
        val pump = lastTranscriptPump
        lastTranscriptPump = null
        return pump
    }

    private fun executeCore(
        controlDir: Path,
        scriptContent: String,
        opId: String,
        config: DurableShConfig,
        request: DurableShellExecutionRequest,
        transcriptRedactor: ((java.io.InputStream) -> java.io.InputStream)? = null,
    ): DurableTaskTerminal {
        var exitCode = -1
        var process: ProcessHandle? = null
        var launched = false
        val timeoutTriggered = AtomicBoolean(false)
        // WU-LPR-011R2: reference to the transcript pump so the terminal projection
        // can drain it BEFORE reading console.log (redaction-before-persistence
        // means the file is only complete once the pump reaches EOF).
        var transcriptPump: Thread? = null

        try {
            // Step 1: Launch
            // P2: environment is injected via pb.environment().putAll in launch()
            process = launch(
                controlDir,
                scriptContent,
                opId,
                config,
                request.captureStdout,
                request.env,
                request.workspaceRoot,
                request.sandbox,
                transcriptRedactor,
            ).also { handle ->
                transcriptPump = consumeLastTranscriptPump(handle)
            }
            launched = true

            // Step 2: Detach
            detach(process, controlDir)

            // Step 3: Poll for result with optional timeout
            // If timeoutMs > 0, schedule watchdog thread
            val watchdogThread = if (request.timeoutMs > 0) {
                Thread {
                    try {
                        Thread.sleep(request.timeoutMs)
                        // Timeout triggered - write flag BEFORE kill (TMO-S-005)
                        writeTimeoutFlag(controlDir)
                        // Jenkins-faithful cookie scan kill
                        // DEVIATION: Jenkins core has no SIGKILL escalation; our FAILED_TIMEOUT
                        // determinism requires it per spec TMO-S-004 (process-tree kill, no zombies)
                        val killResult = killWithCookieScan(process!!, opId)
                        timeoutTriggered.set(
                            when (request.timeoutCompletion) {
                                DurableShellTimeoutCompletion.KILL_RESULT -> killResult
                                DurableShellTimeoutCompletion.WATCHDOG_ATTEMPT -> true
                            },
                        )
                    } catch (_: InterruptedException) {
                        // Normal interruption - timeout was cancelled
                    } catch (_: Exception) {
                        // Fallback to destroyForcibly if cookie scan fails
                        try { process?.destroyForcibly() } catch (_: Exception) {}
                        timeoutTriggered.set(true)
                    }
                }.apply { start() }
            } else null

            // Poll in one interval slices so a watchdog kill does not leave us
            // waiting for a result.txt that the killed wrapper cannot publish.
            val pollDeadline = System.currentTimeMillis() + if (request.timeoutMs > 0) {
                request.timeoutMs + 30_000
            } else {
                3600_000
            }
            while (System.currentTimeMillis() < pollDeadline && !timeoutTriggered.get()) {
                val result = pollResult(controlDir, pollIntervalMs)
                if (result != null) {
                    exitCode = result
                    break
                }
            }

            // TMO race guard (WU-RP-005 r7, CI run 35653013723): under CPU load the
            // watchdog's kill lands AFTER pollResult already read a (misleading)
            // wrapper exit code — the killed script's bash continued, echoed `done`
            // and wrote result.txt 0 just before the SIGKILL, while the watchdog
            // thread had not yet published timeoutTriggered. Without this bounded
            // settle the terminal is classified Exited(0) → success, defeating
            // FAILED_TIMEOUT (TMO-S-001/002). Give the watchdog a bounded window to
            // publish its flag before classifying.
            if (!timeoutTriggered.get() && watchdogThread != null && request.timeoutMs > 0) {
                val settleDeadline = System.currentTimeMillis() + 2_000
                while (!timeoutTriggered.get() && System.currentTimeMillis() < settleDeadline) {
                    Thread.sleep(50)
                }
            }

            // The persisted flag file is the AUTHORITATIVE timeout marker
            // (written BEFORE the kill, TMO-S-005). The in-memory flag depends on
            // watchdog-thread scheduling AND kill completion (KILL_RESULT), so on a
            // loaded 2-core runner it can lose the race against a wrapper that
            // writes result.txt after its child was killed. If the flag file
            // exists, the run timed out regardless of the exit code.
            if (!timeoutTriggered.get() && hasTimeoutFlag(controlDir)) {
                timeoutTriggered.set(true)
            }

            // Cancel watchdog if still running
            watchdogThread?.interrupt()

            // If we exited due to timeout
            if (timeoutTriggered.get()) {
                // One grace poll cycle - late exit wins per TMO-S-009
                val graceExitCode = pollResult(controlDir, 1000)
                if (graceExitCode != null) {
                    exitCode = graceExitCode
                }
            }

            // Read the durable console transcript (console.log) BEFORE cleanup so success-path
            // observability is not lost when the policy deletes the control dir on success.
            // consoleTranscript is the console/event channel; capturedStdout is the typed value
            // channel (output.txt in capture mode). They are distinct (LB-02 / C4.5).
            // WU-LPR-011R2: drain the redaction pump first (bounded join) so the persisted
            // transcript is fully flushed before it is projected.
            transcriptPump?.join(10_000)
            // WU-RP-044 (M5 RSS debt): in plain (JENKINS_LOG) projection the
            // console transcript is NOT materialised here. console.log on disk is
            // the durable authority and the production consumer (ShExecution)
            // streams from it lazily; reading the whole file into the terminal
            // duplicated GiB-scale transcripts in memory. Capture mode still
            // reads output.txt because that is the typed VALUE channel.
            val consoleTranscript: String? = if (
                request.outputProjection == DurableShellOutputProjection.CAPTURE_FILE
            ) {
                readConsoleLogText(controlDir)
            } else {
                null
            }
            val capturedStdout = when (request.outputProjection) {
                DurableShellOutputProjection.CAPTURE_FILE -> readOutputText(controlDir, config.captureRetainPolicy)
                DurableShellOutputProjection.JENKINS_LOG -> consoleTranscript
            }

            // WU-RP-005 r9 (CI run 35656479415): when the watchdog triggered, the
            // terminal is a timeout REGARDLESS of the wrapper exit code. The race
            // evidence: the killed child's surviving bash wrote result.txt=0 after
            // the SIGKILL; classifying Exited(0) marked the run success AND the
            // success-path cleanup deleted the control dir (removing the
            // authoritative timeout.flag). A wrapper exit code published after its
            // child was killed is not a legitimate completion; timeout abort wins.
            return if (timeoutTriggered.get()) {
                DurableTaskTerminal.Cancelled(
                    InterruptionRecord(
                        kind = InterruptionKind.TIMEOUT,
                        message = "durable shell timed out",
                        operationId = opId,
                        details = mapOf("controlDir" to controlDir.toString()),
                    ),
                )
            } else {
                DurableTaskTerminal.Exited(
                    exitCode = exitCode,
                    output = DurableTaskOutput(
                        controlDir = controlDir.toString(),
                        capturedStdout = capturedStdout,
                        consoleTranscript = consoleTranscript,
                    ),
                )
            }
        } catch (e: LinuxRequiredException) {
            throw e
        } catch (e: Exception) {
            return when {
                !launched -> DurableTaskTerminal.LaunchFailed(e.launchFailureRecord(opId, controlDir))
                timeoutTriggered.get() -> DurableTaskTerminal.Cancelled(
                    InterruptionRecord(
                        kind = InterruptionKind.TIMEOUT,
                        message = "durable shell timed out",
                        operationId = opId,
                        details = mapOf("controlDir" to controlDir.toString()),
                    ),
                )
                else -> DurableTaskTerminal.Lost(
                    lostFailureRecord(opId, controlDir, e.message ?: "durable task could not be reconciled"),
                )
            }
        } finally {
            // Cleanup based on final state. A watchdog timeout is a failure, not a
            // success: the surviving wrapper can publish exit code 0 after its child
            // was SIGKILLed (WU-RP-005 r9, CI 35656479415/35657576105), so cleanup
            // MUST NOT read that code as success and delete the control dir (which
            // would erase the authoritative timeout.flag, TMO-S-005).
            cleanup(
                controlDir,
                if (timeoutTriggered.get()) -1 else exitCode,
                keepTranscriptLog = !timeoutTriggered.get() && exitCode == 0 &&
                    request.outputProjection == DurableShellOutputProjection.JENKINS_LOG,
            )
        }
    }

    private fun readConsoleLogText(controlDir: Path): String? = try {
        val logFile = DurableShellFiles.resolveConsoleLog(controlDir)
        if (Files.exists(logFile)) Files.readString(logFile) else null
    } catch (_: Exception) {
        null
    }
}

private data class DurableShellExecutionRequest(
    val timeoutMs: Long,
    val env: Map<String, SecretHandle>,
    val sandbox: SandboxConfig,
    val workspaceRoot: Path?,
    val captureStdout: Boolean,
    val outputProjection: DurableShellOutputProjection,
    val timeoutCompletion: DurableShellTimeoutCompletion,
)

private enum class DurableShellOutputProjection {
    CAPTURE_FILE,
    JENKINS_LOG,
}

private enum class DurableShellTimeoutCompletion {
    KILL_RESULT,
    WATCHDOG_ATTEMPT,
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
 * @param timeoutMs Timeout in milliseconds (0 = no timeout, per TMO-S-013).
 * @param env Environment variables to inject via pb.environment().putAll (P2: env via env map, NOT argv).
 * @param sandbox Sandbox profile for cwd flip and env filtering.
 * @param workspaceRoot Root directory for the stage workspace (DEC-1 cwd flip). If null, defaults to controlDir.
 * @return The execution result.
 */
@Deprecated(
    message = "Compatibility projection for PipelineRun's legacy String status flow. Migrate to DurableShellExecutor.executeTerminal with ShOptions and consume the typed terminal result; removal is scheduled for EM-10.",
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
