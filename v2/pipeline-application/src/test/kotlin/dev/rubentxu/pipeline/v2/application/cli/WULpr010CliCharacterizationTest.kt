package dev.rubentxu.pipeline.v2.application.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * WU-LPR-010 — installed-CLI characterization (real distribution).
 *
 * The WU brief was to MEASURE the existing CLI, not redesign it. This test
 * runs the binary built by `:pipeline-application:installDist` and asserts
 * the **current observed behaviour** so the next WU (likely WU-LPR-011
 * "CLI gap closure") can decide what to fix and what to preserve.
 *
 * Each property is annotated with one of:
 *  - EXISTS  — the behaviour is implemented and exercised
 *  - PARTIAL — the behaviour is partly implemented
 *  - MISSING — the contract claims it but the implementation does not
 *  - BROKEN  — the implementation contradicts the contract
 *
 * The characterization does NOT propose a fix; it locks in what is real
 * so future fixes can be measured against the same baseline.
 *
 * ## Locked-in characterization (HEAD = c01a54a9)
 *
 * Commands:
 *  - `pipeline validate <script>`  EXISTS  — compiles only, no run.
 *  - `pipeline run <script>`       EXISTS  — compile + run + journal.
 *  - `pipeline version`            PARTIAL — prints usage; not a real command.
 *  - `pipeline doctor`             PARTIAL — prints usage; not a real command.
 *  - `pipeline events`             MISSING — not implemented.
 *  - `pipeline credentials`        MISSING — not implemented.
 *
 * Exit codes:
 *  - 0 = pipeline succeeded
 *  - 1 = invocation failure / pipeline failure / validation failure
 *  - 2 = admission / compile failure (only inside the run path)
 *
 * Known inconsistencies (filed for WU-LPR-011):
 *  - `validate` exits 1 on compile failure, NOT 2 (the contract claims 2 for
 *    compile failures).
 *  - `--resume` with no prior run throws IllegalArgumentException
 *    uncaught (uncaught exceptions surface as exit 1, not 2).
 *  - `version` and `doctor` are not commands; they print usage and exit 0
 *    even though the user invoked a "command" they expect to exist.
 *
 * Each test below records the REAL exit code / output observed at the time
 * the test ran. If a future change makes the test fail, the fix must be
 * INTENTIONALLY (i.e. close the gap) and the receipt must explain why.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class WULpr010CliCharacterizationTest {

    private val binary: File by lazy {
        // Locate the installDist output. The test only runs if the binary
        // exists; otherwise it self-skips to keep the architecture test
        // suite green when the binary has not been built.
        val path = WULpr010ScannerSupport.v2Root()
            .resolve("pipeline-application/build/install/pipeline-application/bin/pipeline-application")
        require(path.toFile().exists()) {
            "installDist output not found at $path; run :pipeline-application:installDist first"
        }
        path.toFile()
    }

    private fun run(vararg args: String): ProcessResult {
        val pb = ProcessBuilder(binary.absolutePath, *args)
            .redirectErrorStream(true)
        val proc = pb.start()
        val finished = proc.waitFor(60, TimeUnit.SECONDS)
        require(finished) { "binary hung on ${args.toList()}" }
        return ProcessResult(
            exitCode = proc.exitValue(),
            output = proc.inputStream.bufferedReader().readText(),
        )
    }

    private fun writePipeline(content: String): File {
        val file = Files.createTempFile("lpr010-", ".pipeline.kts").toFile()
        file.writeText(content)
        file.deleteOnExit()
        return file
    }

    // ---- pipeline success / failure ---------------------------------------------

    @Test
    fun `EXISTS — pipeline run with a passing pipeline exits 0`() {
        val script = writePipeline(
            """
            pipeline {
                stages {
                    stage("hello") {
                        echo("hi")
                    }
                }
            }
            """.trimIndent(),
        )
        val dbDir = Files.createTempDirectory("lpr010-db-").toFile()
        val ctlDir = Files.createTempDirectory("lpr010-ctl-").toFile()
        try {
            val r = run(
                "run",
                "--db", File(dbDir, "db.sqlite").absolutePath,
                "--control-root", ctlDir.absolutePath,
                script.absolutePath,
            )
            assertEquals(0, r.exitCode, "passing pipeline must exit 0; output:\n${r.output.takeLast(500)}")
            assertTrue(
                r.output.contains("RunFinished") && r.output.contains("\"outcome\":\"success\""),
                "passing pipeline must emit RunFinished with outcome=success; last 500 chars:\n${r.output.takeLast(500)}",
            )
        } finally {
            dbDir.deleteRecursively()
            ctlDir.deleteRecursively()
            script.delete()
        }
    }

    @Test
    fun `EXISTS — pipeline run with sh exit non-zero exits 1 (pipeline failure)`() {
        val script = writePipeline(
            """
            pipeline {
                stages {
                    stage("hi") {
                        sh("exit 7")
                    }
                }
            }
            """.trimIndent(),
        )
        val dbDir = Files.createTempDirectory("lpr010-db-").toFile()
        val ctlDir = Files.createTempDirectory("lpr010-ctl-").toFile()
        try {
            val r = run(
                "run",
                "--db", File(dbDir, "db.sqlite").absolutePath,
                "--control-root", ctlDir.absolutePath,
                script.absolutePath,
            )
            assertEquals(1, r.exitCode, "pipeline failure must exit 1; output:\n${r.output.takeLast(500)}")
            assertTrue(
                r.output.contains("Pipeline finished with FAILURE"),
                "pipeline failure must print the FAILURE marker",
            )
        } finally {
            dbDir.deleteRecursively()
            ctlDir.deleteRecursively()
            script.delete()
        }
    }

    // ---- validate command -------------------------------------------------------

    @Test
    fun `EXISTS — validate compiles the script and exits 0 on success`() {
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
        try {
            val r = run("validate", script.absolutePath)
            assertEquals(0, r.exitCode, "validate success must exit 0; output:\n${r.output.takeLast(500)}")
            assertTrue(
                r.output.contains("VALIDATION SUCCESSFUL"),
                "validate success must print VALIDATION SUCCESSFUL",
            )
            assertTrue(
                r.output.contains("CompilationFinished"),
                "validate success must emit CompilationFinished event",
            )
        } finally {
            script.delete()
        }
    }

    @Test
    fun `FIXED (WU-LPR-011 F3) — validate with malformed Kotlin exits 2 (invocation-compile error)`() {
        // WU-LPR-011 F3: the exit-code contract is canonical —
        // 0 = success, 1 = pipeline failure, 2 = invocation/compile/admission.
        // Compile failure is an admission error, not a pipeline failure.
        val script = writePipeline("not kotlin syntax {{{")
        try {
            val r = run("validate", script.absolutePath)
            assertEquals(
                2,
                r.exitCode,
                "validate compile-failure must exit 2 per the canonical exit-code contract; output:\n${r.output.takeLast(500)}",
            )
            assertTrue(
                r.output.contains("VALIDATION FAILED"),
                "validate compile-failure must print VALIDATION FAILED",
            )
        } finally {
            script.delete()
        }
    }

    // ---- missing-script handling ------------------------------------------------

    @Test
    fun `EXISTS — validate with missing script exits 1 (invocation error)`() {
        val r = run("validate", "/tmp/lpr010-no-such-${System.nanoTime()}.pipeline.kts")
        assertEquals(1, r.exitCode, "missing script must exit 1; output:\n${r.output.takeLast(500)}")
    }

    // ---- unknown subcommand -----------------------------------------------------

    @Test
    fun `EXISTS — unknown subcommand prints usage and exits 1 (invocation error)`() {
        val r = run("nonexistent-cmd")
        assertEquals(1, r.exitCode, "unknown subcommand must exit 1; output:\n${r.output.takeLast(500)}")
        assertTrue(
            r.output.contains("Usage:"),
            "unknown subcommand must print usage",
        )
    }

    @Test
    fun `FIXED (WU-LPR-011 F1) — version and doctor are real subcommands exiting 0`() {
        // WU-LPR-011 F1: `version` reports the CLI version; `doctor` reports
        // local runtime health. Both are real subcommands with exit 0.
        val versionResult = run("version")
        assertEquals(0, versionResult.exitCode, "version must exit 0; output:\n${versionResult.output.takeLast(200)}")
        assertTrue(
            versionResult.output.contains("pipeline "),
            "version must print 'pipeline <version>'; got:\n${versionResult.output}",
        )

        val doctorResult = run("doctor")
        assertEquals(0, doctorResult.exitCode, "doctor must exit 0 on a healthy local runtime; output:\n${doctorResult.output.takeLast(300)}")
        assertTrue(
            doctorResult.output.contains("jdk:") && doctorResult.output.contains("workdir:"),
            "doctor must report jdk and workdir checks; got:\n${doctorResult.output}",
        )
    }

    // ---- resume / rerun ---------------------------------------------------------

    @Test
    fun `FIXED (WU-LPR-011 F5) — rerun (fresh) emits one RunFinished and --resume on a terminal run emits ZERO new run-lifecycle events`() {
        // WU-LPR-011 F5 (the critical finding): resume of a TERMINAL run must
        // return the same durable result WITHOUT re-executing handlers and
        // WITHOUT emitting a second run lifecycle envelope. The durable event
        // history is the run-lifecycle authority: one RunStarted..RunFinished
        // cycle per runId, ever.
        //
        // Note: the resume stdout still prints the JOURNALED history (the
        // CLI prints eventsFor(runId)) plus the host compile bookend events
        // for the re-compiled script; none of those are NEW lifecycle events.
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
        val dbDir = Files.createTempDirectory("lpr010-rerun-db-").toFile()
        val ctlDir = Files.createTempDirectory("lpr010-rerun-ctl-").toFile()
        try {
            val db = File(dbDir, "db.sqlite").absolutePath
            val ctl = ctlDir.absolutePath

            val first = run("run", "--db", db, "--control-root", ctl, script.absolutePath)
            assertEquals(0, first.exitCode, "first run must succeed")
            assertEquals(
                1,
                first.output.split("\"kind\":\"RunFinished\"").size - 1,
                "first run must emit exactly one RunFinished event",
            )

            // Resume of a terminal run: exactly ONE RunFinished (the journaled
            // one, reprinted via eventsFor), NO second lifecycle envelope.
            val second = run("run", "--db", db, "--control-root", ctl, "--resume", script.absolutePath)
            assertEquals(0, second.exitCode, "resume of a terminal run must succeed; output:\n${second.output.takeLast(500)}")
            assertEquals(
                1,
                second.output.split("\"kind\":\"RunFinished\"").size - 1,
                "resume stdout must contain exactly ONE RunFinished (the journaled one); " +
                    "two means the run lifecycle was re-emitted",
            )
            assertEquals(
                1,
                second.output.split("\"kind\":\"StepStarted\"").size - 1,
                "resume stdout must contain exactly ONE StepStarted (the journaled one); " +
                    "two means the handler re-executed",
            )
            assertEquals(
                1,
                second.output.split("\"kind\":\"EchoOutputCaptured\"").size - 1,
                "resume stdout must contain exactly ONE EchoOutputCaptured (the journaled one)",
            )
        } finally {
            dbDir.deleteRecursively()
            ctlDir.deleteRecursively()
            script.delete()
        }
    }

    @Test
    fun `FIXED (WU-LPR-011 F4) — resume with no prior run is a typed rejection with exit 2 and no stack trace`() {
        // WU-LPR-011 F4: --resume without a prior durable run is an
        // invocation/admission error. Typed message, no stack trace, exit 2
        // per the canonical exit-code contract.
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
        val dbDir = Files.createTempDirectory("lpr010-bare-resume-db-").toFile()
        val ctlDir = Files.createTempDirectory("lpr010-bare-resume-ctl-").toFile()
        try {
            val r = run(
                "run",
                "--db", File(dbDir, "db.sqlite").absolutePath,
                "--control-root", ctlDir.absolutePath,
                "--resume",
                script.absolutePath,
            )
            assertEquals(
                2,
                r.exitCode,
                "bare --resume must exit 2 (invocation/admission error); output:\n${r.output.takeLast(500)}",
            )
            assertTrue(
                r.output.contains("No prior run recorded"),
                "bare --resume must surface the typed error message; got exit=${r.exitCode}",
            )
            assertTrue(
                !r.output.contains("IllegalArgumentException") && !r.output.contains("\tat "),
                "bare --resume must NOT print a stack trace (typed rejection); output:\n${r.output.takeLast(500)}",
            )
        } finally {
            dbDir.deleteRecursively()
            ctlDir.deleteRecursively()
            script.delete()
        }
    }

    // ---- structural invariants --------------------------------------------------

    @Test
    fun `EXISTS — usage message names validate and run as the two real commands`() {
        val r = run()  // no args → usage
        assertTrue(
            r.output.contains("validate") && r.output.contains("run"),
            "usage must list validate and run; got:\n${r.output}",
        )
        assertTrue(
            r.output.contains("--db") && r.output.contains("--control-root"),
            "usage must document --db and --control-root; got:\n${r.output}",
        )
    }

    @Test
    fun `EXISTS — installDist binary exists and is executable`() {
        assertTrue(binary.exists(), "installDist binary must exist at ${binary.absolutePath}")
        assertTrue(binary.canExecute(), "installDist binary must be executable")
        assertNotNull(binary.absolutePath)
    }

    private data class ProcessResult(
        val exitCode: Int,
        val output: String,
    )
}

