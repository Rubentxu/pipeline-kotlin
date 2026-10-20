package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import java.nio.file.Path

/**
 * Dependency Inversion Principle (DIP) interface for launching durable shell processes.
 *
 * This interface abstracts the OS-level process launching, allowing:
 * 1. Real implementation for production (uses ProcessBuilder + nohup/setsid)
 * 2. Fake/Test implementations for unit testing without forking real processes
 *
 * ## P2 Invariant: User Script Never in argv
 *
 * The core security guarantee (ADR-0046 P2) is that the user script content
 * NEVER appears in any argv passed to the OS. This is enforced by:
 * 1. Writing the script to `script.sh` on disk (filesystem, not argv)
 * 2. Passing only the fixed wrapper command to `sh -c`
 * 3. The wrapper references script.sh by PATH, never by argv content
 *
 * Implementations MUST ensure that [launch] never puts script content in argv.
 *
 * ## Control Directory Contract
 *
 * Each call to [launch] operates on a dedicated control directory:
 * ```
 * controlDir/
 *   script.sh          # User script, written verbatim
 *   script.sh.copy    # Copy for Text-File-Busy (JENKINS-70874)
 *   jenkins-log.txt   # stdout+stderr of running script
 *   result.txt        # Exit code, written atomically
 *   result.txt.tmp    # Temp for atomic write
 *   output.txt        # Reserved for returnStdout (L1: never read)
 *   .cookie           # Created by wrapper; signals process is alive
 * ```
 *
 * ## Lifecycle
 *
 * 1. [launch] creates the control dir and starts the process
 * 2. [detach] performs nohup+setsid to detach from JVM
 * 3. [pollResult] checks result.txt for completion (100ms polling)
 * 4. [isAlive] checks via cookie file existence
 * 5. [kill] destroys the process tree
 * 6. [cleanup] removes the control directory
 *
 * @see DurableShConfig for configuration options
 * @see <a href="ADR-0046">ADR-0046 — Durable sh Pattern (P1/P2)</a>
 */
interface DurableShellLaunching {

    /**
     * Launches a shell script in the given control directory.
     *
     * ## P2 Critical: script content goes to FILE, NOT argv
     *
     * The [scriptContent] MUST be written to `script.sh` in [controlDir].
     * The [wrapper] command passed to the OS MUST only reference the file path,
     * never the script content itself.
     *
     * Correct:
     * ```kotlin
     * scriptFile.writeText(scriptContent)  // Script to filesystem
     * val pb = ProcessBuilder("sh", "-c", wrapper(controlDir))  // argv = [sh, -c, wrapper]
     * ```
     *
     * Incorrect (P2 violation):
     * ```kotlin
     * val pb = ProcessBuilder("sh", "-c", "$scriptContent ...")  // ❌ script in argv
     * ```
     *
     * @param controlDir Path to the control directory for this step.
     * @param scriptContent The user's raw shell script (may contain $, `, ", etc.).
     * @param opId Operation ID for cookie/naming.
     * @param config Durable shell configuration.
     * @return The launched process handle.
     * @throws LinuxRequiredException If not on Linux.
     */
    fun launch(
        controlDir: Path,
        scriptContent: String,
        opId: String,
        config: DurableShConfig,
    ): ProcessHandle

    /**
     * Detaches the process using nohup + setsid.
     *
     * After detach, the process runs independently of the JVM.
     * The wrapper continues writing to jenkins-log.txt and touching .cookie.
     *
     * @param process The process handle from [launch].
     * @param controlDir The control directory.
     * @throws LinuxRequiredException If not on Linux.
     */
    fun detach(process: ProcessHandle, controlDir: Path)

    /**
     * Polls for result.txt existence and reads the exit code.
     *
     * Uses 100ms polling interval as specified in ADR-0046.
     *
     * @param controlDir The control directory.
     * @param timeoutMs Maximum time to wait for result.txt (0 = no limit).
     * @return The exit code, or null if timeout exceeded without result.txt.
     */
    fun pollResult(controlDir: Path, timeoutMs: Long): Int?

    /**
     * Checks if the process is still alive via cookie file.
     *
     * The wrapper creates `.cookie` when starting and removes it on exit.
     * If .cookie exists and the PID is still running → alive.
     *
     * @param controlDir The control directory.
     * @param process The process handle.
     * @return true if the process appears to be running.
     */
    fun isAlive(controlDir: Path, process: ProcessHandle): Boolean

    /**
     * Kills the process tree using SIGKILL.
     *
     * Uses `kill -9` on the process group (negative PID) to ensure
     * the entire tree is killed, not just the leaf process.
     *
     * @param process The process handle.
     * @param controlDir The control directory.
     */
    fun kill(process: ProcessHandle, controlDir: Path)

    /**
     * Cleans up the control directory.
     *
     * Behavior controlled by [DurableShConfig.cleanupRetainOnFailure]:
     * - true: Always retain (for debugging)
     * - false: Remove on failure
     *
     * Always retains on success.
     *
     * @param controlDir The control directory.
     * @param exitCode The exit code of the process.
     */
    fun cleanup(controlDir: Path, exitCode: Int)

    /**
     * Builds the Jenkins-style wrapper command for durable shell.
     *
     * ## D3 Wrapper Contract (ADR-0046)
     *
     * The wrapper MUST:
     * 1. Quote the script path (single-quoted to prevent expansion)
     * 2. Touch jenkins-log.txt every [config.heartbeatCheckInterval] seconds
     * 3. Write exit code atomically to result.txt
     * 4. Create .cookie on start, remove on exit
     * 5. Pass cookie via environment variable (NOT argv)
     *
     * Example wrapper (BourneShellScript style):
     * ```sh
     * (
     *   COOKIE='please-do-not-kill-me'
     *   export COOKIE
     *   trap 'rm -f "$COOKIE_FILE"' EXIT
     *   echo "$$" > "$COOKIE_FILE"
     *   while true; do
     *     sleep 300
     *     [ -f "$COOKIE_FILE" ] || exit 0
     *     touch "$LOG_FILE"
     *   done
     * ) &
     *   wait $!
     *   EXIT_CODE=$?
     *   echo $EXIT_CODE > "$RESULT_TMP"
     *   mv "$RESULT_TMP" "$RESULT_FILE"
     *   exit $EXIT_CODE
     * ```
     *
     * @param controlDir The control directory path.
     * @param scriptPath The path to script.sh (single-quoted in wrapper).
     * @param config The durable shell configuration.
     * @return The wrapper command string to pass to `sh -c`.
     */
    fun buildWrapper(controlDir: Path, scriptPath: Path, config: DurableShConfig): String
}
