package com.pipeline.v2.application

import com.pipeline.v2.events.CompilationFinished
import com.pipeline.v2.events.DomainEvent
import com.pipeline.v2.events.JsonEventLog
import com.pipeline.v2.events.RunFinished
import com.pipeline.v2.events.RunStarted
import com.pipeline.v2.events.CompilationStarted
import com.pipeline.v2.events.StageStarted
import com.pipeline.v2.events.StageFinished
import com.pipeline.v2.events.StepStarted
import com.pipeline.v2.events.StepFinished
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * UAT-EVT-002: multi-step pipeline fixture test.
 *
 * Full DSL multi-step evaluation with 2 stages (build, test) x 2 steps each.
 * Produces 12 events: RunStarted, CompilationStarted, CompilationFinished,
 * StageStarted(build) + StepStarted(echo) + StepFinished(echo) + StepStarted(sh) + StepFinished(sh) + StageFinished(build),
 * StageStarted(test) + StepStarted(echo) + StepFinished(echo) + StepStarted(sh) + StepFinished(sh) + StageFinished(test),
 * RunFinished.
 */
class UatEvt002MultiStepReplayTest {

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

    private val multiStepScript: Path by lazy {
        Paths.get(javaClass.getResource("/multi-step.pipeline.kts")!!.toURI())
    }

    @Test
    fun `cli run with multi-step script emits parseable JSON array`() {
        val result = ProcessBuilder(appBin.toString(), "run", multiStepScript.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
            .also { it.waitFor() }

        val stdout = result.inputStream.bufferedReader().readText().trim()
        assertTrue(stdout.isNotEmpty(), "stdout must not be empty")
        assertTrue(stdout.startsWith("["), "stdout must start with '['")
        assertTrue(stdout.endsWith("]"), "stdout must end with ']'")

        // Should not throw
        val events = JsonEventLog.decode(stdout)
        assertNotNull(events)
    }

    @Test
    fun `multi-step script compiles successfully`() {
        val (stdout, events) = runAndDecode()
        // 2 stages x 2 steps each + 4 run/compilation events + 2 stage finishes = 16
        assertEquals(16, events.size, "Expected 16 events from multi-step fixture: $stdout")

        assertTrue(events[0] is RunStarted, "events[0] must be RunStarted")
        assertTrue(events[1] is CompilationStarted, "events[1] must be CompilationStarted")
        assertTrue(events[2] is CompilationFinished, "events[2] must be CompilationFinished")

        val cf = events[2] as CompilationFinished
        assertEquals("v1", cf.cacheKey.version, "cacheKey.version must be v1")
        assertTrue(cf.diagnostics.isEmpty(), "CompilationFinished diagnostics must be empty: ${cf.diagnostics}")

        // Stage 0: "build" with echo + sh
        assertTrue(events[3] is StageStarted, "events[3] must be StageStarted for build stage")
        val stage0Started = events[3] as StageStarted
        assertEquals(0, stage0Started.stageIndex, "stageIndex must be 0 for build stage")
        assertEquals("build", stage0Started.stageName, "stageName must be build")

        assertTrue(events[4] is StepStarted, "events[4] must be StepStarted for echo")
        val step00Started = events[4] as StepStarted
        assertEquals(0, step00Started.stageIndex, "stageIndex must be 0")
        assertEquals(0, step00Started.stepIndex, "stepIndex must be 0 for echo")
        assertEquals("echo", step00Started.stepName, "stepName must be echo")
        assertEquals("echo", step00Started.stepType, "stepType must be echo")

        assertTrue(events[5] is StepFinished, "events[5] must be StepFinished for echo")
        val step00Finished = events[5] as StepFinished
        assertEquals(0, step00Finished.stageIndex, "stageIndex must be 0")
        assertEquals(0, step00Finished.stepIndex, "stepIndex must be 0")

        assertTrue(events[6] is StepStarted, "events[6] must be StepStarted for sh")
        val step01Started = events[6] as StepStarted
        assertEquals(0, step01Started.stageIndex, "stageIndex must be 0")
        assertEquals(1, step01Started.stepIndex, "stepIndex must be 1 for sh")
        assertEquals("sh", step01Started.stepName, "stepName must be sh")
        assertEquals("sh", step01Started.stepType, "stepType must be sh")

        assertTrue(events[7] is StepFinished, "events[7] must be StepFinished for sh")
        val step01Finished = events[7] as StepFinished
        assertEquals(0, step01Finished.stageIndex, "stageIndex must be 0")
        assertEquals(1, step01Finished.stepIndex, "stepIndex must be 1")

        assertTrue(events[8] is StageFinished, "events[8] must be StageFinished for build stage")
        val stage0Finished = events[8] as StageFinished
        assertEquals(0, stage0Finished.stageIndex, "stageIndex must be 0 for build stage")
        assertEquals("build", stage0Finished.stageName, "stageName must be build")
        assertEquals("success", stage0Finished.outcome, "build stage outcome must be success")

        // Stage 1: "test"
        assertTrue(events[9] is StageStarted, "events[9] must be StageStarted for test stage")
        val stage1Started = events[9] as StageStarted
        assertEquals(1, stage1Started.stageIndex, "stageIndex must be 1 for test stage")
        assertEquals("test", stage1Started.stageName, "stageName must be test")

        assertTrue(events[10] is StepStarted, "events[10] must be StepStarted for test echo")
        val step10Started = events[10] as StepStarted
        assertEquals(1, step10Started.stageIndex, "stageIndex must be 1 for test stage")
        assertEquals(0, step10Started.stepIndex, "stepIndex must be 0 for test echo")
        assertEquals("echo", step10Started.stepName, "stepName must be echo")

        assertTrue(events[11] is StepFinished, "events[11] must be StepFinished for test echo")
        val step10Finished = events[11] as StepFinished
        assertEquals(1, step10Finished.stageIndex, "stageIndex must be 1")

        assertTrue(events[12] is StepStarted, "events[12] must be StepStarted for test sh")
        val step11Started = events[12] as StepStarted
        assertEquals(1, step11Started.stageIndex, "stageIndex must be 1")
        assertEquals(1, step11Started.stepIndex, "stepIndex must be 1 for test sh")

        assertTrue(events[13] is StepFinished, "events[13] must be StepFinished for test sh")

        assertTrue(events[14] is StageFinished, "events[14] must be StageFinished for test stage")
        val stage1Finished = events[14] as StageFinished
        assertEquals(1, stage1Finished.stageIndex, "stageIndex must be 1 for test stage")
        assertEquals("test", stage1Finished.stageName, "stageName must be test")
        assertEquals("success", stage1Finished.outcome, "test stage outcome must be success")

        assertTrue(events[15] is RunFinished, "events[15] must be RunFinished")
        val rf = events[15] as RunFinished
        assertEquals("success", rf.outcome, "RunFinished outcome must be success")
        assertTrue(rf.diagnostics.isEmpty(), "RunFinished diagnostics must be empty: ${rf.diagnostics}")
    }

    private fun runAndDecode(): Pair<String, List<DomainEvent>> {
        val pb = ProcessBuilder(appBin.toString(), "run", multiStepScript.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
        val process = pb.start()
        val exitCode = process.waitFor()
        val stdout = process.inputStream.bufferedReader().readText().trim()
        if (exitCode != 0) {
            val stderr = process.errorStream.bufferedReader().readText()
            throw IllegalStateException("CLI exited with $exitCode. stderr: $stderr")
        }
        val events = JsonEventLog.decode(stdout)
        return stdout to events
    }
}