/**
 * Tiny scanner helper so the test does not depend on the
 * `pipeline-architecture-tests` gradle test source set.
 *
 * Resolution: `v2/` lives one level above the module CWD (which is the
 * `v2/pipeline-application/` directory under `:pipeline-application:test`).
 * If that resolution fails (e.g. the test is run from an unexpected
 * CWD), we fall back to the `-Pv2.root` system property or the
 * `V2_ROOT` environment variable.
 */
internal object WULpr010ScannerSupport {
    fun v2Root(): java.nio.file.Path {
        val cwd = java.nio.file.Paths.get(System.getProperty("user.dir"))
        // Heuristic: the gradle test JVM CWD for :pipeline-application:test is
        // v2/pipeline-application; the v2 module root is its parent.
        val candidate = cwd.parent
        if (candidate != null && candidate.fileName?.toString() == "v2") {
            return candidate
        }
        val prop = System.getProperty("v2.root") ?: System.getenv("V2_ROOT")
        if (prop != null) return java.nio.file.Paths.get(prop)
        // Last resort: walk up looking for the directory that contains
        // `pipeline-application/`.
        var p = cwd.toAbsolutePath()
        while (p.parent != null) {
            val test = p.resolve("pipeline-application")
            if (test.toFile().isDirectory) return p
            p = p.parent
        }
        error("could not resolve v2 root from cwd=$cwd; pass -Pv2.root=/abs/path/to/v2")
    }
}
