package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.scripting.CacheKey
import dev.rubentxu.pipeline.v2.scripting.ScriptOrigin
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The receipt of a run: what the run concluded, and what it wrote.
 *
 * **The guarantee.** A receipt published for a run says the outcome the run published LAST,
 * counts the steps it finished, names the compilation it ran under with the version that
 * produced the digest, publishes each operation under its COMPLETE identity, and publishes the
 * operation's bytes so a reader gets back exactly what the operation wrote. A run that
 * published nothing says so with empty values instead of inventing them.
 *
 * **The capacity this serves.** A consumer that wants to know how a run ended and what it
 * left behind reads ONE file. It does not launch the product once per question, and it does
 * not have to re-derive anything: what the receipt says is what the run published.
 *
 * **Why this instrument.** Every decision under test is a PURE function over a list of
 * events, or over a document it renders. The cheapest instrument that can fail is to call
 * that function and read the result — no filesystem, no clock, no process, no journal.
 * The atomic write is deliberately NOT exercised here: proving it needs a real file on a
 * real filesystem, and a unit test in this tree does not touch the filesystem. What the
 * write guarantees is a property of the code that writes it, and the end-to-end scenarios
 * are where a file that appears is observed.
 *
 * **What this does not freeze.** The order of the keys inside the document, the wording of
 * any refusal, and the bytes on disk. It freezes the FACTS the document carries and the
 * shape it carries them in: the schema name, the verdict's three fields, the exit code and
 * the list of operations.
 */
class RunReceiptTest {

    @Test
    fun `a run that published nothing says nothing`() {
        val verdict = RunReceipt.verdictOf(emptyList())

        assertEquals("", verdict.outcome, "a run with no ending published no outcome")
        assertEquals(0, verdict.steps, "a run with no events finished no step")
        assertEquals("", verdict.cacheKey, "a run with no compilation published no cache key")
    }

    @Test
    fun `the last ending is the one the run stands behind`() {
        val verdict = RunReceipt.verdictOf(
            listOf(
                runFinished(RunOutcome.UNSTABLE_TOKEN),
                runFinished(RunOutcome.SUCCESS_TOKEN),
            ),
        )

        assertEquals(
            RunOutcome.SUCCESS_TOKEN,
            verdict.outcome,
            "the ending a run published last is the one that describes it",
        )
    }

    @Test
    fun `the steps of the run are counted`() {
        val verdict = RunReceipt.verdictOf(
            listOf(
                stepFinished("step-a"),
                stepFinished("step-b"),
                runFinished(RunOutcome.SUCCESS_TOKEN),
                stepFinished("step-c"),
            ),
        )

        assertEquals(3, verdict.steps, "every step the run finished is counted, wherever it sits")
    }

    @Test
    fun `the compilation identity is published with the version that produced it`() {
        val verdict = RunReceipt.verdictOf(
            listOf(compilationFinished(CacheKey(value = "abc123", version = "v3"))),
        )

        assertEquals("v3-abc123", verdict.cacheKey, "the key names the version and the digest together")
    }

    @Test
    fun `the last compilation is the one the run ran under`() {
        val verdict = RunReceipt.verdictOf(
            listOf(
                compilationFinished(CacheKey(value = "first", version = "v1")),
                compilationFinished(CacheKey(value = "second", version = "v2")),
            ),
        )

        assertEquals("v2-second", verdict.cacheKey, "the compilation the run ended with is the one it published")
    }

    @Test
    fun `a compilation with no digest publishes no cache key`() {
        val verdict = RunReceipt.verdictOf(
            listOf(compilationFinished(CacheKey(value = "", version = "v3"))),
        )

        assertEquals("", verdict.cacheKey, "a key with no digest is not a key, and is not published as one")
    }

    @Test
    fun `the document names its schema and the run it belongs to`() {
        val rendered = RunReceipt.render(
            RunReceipt.document(
                runId = "run-1",
                verdict = RunReceipt.Verdict(outcome = "success", steps = 2, cacheKey = "v3-abc"),
                exitCode = 0,
                operations = emptyList(),
            ),
        )

        val document = rendered.asDocument()
        assertEquals("pipelinek.run-receipt/1", document.string("schema"), "the document names its schema")
        assertEquals("run-1", document.string("runId"), "the document names the run it belongs to")
        assertEquals(0, document.int("exitCode"), "the document publishes the exit the run earned")
    }

    @Test
    fun `the exit the run earned is the exit the receipt publishes`() {
        val rendered = RunReceipt.render(
            RunReceipt.document(
                runId = "run-1",
                verdict = RunReceipt.Verdict(outcome = "failure", steps = 1, cacheKey = ""),
                exitCode = 1,
                operations = emptyList(),
            ),
        )

        assertEquals(
            1,
            rendered.asDocument().int("exitCode"),
            "the receipt publishes the exit the run earned, not a green one it did not earn",
        )
    }

