package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.MainConsoleCli.ConsoleFormat
import dev.rubentxu.pipeline.v2.application.observation.ObservationJsonLines
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * R4 / WU-R4.3 — the closed `ConsoleFormat` ADT, the parser, and the JSONL envelope shape.
 *
 * The `--format` flag switches the console emitter between two cases:
 *  - `text` — the legacy raw-bytes path (default; unchanged from M1-P3).
 *  - `jsonl` — one [ObservationJsonLines] record per line, the canonical envelope
 *    `{type:"output", channel, operation, ordinal, from, to, text/base64}` that
 *    `pipelinek observe --view console --format jsonl` already produces.
 *
 * Adding a third case is a compile-time break for every caller of
 * [MainConsoleCli.ConsoleFormat.parse] and for [MainConsoleCli.emit].
 */
@Timeout(60)
@DisplayName("R4 / WU-R4.3 — console --format text|jsonl (closed ADT + parser + envelope)")
class MainConsoleFormatTest {

    // ------------------------------------------------------------------ HF0 closed ADT

    @Test
    fun `ConsoleFormat has exactly the two R4 cases and no others`() {
        val all = ConsoleFormat.values().toList()
        assertEquals(2, all.size, "ConsoleFormat must remain a two-case closed ADT")
        // No duplicates by token
        assertEquals(all.size, all.map { it.token }.toSet().size, "ConsoleFormat tokens must be distinct")
        // Documented surface
        assertEquals(listOf("text", "jsonl"), all.map { it.token })
    }

    @Test
    fun `ConsoleFormat parse round-trips on the documented CLI tokens`() {
        assertEquals(ConsoleFormat.TEXT, ConsoleFormat.parse("text"))
        assertEquals(ConsoleFormat.JSONL, ConsoleFormat.parse("jsonl"))
        // Case-sensitive on purpose: a casing choice at admission is a closed ADT, not a
        // search-and-replace.
        assertEquals(null, ConsoleFormat.parse("TEXT"))
        assertEquals(null, ConsoleFormat.parse("Jsonl"))
        assertEquals(null, ConsoleFormat.parse(""))
        assertEquals(null, ConsoleFormat.parse("bogus"))
    }

