package dev.rubentxu.pipeline.v2.events

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * EVT-CR-006: Structural grep gate ensuring no Map<String,String> in event variants.
 * CR-RD-009: Same gate for RedactingEventSink surface.
 *
 * Uses real shell invocation per AGENTS.md rule 25 (canary for grep gate).
 */
class EventSchemaNoMapStringStringTest {

    @Test
    fun `EVT-CR-006 no MapStringString in event variant classes`() {
        val result = ProcessRunner.run(
            "grep",
            listOf("-rE", "Map<String,String>", "v2/pipeline-events/src/main/kotlin")
        )
        assertEquals(
            1,  // grep returns 1 when no matches found
            result.exitCode,
            "grep should return 1 (no matches). Output: ${result.output}"
        )
    }

    @Test
    fun `CR-RD-009 no MapStringString in event-variant DomainEvent classes`() {
        // Explicit coverage for event-variant classes specifically
        val result = ProcessRunner.run(
            "grep",
            listOf("-rE", "Map<String,String>", "v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/DomainEvent.kt")
        )
        assertEquals(
            1,
            result.exitCode,
            "DomainEvent.kt should have no Map<String,String>. Output: ${result.output}"
        )
    }

    /**
     * Helper to run a shell process and capture output.
     */
    private object ProcessRunner {
        data class Result(val exitCode: Int, val output: String)

        fun run(command: String, args: List<String>): Result {
            val process = ProcessBuilder(listOf(command) + args)
                .directory(java.io.File("/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin"))
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            return Result(exitCode, output)
        }
    }
}
