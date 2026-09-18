package dev.rubentxu.pipeline.v2.application.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * WU-LPR-011 — installed-distribution UAT for the `--resume` run lifecycle.
 *
 * Canonical contract (canonical durable contract / ADR-0074 lineage):
 *   run (fresh)            -> exit 0, exactly one RunStarted..RunFinished cycle
 *   run --resume (terminal)-> exit 0, aggregate reused: journaled history +
 *                            SKIP-path lifecycle bookends re-emitted (pinned
 *                            canonical contract), ZERO child re-execution
 *                             events, ZERO re-executed handler effects
 *   run --resume (no prior)-> typed rejection, exit 2, no stack trace
 *
 * Plus a regression canary for the WU-LPR-042 single-writer hang: a
 * successful durable run MUST terminate the JVM on its own (the
 * non-daemon sqlite-event-writer requires an explicit store close()).
 *
 * Runs against the real installDist binary (HF2), like WU-LPR-010.
 */
@Timeout(10, unit = TimeUnit.MINUTES)
class WULpr011ResumeLifecycleUatTest {

    private val binary: File = WULpr010ScannerSupport.v2Root()
        .resolve("pipeline-application/build/install/pipeline-application/bin/pipeline-application")
        .toFile()

    private fun run(vararg args: String): CliResult {
        val pb = ProcessBuilder(binary.absolutePath, *args)
            .redirectErrorStream(true)
        val proc = pb.start()
        val finished = proc.waitFor(5, TimeUnit.MINUTES)
        assertTrue(finished) { "binary hung on ${args.toList()} (WU-LPR-042 writer-hang regression?)" }
        return CliResult(proc.exitValue(), proc.inputStream.bufferedReader().readText())
    }

    private fun writePipeline(content: String): File {
        val file = Files.createTempFile("lpr011-", ".pipeline.kts").toFile()
        file.writeText(content)
        file.deleteOnExit()
        return file
    }

    @Test
    fun `resume lifecycle — fresh run, terminal resume, and bare resume follow the canonical contract`() {
        assertTrue(binary.exists(), "installDist binary must exist; run :pipeline-application:installDist first")
        val script = writePipeline(
            """
            pipeline {
                stages {
                    stage("hi") {
                        echo("hi")
                    }
                }
            }
            """.trimIndent(),
        )
        val dbDir = Files.createTempDirectory("lpr011-db-").toFile()
        val ctlDir = Files.createTempDirectory("lpr011-ctl-").toFile()
        try {
            val db = File(dbDir, "db.sqlite").absolutePath
            val ctl = ctlDir.absolutePath

            // 1. Fresh run: one lifecycle cycle, exit 0.
            val first = run("run", "--db", db, "--control-root", ctl, script.absolutePath)
            assertEquals(0, first.exitCode, "fresh run must succeed; output:\n${first.output.takeLast(400)}")
            assertEquals(1, first.output.countOf("\"kind\":\"RunStarted\""), "fresh run: one RunStarted")
            assertEquals(1, first.output.countOf("\"kind\":\"RunFinished\""), "fresh run: one RunFinished")

            // 2. Resume of the terminal run (WU-LPR-011 F5 closed WONTFIX):
            //    the durable contract (pinned by CanonicalDurableRunCoordinatorTest
            //    and UatDsl003ParallelTest.P6) RE-EMITS the SKIP-path lifecycle
            //    bookends for a reused terminal aggregate. The reuse guarantees
            //    that matter: exit 0 and ZERO child re-execution — the stdout
            //    carries exactly ONE journaled StepStarted/EchoOutputCaptured
            //    (the journaled copy plus the SKIP bookends never re-run the
            //    handler). stdout counts include journaled + fresh bookends,
            //    so assert only the child-execution invariants here.
            val second = run("run", "--db", db, "--control-root", ctl, "--resume", script.absolutePath)
            assertEquals(0, second.exitCode, "terminal resume must succeed; output:\n${second.output.takeLast(400)}")
            assertEquals(1, second.output.countOf("\"kind\":\"StepStarted\""), "terminal resume: handler must not re-execute")
            assertEquals(1, second.output.countOf("\"kind\":\"EchoOutputCaptured\""), "terminal resume: journaled echo only")
            assertTrue(
                second.output.contains("\"outcome\":\"success\""),
                "terminal resume must reuse the recorded success outcome",
            )

            // 3. Bare resume on an empty database: typed rejection, exit 2,
            //    no stack trace.
            val emptyDb = File(dbDir, "empty.sqlite").absolutePath
            val emptyCtl = Files.createTempDirectory("lpr011-ctl-empty-").toFile().absolutePath
            val bare = run("run", "--db", emptyDb, "--control-root", emptyCtl, "--resume", script.absolutePath)
            assertEquals(2, bare.exitCode, "bare resume must exit 2; output:\n${bare.output.takeLast(400)}")
            assertTrue(bare.output.contains("No prior run recorded"), "bare resume must give the typed message")
            assertTrue(
                !bare.output.contains("\tat ") && !bare.output.contains("IllegalArgumentException"),
                "bare resume must not leak a stack trace; output:\n${bare.output.takeLast(400)}",
            )
        } finally {
            dbDir.deleteRecursively()
            ctlDir.deleteRecursively()
            script.delete()
        }
    }

    private fun String.countOf(needle: String): Int = split(needle).size - 1

    private data class CliResult(val exitCode: Int, val output: String)
}
