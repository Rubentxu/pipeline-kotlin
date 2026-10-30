package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The watch over one durable shell's time, and the request to stop it.
 *
 * ## What this owns
 *
 * The DEADLINE and the TERMINATION: when the execution's time is up, the mark that says so and the
 * request that the child stops. Nothing else. The verdict that an expired execution expired at
 * all is [timedOut]'s; the terminal that verdict becomes belongs to [ShellTerminalDecision], the
 * result of the execution to the executor, the transcript to [TranscriptDrain], and the names of
 * the protocol to [ShellProtocolLayout].
 *
 * ## The two facts, and why they are not one
 *
 * There are two marks of an expired execution and they are NOT the same fact:
 *
 *  - the LATCH ([fired]): the watch published that it fired. It depends on the watch thread's
 *    scheduling AND on the kill having completed, so it can lose a race against a wrapper that
 *    publishes an exit code after its child was killed.
 *  - the FLAG ([timedOut]): the `timeout.flag` file, written BEFORE the kill. It is the
 *    authority, because it survives the race: a flag that exists means the execution expired,
 *    whatever the exit code says.
 *
 * The executor asks the authority while it polls (so a poll is not cut short by a mark that is
 * not yet durable) and asks it again once the poll is over (so a race that the latch lost is not
 * reported as a success). Both readings live here, so there is one owner of the difference.
 *
 * ## What this does NOT own
 *
 * The CLASSIFICATION of a terminal — "exited", "expired", "failed" — is [ShellTerminalDecision]'s
 * work, and the resources' release is [ShellResourceCleanup]'s. This class says what expired
 * means; it does not decide which terminal to publish, and it does not delete anything.
 */
