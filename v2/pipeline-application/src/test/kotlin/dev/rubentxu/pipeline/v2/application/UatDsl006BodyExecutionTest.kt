package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.ObservationFormat
import dev.rubentxu.pipeline.v2.application.support.CliContractTestKit
import dev.rubentxu.pipeline.v2.application.support.Subprocess
import dev.rubentxu.pipeline.v2.application.support.requireExited
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.durable.JsonEventLog
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport

/**
 * UAT-DSL-006: Body Execution Substrate (EM-4 JEP-020/029).
 *
 * Validates that block steps compiling to [BlockStepNode] execute their body
 * children correctly through the canonical coordinator dispatchBody path.
 *
 * Block steps on the EM-4 canonical path:
 * - dir { ... } — CWD context scope
 * - timeout(seconds) { ... } — timeout enforcement
 * - retry(count) { ... } — retry on failure
 * - withCredentials { ... } — credential binding scope
 *
 * Block steps remaining on legacy linear path (EM-5/EM-6):
 * - catchError { ... } — failure suppression semantics
 * - warnError { ... } — UNSTABLE classification semantics
 *
 * JEP-020: BlockStepNode canonical execution substrate
 * JEP-029: OpId bodyPath length-prefix for body child journal rows
 *
 * @Timeout 120s per test (UAT with real process spawn)
 */
@Timeout(120)
class UatDsl006BodyExecutionTest {

    // WU-LPR-072: shared AppBinSupport handles the pipelinek (post-WU-LPR-070)
    // and pipeline-application (legacy) install locations.
    private val appBin: Path by lazy { AppBinSupport.discover() }

    /**
     * Creates a temporary pipeline script file.
     */
    private fun tempScript(content: String): Path {
        val temp = Files.createTempFile("body-exec-", ".pipeline.kts")
        Files.writeString(temp, content)
        return temp
    }

    /**
     * Runs the CLI and returns stdout + stderr + exit code.
     */
    private data class CliOutput(val stdout: String, val stderr: String, val exitCode: Int)

    private fun runCli(script: Path): CliOutput {
        // WAITFOR-3: drained while the child runs; see support/Subprocess.kt.
        val cliRun = Subprocess.run(
            command = listOf(appBin.toString(), "run", "--format", "json", script.toString()),
        ).requireExited()
        return CliOutput(cliRun.stdout, cliRun.stderr, cliRun.exitCode)
    }

    @Test
    fun `dir step executes body children via BlockStepNode dispatchBody`() {
        // dir { echo "inner" } — tests JEP-020 body execution substrate
        // Note: BlockStepNode (dir) does not emit StepStarted/StepFinished events directly.
        // Only its body children do. The BlockStepNode ID confirms dispatch via body path.
        val script = tempScript("""
            pipeline {
                stages {
                    stage("test") {
                        dir("/tmp") {
                            echo("inner")
                        }
                    }
                }
            }
        """.trimIndent())

        val result = runCli(script)
        assertEquals(0, result.exitCode, "dir body execution should succeed. stderr: ${result.stderr}")

        // PK-NEXT R0.3: contract is decoded via the testkit.
        val contract = CliContractTestKit.decodeLaunchContract(
            stdout = result.stdout,
            stderr = result.stderr,
            format = ObservationFormat.JSON,
        )
        require(contract.eventDocument.decodeError == null) {
            "JSON event document failed to decode: ${contract.eventDocument.decodeError}"
        }
        val events = contract.eventDocument.events
        // The inner echo step should appear with a body-path-affected name
        val stepStarted = events.filterIsInstance<StepStarted>()
        assertTrue(stepStarted.any { it.stepType == "echo" && it.stepName.contains("dir-body") },
            "Expected inner echo step with dir-body path in StepStarted events. Got: $stepStarted")
    }

    @Test
    fun `timeout block executes body children via BlockStepNode dispatchBody`() {
        // timeout { echo "inside-timeout" } — tests JEP-020 body execution substrate
        // Note: BlockStepNode does not emit its own StepStarted events — only children do.
        val script = tempScript("""
            pipeline {
                stages {
                    stage("test") {
                        timeout(30, "SECONDS") {
                            echo("inside-timeout")
                        }
                    }
                }
            }
        """.trimIndent())

        val result = runCli(script)
        assertEquals(0, result.exitCode, "timeout body execution should succeed. stderr: ${result.stderr}")

        // PK-NEXT R0.3: contract is decoded via the testkit.
        val contract = CliContractTestKit.decodeLaunchContract(
            stdout = result.stdout,
            stderr = result.stderr,
            format = ObservationFormat.JSON,
        )
        require(contract.eventDocument.decodeError == null) {
            "JSON event document failed to decode: ${contract.eventDocument.decodeError}"
        }
        val events = contract.eventDocument.events
        val stepStarted = events.filterIsInstance<StepStarted>()
        assertTrue(stepStarted.any { it.stepType == "echo" && it.stepName.contains("timeout-body") },
            "Expected inner echo step with timeout-body path in StepStarted events. Got: $stepStarted")
    }

    @Test
    fun `retry block executes body children via BlockStepNode dispatchBody`() {
        // retry(1) { echo "retry-body" } — tests JEP-020 body execution substrate
        val script = tempScript("""
            pipeline {
                stages {
                    stage("test") {
                        retry(1) {
                            echo("retry-body")
                        }
                    }
                }
            }
        """.trimIndent())

        val result = runCli(script)
        assertEquals(0, result.exitCode, "retry body execution should succeed. stderr: ${result.stderr}")

        // PK-NEXT R0.3: contract is decoded via the testkit.
        val contract = CliContractTestKit.decodeLaunchContract(
            stdout = result.stdout,
            stderr = result.stderr,
            format = ObservationFormat.JSON,
        )
        require(contract.eventDocument.decodeError == null) {
            "JSON event document failed to decode: ${contract.eventDocument.decodeError}"
        }
        val events = contract.eventDocument.events
        val stepStarted = events.filterIsInstance<StepStarted>()
        assertTrue(stepStarted.any { it.stepType == "echo" && it.stepName.contains("retry-body") },
            "Expected inner echo step with retry-body path in StepStarted events. Got: $stepStarted")
    }

    @Test
    fun `nested block steps execute inner bodies correctly`() {
        // retry { dir { echo "nested" } } — tests depth-2 body recursion (JEP-020 + JEP-029)
        val script = tempScript("""
            pipeline {
                stages {
                    stage("test") {
                        retry(1) {
                            dir("/tmp") {
                                echo("nested")
                            }
                        }
                    }
                }
            }
        """.trimIndent())

        val result = runCli(script)
        assertEquals(0, result.exitCode, "nested block body execution should succeed. stderr: ${result.stderr}")

        // PK-NEXT R0.3: contract is decoded via the testkit.
        val contract = CliContractTestKit.decodeLaunchContract(
            stdout = result.stdout,
            stderr = result.stderr,
            format = ObservationFormat.JSON,
        )
        require(contract.eventDocument.decodeError == null) {
            "JSON event document failed to decode: ${contract.eventDocument.decodeError}"
        }
        val events = contract.eventDocument.events
        val stepStarted = events.filterIsInstance<StepStarted>()
        // Both inner steps should appear with their body-path-affected names
        assertTrue(stepStarted.any { it.stepName.contains("retry-body") && it.stepName.contains("dir-body") },
            "Expected nested steps with body paths in StepStarted events. Got: $stepStarted")
    }
}
