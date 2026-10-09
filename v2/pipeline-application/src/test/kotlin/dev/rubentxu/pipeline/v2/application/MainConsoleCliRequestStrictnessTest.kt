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
 * `pipeline console` had two ways of reading, chosen by a branch, with no type saying which was asked for.
 *
 * ## The defect
 *
 * `main` collected `--range`, `--after-cursor` and `--max-bytes` into three locals and then:
 *
 * ```kotlin
 * val result = if (range != null) { readRange(...) } else { read(..., after, maxBytes) }
 * ```
 *
 * A combination was not refused, it was IGNORED. `--range 0:10 --after-cursor TOKEN` parsed the token
 * successfully and then discarded it, and `--max-bytes 5` was discarded too. Both exited 0 having
 * answered a narrower question than the one that was asked, and neither stderr line said so.
 *
 * Worse, the two readings are not variants of one thing. A page CONTINUES after a cursor and is
 * bounded by a budget; a range NAMES A SPAN of the merged offset space and is not resumable, because
 * continuation is expressed with a cursor and a span across two channel streams has no honest single
 * cursor. A `String?` for each and an `if` between them is the shape that let the combination go
 * unnoticed: nothing in the program said the two are different requests.
 *
 * ## What this asserts
 *
 * [MainConsoleCli.main] in process. Every refusal below is decided during argument validation, before
 * a store is opened, so the property being asserted is that no store was reached — not a limitation
 * of the harness. HF1, in-process, real entry point.
 *
 * ## The mutations that must kill this
 *
 * - **REQ-1** — replace the refusal with the old `if (range != null)` branch. Killed by
 *   CONSOLE-RANGE-CURSOR and CONSOLE-RANGE-BYTES together.
 * - **REQ-2** — refuse only `--after-cursor` and drop the `--max-bytes` case. Killed by
 *   CONSOLE-RANGE-BYTES alone, which is why the two are separate rows rather than one loop.
 * - **REQ-3** — validate types BEFORE conflicts, so `--range --max-bytes abc` complains about the
 *   number. Killed by CONSOLE-RANGE-BEATS-TYPE, because the flag is wrong regardless of its value.
 * - **REQ-4** — write the refusal to stdout as well as stderr. Killed by every stdout-empty
 *   assertion here.
 */
@DisplayName("consola — lectura paginada y lectura por rango son dos peticiones distintas, no un if")
class MainConsoleCliRequestStrictnessTest {

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
        } catch (thrown: Throwable) {
            return CliResult(-1, out.toString(Charsets.UTF_8), "THREW ${thrown::class.simpleName}: ${thrown.message}")
        } finally {
            System.setOut(savedOut)
            System.setErr(savedErr)
        }
        return CliResult(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    private fun refuse(
        @Suppress("SameParameterValue") result: CliResult,
        vararg named: String,
    ): CliResult {
        assertEquals(2, result.exitCode, "a combination with no meaning is a refusal, not a narrower read")
        named.forEach { token ->
            assertTrue(
                result.stderr.contains(token),
                "the refusal names '$token', or it does not say which flag is wrong; stderr:\n${result.stderr}",
            )
        }
        assertTrue(
            result.stdoutLines().isEmpty(),
            "a refusal is not an empty page: stdout must carry nothing, stderr:\n${result.stderr}",
        )
        return result
    }

    // ---------------------------------------------------------------- The two requests must not be combinable

    @Test
    fun `CONSOLE-RANGE-CURSOR un rango con cursor se rechaza en vez de ignorar el cursor`(@TempDir dir: Path) {
        val token = "out-cursor-v1:" + java.net.URLEncoder.encode("run-x/op-y/stdout", Charsets.UTF_8) + ":10"

        val result = cli("--control-dir", dir.toString(), "run-x", "op-y", "--range", "0:10", "--after-cursor", token)

        refuse(result, "--range", "--after-cursor")
    }

    @Test
    fun `CONSOLE-RANGE-BYTES un rango con presupuesto se rechaza en vez de ignorar el presupuesto`(@TempDir dir: Path) {
        val result = cli("--control-dir", dir.toString(), "run-x", "op-y", "--range", "0:10", "--max-bytes", "5")

        refuse(result, "--range", "--max-bytes")
    }

    @Test
    fun `CONSOLE-RANGE-BEATS-TYPE el conflicto se dice antes que el numero`(@TempDir dir: Path) {
        val result = cli("--control-dir", dir.toString(), "run-x", "op-y", "--range", "0:10", "--max-bytes", "abc")

        refuse(result, "--range", "--max-bytes")
        assertTrue(
            !result.stderr.contains("must be a positive integer"),
            "the flag has no meaning here, so fixing its value would not help. Answering 'abc is not a " +
                "number' sends the caller to edit a token they should delete; stderr:\n${result.stderr}",
        )
    }

    // ---------------------------------------------------------------- The refusals that already existed

    @Test
    fun `CONSOLE-RANGE-SHAPE un rango mal formado se rechaza nombrando la opcion`(@TempDir dir: Path) {
        listOf("10", "a:b", "10:20:30", "").forEach { bad ->
            val result = cli("--control-dir", dir.toString(), "run-x", "op-y", "--range", bad)
            refuse(result, "--range")
        }
    }

    @Test
    fun `CONSOLE-CURSOR un token que no es cursor se rechaza`(@TempDir dir: Path) {
        val result = cli("--control-dir", dir.toString(), "run-x", "op-y", "--after-cursor", "evt-cursor-v1:run-x:3")

        refuse(result, "cursor")
    }

    @Test
    fun `CONSOLE-NOSTORE un rechazo se decide sin abrir el plano`(@TempDir dir: Path) {
        // The control dir exists but holds no output plane. Every refusal below must still be a clean
        // exit 2 rather than whatever the store says about an operation that was never written.
        val missing = dir.resolve("nope")

        val result = cli("--control-dir", missing.toString(), "run-x", "op-y", "--range", "0:10", "--max-bytes", "5")

        refuse(result, "--range", "--max-bytes")
    }

    // ---------------------------------------------------------------- Guards: behaviour that must NOT change

    @Test
    fun `GUARD-MISSING la invocacion incompleta sigue pidiendo uso`(@TempDir dir: Path) {
        val result = cli("--control-dir", dir.toString(), "run-x")

        assertEquals(2, result.exitCode)
        assertTrue(
            result.stderr.contains("Usage: pipeline console"),
            "AUD-04 parity for `console` is unchanged; stderr:\n${result.stderr}",
        )
    }

    @Test
    fun `GUARD-UNKNOWN una opcion desconocida se sigue rechazando al parsear`(@TempDir dir: Path) {
        val result = cli("--control-dir", dir.toString(), "run-x", "op-y", "--verbose")

        refuse(result, "--verbose")
    }

    @Test
    fun `GUARD-EXTRA un tercer posicional se sigue rechazando`(@TempDir dir: Path) {
        val result = cli("--control-dir", dir.toString(), "run-x", "op-y", "op-z")

        refuse(result, "op-z")
    }

    @Test
    fun `GUARD-MAXBYTES un presupuesto no numerico se sigue rechazando`(@TempDir dir: Path) {
        listOf("abc", "0", "-1").forEach { bad ->
            val result = cli("--control-dir", dir.toString(), "run-x", "op-y", "--max-bytes", bad)
            assertEquals(2, result.exitCode, "--max-bytes $bad")
            assertTrue(
                result.stderr.contains("--max-bytes must be a positive integer"),
                "and the message says which flag; stderr:\n${result.stderr}",
            )
        }
    }
}
