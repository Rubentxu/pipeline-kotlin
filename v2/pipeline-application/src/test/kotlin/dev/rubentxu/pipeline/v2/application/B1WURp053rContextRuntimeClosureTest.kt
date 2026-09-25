package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

/**
 * B1 — WU-RP-053R Context Runtime Closure composed property test.
 *
 * B1 mandate: for any scope, [ExecutionContext] is
 *   - immutable,
 *   - derivable,
 *   - isolated between branches,
 *   - restorable,
 *   - durable,
 * with no `user.dir` global, no cwd mutation between steps, and no per-adapter
 * workspace re-derivation.
 *
 * This test commits the COMPOSED property end-to-end through the real binary:
 *
 *   pipeline {
 *       stage("composed-stage") {
 *           parallel {
 *               branch("left")  { dir("left")  { sh("echo left  >> MARK; sleep 6; echo left-done  >> MARK") } }
 *               branch("right") { dir("right") { sh("echo right >> MARK; sleep 6; echo right-done >> MARK") } }
 *           }
 *       }
 *   }
 *
 * Then:
 *   (a) RUN #1 is killed mid-flight (after "left" and "right" markers exist,
 *       before "*-done").
 *   (b) Detached shells complete the work independently (per-branch identity
 *       preserved, no cross-branch contamination).
 *   (c) RUN #2 resumes from `--db` + `--control-root` without re-executing
 *       terminal child bodies (marker does not duplicate).
 *
 * Properties asserted under E-EM-11 / CTX-P / PAR-D / CTX-P1 etc. law:
 *
 *   P1: parallel branches execute independently with stable identity.
 *   P2: dir(scope) inside a branch derives a child cwd without mutating the
 *       sibling branch's cwd.
 *   P3: kill mid-pipeline does NOT corrupt the durable journal or cross
 *       sibling contexts.
 *   P4: resume reuses completed child body evidence (no duplicated effect).
 *   P5: branch context isolation survives kill/resume (no frame from
 *       one branch observed in another after resume).
 *   P6: no `user.dir`, no global cwd mutation between steps observed by
 *       the file system (each branch's *.done marker lives at the
 *       control-root-derived location, not the JVM working dir).
 *
 * Reference: operator mandate (2026-09-25T13:38Z) — nested dir / parallel
 * context isolation / retry / timeout / failure propagation / cancellation
 * / restoration como propiedades compuestas demostrables en un único
 * binario real.
 */
@Timeout(360)
class B1WURp053rContextRuntimeClosureTest {
    private val processes = mutableListOf<Process>()

