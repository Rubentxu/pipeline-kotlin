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
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.StepFinished
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Path
import java.nio.file.Paths

/**
 * UAT-EVT-001: CLI invocation produces a JSON event log that can be re-parsed
 * and yields the same timeline across two invocations.
 */
@Timeout(120)
class UatEvt001ReplayTest {

    private val appBin: Path by lazy {
        // The binary is at v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application
        // relative to the repo root, or at build/install/pipeline-application/bin/pipeline-application
        // relative to the :pipeline-application module directory.
        val userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        // Detect whether user.dir is the module dir or the repo root.
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

    private val helloScript: Path by lazy {
        Paths.get(javaClass.getResource("/hello.pipeline.kts")!!.toURI())
    }

    @Test
    fun `cli run emits parseable JSON array`() {
        val result = ProcessBuilder(appBin.toString(), "run", helloScript.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
            .also { it.waitFor() }

        val stdout = result.inputStream.bufferedReader().readText().trim()
        assertTrue(stdout.isNotEmpty(), "stdout must not be empty")
        assertEquals("[", stdout.first().toString(), "stdout must start with '['")
        assertEquals("]", stdout.last().toString(), "stdout must end with ']'")

        // Should not throw
        val events = JsonEventLog.decode(stdout)
        assertNotNull(events)
    }

    @Test
    fun `re-parsed timeline equals original with correct kinds`() {
        val (stdout, events) = runAndDecode()
        // hello.pipeline.kts (DSL, 1 stage x 1 step) under the Single Runtime
        // Spine (LF-0208): compilation events precede RunStarted because the
        // script compiles before the coordinator starts the run.
        // CompilationStarted, CompilationFinished, RunStarted, StageStarted,
        // StepStarted, EchoOutputCaptured, StepFinished, StageFinished, RunFinished
        assertEquals(9, events.size, "Expected 9 events for hello DSL pipeline: $stdout")

        assertTrue(events[0] is CompilationStarted, "events[0] must be CompilationStarted")
        assertTrue(events[1] is CompilationFinished, "events[1] must be CompilationFinished")
        assertTrue(events[2] is RunStarted, "events[2] must be RunStarted")
        assertTrue(events[3] is StageStarted, "events[3] must be StageStarted (DSL evaluated)")
        assertTrue(events[4] is StepStarted, "events[4] must be StepStarted")
        assertTrue(events[5] is EchoOutputCaptured, "events[5] must be EchoOutputCaptured")
        assertTrue(events[6] is StepFinished, "events[6] must be StepFinished")
        assertTrue(events[7] is StageFinished, "events[7] must be StageFinished")
        assertTrue(events[8] is RunFinished, "events[8] must be RunFinished")

        val cf = events[1] as CompilationFinished
        assertEquals("v1", cf.cacheKey.version, "cacheKey.version must be v1")
        assertEquals(64, cf.cacheKey.value.length, "cacheKey.value must be 64-char hex")
        assertTrue(cf.diagnostics.isEmpty(), "CompilationFinished diagnostics must be empty for DSL: ${cf.diagnostics}")

        val rf = events[8] as RunFinished
        assertEquals("success", rf.outcome, "RunFinished outcome must be success")
        assertTrue(rf.diagnostics.isEmpty(), "RunFinished diagnostics must be empty: ${rf.diagnostics}")

        val ss = events[3] as StageStarted
        assertEquals(0, ss.stageIndex, "stageIndex must be 0")
        assertEquals("hello", ss.stageName, "stageName must be hello")

        val stepStarted = events[4] as StepStarted
        assertEquals(0, stepStarted.stageIndex, "step stageIndex must be 0")
        assertEquals(0, stepStarted.stepIndex, "stepIndex must be 0")
        assertEquals("echo", stepStarted.stepName, "stepName must be echo")
        assertEquals("echo", stepStarted.stepType, "stepType must be echo")
    }

    @Test
    fun `two cli invocations yield structurally equal timelines`() {
        val (_, events1) = runAndDecode()
        val (_, events2) = runAndDecode()

        assertEquals(events1.size, events2.size, "Both runs must produce the same number of events")

        // LF-0206 identity model: each invocation has exactly one RunId and
        // two invocations of the same script MUST have different RunIds
        // (M1-001) while producing structurally identical timelines.
        val runIds1 = events1.map { it.runId }.distinct()
        val runIds2 = events2.map { it.runId }.distinct()
        assertEquals(1, runIds1.size, "Timeline 1 must use a single RunId")
        assertEquals(1, runIds2.size, "Timeline 2 must use a single RunId")
        assertTrue(
            runIds1.single() != runIds2.single(),
            "Two invocations of the same script must have different RunIds (M1-001)",
        )

        for (i in events1.indices) {
            val e1 = events1[i]
            val e2 = events2[i]

            // LF-0206: RunId is unique per invocation. Timelines must be
            // structurally equal EXCEPT the run id, which must differ
            // between invocations (M1-001) while staying internally
            // consistent within each timeline.
            assertEquals(e1.kind, e2.kind, "Event $i kind must match")
            assertEquals(e1.sequence, e2.sequence, "Event $i sequence must match")

            if (e1 is CompilationFinished && e2 is CompilationFinished) {
                assertEquals(e1.cacheKey.version, e2.cacheKey.version, "Event $i cacheKey.version must match")
                assertEquals(e1.cacheKey.value, e2.cacheKey.value, "Event $i cacheKey.value must match")
            }
            if (e1 is RunFinished && e2 is RunFinished) {
                assertEquals(e1.outcome, e2.outcome, "Event $i outcome must match")
            }
        }
    }

    private fun runAndDecode(): Pair<String, List<DomainEvent>> {
        val pb = ProcessBuilder(appBin.toString(), "run", helloScript.toString())
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
