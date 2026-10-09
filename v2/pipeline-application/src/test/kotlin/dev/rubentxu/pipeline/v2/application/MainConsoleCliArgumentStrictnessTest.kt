package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

/**
 * AUD-04 parity for the `console` verb — the second instance of the parser laxity that the external
 * audit named in `events`, in a file it did not open.
 *
 * ## Why this file exists when the audit named only `events`
 *
 * [MainConsoleCli]'s parser had the same shape: an `else` arm that completed as `Unit` for an unknown
 * `--flag` and for a third positional. Unlike `events` it also read `--max-bytes` with
 * `?: DEFAULT_PAGE_BYTES`, so `--max-bytes abc` silently became the default page size with exit 0.
 *
 * Both defects predate this branch's OBS work — they are present at the common ancestor `2e9b4824`,
 * which is why no commit of mine introduced them — and both were independently fixed on
 * `s6-plugin-sdk` under AUD-04. Aligning here is not importing new semantics: it is making this branch
 * agree with a contract S6 has already accepted, so the two copies of the file answer identically no
 * matter which one survives a merge. Reported to the user as an extension of the audit's finding.
 *
 * ## The production entry point this crosses
 *
 * [MainConsoleCli.main], in process. Every row below is a refusal decided during argument parsing, so
 * none of them reaches a store, a journal or a control dir: that is the property being asserted, not
 * a limitation of the harness. HF1, in-process, real entry point.
 *
 * ## The mutations that must kill this
 *
 * - **D-CON-1** — replace `arg.startsWith("--")` in the `else` arm with a constant `false`, restoring
 *   the "falls through as Unit" behaviour for an unknown flag. Kills CONSOLE-UNKNOWN alone.
 * - **D-CON-1b** — replace the trailing `else` arm with `Unit`, restoring the dropped third positional.
 *   Kills CONSOLE-EXTRA alone.
 *   These two are separate mutations because they are separate guards, and the first version of this
 *   note claimed D-CON-1 killed both. The measurement said otherwise — CONSOLE-EXTRA stayed green
 *   under D-CON-1, because the extra-positional arm does not depend on the unknown-option arm. The
 *   claim was wrong and the split mutation is what makes both guards demonstrably load-bearing.
 * - **D-CON-2** — restore `--max-bytes -> …toIntOrNull() ?: DEFAULT_PAGE_BYTES`, which sets
 *   `maxBytesArg = null` on an unparseable value. Kills CONSOLE-MAXBYTES alone.
 * - **D-CON-3** — refuse everything from the top of `main`. Killed by CONSOLE-MISSING-ARG, the only row
 *   asserting a served refusal of a well-formed shape, so "always 2" cannot pass the three
 *   strictness rows.
 */
@DisplayName("AUD-04 — el verbo console ejecuta el comando que se le escribió, o lo rechaza")
class MainConsoleCliArgumentStrictnessTest {

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String) {
        fun stdoutLines(): List<String> = stdout.lineSequence().filter { it.isNotBlank() }.toList()
    }

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
        return CliResult(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    @Test
    fun `CONSOLE-MISSING-ARG la invocacion incompleta sigue pidiendo uso y sale con 2`(@TempDir dir: Path) {
        val result = cli("--control-dir", dir.toString(), "run-x")

        assertEquals(2, result.exitCode)
        assertTrue(
            result.stderr.contains("Usage: pipeline console"),
            "the ordinary missing-argument refusal is unchanged, which is what D-CON-3 would break; " +
                "stderr:\n${result.stderr}",
        )
        assertTrue(result.stdoutLines().isEmpty(), "and nothing was written to stdout")
    }

    @Test
    fun `CONSOLE-UNKNOWN una opcion desconocida se rechaza antes de tocar el disco`(@TempDir dir: Path) {
        val missing = dir.resolve("no-such-control-dir")

        val result = cli("--control-dir", missing.toString(), "run-x", "op-x", "--verbose")

        assertEquals(2, result.exitCode, "an option this build does not have is an error, not a no-op")
        assertTrue(
            result.stderr.contains("unknown option: --verbose"),
            "and it names it; stderr:\n${result.stderr}",
        )
        assertTrue(
            !result.stderr.contains("control dir not found"),
            "the flag is refused while parsing, so a caller fixing one error at a time is not sent after " +
                "a second one that a corrected flag would remove; stderr:\n${result.stderr}",
        )
    }

    @Test
    fun `CONSOLE-EXTRA un tercer posicional se rechaza en vez de descartarse`(@TempDir dir: Path) {
        val result = cli("--control-dir", dir.toString(), "run-x", "op-x", "op-z")

        assertEquals(2, result.exitCode, "the command reads ONE run and ONE op")
        assertTrue(
            result.stdoutLines().isEmpty(),
            "and it did not answer for the pair it silently substituted",
        )
        assertTrue(
            result.stderr.contains("unexpected extra argument: op-z"),
            "naming what was dropped is what makes this diagnosable; stderr:\n${result.stderr}",
        )
    }

    @Test
    fun `CONSOLE-MAXBYTES un valor no numerico se rechaza en vez de volverse el default`(@TempDir dir: Path) {
        listOf("abc", "0", "-1").forEach { bad ->
            val result = cli("--control-dir", dir.toString(), "run-x", "op-x", "--max-bytes", bad)
            assertEquals(
                2,
                result.exitCode,
                "--max-bytes $bad must be refused, not silently read as DEFAULT_PAGE_BYTES",
            )
            assertTrue(
                result.stderr.contains("--max-bytes must be a positive integer"),
                "and the message says which flag; stderr:\n${result.stderr}",
            )
        }
    }
}