package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.JsonEventLog
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

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

    private val appBin: Path by lazy {
        val userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        val moduleDir = if (userDir.fileName?.toString() == "pipeline-application") {
            userDir
        } else {
            userDir.resolve("v2").resolve("pipeline-application")
        }
        val bin = moduleDir
            .resolve("build")
            .resolve("install")
            .resolve("pipeline-application")
            .resolve("bin")
            .resolve("pipeline-application")
        if (!bin.toFile().exists()) {
            throw IllegalStateException(
                "Application binary not found at $bin. " +
                "Run ./gradlew :pipeline-application:installDist first."
            )
        }
        bin
    }

    /**
     * Creates a temporary pipeline script file.
     */
    private fun tempScript(content: String): Path {
        val temp = Files.createTempFile("body-exec-", ".pipeline.kts")
        Files.writeString(temp, content)
        return temp
    }

    /**
     * Runs the CLI and returns stdout + exit code.
     */
    private fun runCli(script: Path): Pair<String, Int> {
        val pb = ProcessBuilder(appBin.toString(), "run", script.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
        val process = pb.start()
        val exitCode = process.waitFor()
        val stdout = process.inputStream.bufferedReader().readText().trim()
        return stdout to exitCode
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

        val (stdout, exitCode) = runCli(script)
        assertEquals(0, exitCode, "dir body execution should succeed. stderr: $stdout")

        val events = JsonEventLog.decode(stdout)
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

        val (stdout, exitCode) = runCli(script)
        assertEquals(0, exitCode, "timeout body execution should succeed. stderr: $stdout")

        val events = JsonEventLog.decode(stdout)
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

        val (stdout, exitCode) = runCli(script)
        assertEquals(0, exitCode, "retry body execution should succeed. stderr: $stdout")

        val events = JsonEventLog.decode(stdout)
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

        val (stdout, exitCode) = runCli(script)
        assertEquals(0, exitCode, "nested block body execution should succeed. stderr: $stdout")

        val events = JsonEventLog.decode(stdout)
        val stepStarted = events.filterIsInstance<StepStarted>()
        // Both inner steps should appear with their body-path-affected names
        assertTrue(stepStarted.any { it.stepName.contains("retry-body") && it.stepName.contains("dir-body") },
            "Expected nested steps with body paths in StepStarted events. Got: $stepStarted")
    }
}
