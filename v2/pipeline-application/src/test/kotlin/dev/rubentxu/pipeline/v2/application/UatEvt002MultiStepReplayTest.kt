package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.JsonEventLog
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.StepFinished
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Path
import java.nio.file.Paths

/**
 * UAT-EVT-002: multi-step pipeline fixture test.
 *
 * Full DSL multi-step evaluation with 2 stages (build, test) x 2 steps each.
 * Produces 22 events: RunStarted, CompilationStarted, CompilationFinished,
 * StageStarted(build) + StepStarted(echo) + EchoOutputCaptured + StepFinished(echo)
 *   + StepStarted(sh) + EchoOutputCaptured + StepFailed + StepFinished(sh) + StageFinished(build),
 * StageStarted(test) + StepStarted(echo) + EchoOutputCaptured + StepFinished(echo)
 *   + StepStarted(sh) + EchoOutputCaptured + StepFailed + StepFinished(sh) + StageFinished(test),
 * RunFinished.
 *
 * The 2 extra StepFailed events are emitted by INC-R8-ARC-001 (sh non-zero exit
 * emits StepFailed). The `make` and `make test` commands fail because no Makefile
 * exists in the test working directory.
 */
@Timeout(120)
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
        // Durable-spine timeline (LF-0208: sh steps REALLY execute now):
        // 2 compilation events + 2 run events + 2x (StageStarted + 2x
        // (StepStarted + EchoOutputCaptured for echo + StepFinished for
        // echo + StepStarted + StepFinished for sh) + StageFinished) = 18.
        // INC-R8-ARC-001 historical note: under the legacy record-only
        // walker the make steps were simulated as StepFailed; under real
        // execution the fixture uses succeeding commands.
        assertEquals(18, events.size, "Expected 18 events from multi-step fixture: $stdout")

        assertTrue(events[0] is CompilationStarted, "events[0] must be CompilationStarted (durable spine)")
        assertTrue(events[1] is CompilationFinished, "events[1] must be CompilationFinished")
        assertTrue(events[2] is RunStarted, "events[2] must be RunStarted")

        val cf = events[1] as CompilationFinished
        assertEquals("v1", cf.cacheKey.version, "cacheKey.version must be v1")
        assertTrue(cf.diagnostics.isEmpty(), "CompilationFinished diagnostics must be empty: ${cf.diagnostics}")

        // Use type-based queries to avoid brittle index dependencies
        val stageStartedEvents = events.filterIsInstance<StageStarted>()
        val stageFinishedEvents = events.filterIsInstance<StageFinished>()
        val stepStartedEvents = events.filterIsInstance<StepStarted>()
        val stepFinishedEvents = events.filterIsInstance<StepFinished>()
        val echoCapturedEvents = events.filterIsInstance<EchoOutputCaptured>()
        val stepFailedEvents = events.filterIsInstance<StepFailed>()

        assertEquals(2, stageStartedEvents.size, "Must have 2 StageStarted events")
        assertEquals(2, stageFinishedEvents.size, "Must have 2 StageFinished events")
        assertEquals(4, stepStartedEvents.size, "Must have 4 StepStarted events")
        assertEquals(4, stepFinishedEvents.size, "Must have 4 StepFinished events")
        // EchoOutputCaptured is emitted for echo steps under the durable spine.
        assertEquals(2, echoCapturedEvents.size, "Must have 2 EchoOutputCaptured events (one per echo step)")
        // Real execution: succeeding commands produce no StepFailed events.
        assertEquals(0, stepFailedEvents.size, "No StepFailed events expected: ${stepFailedEvents.map { it.message }}")

        // Verify build stage
        val buildStageStart = stageStartedEvents.find { it.stageName == "build" }
        assertEquals(0, buildStageStart?.stageIndex, "build stageIndex must be 0")

        val buildStageFinish = stageFinishedEvents.find { it.stageName == "build" }
        assertEquals(0, buildStageFinish?.stageIndex, "build stageIndex must be 0")
        assertEquals("success", buildStageFinish?.outcome, "build stage outcome must be success")

        // Verify test stage
        val testStageStart = stageStartedEvents.find { it.stageName == "test" }
        assertEquals(1, testStageStart?.stageIndex, "test stageIndex must be 1")

        val testStageFinish = stageFinishedEvents.find { it.stageName == "test" }
        assertEquals(1, testStageFinish?.stageIndex, "test stageIndex must be 1")
        assertEquals("success", testStageFinish?.outcome, "test stage outcome must be success")

        // Verify step indices for build stage (stageIndex=0)
        val buildSteps = stepStartedEvents.filter { it.stageIndex == 0 }
        assertEquals(2, buildSteps.size, "build stage must have 2 steps")
        assertTrue(buildSteps.any { it.stepIndex == 0 && it.stepType == "echo" }, "build must have echo step at index 0")
        assertTrue(buildSteps.any { it.stepIndex == 1 && it.stepType == "sh" }, "build must have sh step at index 1")

        // Verify step indices for test stage (stageIndex=1)
        val testSteps = stepStartedEvents.filter { it.stageIndex == 1 }
        assertEquals(2, testSteps.size, "test stage must have 2 steps")
        assertTrue(testSteps.any { it.stepIndex == 0 && it.stepType == "echo" }, "test must have echo step at index 0")
        assertTrue(testSteps.any { it.stepIndex == 1 && it.stepType == "sh" }, "test must have sh step at index 1")

        assertTrue(events.last() is RunFinished, "events.last() must be RunFinished")
        val rf = events.last() as RunFinished
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