internal class ShellWatchdog(
    controlDir: Path,
    private val opId: String,
    private val timeoutMs: Long,
    private val completion: DurableShellTimeoutCompletion,
) {

    /** The names of the protocol. The timeout marker belongs to the layout, not to this class. */
    private val protocol = ShellProtocolLayout(controlDir)

    /** The latch: the watch fired and published its outcome. */
    private val fired = AtomicBoolean(false)

    /** The watch's timer. Null when the execution has no deadline. */
    private var timer: Thread? = null

    /**
     * When the watch was armed, on the monotonic clock. Null when the execution has no deadline.
     *
     * It is the instant the deadline is measured from, and it is what tells the settle window
     * whether the deadline has ALREADY passed — which is the only moment the race it closes can
     * happen.
     */
    private var armedAtNanos: Long? = null

    /**
     * Arms the watch.
     *
     * An execution without a deadline has no watch: the timer is not created, and every
     * question this class answers is answered "no" until the authority says otherwise.
     *
     * @param process The child. The cookie scan finds the real process tree on its own, so this
     *   is only the child the execution launched.
     */
    fun arm(process: ProcessHandle) {
        if (!ShellWatchdog.watchArmed(timeoutMs)) {
            return
        }
        armedAtNanos = System.nanoTime()
        timer = Thread(
            {
                try {
                    Thread.sleep(timeoutMs)
                    // Timeout triggered - write flag BEFORE kill
                    writeTimeoutFlag()
                    // Jenkins-faithful cookie scan kill
                    // DEVIATION: Jenkins core has no SIGKILL escalation; our FAILED_TIMEOUT
                    // Determinism requires it per spec (process-tree kill, no zombies)
                    val killResult = killWithCookieScan(opId)
                    fired.set(ShellWatchdog.watchCompleted(killResult, completion))
                } catch (_: InterruptedException) {
                    // Normal interruption - timeout was cancelled
                } catch (_: Exception) {
                    // Fallback to destroyForcibly if cookie scan fails
                    try {
                        process.destroyForcibly()
                    } catch (_: Exception) {}
                    fired.set(true)
                }
            },
            "durable-sh-watchdog-$opId",
        ).apply { start() }
    }

    /**
     * Stops the watch.
     *
     * Interrupting the timer is how a deadline that did NOT arrive is cancelled: the timer wakes
     * up, sees the interruption, and publishes nothing.
     */
    fun stop() {
        timer?.interrupt()
    }

    /**
     * The authority on "this execution expired": the latch, or the persisted flag.
     *
     * The flag file is written BEFORE the kill, so it exists even when the watch was killed
     * before it could publish, and even when the wrapper published an exit code of its own after
     * its child was killed. A flag that exists means the execution expired, whatever the exit
     * code says.
     *
     * Reading the authority also LATCHES it: once the flag has been seen, the fact that the
     * execution expired is settled for the rest of the execution.
     */
    fun timedOut(): Boolean {
        val expired = ShellWatchdog.runExpired(fired.get(), Files.exists(protocol.timeoutFlag))
        if (expired) {
            fired.set(true)
        }
        return expired
    }

    /**
     * Gives the watch a bounded window to publish its latch — but only once the deadline has
     * PASSED.
     *
     * The race this closes: under CPU load the watch's kill lands AFTER the poll already read a
     * (misleading) wrapper exit code — the killed script's bash continued, echoed `done` and
     * wrote result.txt 0 just before the SIGKILL, while the watch had not yet published. Without
     * this bounded settle the terminal is classified Exited(0) → success, defeating the timeout.
     *
     * That race needs the deadline to have passed: before it, the watch has not fired and there
     * is nothing to race with. And the wait used to be unconditional, so EVERY execution with a
     * deadline paid two seconds of sleeping for a kill that was never coming. The window is now
     * opened by the deadline and not by the call.
     */
    fun settle() {
        val armedAt = armedAtNanos ?: return
        if (System.nanoTime() - armedAt < timeoutMs * NANOS_PER_MILLI) {
            return
        }
        val settleDeadline = System.nanoTime() + SETTLE_WINDOW_MS * NANOS_PER_MILLI
        while (!fired.get() && System.nanoTime() < settleDeadline) {
            Thread.sleep(SETTLE_POLL_MS)
        }
    }

    /**
     * The ceiling of the poll: the execution's own time plus the grace the kill needs.
     *
     * Null when the execution has no deadline: an execution that was asked not to time out is
     * not abandoned by a clock the operator never set.
     */
    fun pollDeadlineMs(): Long? = ShellWatchdog.pollDeadlineFor(timeoutMs)

    /**
     * The exit code the cleanup receives.
     *
     * An expired execution is a failure: the surviving wrapper can publish exit code 0 after its
     * child was SIGKILLed, so the cleanup MUST NOT read that code as success and delete the
     * control dir — which would erase the authoritative timeout flag.
     */
    fun cleanupExitCode(exitCode: Int): Int = ShellWatchdog.exitCodeForCleanup(timedOut(), exitCode)

    /**
     * Whether the transcript is kept for a reader.
     *
     * The transcript is kept only for a plain (JENKINS_LOG) projection that SUCCEEDED: the
     * production consumer streams it after this method returns and deletes it itself. An expired
     * execution keeps nothing: its control dir is the evidence.
     */
    fun keepsTranscriptLog(exitCode: Int, projection: DurableShellOutputProjection): Boolean =
        ShellWatchdog.keepsTranscriptFor(timedOut(), exitCode, projection)

    /**
     * Writes the timeout.flag file to signal that the watchdog triggered.
     * Per: timeout.flag MUST be written BEFORE kill to ensure
     * the reconciler can distinguish TIMED_OUT from LOST.
     */
    private fun writeTimeoutFlag() {
        try {
            Files.writeString(
                protocol.timeoutFlag,
                System.currentTimeMillis().toString(),
            )
        } catch (_: Exception) {
            // The watchdog must still attempt process cleanup if its marker cannot be persisted.
        }
    }

    /**
     * Jenkins-faithful cookie-scan kill.
     * Scans /proc/<pid>/environ for processes matching PIPELINE_OP_COOKIE=<real>
     * (same UID; excludes the JVM itself), sends SIGTERM to each via ProcessHandle.destroy,
     * waits up to 5000ms (SoftKillWaitSeconds parity), then escalates to
     * `kill -9 -<sid>` (sid = session ID, stable).
     * DEVIATION from Jenkins core: Jenkins has no SIGKILL escalation (no determinism
     * requirement); our FAILED_TIMEOUT state requires definitive process-tree kill.
     *
     * The child handle is NOT needed: the scan finds the real process tree by the cookie, which
     * is why the handle was an unused parameter of this operation before the extraction.
     *
     * The steps are separate functions because this one is a SEQUENCE and each step has its own
     * shape. The single function that held all of them was a complexity the moved code cannot
     * bring with it: the static analysis suppressed it under the OLD file's name, and a
     * suppression is not a property of the code it names.
     *
     * @param opId The operation ID (real cookie value).
     * @return true if kill was triggered (always returns true if called).
     */
    private fun killWithCookieScan(opId: String): Boolean {
        val jvmPid = ProcessHandle.current().pid()

        // Step 1: Find all processes with the matching cookie
        val matchingPids = findCookieProcesses(opId, jvmPid)
        if (matchingPids.isEmpty()) {
            return false
        }

        // Get the SID from any matching process's session
        // kill -TERM -<sid> sends SIGTERM to all processes in the session
        val targetSid = sessionIdOf(matchingPids) ?: matchingPids.first()

        // Step 2: SIGTERM to the session (all processes in the session)
        terminateSession(targetSid)

        // Step 3: The grace the TERM is given, before the escalation replaces it with the KILL.
        // The window is bounded and named: a wait whose length is a number buried in the middle
        // of a function is a wait nobody reviewed.
        if (!allGone(matchingPids)) {
            Thread.sleep(SOFT_KILL_GRACE_MS)
        }

        // Step 4: SIGKILL escalation if still alive
        if (!allGone(matchingPids)) {
            escalateToKill(matchingPids, targetSid, opId, jvmPid)
        }

        return true
    }

    /**
     * The session id of the first process whose `/proc/<pid>/stat` can be parsed.
     *
     * `kill -TERM -<sid>` reaches every process of the session, which is why the session is the
     * unit of termination and not the pid.
     */
    private fun sessionIdOf(pids: List<Long>): Long? {
        for (pid in pids) {
            try {
                val stat = java.io.File("/proc/$pid/stat").readText()
                val lastParen = stat.lastIndexOf(')')
                if (lastParen < 0) continue
                val afterComm = stat.substring(lastParen + 1).trim()
                val fields = afterComm.split(Regex("\\s+"))
                if (fields.size < 4) continue
                val sid = fields[3].toLongOrNull() ?: continue
                return sid
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    /**
     * SIGTERM to the whole session, with SIGKILL as the fallback when the signal itself cannot
     * be delivered.
     */
    private fun terminateSession(targetSid: Long) {
        try {
            ProcessBuilder("kill", "-TERM", "-$targetSid").start().waitFor()
        } catch (_: Exception) {
            try {
                ProcessBuilder("kill", "-KILL", "-$targetSid").start().waitFor()
            } catch (_: Exception) {}
        }
    }

    /** Whether every process of the tree is gone. A process that cannot be read counts as gone. */
    private fun allGone(pids: List<Long>): Boolean = pids.all { pid ->
        try {
            ProcessHandle.of(pid).map { !it.isAlive }.orElse(true)
        } catch (_: Exception) { true }
    }

    /**
     * The escalation: SIGKILL to the session, the per-process fallback when the signal cannot be
     * delivered, the bounded wait for the tree to die, and the sweep of whoever survived it.
     */
    private fun escalateToKill(pids: List<Long>, targetSid: Long, opId: String, jvmPid: Long) {
        try {
            ProcessBuilder("kill", "-9", "-$targetSid").start().waitFor()
        } catch (_: Exception) {
            for (pid in pids) {
                try {
                    ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
                } catch (_: Exception) {}
            }
        }

        try {
            val deadline = System.currentTimeMillis() + KILL_WAIT_DEADLINE_MS
            while (pids.any { pid ->
                try {
                    ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
                } catch (_: Exception) { false }
            } && System.currentTimeMillis() < deadline) {
                Thread.sleep(KILL_WAIT_POLL_MS)
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

    /**
     * Finds PIDs of processes whose /proc/<pid>/environ contains PIPELINE_OP_COOKIE=<real>.
     * @param realCookie The real cookie value (opId) to match.
     * @param jvmPid The JVM's own PID to exclude from the scan.
     * @return List of matching PIDs.
     */
    private fun findCookieProcesses(realCookie: String, jvmPid: Long): List<Long> {
        val matching = mutableListOf<Long>()
        val procDir = java.io.File("/proc")

        try {
            // Get our own UID from /proc/self/status (format: "Uid:\t<uid>\t<euid>\t<suid>\t<fsuid>")
            val selfUid = ownUid() ?: return matching

            val entries = procDir.listFiles() ?: return matching
            for (entry in entries) {
                if (!entry.isDirectory) continue
                val pidStr = entry.name
                val pid = pidStr.toLongOrNull() ?: continue

                // Exclude the JVM itself
                if (pid == jvmPid) continue

                // Only scan processes of the same UID (from /proc/<pid>/status)
                val targetUid = uidOf(pidStr) ?: continue
                if (targetUid != selfUid) continue

                // Read /proc/<pid>/environ and scan for the cookie
                if (hasCookie(pidStr, realCookie)) {
                    matching.add(pid)
                }
            }
        } catch (_: Exception) {
            // Fallback: return empty
        }

        return matching
    }

    /** The UID of this process, read from `/proc/self/status`. Null when it cannot be read. */
    private fun ownUid(): Long? = uidOf("self")

    /** The UID of a process, read from `/proc/<pid>/status`. Null when it cannot be read. */
    private fun uidOf(pid: String): Long? = try {
        java.io.File("/proc/$pid/status").readText().lines()
            .mapNotNull { line -> if (line.startsWith("Uid:")) line.split("\t")[1].toLongOrNull() else null }
            .firstOrNull()
    } catch (_: Exception) {
        null
    }

    /**
     * Whether the process's environment carries this operation's cookie.
     *
     * `/proc/<pid>/environ` is null-separated and unreadable for another user's process, which is
     * why the scan is limited to this user's processes in the first place.
     */
    private fun hasCookie(pid: String, realCookie: String): Boolean = try {
        val environFile = java.io.File("/proc/$pid/environ")
        if (!environFile.exists() || !environFile.canRead()) {
            false
        } else {
            val environBytes = environFile.readBytes()
            val environContent = String(environBytes, java.nio.charset.StandardCharsets.UTF_8)
            // /proc/<pid>/environ is null-separated
            val envPairs = environContent.split('\u0000')
            envPairs.any { pair ->
                pair.startsWith("PIPELINE_OP_COOKIE=") &&
                    pair.substring("PIPELINE_OP_COOKIE=".length) == realCookie
            }
        }
    } catch (_: Exception) {
        false
    }

    internal companion object {

        /** The grace the poll allows on top of the execution's own time, for the kill to land. */
        const val POLL_GRACE_MS: Long = 30_000L

        /** The bounded window the watch is given to publish its latch before the terminal is read. */
        const val SETTLE_WINDOW_MS: Long = 2_000L

        /** How often the settle window looks at the latch. */
        const val SETTLE_POLL_MS: Long = 50L

        /**
         * The grace the TERM is given before the escalation replaces it with the KILL.
         *
         * This is the window in which the child is EXPECTED to die on its own, so the wait is a
         * grace and not a decision: the escalation is what decides, and it reads the tree when
         * the grace ends.
         */
        const val SOFT_KILL_GRACE_MS: Long = 500L

        /** How long the escalation waits for the tree to die after the KILL. */
        const val KILL_WAIT_DEADLINE_MS: Long = 3_000L

        /** How often the escalation looks at the tree while it waits for it to die. */
        const val KILL_WAIT_POLL_MS: Long = 100L

        /** How many nanoseconds a millisecond is. Durations are measured on the monotonic clock. */
        const val NANOS_PER_MILLI: Long = 1_000_000L

        /**
         * Whether the execution has a watch at all.
         *
         * Zero means no deadline, and an execution without a deadline has no watch: there is
         * nothing to fire and nothing to cancel.
         */
        fun watchArmed(timeoutMs: Long): Boolean = timeoutMs > 0

        /**
         * The ceiling of the poll, or null when the execution has no deadline.
         *
         * With a deadline it is the execution's own time plus the grace the kill needs: the poll
         * must outlast the watch, or it would give up while the child is still being killed.
         *
         * WITHOUT a deadline there is no ceiling. There was one — an hour — and it made
         * "no timeout" mean "one hour, and then a made-up exit code": the poll gave up on an
         * execution that was still running and published the exit code -1, a number no wrapper
         * ever wrote. The wait of an execution with no deadline now ends when the protocol says
         * it ends: the wrapper publishes a result, or it ends without publishing one.
         */
        fun pollDeadlineFor(timeoutMs: Long): Long? = if (watchArmed(timeoutMs)) {
            timeoutMs + POLL_GRACE_MS
        } else {
            null
        }

        /**
         * Whether the execution expired.
         *
         * The flag wins over the latch being absent: the flag is written BEFORE the kill, so it
         * exists in the window where the watch has fired but has not yet published.
         */
        fun runExpired(fired: Boolean, flagPresent: Boolean): Boolean = fired || flagPresent

        /**
         * Whether the watch's attempt counts as the timeout.
         *
         * [DurableShellTimeoutCompletion.KILL_RESULT] follows the kill's own result — a kill that
         * found nothing to kill is not a timeout. [DurableShellTimeoutCompletion.WATCHDOG_ATTEMPT]
         * follows the attempt: the deadline passed, and that is the fact.
         */
        fun watchCompleted(
            killResult: Boolean,
            completion: DurableShellTimeoutCompletion,
        ): Boolean = when (completion) {
            DurableShellTimeoutCompletion.KILL_RESULT -> killResult
            DurableShellTimeoutCompletion.WATCHDOG_ATTEMPT -> true
        }

        /**
         * The exit code the cleanup receives.
         *
         * An expired execution is a failure (-1), whatever the wrapper published: the surviving
         * wrapper can publish 0 after its child was killed, and a cleanup that reads that 0 as a
         * success deletes the control dir — and with it the authoritative timeout flag.
         */
        fun exitCodeForCleanup(timedOut: Boolean, exitCode: Int): Int = if (timedOut) -1 else exitCode

        /**
         * Whether the transcript is kept for a reader.
         *
         * Only a plain (JENKINS_LOG) projection that SUCCEEDED keeps its transcript: the
         * production consumer streams it afterwards and deletes it itself. An expired execution
         * keeps nothing — its control dir is the evidence.
         */
        fun keepsTranscriptFor(
            timedOut: Boolean,
            exitCode: Int,
            projection: DurableShellOutputProjection,
        ): Boolean = !timedOut &&
            exitCode == 0 &&
            projection == DurableShellOutputProjection.JENKINS_LOG
    }
}
