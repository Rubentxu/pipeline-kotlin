package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The arity of the request the shared parser admits.
 *
 * ## The guarantee
 *
 * `run` and `validate` own exactly ONE positional argument — the script path
 * — and a request that carries another one is REFUSED, naming the token,
 * before any effect. The operator must never read an answer about a request
 * nobody made: measured on the built distribution, `run s.kts extra-arg` RAN
 * the pipeline and said NOTHING about `extra-arg`, and `validate s.kts
 * extra-arg` answered "VALIDATION SUCCESSFUL" with the byte-identical output
 * of the request that carries no extra argument.
 *
 * ## The capacity this serves
 *
 * The CLI's own vocabulary of what a request may carry. A verb that owns one
 * positional and is handed a second one has to say so, through the one table
 * that renders refusals, and the token is what the operator has to remove.
 *
 * ## Why this instrument
 *
 * [CliExecutionParser.parse] is pure: it opens nothing, reads nothing and writes
 * nothing, and it answers with a typed outcome. The cheapest instrument that
 * can fail is to call it and read that outcome — no process, no filesystem, no
 * clock.
 *
 * The boundary ABOVE it is where the defect was MEASURED (the built
 * distribution), but a unit row cannot observe the absence of an effect on a
 * filesystem, and a unit row that tried to would be testing the wrong thing:
 * the guarantee is that the request never becomes a run, and it cannot become
 * one because the parser refuses it first.
 *
 * ## What this instrument cannot see
 *
 * The WORDS the operator reads are the renderer's, and they are guarded in
 * [CliErrorRenderingTest]. The exit code and the stderr of the binary belong
 * to the CLI as a whole, and their measurement belongs to the distribution.
 */
class CliExecutionPositionalTest {

    @Test
    fun `run refuses the positional argument it does not own`() {
        val result = CliExecutionParser.parse(arrayOf("run", "s.pipeline.kts", "extra-arg"))

        assertEquals(CliParseResult.Rejected(CliError.TooManyPositionals("extra-arg")), result)
    }

    @Test
    fun `validate refuses the positional argument it does not own`() {
        val result = CliExecutionParser.parse(arrayOf("validate", "s.pipeline.kts", "extra-arg"))

        assertEquals(CliParseResult.Rejected(CliError.TooManyPositionals("extra-arg")), result)
    }

    @Test
    fun `an option before the script path does not change the arity`() {
        val result = CliExecutionParser.parse(arrayOf("run", "--db", "/tmp/pipelinek.db", "s.pipeline.kts", "extra-arg"))

        assertEquals(CliParseResult.Rejected(CliError.TooManyPositionals("extra-arg")), result)
    }

    @Test
    fun `the request that owns exactly one positional argument is still admitted`() {
        val result = CliExecutionParser.parse(arrayOf("run", "s.pipeline.kts"))

        assertTrue(result is CliParseResult.Parsed, result.toString())
    }

    @Test
    fun `a trailing option is still the refusal it was`() {
        val result = CliExecutionParser.parse(arrayOf("run", "s.pipeline.kts", "--format", "text"))

        assertEquals(CliParseResult.Rejected(CliError.TrailingOption("--format")), result)
    }

    @Test
    fun `a request carrying a trailing option and a trailing positional names the option first`() {
        val result = CliExecutionParser.parse(arrayOf("run", "s.pipeline.kts", "extra-arg", "--format", "text"))

        assertEquals(CliParseResult.Rejected(CliError.TrailingOption("--format")), result)
    }
}
