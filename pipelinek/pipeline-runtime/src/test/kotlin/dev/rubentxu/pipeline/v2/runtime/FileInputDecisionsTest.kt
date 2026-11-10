package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.runtime.durable.OpId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * What a run publishes when it asks, and what it takes as an answer.
 *
 * ## The guarantee under trial
 *
 * That the question a run publishes can be read by somebody else — whatever its text — and says
 * which run and which operation asked it and when it stops being answerable; that an answer is
 * taken only from the file of the question that was asked, so an operation of another stage or
 * another branch never receives it; and that an answer already filed is taken without asking
 * again.
 *
 * ## Why this instrument
 *
 * The adapter is tried over a real directory, because what it decides —what is written, where
 * and what is read back— is a property of the files. The clock is a double that jumps past the
 * deadline after the first reading, so no row waits for a real timeout.
 *
 * ## What this does not freeze
 *
 * The interval of the polling and the temporary names used while a question is being written.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class FileInputDecisionsTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `the question is a valid document whatever the message holds`() {
        val message = "Deploy?\nline \"two\" \\ with a\ttab and a \u0001 control, café 🚀"

        ask(operation("run-1-s0-2"), message = message, waitMillis = 5_000L)

        val raw = Files.readString(requestOf("run-1-s0-2"))
        Json.parseToJsonElement(raw)
        assertEquals(message, InputQuestionCodec.decode(raw)?.message)
    }

    @Test
    fun `the question says which run and which operation asked it and when it stops being answerable`() {
        ask(operation("run-1-s0-2"), waitMillis = 5_000L)

        val published = publishedBy("run-1-s0-2")
        assertEquals("run-1", published.runId)
        assertEquals("run-1-s0-2", published.opId)
        assertEquals(1_000L, published.publishedAtMs)
        assertEquals(6_000L, published.expiresAtMs, "the run stops waiting five seconds after it asked")
    }

    @Test
    fun `a question that waits without a bound never expires`() {
        answerFile("run-1-s0-2", PROCEED)

        ask(operation("run-1-s0-2"), waitMillis = null)

        val published = publishedBy("run-1-s0-2")
        assertEquals(null, published.expiresAtMs)
    }

    @Test
    fun `an answer already filed is taken without waiting for it`() {
        answerFile("run-1-s0-2", PROCEED)

        val resolution = ask(operation("run-1-s0-2"), waitMillis = 5_000L)

        val answered = assertInstanceOf(InputResolution.Answered::class.java, resolution)
        assertEquals(InputDecision.Proceed("alice", "go"), answered.decision)
    }

    @Test
    fun `an abort already filed is taken as an abort`() {
        answerFile("run-1-s0-2", """{"decision":"ABORT","submitter":"bob"}""")

        val resolution = ask(operation("run-1-s0-2"), waitMillis = 5_000L)

        val answered = assertInstanceOf(InputResolution.Answered::class.java, resolution)
        assertEquals(InputDecision.Abort("bob", null), answered.decision)
    }

    @Test
    fun `an answer that is not complete does not end the wait`() {
        answerFile("run-1-s0-2", """{"decision":"PRO""")

        val resolution = ask(operation("run-1-s0-2"), waitMillis = 5_000L)

        assertInstanceOf(InputResolution.Denied::class.java, resolution)
    }

    @Test
    fun `the answer to one operation is not the answer to another of the same step index`() {
        val sameStepOtherStage = OpId("run-1", stageIndex = 1, stepIndex = 2).format()
        val sameStepOtherBranch = OpId("run-1", stageIndex = 0, stepIndex = 2, branchIndex = 1).format()
        answerFile(OpId("run-1", stageIndex = 0, stepIndex = 2).format(), PROCEED)

        val otherStage = ask(operation(sameStepOtherStage), waitMillis = 5_000L)
        val otherBranch = ask(operation(sameStepOtherBranch), waitMillis = 5_000L)

        assertInstanceOf(InputResolution.Denied::class.java, otherStage)
        assertInstanceOf(InputResolution.Denied::class.java, otherBranch)
    }

    @Test
    fun `operations that differ in stage or branch file their questions apart`() {
        val operations = listOf(
            OpId("run-1", stageIndex = 0, stepIndex = 2),
            OpId("run-1", stageIndex = 1, stepIndex = 2),
            OpId("run-1", stageIndex = 0, stepIndex = 2, branchIndex = 0),
            OpId("run-1", stageIndex = 0, stepIndex = 2, branchIndex = 1),
        ).map { it.format() }

        operations.forEach { ask(operation(it), waitMillis = 5_000L) }

        operations.forEach { opId ->
            val published = publishedBy(opId)
            assertEquals(opId, published.opId, "each question is filed under the operation that asked it")
        }
        assertEquals(operations.size, Files.list(tmp).use { it.count() }.toInt(), "one directory per operation")
    }

    @Test
    fun `publishing leaves nothing but the question in its directory`() {
        ask(operation("run-1-s0-2"), waitMillis = 5_000L)

        val entries = Files.list(requestOf("run-1-s0-2").parent).use { stream ->
            stream.map { it.fileName.toString() }.toList()
        }
        assertEquals(listOf(InputQuestionLayout.REQUEST_FILE), entries)
    }

    @Test
    fun `a question that cannot be filed is refused with the path and leaves no residue`() {
        val dir = tmp.resolve(InputQuestionLayout.directoryName("run-1-s0-2"))
        val blocker = dir.resolve(InputQuestionLayout.REQUEST_FILE)
        Files.createDirectories(blocker)
        Files.writeString(blocker.resolve("occupied"), "a directory where the question goes")

        val refusal = assertThrows(UnanswerableException::class.java) {
            ask(operation("run-1-s0-2"), waitMillis = 5_000L)
        }

        assertTrue(refusal.message.orEmpty().contains(blocker.toString()), "the refusal names where: ${refusal.message}")
        val entries = Files.list(dir).use { stream -> stream.map { it.fileName.toString() }.toList() }
        assertEquals(listOf(InputQuestionLayout.REQUEST_FILE), entries, "the staged copy is not left behind")
    }

    @Test
    fun `a reader that lists the questions while one is republished never sees half of it`() {
        val message = "x".repeat(2_000_000)
        ask(operation("run-1-s0-2"), message = message, waitMillis = 5_000L)
        val file = requestOf("run-1-s0-2")
        val stop = AtomicBoolean(false)
        val reads = AtomicInteger(0)
        val torn = AtomicInteger(0)
        val reader = thread(start = true, isDaemon = true) {
            while (!stop.get()) {
                val raw = try {
                    Files.readString(file)
                } catch (_: NoSuchFileException) {
                    torn.incrementAndGet()
                    continue
                }
                reads.incrementAndGet()
                if (InputQuestionCodec.decode(raw) == null) torn.incrementAndGet()
            }
        }

        repeat(REPUBLISHES) { ask(operation("run-1-s0-2"), message = message, waitMillis = 5_000L) }
        stop.set(true)
        reader.join()

        assertTrue(reads.get() > 0, "the reader looked at the question while it was being republished")
        assertEquals(0, torn.get(), "every document the reader found was a complete question")
    }

    @Test
    fun `asking again rewrites the question over the same file`() {
        ask(operation("run-1-s0-2"), message = "first", waitMillis = 5_000L)
        ask(operation("run-1-s0-2"), message = "second", waitMillis = 5_000L)

        val published = publishedBy("run-1-s0-2")
        assertEquals("second", published.message)
    }

    // --- fixtures ---

    private fun operation(opId: String) = InputOperation(runId = "run-1", opId = opId)

    private fun ask(operation: InputOperation, message: String = "Deploy?", waitMillis: Long?): InputResolution {
        var reads = 0
        val adapter = FileInputDecisions(
            root = tmp,
            operation = operation,
            pollIntervalMs = 1L,
            // 1000 when the wait starts and past any deadline afterwards, so a wait that nobody
            // answers ends at once and the rows do not sleep.
            nowMillis = { if (reads++ == 0) 1_000L else 1_000_000L },
        )
        return runBlocking {
            adapter.awaitDecision(
                InputRequest(message = message, ok = "Proceed", submitter = null, id = null),
                waitMillis,
            )
        }
    }

    private fun publishedBy(opId: String): PublishedInputQuestion =
        checkNotNull(InputQuestionCodec.decode(Files.readString(requestOf(opId)))) {
            "the question of '$opId' is not a complete document"
        }

    private fun requestOf(opId: String): Path =
        tmp.resolve(InputQuestionLayout.directoryName(opId)).resolve(InputQuestionLayout.REQUEST_FILE)

    private fun answerFile(opId: String, content: String) {
        val dir = tmp.resolve(InputQuestionLayout.directoryName(opId))
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(InputQuestionLayout.RESPONSE_FILE), content)
    }

    private companion object {
        const val REPUBLISHES: Int = 40
        const val PROCEED: String = """{"decision":"PROCEED","submitter":"alice","message":"go"}"""
    }
}
