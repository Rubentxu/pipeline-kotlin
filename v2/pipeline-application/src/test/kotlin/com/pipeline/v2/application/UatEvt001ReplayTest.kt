package com.pipeline.v2.application

import com.pipeline.v2.events.CompilationFinished
import com.pipeline.v2.events.DomainEvent
import com.pipeline.v2.events.JsonEventLog
import com.pipeline.v2.events.RunFinished
import com.pipeline.v2.events.RunStarted
import com.pipeline.v2.events.CompilationStarted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * UAT-EVT-001: CLI invocation produces a JSON event log that can be re-parsed
 * and yields the same timeline across two invocations.
 */
class UatEvt001ReplayTest {

    private val appBin: Path by lazy {
        // The binary is at v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application
        // relative to the project root (current working directory).
        val bin = Paths.get(System.getProperty("user.dir"))
            .resolve("v2")
            .resolve("pipeline-application")
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
        assertEquals(4, events.size)

        assertTrue(events[0] is RunStarted, "events[0] must be RunStarted")
        assertTrue(events[1] is CompilationStarted, "events[1] must be CompilationStarted")
        assertTrue(events[2] is CompilationFinished, "events[2] must be CompilationFinished")
        assertTrue(events[3] is RunFinished, "events[3] must be RunFinished")

        val cf = events[2] as CompilationFinished
        assertEquals("v1", cf.cacheKey.version, "cacheKey.version must be v1")
        assertEquals(64, cf.cacheKey.value.length, "cacheKey.value must be 64-char hex")
    }

    @Test
    fun `two cli invocations yield structurally equal timelines`() {
        val (_, events1) = runAndDecode()
        val (_, events2) = runAndDecode()

        assertEquals(events1.size, events2.size, "Both runs must produce the same number of events")

        for (i in events1.indices) {
            val e1 = events1[i]
            val e2 = events2[i]
            assertEquals(e1.kind, e2.kind, "Event $i kind must match")
            assertEquals(e1.runId, e2.runId, "Event $i runId must match")
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
