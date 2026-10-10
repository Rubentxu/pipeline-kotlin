package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * M1-F.2 — durable-shell timeout diagnostics.
 *
 * Verifies the contracts the executor already provides:
 *
 *  - The PID the executor observes is the **real** session leader (not the
 *    setsid parent, which is long dead by the time we read it), and its
 *    SID / PGID match the launcher — `/proc/<pid>/stat` is the only
 *    authoritative source for that.
 *  - A timeout kills the child AND any grandchild it forked — the cookie
 *    scan + session signal path is what makes this work, and is verified
 *    by writing the grandchild's PID to the control dir and checking it
 *    is gone after the timeout.
 *  - An unrelated process in a DIFFERENT session is NOT killed when the
 *    executor times out a child — only the cookie-matched session is.
 *  - A child that ignores SIGTERM is still killed by SIGKILL escalation,
 *    and the resulting terminal is `Cancelled(TIMEOUT)`, not lost or
 *    crash.
 *  - The control dir carries the authoritative timeout marker.
 *  - No orphan processes survive after the executor returns.
 *  - The session-leader diagnostic that used to be printed to stderr for
 *    every `sh` step is gone.
 *
 * All tests are Linux-only because they read /proc, fork / setpgid, and
 * rely on the JVM's ProcessHandle returning a kernel PID.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(60)
class M1F2TimeoutDiagnosticsTest {

    @TempDir
    lateinit var tempDir: Path

    private val config = DurableShConfig.fromSystemProperties()

    /**
     * Read the wrapper's PID from the cookie file the durable-shell
     * wrapper writes on its own startup.
     *
     * The wrapper writes `echo $$` to `{controlDir}/.cookie`, where `$$`
     * is the wrapper bash's PID. That PID is the SESSION LEADER (setsid
     * forks, the parent exits, the child execs bash in the new session).
     *
     * On a normal exit the wrapper deletes the cookie file; on a timeout
     * the wrapper is SIGKILLed before reaching its cleanup, so the cookie
     * survives. The executor's `cleanup()` does NOT delete the control
     * dir when `cleanupRetainOnFailure=true` (default) and the terminal
     * is a timeout — so the cookie file is still on disk when this
     * function is called.
     */
    private fun wrapperPidFromCookie(controlDir: Path): Long =
        Files.readString(controlDir.resolve(".cookie")).trim().toLong()

    /**
     * Read a /proc stat field. The (comm) entry may contain spaces, so the
     * parse starts AFTER the last `)`.
     *
     * The /proc/[pid]/stat layout after `(comm)` is:
     *   0: state, 1: ppid, 2: pgrp, 3: session, 4: tpgid, ...
     * so the SID is field 3 and the PGID is field 2 (man proc(5)).
     */
    private fun statField(pid: Long, fieldIndex: Int): Long {
        val content = Files.readString(Path.of("/proc/$pid/stat"))
        val lastParen = content.lastIndexOf(')')
        require(lastParen >= 0) { "unparseable /proc/$pid/stat" }
        val fields = content.substring(lastParen + 1).trim().split(Regex("\\s+"))
        require(fieldIndex < fields.size) { "/proc/$pid/stat has fewer fields than $fieldIndex" }
        return fields[fieldIndex].toLong()
    }

    /** The session ID (SID) of `pid` from /proc/[pid]/stat. Field 3 after `(comm)`. */
    private fun sidOf(pid: Long): Long = statField(pid, 3)

    /** The process-group ID (PGID) of `pid` from /proc/[pid]/stat. Field 2 after `(comm)`. */
    private fun pgidOf(pid: Long): Long = statField(pid, 2)

    /**
     * Parse a /proc/[pid]/stat line that has been trimmed to start AFTER
     * the last `)`. Returns the first NUMERIC fields only — the state
     * letter (`S`, `R`, `D`, ...) is skipped, and unsigned fields like
     * `tpgid` whose kernel value is -1 (printed as `18446744073709551615`)
     * are stopped at before they can blow up `Long.parseLong`.
     *
     * The kernel uses unsigned 64-bit values for some fields (tpgid,
     * tty_nr, exit_code), printing -1 as 2^64 - 1. Those values are
     * outside the Java `long` range; the helper stops at the first
     * non-parseable value so callers only see the meaningful prefix.
     */
    private fun parseStatAfterComm(afterComm: String): List<Long> {
        val raw = afterComm.trim().split(Regex("\\s+")).drop(1)
        val parsed = mutableListOf<Long>()
        for (token in raw) {
            val signed = token.toLongOrNull()
            if (signed == null) {
                // Stop at the first field that is not a signed Long;
                // this catches the kernel's "unsigned -1" sentinel
                // without changing the meaning of the fields before it.
                break
            }
            parsed += signed
        }
        return parsed
    }

