package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * An argument that names nothing is refused by the boundary that read it.
 *
 * ## The guarantee
 *
 * A read verb answers an invalid argument with the CLI's own form: one line that names what is
 * wrong, and the refusal exit code. It never answers with a Java stack trace.
 *
 * ## The capacity this serves
 *
 * An operator who writes `pipelinek console <runId> ""` is told that the operation id names
 * nothing. Before this, the same command answered with the trace of an internal invariant — a
 * line that says nothing about what to type next, and an exit code that says "unexpected crash"
 * where the operator had made a plain mistake.
 *
 * ## Why this instrument
 *
 * The observable is the ANSWER: the verb is called in process, its exit code is read and its
 * stderr captured. The control directory named in the request does not exist, and that is the
 * point rather than a shortcut: the refusal about the ARGUMENT has to arrive BEFORE the verb
 * asks anything of the filesystem, so a row that names the argument also proves the order.
 * Nothing is written and nothing is read.
 *
 * ## Why no deeper layer
 *
 * The defect is a boundary, and the boundary is decided with no IO at all: the parser reads an
 * array of tokens and returns a decision. A layer that composes the real modules would have to
 * open a store on disk to observe a decision that was already taken, and this repository has no
 * end-to-end layer to put it in.
 *
 * ## What this does not freeze
 *
 * The exact wording of the line is not frozen. What is frozen is that the line NAMES the
 * argument, that the exit code is the refusal code, and that no stack trace is printed.
 */
class ConsoleBlankArgumentTest {

    @Test
    fun `a blank operation id is refused by name`() {
        assertEquals(
            ConsoleCliRefusal.BlankArgument("opId"),
            refusalOf(arrayOf("--control-dir", CONTROL_DIR, RUN_ID, "")),
            "the blank operation id was not refused by name",
        )
    }

    @Test
    fun `the verb answers a blank operation id with the CLI's form and not a stack trace`() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = MainConsoleCli.main(
            arrayOf("--control-dir", CONTROL_DIR, RUN_ID, ""),
            PrintStream(out),
            PrintStream(err),
        )
        val stderr = err.toString()
        assertEquals(
            CliExit.INVALID_ARGUMENT.code,
            exit,
            "the blank argument did not get the refusal exit code",
        )
        assertTrue(stderr.contains("opId"), "the answer does not name the argument: $stderr")
        assertFalse(stderr.contains("Exception in thread"), "the answer is a crash: $stderr")
        assertFalse(
            stderr.lines().any { it.trimStart().startsWith("at ") },
            "the answer carries a stack trace: $stderr",
        )
        assertEquals("", out.toString(), "a refusal wrote to stdout")
    }

    @Test
    fun `a blank run id is refused by name`() {
        // The run id reaches the verb through two doors: the positional and the `--run`
        // override. Both are the same argument, and both have to be refused by the same name.
        assertEquals(
            ConsoleCliRefusal.BlankArgument("runId"),
            refusalOf(arrayOf("--control-dir", CONTROL_DIR, "", OP_ID)),
            "the blank run id was not refused by name",
        )
        assertEquals(
            ConsoleCliRefusal.BlankArgument("runId"),
            refusalOf(arrayOf("--control-dir", CONTROL_DIR, "--run", "", OP_ID)),
            "the blank run id override was not refused by name",
        )
    }

    @Test
    fun `the refusal does not depend on the read shape asked for`() {
        // The verb reads the console in more than one shape, and each of them used to defend
        // itself with its own invariant. The refusal belongs to the boundary, so every shape
        // gets the same answer.
        val shapes = listOf(
            arrayOf("--control-dir", CONTROL_DIR, RUN_ID, ""),
            arrayOf("--control-dir", CONTROL_DIR, RUN_ID, "", "--frames"),
            arrayOf("--control-dir", CONTROL_DIR, RUN_ID, "", "--frames", "--stage", "0"),
            arrayOf("--control-dir", CONTROL_DIR, RUN_ID, "", "--range", "0:10"),
            arrayOf("--control-dir", CONTROL_DIR, RUN_ID, "", "--max-bytes", "16"),
            arrayOf("--control-dir", CONTROL_DIR, "--run", RUN_ID, ""),
        )
        for (shape in shapes) {
            assertEquals(
                ConsoleCliRefusal.BlankArgument("opId"),
                refusalOf(shape),
                "the shape ${shape.toList()} was answered differently",
            )
        }
    }

    @Test
    fun `a real run id and operation id are still admitted`() {
        // The other half of the partition: a refusal that is too broad is a defect too, and it
        // would answer about a request the operator had written correctly.
        val result = CliConsoleParser.parse(arrayOf("--control-dir", CONTROL_DIR, RUN_ID, OP_ID))
        assertTrue(
            result is ConsoleCliParseResult.Admitted,
            "the console refused a real request: $result",
        )
    }

    private fun refusalOf(args: Array<String>): ConsoleCliRefusal =
        when (val result = CliConsoleParser.parse(args)) {
            is ConsoleCliParseResult.Rejected -> result.refusal
            is ConsoleCliParseResult.Admitted -> fail("the console admitted the request: ${result.request}")
        }

    private companion object {
        const val RUN_ID = "run-1"
        const val OP_ID = "run-1-s0-0"

        /** A control directory that does not exist: the verb never has to be asked about it. */
        const val CONTROL_DIR = "/tmp/pipelinek-console-blank-argument-absent"
    }
}
