package dev.rubentxu.pipeline.v2.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * How a question of `core.input` is written to the control directory and where it is filed.
 *
 * ## The guarantee under trial
 *
 * That the document of a question is read back as the question that was written, whatever text
 * the pipeline author put in it; that anything that is not a complete question is read as none;
 * and that two different operations never share a directory, so that the answer of one is never
 * the answer of another.
 *
 * ## Why this instrument
 *
 * Both pieces are pure functions of their input, so they are tried with tables of inputs and no
 * file system. The inputs are the ones that separate an injective mapping from a lossy one: names
 * that differ only in a character the old mapping folded into an underscore, names that would
 * address a parent directory, and names too long for a file system.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class InputQuestionFilesTest {

    // --- the document ---

    @Test
    fun `a question is read back as it was written whatever its message holds`() {
        val question = question(
            message = "line one\nline \"two\" \\ with a\ttab, a \u0001 control and café 🚀",
            submitter = "release-managers",
            id = "deploy-gate",
            expiresAtMs = 6_000L,
        )

        val raw = InputQuestionCodec.encode(question)

        assertEquals(question, InputQuestionCodec.decode(raw))
    }

    @Test
    fun `the optional fields of a question survive being absent`() {
        val question = question(message = "go?", submitter = null, id = null, expiresAtMs = null)

        assertEquals(question, InputQuestionCodec.decode(InputQuestionCodec.encode(question)))
    }

    @Test
    fun `a document that is not a complete question is read as none`() {
        val complete = InputQuestionCodec.encode(question())
        val withoutRun = complete.replace("\"runId\":\"run-1\",", "")
        val withoutOperation = complete.replace("\"opId\":\"run-1-s0-2\",", "")
        val withoutPublication = complete.replace("\"publishedAtMs\":1000,", "")

        assertNull(InputQuestionCodec.decode("{\"message\":\"half of a docu"), "a truncated document")
        assertNull(InputQuestionCodec.decode("[]"), "JSON that is not an object")
        assertNull(InputQuestionCodec.decode(withoutRun), "no run to attribute it to")
        assertNull(InputQuestionCodec.decode(withoutOperation), "no operation to answer it under")
        assertNull(InputQuestionCodec.decode(withoutPublication), "no moment to expire it from")
        assertNull(
            InputQuestionCodec.decode("{\"message\":\"go?\",\"ok\":\"Proceed\",\"submitter\":null,\"id\":null}"),
            "a question written before the run was part of it",
        )
        assertTrue(complete != withoutRun && complete != withoutOperation && complete != withoutPublication)
    }

    // --- the directory ---

    @Test
    fun `a plain operation id is its own directory name`() {
        assertEquals("run-1-s0-2", InputQuestionLayout.directoryName("run-1-s0-2"))
    }

    @Test
    fun `two operations never share a directory`() {
        val operations = listOf(
            "run-1-s0-2",
            "run-1-s1-2",
            "run-1-s0-2-b0",
            "run-1-s0-2-b1",
            "run-1-s0-2-bp1-0:core.parallel",
            "run-1-s0-2-bp1-0_core.parallel",
            "run-1#2",
            "run-1_2",
            "a~b",
            "a~7Eb",
            "a%b",
            "a b",
            "a/b",
            "a\\b",
            "été",
            "ete",
            "",
            "unnamed",
            "~",
        )

        val names = operations.map(InputQuestionLayout::directoryName)

        assertEquals(operations.size, names.toSet().size, "every operation has a directory of its own: $names")
    }

    @Test
    fun `no operation id names a directory outside the one of the questions`() {
        val hostile = listOf("..", ".", "../escape", "a/../../b", "/etc/passwd", ".hidden", "a\u0000b")

        hostile.forEach { opId ->
            val name = InputQuestionLayout.directoryName(opId)
            assertFalse(name == "." || name == "..", "'$opId' must not name a parent: '$name'")
            assertFalse(name.contains('/') || name.contains('\\') || name.contains('\u0000'), "'$opId' -> '$name'")
        }
    }

    @Test
    fun `an operation id too long for a file system keeps a name it can use and a digest of the whole`() {
        val deep = "run-1-s0-2-bp9" + (0 until 12).joinToString("") { "-$it:core.sequence.with.a.long.name" }
        val sibling = deep + "x"

        val name = InputQuestionLayout.directoryName(deep)

        assertTrue(name.length <= 255, "a file system rejects a name past 255 bytes: ${name.length}")
        assertEquals(name, InputQuestionLayout.directoryName(deep), "the same operation always has the same name")
        assertNotEquals(name, InputQuestionLayout.directoryName(sibling), "the tail of the identity is not lost")
    }

    private fun question(
        message: String = "Deploy to production?",
        submitter: String? = "ops",
        id: String? = "gate",
        expiresAtMs: Long? = 2_000L,
    ) = PublishedInputQuestion(
        runId = "run-1",
        opId = "run-1-s0-2",
        message = message,
        ok = "Proceed",
        submitter = submitter,
        id = id,
        publishedAtMs = 1_000L,
        expiresAtMs = expiresAtMs,
    )
}
