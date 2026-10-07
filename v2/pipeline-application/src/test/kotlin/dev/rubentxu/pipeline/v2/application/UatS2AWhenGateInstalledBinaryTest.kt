package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * S2-A — `when` on the INSTALLED DISTRIBUTION.
 *
 * The coordinator tests inject a fake gate context, so they cannot see whether
 * the production wiring feeds the gate the environment a real script declares.
 * They did not: the first version read `pipeline.environment`, which the DSL
 * never populates, so on a real script every gate saw an empty world and skipped
 * unconditionally. The unit tests were green and the product was wrong.
 *
 * Only running the built binary catches that class of defect, so these scenarios
 * execute the real launcher against real `.pipeline.kts` files and assert on the
 * OBSERVED event stream.
 *
 * The `false` case is the one S1 could never demonstrate: before S2-A, a
 * conditionally-guarded body always ran, and the run reported success.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class UatS2AWhenGateInstalledBinaryTest {

    private fun installedLauncher(): Path = AppBinSupport.discover()

    private data class RunObservation(
        val exitCode: Int,
        val output: String,
    ) {
        fun ran(marker: String): Boolean = output.contains(marker)
        fun skipped(reasonFragment: String): Boolean =
            output.contains("StageSkipped") && output.contains(reasonFragment)
        fun compilationFailed(): Boolean = output.contains("\"severity\":\"ERROR\"")
    }

    private fun runScript(dir: Path, script: String, body: String): RunObservation {
        val file = dir.resolve(script)
        Files.writeString(file, body)
        val process = ProcessBuilder(installedLauncher().toString(), "run", "--format", "json", file.fileName.toString())
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(240, TimeUnit.SECONDS)) {
            "the installed binary did not finish within 240s for $script"
        }
        return RunObservation(process.exitValue(), output)
    }

    private val trueScript = """
        pipeline {
            stages {
                stage("always") { echo("stage-always-ran") }
                stage("gated-on-prod") {
                    environment { env("DEPLOY_ENV", "prod") }
                    whenEnvIs("DEPLOY_ENV", "prod")
                    echo("PROD-BODY-RAN")
                }
            }
        }
    """.trimIndent()

    private val falseScript = """
        pipeline {
            stages {
                stage("gated-on-prod") {
                    environment { env("DEPLOY_ENV", "staging") }
                    whenEnvIs("DEPLOY_ENV", "prod")
                    echo("SHOULD-NOT-RUN")
                }
                stage("after") { echo("after-ran") }
            }
        }
    """.trimIndent()

    private val unsetScript = """
        pipeline {
            stages {
                stage("unset-variable") {
                    whenEnvIs("A_VARIABLE_NOBODY_SET", "expected")
                    echo("SHOULD-NOT-RUN")
                }
                stage("still-ran") { echo("unrelated-stage-ran") }
            }
        }
    """.trimIndent()

    private val composedScript = """
        import dev.rubentxu.pipeline.v2.domain.directive.WhenPredicate

        pipeline {
            stages {
                stage("composed-gate") {
                    environment {
                        env("DEPLOY_ENV", "prod")
                        env("ALSO_NOT_SET", "")
                    }
                    whenGate(
                        WhenPredicate.AllOf(
                            listOf(
                                WhenPredicate.VariableEquals("DEPLOY_ENV", "prod"),
                                WhenPredicate.AnyOf(
                                    listOf(
                                        WhenPredicate.VariablePresent("NOBODY_SET_THIS"),
                                        WhenPredicate.Not(WhenPredicate.VariablePresent("ALSO_NOT_SET")),
                                    ),
                                ),
                            ),
                        ),
                    )
                    echo("COMPOSED-BODY-RAN")
                }
            }
        }
    """.trimIndent()

    @Test
    @DisplayName("S2A-UAT-001: a satisfied gate runs the body on the real binary")
    fun satisfiedGateRunsBody(@TempDir dir: Path) {
        val run = runScript(dir, "true.pipeline.kts", trueScript)

        assertFalse(run.compilationFailed(), "the script must compile:\n${run.output.take(2000)}")
        assertEquals(0, run.exitCode, "output:\n${run.output.take(2000)}")
        assertTrue(run.ran("PROD-BODY-RAN"), "the gated body must run:\n${run.output.take(2000)}")
        assertTrue(run.ran("stage-always-ran"), "the ungated stage must run")
        assertFalse(
            run.output.contains("StageSkipped"),
            "a satisfied gate must not report a skip:\n${run.output.take(2000)}",
        )
    }

    @Test
    @DisplayName("S2A-UAT-002: a decided-negative gate STOPS the body — the defect S1 could not fix")
    fun decidedNegativeStopsBody(@TempDir dir: Path) {
        val run = runScript(dir, "false.pipeline.kts", falseScript)

        assertFalse(run.compilationFailed(), "the script must compile:\n${run.output.take(2000)}")
        assertEquals(0, run.exitCode, "a deliberate skip is not a failure:\n${run.output.take(2000)}")
        assertFalse(
            run.ran("SHOULD-NOT-RUN"),
            "THE DEFECT: a body gated on a false condition must not run:\n${run.output.take(2000)}",
        )
        assertTrue(
            run.skipped("DEPLOY_ENV"),
            "the skip must be observable and name the variable:\n${run.output.take(2000)}",
        )
        assertTrue(
            run.ran("after-ran"),
            "a skipped stage must not stop the rest of the pipeline:\n${run.output.take(2000)}",
        )
    }

    @Test
    @DisplayName("S2A-UAT-003: an unset variable is a skip, not a failure")
    fun unsetVariableSkips(@TempDir dir: Path) {
        val run = runScript(dir, "unset.pipeline.kts", unsetScript)

        assertFalse(run.compilationFailed(), "the script must compile:\n${run.output.take(2000)}")
        assertEquals(0, run.exitCode, "an absent variable must skip quietly:\n${run.output.take(2000)}")
        assertFalse(run.ran("SHOULD-NOT-RUN"), "an unset variable must not run the body")
        assertTrue(
            run.skipped("A_VARIABLE_NOBODY_SET"),
            "the skip must name the unset variable:\n${run.output.take(2000)}",
        )
        assertTrue(run.ran("unrelated-stage-ran"), "later stages must still run")
    }

    @Test
    @DisplayName("S2A-UAT-004: a NESTED predicate evaluates end to end, not silently discarded")
    fun nestedPredicateEvaluates(@TempDir dir: Path) {
        // This is the scenario that justifies the structural codec: with the
        // first multi-line text format the nested `any` failed to decode, the
        // gate was discarded, and the stage ran unconditionally. Here the
        // composed gate must be satisfied AND the body must run, which only
        // holds if nesting survived the round trip.
        val run = runScript(dir, "composed.pipeline.kts", composedScript)

        assertFalse(run.compilationFailed(), "the script must compile:\n${run.output.take(2000)}")
        assertEquals(0, run.exitCode, "output:\n${run.output.take(2000)}")
        assertTrue(
            run.ran("COMPOSED-BODY-RAN"),
            "the nested predicate must be evaluated, not dropped:\n${run.output.take(2000)}",
        )
        assertFalse(run.output.contains("StageSkipped"), "the composed gate is satisfied")
    }
}