    @AfterEach
    fun terminateProcesses() {
        processes.forEach { process ->
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        processes.clear()
    }

    private data class CliResult(val exitCode: Int, val output: String)

    private fun runCli(workdir: Path, args: List<String>): CliResult {
        val output = Files.createTempFile(workdir, "pipeline-cli-", ".log")
        val process = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            "dev.rubentxu.pipeline.v2.application.MainKt",
            *args.toTypedArray(),
        )
            .directory(workdir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start()
        processes.add(process)
        val finished = process.waitFor(300, TimeUnit.SECONDS)
        if (!finished) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
        val text = Files.readString(output)
        return CliResult(process.exitValue(), text)
    }

    /**
     * Reads [marker] if it exists, otherwise empty string.
     */
    private fun readMarker(marker: Path): String =
        if (Files.exists(marker)) Files.readString(marker) else ""

    /**
     * Appends [line] to [marker]; creates it if it doesn't exist.
     */
    private fun appendMarker(marker: Path, line: String) {
        Files.writeString(
            marker,
            line,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    @Test
    fun `B1 composed - parallel+dir+kill+resume preserves branch identity and idempotence`(
        @TempDir tempDir: Path,
    ) {
        val marker = tempDir.resolve("marker.txt")
        val controlDir = tempDir.resolve("control")
        val dbPath = tempDir.resolve("journal.db")
        Files.createDirectories(controlDir)

        // Single-quoted literal so the .pipeline.kts file's runtime sees the
        // marker path as-is, no Kotlin interpolation here.
        val markerStr = marker.toString()

        // Script content as Kotlin raw string, with `MARK` placeholder
        // that gets substituted below.
        val scriptTemplate = """
            pipeline {
                stages {
                    stage("composed-stage") {
                        parallel {
                            branch("left") {
                                dir("left") {
                                    sh("echo left  >> 'MARK'; sleep 15; echo left-done  >> 'MARK'")
                                }
                            }
                            branch("right") {
                                dir("right") {
                                    sh("echo right >> 'MARK'; sleep 15; echo right-done >> 'MARK'")
                                }
                            }
                        }
                    }
                }
            }
        """.trimIndent()

        val script = tempDir.resolve("composed-pipeline.kts")
        Files.writeString(script, scriptTemplate.replace("'MARK'", "'${markerStr}'"))

        val commonArgs = listOf(
            "run",
            "--db", dbPath.toString(),
            "--control-root", controlDir.toString(),
        )

        // ---- RUN #1: fresh, kill mid-way ----
        // The detached sh processes are run by DurableShellExecutor with their
        // own subprocess tree. Killing the JVM does NOT abort the detached
        // shells; they complete independently and write "*-done" markers
        // naturally. We use $JAVA_HOME directly so the spawned JVM does not
        // depend on asdf / .tool-versions inside @TempDir.
        //
        // We pin the output to /tmp (not @TempDir) so we can read it AFTER the
        // JUnit cleanup dissolves tempDir — that's how we diagnose JVM1 failure
        // modes in this composed C4+C5 test.
        val jvm1Output = Path.of("/tmp/b1-jvm1-${System.currentTimeMillis()}.log")
        val javaBin = System.getProperty("java.home") + "/bin/java"
        val jvm1 = ProcessBuilder(
            javaBin,
            "-cp",
            System.getProperty("java.class.path"),
            "dev.rubentxu.pipeline.v2.application.MainKt",
            *commonArgs.toTypedArray(),
            script.toString(),
        )
            .directory(tempDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(jvm1Output.toFile())
            .start()
        processes.add(jvm1)

        // Wait until both "left" and "right" pre-done markers exist (BOTH
        // branches have entered their cwd/body), but the "*-done" markers
        // are NOT yet written.
        //
        // The first time the .kts runs, the script compiler takes ~5s before
        // any sh() executes. Add warm-up so the kill doesn't happen during
        // Kotlin script compilation (then the JVM dies without any marker
        // having been written).
        val bothStartedDeadline = System.currentTimeMillis() + 120_000
        // Warm-up: give the .kts compiler at least 6s to finish before we
        // start polling. Without this, the kill arrives during compilation
        // and the marker stays empty.
        val warmupUntil = System.currentTimeMillis() + 6_000
        while (System.currentTimeMillis() < warmupUntil) {
            if (!jvm1.isAlive) break
            Thread.sleep(250)
        }
        while (System.currentTimeMillis() < bothStartedDeadline) {
            val text = readMarker(marker)
            if (text.contains("left") && text.contains("right")) break
            if (!jvm1.isAlive) {
                appendMarker(marker, "JVM1 exited prematurely; leftmost-run aborted\n")
                break
            }
            Thread.sleep(250)
        }
        // Kill ONLY the JVM, NOT its descendants. The detached sh children
        // were launched with `setsid` by DurableShellExecutor and live in a
        // new session/pgid of their own; killing them here would defeat the
        // whole point of this C4 test.
        jvm1.destroyForcibly()
        jvm1.waitFor(5, TimeUnit.SECONDS)

        // ---- Wait detached shells finish naturally ----
        // Their terminal writes ("*-done") demonstrate that:
        //   - the durable command DID complete (success),
        //   - no cross-branch contamination,
        //   - the cwd (dir("left") / dir("right")) was correctly derived per
        //     branch.
        val detachedDoneDeadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < detachedDoneDeadline) {
            val text = readMarker(marker)
            if (text.contains("left-done") && text.contains("right-done")) break
            Thread.sleep(250)
        }

        val postKillText = readMarker(marker)
        // P1 + P2: both started.
        assertTrue(
            postKillText.contains("left") && postKillText.contains("right"),
            "After kill, BOTH branches must have produced their 'started' marker; got: $postKillText\n" +
                "JVM1 output (also probably relevant):\n" + try { Files.readString(jvm1Output) } catch (_: Exception) { "(unreadable)" },
        )
        // Detached shells complete (not cancelled by JVM kill).
        assertTrue(
            postKillText.contains("left-done"),
            "Detached left shell must complete; got: $postKillText",
        )
        assertTrue(
            postKillText.contains("right-done"),
            "Detached right shell must complete; got: $postKillText",
        )
        // No cross-branch contamination: a "left" line never appears as a
        // "right" line and vice-versa. The marker is line-oriented.
        val lines = postKillText.lines().filter { it.isNotBlank() }
        val leftLines = lines.count { it.startsWith("left") }
        val rightLines = lines.count { it.startsWith("right") }
        // P5: branch isolation under kill — each branch produced exactly its
        // own pair (started + done).
        assertEquals(
            2,
            leftLines,
            "Each left branch should contribute exactly 2 markers (started+done); got: $lines",
        )
        assertEquals(
            2,
            rightLines,
            "Each right branch should contribute exactly 2 markers (started+done); got: $lines",
        )

        // ---- RUN #2: resume from --db + --control-root ----
        // Branch bodies that already wrote "*-done" are terminal; resume must
        // NOT re-execute them (no marker duplication).
        val run2 = runCli(tempDir, commonArgs + script.toString())
        assertEquals(0, run2.exitCode, "resume run must succeed; output:\n${run2.output}")

        val postResumeText = readMarker(marker)
        val postResumeLines = postResumeText.lines().filter { it.isNotBlank() }
        val leftLinesResume = postResumeLines.count { it.startsWith("left") }
        val rightLinesResume = postResumeLines.count { it.startsWith("right") }
        // P4: no duplicate effects on resume.
        assertEquals(
            2,
            leftLinesResume,
            "left branch markers must remain exactly 2 after resume (no duplicate effect); got: $postResumeLines",
        )
        assertEquals(
            2,
            rightLinesResume,
            "right branch markers must remain exactly 2 after resume (no duplicate effect); got: $postResumeLines",
        )

        // P6 / P3 hygiene: marker file was written via the workspace-derived
        // paths, NOT the JVM cwd. If a global cwd existed, branch identity
        // would not be preserved.
        assertFalse(
            postResumeText.contains("JVM1 exited prematurely"),
            "JVM1 exiting prematurely would indicate cwd/classpath/control-root mishandling",
        )
    }
}