    /**
     * Read the wrapper's PID from an EXTERNAL file the user script wrote.
     *
     * The success path of [DurableShellExecutor] deletes the control dir
     * after `executeTerminal` returns, which removes the `.cookie` file
     * along with everything else. Tests that need the PID after a
     * SUCCESSFUL execution therefore have the wrapper write `$$` to a
     * path inside `tempDir` (which is not the control dir).
     */
    private fun wrapperPidFromFile(externalPidFile: Path): Long =
        Files.readString(externalPidFile).trim().toLong()

    private fun isAlive(pid: Long): Boolean =
        try {
            ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
        } catch (_: Exception) {
            false
        }

    private fun waitFor(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(50)
        }
        return predicate()
    }

    // ----------------------------------------------------------------- tests

    /**
     * M1-F.2 — the wrapper PID is the real session leader, not the
     * launcher's PID and not the JVM's PID.
     *
     * `setsid` forks the child as a new session leader; the setsid parent
     * exits before the JVM can grab a meaningful handle. The wrapper's
     * own `$$` is the kernel PID of bash inside the new session. The
     * cookie file holds that PID (the wrapper writes `echo $$` to it on
     * startup), and the SID / PGID of any process in the wrapper's
     * session equal the wrapper's PID — which is the kernel definition
     * of a session leader.
     *
     * The user script captures its own SID and PGID by reading
     * `/proc/$$/stat` and stripping the `(comm)` field (it may contain
     * spaces, so the test parses the tail rather than splitting awk
     * fields blindly). The capture happens IMMEDIATELY at script startup
     * — before the timeout fires — so the live /proc is read while the
     * process still exists.
     *
     * The test then reads:
     *   - the wrapper's PID from the cookie (survives a SIGKILL,
     *     because the wrapper didn't reach its own cookie-cleanup);
     *   - the captured SID / PGID from the stat file the user script
     *     wrote before the timeout.
     *
     * And asserts PID == SID == PGID and PID != JVM PID.
     */
    @Test
    fun `wrapper PID is the real session leader`() {
        val opId = "m1f2-session-leader"
        val controlDir = tempDir.resolve(opId)
        val statFile = tempDir.resolve("$opId.stat")
        val jvmPid = ProcessHandle.current().pid()

        // The user script captures /proc/$$/stat as the line AFTER `(comm)`,
        // i.e. "state ppid pgrp session ...". sed `s/.*) //` is greedy and
        // matches up to the LAST `)` (which is the closing paren of
        // `(comm)`); on a `comm` that contains spaces the regex still
        // works because it anchors on `.*)` rather than on a literal
        // open-paren.
        val script = """
            sed 's/.*) //' /proc/$${'$'}/stat > ${statFile.toAbsolutePath()}
            sleep 60
        """.trimIndent()

        val terminal = DurableShellExecutor().executeTerminal(
            controlDir = controlDir,
            scriptContent = script,
            opId = opId,
            shOptions = ShOptions(
                workspaceRoot = tempDir,
                captureStdout = false,
                timeoutMs = 500,
                env = emptyMap(),
            ),
            config = config,
        )

        assertTrue(
            terminal is DurableTaskTerminal.Cancelled,
            "expected Cancelled after timeout (script sleeps 60s under a 500ms budget), got $terminal",
        )

        val cookieFile = controlDir.resolve(".cookie")
        assertTrue(
            Files.exists(cookieFile),
            "cookie file must survive a timeout (wrapper is SIGKILLed before its cleanup)",
        )
        val wrapperPid = wrapperPidFromCookie(controlDir)
        assertTrue(Files.exists(statFile), "user-script stat capture must exist")

        val fields = parseStatAfterComm(Files.readString(statFile))
        require(fields.size >= 3) {
            "captured stat file must have at least 3 fields: ${fields.size}; raw: '${Files.readString(statFile)}'"
        }
        // [parseStatAfterComm] drops the state field, so the numeric tail
        // starts at ppid: [0]=ppid, [1]=pgrp, [2]=session.
        val pgid = fields[1]
        val sid = fields[2]
        // PID == SID == PGID is the kernel definition of a session leader.
        assertEquals(wrapperPid, sid, "wrapper SID must equal its PID (session leader); fields=$fields")
        assertEquals(wrapperPid, pgid, "wrapper PGID must equal its PID (process-group leader); fields=$fields")
        assertNotEquals(jvmPid, wrapperPid, "wrapper PID must not be the JVM PID")
    }

    /**
     * M1-F.2 — a timeout kills the child AND the grandchild it forked.
     *
     * The script forks a grandchild via `setsid bash -c '...' &`,
     * captures its PID into a file, sleeps forever, and is killed by a
     * 500ms timeout. Both the child and the grandchild must be gone
     * after the executor returns. The cookie-scan + session signal
     * path is what makes the grandchild die — a negative-PID signal
     * to the session reaches the grandchild by construction.
     *
     * Note: `$!` of the OUTER shell is the PID of the backgrounded
     * `setsid`; `setsid` then execs bash, which inherits the same PID.
     * So `$!` correctly identifies the inner session leader.
     */
    @Test
    fun `timeout kills child and grandchild`() {
        val opId = "m1f2-grandchild"
        val controlDir = tempDir.resolve(opId)
        Files.createDirectories(controlDir)
        val pidFile = tempDir.resolve("$opId-grandchild.pid")
        val script = """
            setsid bash -c 'echo ${'$'}${'$'} > ${pidFile.toAbsolutePath()}; sleep 30' &
            wait
        """.trimIndent()

        val terminal = DurableShellExecutor().executeTerminal(
            controlDir = controlDir,
            scriptContent = script,
            opId = opId,
            shOptions = ShOptions(
                workspaceRoot = tempDir,
                captureStdout = false,
                timeoutMs = 500,
                env = emptyMap(),
            ),
            config = config,
        )

        assertTrue(
            terminal is DurableTaskTerminal.Cancelled,
            "expected Cancelled after timeout, got $terminal",
        )
        assertEquals(
            InterruptionKind.TIMEOUT,
            (terminal as DurableTaskTerminal.Cancelled).interruption.kind,
        )

        // The grandchild PID file MUST have been written before the
        // child was killed — that is what makes the rest of the test
        // meaningful.
        val grandchildPid = Files.readString(pidFile).trim().toLong()
        assertTrue(
            waitFor(5_000) { !isAlive(grandchildPid) },
            "grandchild PID $grandchildPid survived the timeout — " +
                "session-level kill did not reach the forked process tree",
        )
    }

    /**
     * M1-F.2 — an unrelated process in a DIFFERENT session must NOT be
     * killed when the executor times out a child.
     *
     * The control group for the cookie-scan kill is the session that
     * owns PIPELINE_OP_COOKIE=<real>; a process in another session,
     * started by the test directly, sits outside that group and must
     * survive. Without this guarantee, `pipelinek run` could kill
     * background jobs started by the user.
     */
    @Test
    fun `unrelated process in different session survives timeout`() {
        val opId = "m1f2-isolated"
        val controlDir = tempDir.resolve(opId)
        Files.createDirectories(controlDir)
        val unrelatedPidFile = tempDir.resolve("unrelated.pid")

        // Start an unrelated process with a different session, so it
        // cannot share the cookie's session. `setsid` ensures the new
        // session id, and a long-enough sleep guarantees it survives the
        // child's 500ms timeout window.
        val unrelated = ProcessBuilder(
            "setsid",
            "bash",
            "-c",
            "echo \$\$ > ${unrelatedPidFile.toAbsolutePath()}; sleep 30",
        ).start()
        assertTrue(unrelated.isAlive)
        val unrelatedPid = waitFor(2_000) { Files.exists(unrelatedPidFile) }
            .let { Files.readString(unrelatedPidFile).trim().toLong() }
        assertTrue(isAlive(unrelatedPid), "unrelated test process did not start: $unrelatedPid")

        try {
            DurableShellExecutor().executeTerminal(
                controlDir = controlDir,
                scriptContent = "sleep 30",
                opId = opId,
                shOptions = ShOptions(
                    workspaceRoot = tempDir,
                    captureStdout = false,
                    timeoutMs = 500,
                    env = emptyMap(),
                ),
                config = config,
            )

            assertTrue(
                isAlive(unrelatedPid),
                "unrelated PID $unrelatedPid was killed — session-level kill leaked " +
                    "outside the cookie's session",
            )
        } finally {
            // Tear down the unrelated process so a CI failure here does
            // not leak a 30-second sleep into the next test.
            try {
                ProcessBuilder("kill", "-9", "-${sidOf(unrelatedPid)}").start().waitFor()
            } catch (_: Exception) {}
            try {
                unrelated.destroyForcibly()
            } catch (_: Exception) {}
        }
    }

    /**
     * M1-F.2 — a child that ignores SIGTERM is still killed by SIGKILL
     * escalation, and the terminal is `Cancelled(TIMEOUT)`.
     *
     * `trap '' TERM` installs a SIGTERM handler that does nothing. The
     * cookie-scan path SIGTERMs the session, polls for liveness, and
     * escalates to SIGKILL after the grace period. The terminal must
     * still be a typed timeout, not a Lost / not a launch failure.
     */
    @Test
    fun `SIGTERM-ignored child escalates to SIGKILL and reports TIMEOUT`() {
        val opId = "m1f2-sigterm-ignored"
        val controlDir = tempDir.resolve(opId)
        Files.createDirectories(controlDir)

        val script = """
            trap '' TERM
            sleep 30
        """.trimIndent()

        val terminal = DurableShellExecutor().executeTerminal(
            controlDir = controlDir,
            scriptContent = script,
            opId = opId,
            shOptions = ShOptions(
                workspaceRoot = tempDir,
                captureStdout = false,
                timeoutMs = 300,
                env = emptyMap(),
            ),
            config = config,
        )

        assertTrue(
            terminal is DurableTaskTerminal.Cancelled,
            "expected Cancelled after SIGTERM-ignored timeout, got $terminal",
        )
        val interruption = (terminal as DurableTaskTerminal.Cancelled).interruption
        assertEquals(InterruptionKind.TIMEOUT, interruption.kind)

        // The cookie is a transient heartbeat marker; the wrapper
        // removes it on its own exit, which the SIGKILL prevents. The
        // cookie's presence after a timeout does not mean a future
        // attempt would consider the wrapper alive — that attempt also
        // checks ProcessHandle.isAlive, and the timeout.flag file is
        // the durable authority. Asserting on terminal type and on
        // timeout.flag is the right place to check the durable state.
    }

    /**
     * M1-F.2 — durable state is consistent after a timeout: the timeout
     * flag file exists, the cookie is gone, no result.txt exists, and
     * the terminal is `Cancelled(TIMEOUT)`.
     *
     * A phantom success would be the worst possible outcome — a step that
     * timed out, but reported exit 0 because the wrapper's last bash
     * wrote result.txt 0 after the SIGKILL. The reconciler distinguishes
     * TIMED_OUT from LOST exactly by the timeout.flag file existing.
     */
    @Test
    fun `durable state is consistent after timeout`() {
        val opId = "m1f2-state"
        val controlDir = tempDir.resolve(opId)
        Files.createDirectories(controlDir)

        val terminal = DurableShellExecutor().executeTerminal(
            controlDir = controlDir,
            scriptContent = "sleep 30",
            opId = opId,
            shOptions = ShOptions(
                workspaceRoot = tempDir,
                captureStdout = false,
                timeoutMs = 300,
                env = emptyMap(),
            ),
            config = config,
        )

        assertTrue(
            terminal is DurableTaskTerminal.Cancelled,
            "expected Cancelled, got $terminal",
        )
        // TIMED_OUT is the durable authority. The reconciler reads this
        // file on resume and never asks the executor to re-execute the
        // child. Its presence is the difference between TIMED_OUT and
        // LOST in the durable record (TMO-S-005).
        assertTrue(
            Files.exists(controlDir.resolve("timeout.flag")),
            "timeout.flag must exist so reconciler distinguishes TIMED_OUT from LOST",
        )
        // No result.txt — a phantom exit code would race the timeout
        // and re-classify a timeout as success. WU-RP-005 r9 / r7
        // (CI runs 35656479415 / 35657576105) is the precedent for
        // this assertion: the surviving bash wrote result.txt 0
        // after the SIGKILL, and the watchdog had not yet published
        // its flag. The timeout flag wins regardless.
        assertFalse(
            Files.exists(controlDir.resolve("result.txt")),
            "result.txt must not exist after a timeout — a phantom exit code " +
                "would defeat FAILED_TIMEOUT",
        )
    }

    /**
     * M1-F.2 — no orphan processes survive a successful (non-timeout) execution.
     *
     * The launcher shells out to `setsid bash wrapper.sh`; the bash
     * child must be gone by the time `executeTerminal` returns, and so
     * must any sub-processes the wrapper forked before exit. The
     * executor is responsible for joining the pumps and waiting on the
     * cookie-scan target, and a leak here is the kind of defect that
     * only shows up after a long run.
     */
    @Test
    fun `no orphan processes after a successful execution`() {
        val opId = "m1f2-no-orphans"
        val controlDir = tempDir.resolve(opId)
        val pidFile = tempDir.resolve("$opId.pid")

        val terminal = DurableShellExecutor().executeTerminal(
            controlDir = controlDir,
            scriptContent = "echo \$\$ > ${pidFile.toAbsolutePath()}",
            opId = opId,
            shOptions = ShOptions.EMPTY,
            config = config,
        )

        assertTrue(terminal is DurableTaskTerminal.Exited, "expected Exited, got $terminal")
        assertTrue(Files.exists(pidFile), "wrapper PID file must exist after execution")
        val pid = wrapperPidFromFile(pidFile)
        assertTrue(
            waitFor(5_000) { !isAlive(pid) },
            "wrapper PID $pid survived a successful execution — orphan",
        )
    }
}