package dev.rubentxu.pipeline.v2.application

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * B1c / AUD-04 — the console CLI must refuse an unparseable `--max-bytes`, coherently with the rest
 * of its own file.
 *
 * ## The defect this pins
 *
 * `--max-bytes` read `args.getOrNull(++i)?.toIntOrNull() ?: DEFAULT`, so a value that did not parse
 * silently became the default page size, while the NEXT lines of the same function already returned
 * `2` for `maxBytes <= 0`. The file contradicted itself: `0` was an error, `abc` was a shrug. A flag
 * that does not parse must not become the default, because the caller then gets output shaped by a
 * value they never supplied.
 *
 * ## Fidelity and hermeticity
 *
 * [MainConsoleCli.main] is the production entry point, run in process. The refusal rows exercise the
 * real argv parser and stop before any store read. The positive control (a well-formed `--max-bytes`)
 * shows the rejection is not simply "always refuse": it reaches the read path. `@TempDir` supplies
 * the control directory; both process streams are captured and restored in `finally`; no network, no
 * `/tmp`, no wall-clock assertion.
 *
 * ## The mutation that must kill this
 *
 * Restoring `--max-bytes` to `args.getOrNull(++i)?.toIntOrNull() ?: DEFAULT` makes the `abc` row see
 * the default page size and a non-2 exit, failing the `assertEquals(2, ...)`.
 */
@Timeout(60)
@DisplayName("B1c — la CLI de consola rechaza un --max-bytes no convertible")
class MainConsoleCliArgumentContractTest {

    private data class CliResult(val exitCode: Int, val stderr: String)

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
                    MainConsoleCli.main(arrayOf(*args))
                }
            }
        } finally {
            System.setOut(savedOut)
            System.setErr(savedErr)
        }
        return CliResult(exit, err.toString(Charsets.UTF_8))
    }

    @Test
    fun `a non-numeric or non-positive --max-bytes is refused rather than defaulted`(@TempDir root: Path) {
        for (bad in listOf("abc", "0", "-3", "1.5")) {
            val result = cli(
                "--control-dir", root.toString(), "run-1", "op-1", "--max-bytes", bad,
            )
            assertEquals(
                2,
                result.exitCode,
                "--max-bytes $bad must be refused, not silently read as the default page size; " +
                    "stderr:\n${result.stderr}",
            )
            assertTrue(
                result.stderr.contains("--max-bytes"),
                "the refusal must name the flag; stderr:\n${result.stderr}",
            )
        }
    }

    @Test
    fun `an unknown option is refused instead of being ignored`(@TempDir root: Path) {
        val result = cli(
            "--control-dir", root.toString(), "run-1", "op-1", "--bogus",
        )
        assertEquals(
            2,
            result.exitCode,
            "an unknown option must be a usage error, not silently ignored; stderr:\n${result.stderr}",
        )
        assertTrue(
            result.stderr.contains("--bogus"),
            "the refusal must NAME the option; stderr:\n${result.stderr}",
        )
    }

    @Test
    fun `a third positional is refused instead of being dropped`(@TempDir root: Path) {
        // The command reads ONE run and ONE op. A third positional used to be discarded while the
        // first two were kept, so the invocation silently narrowed to something the caller did not
        // type.
        val result = cli(
            "--control-dir", root.toString(), "run-1", "op-1", "op-2",
        )
        assertEquals(
            2,
            result.exitCode,
            "an extra positional must be a usage error; stderr:\n${result.stderr}",
        )
        assertTrue(
            result.stderr.contains("op-2"),
            "the refusal must NAME the extra argument; stderr:\n${result.stderr}",
        )
    }

    @Test
    fun `an option whose value is missing is refused rather than defaulted`(@TempDir root: Path) {
        val result = cli(
            "--control-dir", root.toString(), "run-1", "op-1", "--max-bytes",
        )
        assertEquals(
            2,
            result.exitCode,
            "a missing --max-bytes value must be a usage error, not the default page size; " +
                "stderr:\n${result.stderr}",
        )
        assertTrue(
            result.stderr.contains("--max-bytes"),
            "the refusal must name the flag; stderr:\n${result.stderr}",
        )
    }

    @Test
    fun `a well-formed --max-bytes is not refused by the parser`(@TempDir root: Path) {
        // Positive control: with a valid value the parser must let the command through, so the
        // rejection above cannot be "the CLI always exits 2". The run/op are unknown here, so the
        // read is refused by the plane (exit 1) -- the point is only that it is NOT a parse error.
        val result = cli(
            "--control-dir", root.toString(), "run-1", "op-1", "--max-bytes", "128",
        )
        assertNotEquals(2, result.exitCode, "a valid --max-bytes must not be a usage error")
    }
}