    @Test
    fun `the verdict is published with its three facts`() {
        val rendered = RunReceipt.render(
            RunReceipt.document(
                runId = "run-1",
                verdict = RunReceipt.Verdict(outcome = "failure", steps = 3, cacheKey = "v3-abc"),
                exitCode = 1,
                operations = emptyList(),
            ),
        )

        val verdict = rendered.asDocument()["verdict"]!!.jsonObject
        assertEquals("failure", verdict.string("outcome"), "the outcome is published")
        assertEquals(3, verdict.int("steps"), "the number of steps is published")
        assertEquals("v3-abc", verdict.string("cacheKey"), "the compilation identity is published")
    }

    @Test
    fun `the operations keep their complete identity`() {
        val rendered = RunReceipt.render(
            RunReceipt.document(
                runId = "run-1",
                verdict = RunReceipt.Verdict(outcome = "success", steps = 1, cacheKey = ""),
                exitCode = 0,
                operations = listOf(RunReceipt.Operation(operation = "run-1-s0-0", bytes = "")),
            ),
        )

        val operation = rendered.asDocument()["operations"]!!.jsonArray.single().jsonObject
        assertEquals(
            "run-1-s0-0",
            operation.string("operation"),
            "the operation is published under the identity it was minted with, separators included",
        )
    }

    @Test
    fun `the bytes of an operation decode to what the operation wrote`() {
        val written = "hello, plane\n".toByteArray(Charsets.UTF_8)
        val encoded = Base64.getEncoder().encodeToString(written)

        val rendered = RunReceipt.render(
            RunReceipt.document(
                runId = "run-1",
                verdict = RunReceipt.Verdict(outcome = "success", steps = 1, cacheKey = ""),
                exitCode = 0,
                operations = listOf(RunReceipt.Operation(operation = "run-1-s0-0", bytes = encoded)),
            ),
        )

        val operation = rendered.asDocument()["operations"]!!.jsonArray.single().jsonObject
        assertEquals(
            "hello, plane\n",
            String(Base64.getDecoder().decode(operation.string("bytes")), Charsets.UTF_8),
            "the published bytes are the bytes the operation wrote",
        )
    }

    @Test
    fun `a run with no operations publishes an empty list`() {
        val rendered = RunReceipt.render(
            RunReceipt.document(
                runId = "run-1",
                verdict = RunReceipt.Verdict(outcome = "success", steps = 0, cacheKey = ""),
                exitCode = 0,
                operations = emptyList(),
            ),
        )

        assertEquals(
            0,
            rendered.asDocument()["operations"]!!.jsonArray.size,
            "a run that wrote nothing publishes no operations",
        )
    }

    @Test
    fun `the receipt is one line ended by a newline`() {
        val rendered = RunReceipt.render(
            RunReceipt.document(
                runId = "run-1",
                verdict = RunReceipt.Verdict(outcome = "success", steps = 1, cacheKey = "v3-abc"),
                exitCode = 0,
                operations = listOf(RunReceipt.Operation(operation = "run-1-s0-0", bytes = "aGk=")),
            ),
        )

        assertTrue(rendered.endsWith("\n"), "the receipt ends with a newline, so its last line is read")
        assertEquals(1, rendered.count { it == '\n' }, "the receipt is one line")
    }

    private fun String.asDocument(): JsonObject =
        Json.parseToJsonElement(this).jsonObject

    private fun JsonObject.string(name: String): String = this[name]?.jsonPrimitive?.content.orEmpty()

    private fun JsonObject.int(name: String): Int = this[name]?.jsonPrimitive?.content?.toInt() ?: -1

    /** The ending of a run, at a fixed instant: the decision under test reads the outcome word. */
    private fun runFinished(outcome: String): DomainEvent = RunFinished(
        eventId = "evt-1",
        runId = "run-1",
        sequence = 1L,
        occurredAt = Instant.EPOCH,
        outcome = outcome,
        diagnostics = emptyList(),
    )

    /** A step the run finished, at a fixed instant. */
    private fun stepFinished(name: String): DomainEvent = StepFinished(
        eventId = "evt-$name",
        runId = "run-1",
        sequence = 1L,
        occurredAt = Instant.EPOCH,
        stageIndex = 0,
        stepIndex = 0,
        stepName = name,
        stepType = "core.sh",
        outcome = "success",
    )

    /** A compilation the run published, at a fixed instant. */
    private fun compilationFinished(cacheKey: CacheKey): DomainEvent = CompilationFinished(
        eventId = "evt-compilation",
        runId = "run-1",
        sequence = 1L,
        occurredAt = Instant.EPOCH,
        cacheKey = cacheKey,
        diagnostics = emptyList(),
        origin = ScriptOrigin.COMPILED,
    )
}
