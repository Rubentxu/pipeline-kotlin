package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.support.Subprocess
import dev.rubentxu.pipeline.v2.application.support.requireExited
import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * A Kotlin exception thrown while the DSL body is being evaluated at script
 * construction time must surface as an admission failure (exit 2), never as an
 * uncaught [NullPointerException] on stdout-quieted stderr with exit 0.
 *
 * OBSERVED before the fix on the installed distribution, with this exact
 * script shape:
 *
 * ```kotlin
 * stage("x") {
 *     echo("before")
 *     require(false) { "boom during construction" }
 *     echo("after")
 * }
 * ```
 *
 * produced `Exception in thread "main" java.lang.NullPointerException at
 * MainKt.main(Main.kt:297)` and `run_exit=0`.
 *
 * The mechanism is a [DEFAULT_SUCCESS] chain, the exact class the
 * TRAIN-DSL-HONESTY Semantic Conservation Law forbids:
 *
 * 1. the scripted DSL evaluates the stage body eagerly during `host.eval`;
 * 2. an exception during that evaluation is reported by the scripting host as a
 *    successful evaluation whose `scriptInstance` carries no `$$result`, so
 *    `pipelineSpec` is `null` while `compileResult.isSuccess` stays `true`;
 * 3. `compileOutcome` is therefore `null` too — it only captures compile
 *    failures, not evaluation failures;
 * 4. `nonCanonicalSteps` derives from `compiledPipeline` and is `orEmpty()`, so
 *    the gate sees an empty list;
 * 5. the `when` falls into `runCanonicalPipeline(compiledPipeline!!)` → NPE;
 *    for `validate`, the same null spec reports `VALIDATION SUCCESSFUL`.
 *
 * This also silently swallows the fail-closed diagnostics of
 * `whenCondition`/`retry` guards: the rejection exception never reaches the
 * user. It predates those guards — any `require(false)` in a stage body
 * triggers it — which makes it a defect of the CLI harness itself.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class CliDslConstructionFailureSurfacesTest {

    @TempDir
    lateinit var tempDir: Path

    private fun run(scriptBody: String): Triple<Int, String, String> {
        val script = tempDir.resolve("construction-failure.pipeline.kts")
        Files.writeString(script, scriptBody)
        val appBin = AppBinSupport.discover()
        // WAITFOR-3: drained while the child runs; see support/Subprocess.kt.
        val cliRun = Subprocess.run(
            command = listOf(appBin.toString(), "run", script.toAbsolutePath().toString()),
        ).requireExited()
        val exitCode = cliRun.exitCode
        val stdout = cliRun.stdout
        val stderr = cliRun.stderr.trim()
        return Triple(exitCode, stdout, stderr)
    }

    @Test
    fun `a require failure during DSL construction exits 1 with FAILURE and the user diagnostic`() {
        val (exitCode, stdout, stderr) = run(
            """
            pipeline {
                stages {
                    stage("x") {
                        echo("before")
                        require(false) { "boom during construction" }
                        echo("after")
                    }
                }
            }
            """.trimIndent(),
        )

        // Canonical CLI exit contract: run with a pipeline that cannot execute
        // reports FAILURE with exit 1 (validate, the invocation gate, exits 2).
        assertEquals(1, exitCode, "construction failure must be a FAILURE run, got stderr: $stderr")
        assertTrue(
            "Pipeline finished with FAILURE" in stderr,
            "run must report FAILURE, got: $stderr",
        )
        assertTrue(
            "NullPointerException" !in stderr,
            "the raw NPE must not leak to the user, got: $stderr",
        )
        assertTrue(
            stdout.contains("boom during construction") || stderr.contains("boom during construction"),
            "the user's diagnostic message must survive, got stdout: $stdout stderr: $stderr",
        )
    }

    @Test
    fun `the whenCondition fail-closed rejection surfaces as FAILURE with its diagnostic`() {
        val (exitCode, stdout, stderr) = run(
            """
            pipeline {
                stages {
                    stage("x") {
                        whenCondition("env.LPR_PUBLISH == 'true'") {
                            echo("publishing")
                        }
                    }
                }
            }
            """.trimIndent(),
        )

        assertEquals(1, exitCode, "whenCondition rejection must produce a FAILURE run, got stderr: $stderr")
        assertTrue(
            "NullPointerException" !in stderr,
            "the raw NPE must not leak, got: $stderr",
        )
        assertTrue(
            "whenCondition" in stderr || "whenCondition" in stdout,
            "the fail-closed diagnostic must name the rejected surface, got stderr: $stderr",
        )
    }

    @Test
    fun `validate reports the construction failure instead of claiming success`() {
        val script = tempDir.resolve("construction-failure-validate.pipeline.kts")
        Files.writeString(
            script,
            """
            pipeline {
                stages {
                    stage("x") {
                        require(false) { "boom during construction" }
                    }
                }
            }
            """.trimIndent(),
        )
        val appBin = AppBinSupport.discover()
        // WAITFOR-3: drained while the child runs; see support/Subprocess.kt.
        val cliRun = Subprocess.run(
            command = listOf(appBin.toString(), "validate", script.toAbsolutePath().toString()),
        ).requireExited()
        val exitCode = cliRun.exitCode
        val stderr = cliRun.stderr.trim()

        assertEquals(2, exitCode, "validate must fail closed on a construction failure, got stderr: $stderr")
        assertTrue(
            "VALIDATION SUCCESSFUL" !in stderr,
            "validate must not claim success for a script that cannot construct its IR, got: $stderr",
        )
    }
}
