package dev.rubentxu.pipeline.v2.application

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * R4 / WU-R4.1.3 — `--log-tail-op <opId>` (per-operation console tail).
 *
 * Adds an opt-in flag to scope the `--log-tail` to ONE operation's stream-id prefix.
 * Where the run-wide tail answers "what did the run print at the end", the per-op
 * tail answers "what did THIS step print".
 *
 * The auto-binding (per-step → per-op without `--log-tail-op`) requires opId
 * resolution from the durable control journals and is documented in the WU-R4.1.3
 * receipt as a follow-up. This slice is the new CLI primitive, not the
 * cross-referenced auto-binding.
 */
@Timeout(60)
@DisplayName("R4 / WU-R4.1.3 — inspect --log-tail-op <opId> (per-op console tail)")
class MainInspectCliLogTailOpTest {

    // ------------------------------------------------------------------ HF0 parser

    @Test
    fun `parser accepts --log-tail-op with a non-blank opId`() {
        val r = MainInspectCli.parseInspect(
            arrayOf("--db", "events.sqlite", "--control-root", "control", "--log-tail-op", "op-1"),
        )
        assertTrue(r is InspectParseResult.Parsed)
        val q = (r as InspectParseResult.Parsed).value
        assertEquals("op-1", q.opId, "opId must round-trip from --log-tail-op to CompiledInspectionQuery")
    }

    @Test
    fun `parser refuses --log-tail-op with no value at the CLI layer, not silently`() {
        val r = MainInspectCli.parseInspect(
            arrayOf("--db", "events.sqlite", "--log-tail-op"),
        )
        assertTrue(r is InspectParseResult.Rejected, "missing value must be a typed rejection")
        assertTrue(
            (r as InspectParseResult.Rejected).error.contains("--log-tail-op requires a value"),
            "stderr-shaped reason must name --log-tail-op; got:\n${r.error}",
        )
    }

    @Test
    fun `parser refuses a blank --log-tail-op value with a typed reason`() {
        val r = MainInspectCli.parseInspect(
            arrayOf("--db", "events.sqlite", "--log-tail-op", ""),
        )
        assertTrue(r is InspectParseResult.Rejected)
        assertTrue(
            (r as InspectParseResult.Rejected).error.contains("must not be blank"),
            "blank value must be a typed rejection, not a silent default; got:\n${r.error}",
        )
    }

    @Test
    fun `parser combines --log-tail and --log-tail-op with --log-tail taking the byte budget and --log-tail-op taking the scope`() {
        val r = MainInspectCli.parseInspect(
            arrayOf(
                "--db", "events.sqlite",
                "--control-root", "control",
                "--log-tail", "512",
                "--log-tail-op", "op-1",
            ),
        )
        assertTrue(r is InspectParseResult.Parsed)
        val q = (r as InspectParseResult.Parsed).value
        assertEquals(512, q.logTail, "--log-tail must set the byte budget")
        assertEquals("op-1", q.opId, "--log-tail-op must set the scope; both flags coexist")
    }

    // ------------------------------------------------------------------ HF1 main() wiring

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun cli(vararg args: String): CliResult {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val savedOut = System.out
        val savedErr = System.err
        val exit = try {
            PrintStream(out, true, Charsets.UTF_8).use { stdout ->
                PrintStream(err, true, Charsets.UTF_8).use { stderr ->
                    System.setOut(stdout)
                    System.setErr(stderr)
                    MainInspectCli.main(arrayOf(*args))
                }
            }
        } finally {
            System.setOut(savedOut)
            System.setErr(savedErr)
        }
        return CliResult(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    @Test
    fun `main() refuses --log-tail-op with no value at the CLI layer`() {
        val r = cli("run-1", "--db", "events.sqlite", "--log-tail-op")
        assertEquals(2, r.exitCode)
        assertTrue(
            r.stderr.contains("--log-tail-op requires a value"),
            "stderr must name the missing-value reason; got:\n${r.stderr}",
        )
    }

    @Test
    fun `main() refuses a blank --log-tail-op value with a typed message`() {
        val r = cli("run-1", "--db", "events.sqlite", "--log-tail-op", "")
        assertEquals(2, r.exitCode)
        assertTrue(
            r.stderr.contains("must not be blank"),
            "stderr must name the blank-value reason; got:\n${r.stderr}",
        )
    }
}