    // ------------------------------------------------------------------ HF1 parser + emitter

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
    fun `parser refuses an unknown --format value with the accepted list on stderr`() {
        // Use a control dir that exists; the path is parsed first, the format second, and the
        // refusal is on stderr with the closed-typed reason. The store is never opened.
        val tmp = java.nio.file.Files.createTempDirectory("r4-wu-r4-3-format-")
        try {
            val r = cli("--control-dir", tmp.toString(), "run-1", "op-1", "--format", "yaml")
            assertEquals(2, r.exitCode, "unknown --format must be a usage error, not a silent default")
            assertTrue(
                r.stderr.contains("--format") && r.stderr.contains("text|jsonl") && r.stderr.contains("yaml"),
                "stderr must name --format, the accepted list, and the offending value; got:\n${r.stderr}",
            )
        } finally {
            java.nio.file.Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `parser refuses --format with no value at the CLI layer, not silently`() {
        val tmp = java.nio.file.Files.createTempDirectory("r4-wu-r4-3-format-noval-")
        try {
            val r = cli("--control-dir", tmp.toString(), "run-1", "op-1", "--format")
            assertEquals(2, r.exitCode)
            assertTrue(
                r.stderr.contains("--format requires a value"),
                "stderr must name the missing-value reason; got:\n${r.stderr}",
            )
        } finally {
            java.nio.file.Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `usage line lists the new --format option, not just the legacy surface`() {
        // Drive the verb with no runId/opId; the missing-argument refusal renders the USAGE line
        // and the line must include the new --format option so a first-time caller sees it.
        val r = cli("--control-dir", "/tmp")
        assertEquals(2, r.exitCode)
        assertTrue(
            r.stderr.contains("--format"),
            "the usage line must mention the new --format option; got:\n${r.stderr}",
        )
    }

    @Test
    fun `usage line includes both --format cases and the new --format token`() {
        val r = cli("--control-dir", "/tmp")
        assertTrue(r.stderr.contains("text|jsonl"), "the usage line must list the closed ADT; got:\n${r.stderr}")
    }

    @Test
    fun `emitter with --format text and no bytes on either channel refuses with UnknownStream on stderr`() {
        // A control dir that exists but holds no run. The store answers with UnknownStream on
        // the first channel, and the emitter renders the refusal on stderr, not on stdout.
        val tmp = java.nio.file.Files.createTempDirectory("r4-wu-r4-3-text-empty-")
        try {
            val r = cli("--control-dir", tmp.toString(), "run-1", "op-1", "--format", "text")
            assertEquals(1, r.exitCode, "a refusal is exit 1, not 0")
            assertEquals("", r.stdout, "stdout must stay clean for a refusal")
            assertTrue(
                r.stderr.contains("console-refused") && r.stderr.contains("unknown-stream"),
                "stderr must carry the typed refusal; got:\n${r.stderr}",
            )
        } finally {
            java.nio.file.Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `emitter with --format jsonl refuses identically to --format text on a missing run`() {
        // The JSONL path must NOT change the refusal contract. The dispatch short-circuits on
        // Refused and renders the same `console-refused:` prefix, so a script that switched to
        // --format jsonl does not also have to switch its error-grep prefix.
        val tmp = java.nio.file.Files.createTempDirectory("r4-wu-r4-3-jsonl-empty-")
        try {
            val r = cli("--control-dir", tmp.toString(), "run-1", "op-1", "--format", "jsonl")
            assertEquals(1, r.exitCode, "a refusal is exit 1, not 0")
            assertEquals("", r.stdout, "stdout must stay clean for a refusal")
            assertTrue(
                r.stderr.contains("console-refused") && r.stderr.contains("unknown-stream"),
                "stderr must carry the typed refusal regardless of --format; got:\n${r.stderr}",
            )
        } finally {
            java.nio.file.Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `ObservationJsonLines envelope shape is the same one observe emits`() {
        // The contract is: the console --format jsonl emitter routes through
        // [ObservationJsonLines], which is the SAME writer `pipelinek observe --view
        // console --format jsonl` already uses. The shape is documented at
        // [ObservationJsonLines] (`type, channel, operation, ordinal, from, to, text/base64`).
        // This test does not drive the full path (it would need a real store); it pins the
        // surface so a future refactor cannot silently swap the encoder.
        val record = ObservationJsonLinesForTest.syntheticOutput()
        val line = ObservationJsonLines.encodeOne(record)
        assertTrue(line.contains("\"type\":\"output\""), "envelope must tag type=output; got: $line")
        assertTrue(line.contains("\"channel\":\""), "envelope must carry channel; got: $line")
        assertTrue(line.contains("\"operation\":\""), "envelope must carry operation; got: $line")
        assertTrue(line.contains("\"ordinal\":"), "envelope must carry ordinal; got: $line")
        assertTrue(line.contains("\"from\":"), "envelope must carry from; got: $line")
        assertTrue(line.contains("\"to\":"), "envelope must carry to; got: $line")
        assertTrue(
            line.contains("\"text\":") || line.contains("\"base64\":"),
            "envelope must carry text OR base64 (never neither); got: $line",
        )
        assertNotNull(line, "the encoder must produce a line")
    }
}

/**
 * R4 / WU-R4.3 — helper for the envelope-shape row above. Mirrors the synthetic frame the
 * emitter constructs in [MainConsoleCli.emitJsonlPage]: one [ObservationRecord.Output] over
 * a synthetic [OutputFrame] whose channel and range are derived from a page-like value.
 *
 * Not a domain object: the test only needs the encoder path to be exercised, not the real
 * Output Plane read.
 */
private object ObservationJsonLinesForTest {
    fun syntheticOutput(): dev.rubentxu.pipeline.v2.application.observation.ObservationRecord.Output =
        dev.rubentxu.pipeline.v2.application.observation.ObservationRecord.Output(
            frame = dev.rubentxu.pipeline.v2.output.OutputFrame(
                ordinal = 0L,
                stream = dev.rubentxu.pipeline.v2.output.OutputStreamId("run-1/op-1/stdout"),
                channel = dev.rubentxu.pipeline.v2.output.OutputChannel.STDOUT,
                from = 0L,
                to = 5L,
            ),
            bytes = "hello".toByteArray(Charsets.UTF_8),
            text = "hello",
        )
}
