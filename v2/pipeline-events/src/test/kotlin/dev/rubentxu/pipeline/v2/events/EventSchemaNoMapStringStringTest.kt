package dev.rubentxu.pipeline.v2.events

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * EVT-CR-006: Structural grep gate ensuring no Map<String,String> in event variants.
 * CR-RD-009: Same gate for RedactingEventSink surface.
 *
 * Uses real shell invocation per AGENTS.md rule 25 (canary for grep gate).
 */
class EventSchemaNoMapStringStringTest {

    /**
     * Both main source trees of the event plane, scanned separately.
     *
     * BLOCK 2 split the plane, and the codecs that encode and decode events moved to
     * `:pipeline-events-store` with the rest of the durable implementation. A grep over the contract
     * module alone would have gone on reporting exit 1 while reading half the subject — the same
     * green-that-measures-part-of-the-thing this repo has paid for more than once.
     */
    private val eventSourceTrees = listOf(
        "v2/pipeline-events/src/main/kotlin",
        "v2/pipeline-events-store/src/main/kotlin",
    )

    /**
     * The shape the gate forbids, tolerant of the one space Kotlin style may put after the comma.
     *
     * The pattern used to be the bare literal `Map<String,String>`, which matched exactly one of
     * the two spellings a Kotlin author actually writes. `Map<String, String>` — the spelling
     * ktlint and IDE formatting produce — walked straight through the gate that exists to stop it.
     * A guard that only catches the spelling nobody types is not a weaker guard, it is a decoration.
     */
    private val forbiddenShape = "Map<String, ?String>"

    @Test
    fun `EVT-CR-006 no MapStringString in event variant classes`() {
        for (tree in eventSourceTrees) {
            val result = ProcessRunner.run(
                "grep",
                listOf("-rE", forbiddenShape, tree)
            )
            assertEquals(
                1,  // grep returns 1 when no matches found
                result.exitCode,
                "grep should return 1 (no matches) under $tree. Output: ${result.output}"
            )
        }
    }

    @Test
    fun `CR-RD-009 no MapStringString in event-variant DomainEvent classes`() {
        // Explicit coverage for event-variant classes specifically
        val result = ProcessRunner.run(
            "grep",
            listOf("-rE", forbiddenShape, "v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/DomainEvent.kt")
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
                .directory(TestProjectRoot.dir)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            return Result(exitCode, output)
        }
    }
}
