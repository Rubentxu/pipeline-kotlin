package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.support.Subprocess
import dev.rubentxu.pipeline.v2.application.support.requireExited
import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import dev.rubentxu.pipeline.v2.application.support.ConsolePlaneProbe
import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.durable.JsonEventLog
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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * UAT-EVT-002: multi-step pipeline fixture test.
 *
 * Full DSL multi-step evaluation with 2 stages (build, test) x 2 steps each.
 * Produces 16 events: CompilationStarted, CompilationFinished, RunStarted,
 * StageStarted(build) + StepStarted(echo) + EchoOutputCaptured + StepFinished(echo)
 *   + StepStarted(sh) + StepFinished(sh) + StageFinished(build),
 * StageStarted(test) + StepStarted(echo) + EchoOutputCaptured + StepFinished(echo)
 *   + StepStarted(sh) + StepFinished(sh) + StageFinished(test),
 * RunFinished.
 *
 * The two `sh` steps emit no output event, by design and since M1: the event plane carries
 * semantic facts, and a process's stdout is bytes, not a fact about the run. The test reads
 * those bytes from the Output Plane through [ConsolePlaneProbe] and still asserts all four
 * texts, so coverage was moved, not dropped. It was 20 events while `sh` output was still
 * duplicated into the event log.
 *
 * The fixture uses succeeding commands, so no StepFailed is expected: under the legacy
 * record-only walker the `make` steps were simulated as StepFailed (INC-R8-ARC-001), and that
 * simulation is exactly what real execution replaced.
 */
@Timeout(120)
class UatEvt002MultiStepReplayTest {

    // WU-LPR-072: shared AppBinSupport handles the pipelinek (post-WU-LPR-070)
    // and pipeline-application (legacy) install locations.
    private val appBin: Path by lazy { AppBinSupport.discover() }

    private val multiStepScript: Path by lazy {
        Paths.get(javaClass.getResource("/multi-step.pipeline.kts")!!.toURI())
    }

    @Test
    fun `cli run with multi-step script emits parseable JSON array`() {
        // WAITFOR-3: drained while the child runs; see support/Subprocess.kt.
        val stdout = Subprocess.run(
            command = listOf(appBin.toString(), "run", "--format", "json", multiStepScript.toString()),
        ).requireExited().stdout.trim()
        assertTrue(stdout.isNotEmpty(), "stdout must not be empty")
        assertTrue(stdout.startsWith("["), "stdout must start with '['")
        assertTrue(stdout.endsWith("]"), "stdout must end with ']'")

        // Should not throw
        val events = JsonEventLog.decode(stdout)
        assertNotNull(events)
    }

    @Test
    fun `multi-step script compiles successfully`() {
        val (stdout, events, controlDir) = runAndDecode()
        // Durable-spine timeline (LF-0208: sh steps REALLY execute now), recounted against the
        // run that actually happened rather than against an arithmetic guess:
        //
        //   CompilationStarted, CompilationFinished, RunStarted          =  3
        //   per stage: StageStarted + (StepStarted/Echo/StepFinished)      =  4
        //              + (StepStarted/StepFinished)                       =  2
        //              + StageFinished                                     =  1   -> 7 each
        //   RunFinished                                                    =  1
        //                                                                   =  3 + 14 + 1 = 18
        //
        // It was 20 while `sh` output was duplicated into the event log; the two `sh` steps
        // lost their EchoOutputCaptured and nothing else moved. The four texts asserted below
        // are still all observed — two as events, two from the plane. What no longer exists is
        // the second authority that let stdout be read from events in the first place.
        //
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
        // `core.echo` is a semantic Step, so its text is a fact about the run and earns an
        // event. The two `sh` steps have no such event any more, and that is the migration
        // working: their output is read from the Output Plane just below. Counting 4 here
        // again would resurrect the conflation this receipt is closing.
        assertEquals(2, echoCapturedEvents.size, "Must have 2 EchoOutputCaptured events (one per echo step)")
        val processOut = ConsolePlaneProbe.transcriptsOfSteps(controlDir, events, stepType = "sh")
        val observedOutput = echoCapturedEvents.joinToString("\n") { it.content } + "\n" + processOut
        listOf("compiling", "build-ok", "testing", "test-ok").forEach { expected ->
            assertTrue(observedOutput.contains(expected), "Observed output must contain `$expected`")
        }
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

    private fun runAndDecode(): Triple<String, List<DomainEvent>, Path> {
        // S4/M1: the control dir is named, not inferred from the invocation CWD, because the
        // assertions below read process output and process output is the Output Plane's.
        val controlDir = Files.createTempDirectory("uat-evt002-control")
        // WAITFOR-3: drained while the child runs; see support/Subprocess.kt.
        val cliRun = Subprocess.run(
            command = listOf(
                appBin.toString(),
                "run", "--format", "json",
                // Options before the script path: CliParser stops consuming flags at the first
                // non-flag argument, so a trailing `--control-root` is dropped in silence.
                "--control-root",
                controlDir.toAbsolutePath().toString(),
                multiStepScript.toString(),
            ),
        ).requireExited()
        val exitCode = cliRun.exitCode
        val stdout = cliRun.stdout
        if (exitCode != 0) {
            throw IllegalStateException("CLI exited with $exitCode. stderr: ${cliRun.stderr}")
        }
        val events = JsonEventLog.decode(stdout)
        return Triple(stdout, events, controlDir)
    }
}
