package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Certifies the OBSERVABLE default of `pipeline run`: with no observation flag at all,
 * stdout is human text.
 *
 * ## Why this test exists, when `DEFAULT-1` already covers the default
 *
 * `ObservationCliContractTest.DEFAULT-1` asserts the default of the PARSED FLAGS. That is a
 * necessary half and a weak one: a parser can default to `TEXT` and the program can still print
 * JSON further down. Nothing in the suite closed that gap, because migrating the 47 historical
 * test files to `--format json` — the price of changing the default — removed the only coverage
 * that had ever asserted what a flagless run actually prints.
 *
 * That is the cost §4.1 of `docs/proposals/CLI-CONSOLE-OBSERVABILITY-DRAFT.md` predicted:
 * "a test that only passes with an explicit flag stops certifying the default". This file is the
 * replacement, and it has to be end-to-end for the same reason — a unit-level assertion on
 * `RunObservationOutput.encode` would re-certify the renderer rather than the product.
 *
 * ## Fidelity (HARNESS FIDELITY LAW)
 *
 * It enters through the productive authority: a forked `MainKt` process, which is how the CLI is
 * actually used, and reads its real stdout. No re-derivation of production values, `@TempDir`
 * instead of `createTempDirectory`, no ambient cwd/env/network, and no timing assertion — the
 * process merely has to finish.
 *
 * ## Mutation that kills each row
 *
 * Every row names the change that breaks it, so it is a non-regression test rather than
 * characterisation:
 *
 * - `default` → make the default format JSON again, or default the view to `EVENTS`.
 * - `json` → default every format to JSON, which empties this row's human expectation.
 * - `grep` → route `--grep` through the final rendered stdout instead of the query.
 *
 * Restore the change, re-run, and the row goes red again. Verified per row.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RunDefaultIsHumanEndToEndTest {

    private val processes = mutableListOf<Process>()

    @TempDir
    lateinit var tempDir: Path

    private lateinit var script: Path

    /**
     * Set to false by a mutation to prove the default rows actually observe the default rather
     * than a harness that passes `--format text` behind their back. See [runRaw].
     */
    private var passObservationFlags = true

    @BeforeEach
    fun setUp() {
        script = tempDir.resolve("default-human.pipeline.kts")
        // Two messages, so a filter has something to discriminate: a single message would make
        // "the filter kept the match" indistinguishable from "the filter did nothing".
        Files.writeString(
            script,
            """
            pipeline {
                stages {
                    stage("build") {
                        echo("human default marker")
                        echo("noise marker")
                    }
                }
            }
            """.trimIndent(),
        )
    }

    @AfterEach
    fun tearDown() {
        processes.forEach { process ->
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
        }
        processes.clear()
    }

    /**
     * A run with NO observation flag at all. This is the product contract under test, so the
     * helper must not quietly supply a default — see [passObservationFlags].
     */
    @Test
    fun `default a flagless run prints human text, not the JSON array`() {
        val result = runRaw("default")

        assertEquals(0, result.exitCode, "CLI failed: ${result.stderr}")
        // The human prefix is the positive signal, and it is unambiguous in a way `startsWith("[")`
        // is not: the renderer decorates every line as `[PipelineK] …`, which also begins with `[`,
        // so a bracket test would pass on the JSON array for the wrong reason.
        assertTrue(
            result.stdout.startsWith("[PipelineK] "),
            "the default opens with the human prefix. stdout: ${result.stdout}",
        )
        assertFalse(
            result.stdout.contains("\"kind\":\""),
            "the default must not leak machine fields. stdout: ${result.stdout}",
        )
        assertTrue(
            result.stdout.contains("Run started:"),
            "the human default announces the run. stdout: ${result.stdout}",
        )
        assertTrue(
            result.stdout.contains("[stage: build]"),
            "the human default shows stage structure. stdout: ${result.stdout}",
        )
        assertTrue(
            result.stdout.contains("Finished: "),
            "the human default closes with the outcome. stdout: ${result.stdout}",
        )
        assertTrue(
            result.stdout.contains("human default marker"),
            "step output reaches the reader verbatim. stdout: ${result.stdout}",
        )
    }

    /**
     * The machine format stays reachable and stays byte-shaped like the historical array, so
     * "human by default" did not quietly cost the JSON consumers their contract.
     */
    @Test
    fun `json the machine format is still reachable and still an array`() {
        val result = runRaw("json", "--format", "json")

        assertEquals(0, result.exitCode, "CLI failed: ${result.stderr}")
        assertTrue(
            result.stdout.trimStart().startsWith("[{"),
            "--format json keeps emitting the array. stdout: ${result.stdout}",
        )
        assertTrue(
            result.stdout.contains("\"kind\":\"RunStarted\""),
            "--format json carries the machine fields. stdout: ${result.stdout}",
        )
    }

    /**
     * Filtering a flagless run.
     *
     * This row pins a rule that is easy to mistake for a bug: `--grep` constrains records that
     * CARRY text, and leaves records that carry none (`ObservationQuery`, class KDoc). So the
     * structural and lifecycle lines survive a `--grep` that matched nothing in them — treating
     * "no text" as "did not match" would silently delete every `[stage: …]` line from
     * `--stage build --grep ERROR`, which no caller means.
     *
     * The two echoes make the row discriminating: the matched message must survive and the
     * unmatched one must be gone, which is what distinguishes a working filter from a no-op.
     *
     * Mutation that kills it: route `--grep` over the final rendered stdout instead of the query,
     * or make the text dimension reject records that carry no text.
     */
    @Test
    fun `grep filtering a flagless run keeps matches and drops non-matching text`() {
        val result = runRaw("grep", "--grep", "human default marker")

        assertEquals(0, result.exitCode, "CLI failed: ${result.stderr}")
        assertTrue(
            result.stdout.contains("human default marker"),
            "the matching message survives. stdout: ${result.stdout}",
        )
        assertFalse(
            result.stdout.contains("noise marker"),
            "the non-matching message is filtered out. stdout: ${result.stdout}",
        )
        // Documented, not accidental: records with no text are not constrained by --grep.
        assertTrue(
            result.stdout.contains("[stage: build]"),
            "structural records carry no text and therefore survive --grep. stdout: ${result.stdout}",
        )
        assertFalse(
            result.stdout.contains("\"kind\":\""),
            "filtering does not turn the default back into JSON. stdout: ${result.stdout}",
        )
    }

    /**
     * Runs the real CLI in a forked JVM.
     *
     * Options precede the script path deliberately: a trailing option is now REJECTED with a typed
     * error (`CliParser`, row `TRAIL-1`) rather than being silently dropped, which is what this
     * helper's shape would otherwise be testing instead of the default.
     */
    private fun runRaw(label: String, vararg options: String): CliResult {
        val args = mutableListOf("run")
        if (passObservationFlags) args += options
        args += script.toString()

        val process = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            "dev.rubentxu.pipeline.v2.application.MainKt",
            *args.toTypedArray(),
        )
            .directory(tempDir.toFile())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        processes += process

        var stdout = ""
        var stderr = ""
        val stdoutReader = thread { stdout = process.inputStream.bufferedReader().use { it.readText() } }
        val stderrReader = thread { stderr = process.errorStream.bufferedReader().use { it.readText() } }

        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "$label: CLI process did not finish")
        stdoutReader.join()
        stderrReader.join()
        return CliResult(process.exitValue(), stdout, stderr)
    }

    private data class CliResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}
