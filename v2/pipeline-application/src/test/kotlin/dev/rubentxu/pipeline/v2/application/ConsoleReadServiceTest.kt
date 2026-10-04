package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxConfig
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * M1-P3 — the read side is consumable.
 *
 * Everything here drives a **real `sh` process** and reads the result back through the public
 * surface, because a cursor contract proven only against a store populated by a test is a contract
 * proven against a fiction.
 */
@Timeout(180)
class ConsoleReadServiceTest {

    /** A literal `$` for a shell script embedded in a Kotlin string. */
    private val dollarLiteral: String = "$" + "$"

    @BeforeEach
    fun resetProvider() {
        OutputPlaneProvider.forgetAll()
    }

    private fun linuxOnly() {
        assumeTrue(
            !System.getProperty("os.name").orEmpty().lowercase().contains("win"),
            "the durable shell substrate requires a POSIX host",
        )
    }

    /** Run a real sh step and return its control-dir root. */
    private fun runSh(
        controlDirRoot: Path,
        workspaceRoot: Path,
        script: String,
        runId: String,
    ): OpId {
        val opId = OpId(runId = runId, stageIndex = 0, stepIndex = 0)
        runBlocking {
            ShExecutionBridge.invoke(
                command = ShellCommand(script = script, returnMode = ShellReturnMode.NONE),
                opId = opId,
                runId = runId,
                controlDirRoot = controlDirRoot,
                workspaceRoot = workspaceRoot,
            )
        }
        return opId
    }

    private fun page(result: ConsoleReadService.Result): dev.rubentxu.pipeline.v2.output.OutputPage =
        assertInstanceOf(ConsoleReadService.Result.Page::class.java, result, "expected a page, got $result").page

    private fun refusal(result: ConsoleReadService.Result): OutputRefusal =
        assertInstanceOf(ConsoleReadService.Result.Refused::class.java, result, "expected a refusal, got $result").reason

    // ------------------------------------------------------- the consumable path

    @Test
    fun `a consumer reads a real transcript by run and op, without knowing the stream id`(
        @TempDir root: Path,
    ) {
        linuxOnly()
        val controlDirRoot = Files.createDirectories(root.resolve("control"))
        val workspaceRoot = Files.createDirectories(root.resolve("workspace"))
        val runId = "r-read"
        val opId = runSh(controlDirRoot, workspaceRoot, "echo readable-console", runId)

        val text = MainConsoleCli.utf8(
            MainConsoleCli.readWholeStream(controlDirRoot, runId, opId.format()),
        )
        assertTrue(text.contains("readable-console"), "the consumer could not read the transcript: $text")
    }

    @Test
    fun `a paged read hands back a continuation token and the next page continues exactly`(
        @TempDir root: Path,
    ) {
        linuxOnly()
        val controlDirRoot = Files.createDirectories(root.resolve("control"))
        val workspaceRoot = Files.createDirectories(root.resolve("workspace"))
        val runId = "r-paged"
        val opId = runSh(
            controlDirRoot, workspaceRoot,
            "for i in $(seq 1 400); do echo \"row-${dollarLiteral}i-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"; done",
            runId,
        )

        val first = page(ConsoleReadService.read(controlDirRoot, runId, opId.format(), after = null, maxBytes = 512))
        assertEquals(512, first.bytes.size, "a page must be bounded by maxBytes")
        assertNotNull(first.next, "a partial page must carry a continuation cursor")
        val token = first.next!!.encode()

        val second = page(ConsoleReadService.read(controlDirRoot, runId, opId.format(), after = OutputCursor.decode(token), maxBytes = 512))
        assertEquals(512L, second.from, "the second page must start exactly where the token says")

        // And the whole stream reassembles to the same bytes the first page started.
        val whole = MainConsoleCli.readWholeStream(controlDirRoot, runId, opId.format(), maxBytes = 4096)
        assertArrayEquals(
            first.bytes + second.bytes,
            whole.copyOfRange(0, first.bytes.size + second.bytes.size),
            "paged reads did not reassemble to the same bytes as one read",
        )
    }

    @Test
    fun `an arbitrary byte range returns the same bytes as the paged read`(@TempDir root: Path) {
        linuxOnly()
        val controlDirRoot = Files.createDirectories(root.resolve("control"))
        val workspaceRoot = Files.createDirectories(root.resolve("workspace"))
        val runId = "r-range-consumer"
        val opId = runSh(controlDirRoot, workspaceRoot, "seq 1 50", runId)

        val whole = MainConsoleCli.readWholeStream(controlDirRoot, runId, opId.format())
        assertTrue(whole.isNotEmpty())

        val from = 7L
        val to = 23L
        val ranged = page(ConsoleReadService.readRange(controlDirRoot, runId, opId.format(), from, to)).bytes
        assertArrayEquals(whole.copyOfRange(from.toInt(), to.toInt()), ranged)
    }

    // ------------------------------------------------------------- the refusals

    @Test
    fun `an event cursor pasted as an output token is refused, not silently offset`(@TempDir root: Path) {
        linuxOnly()
        val controlDirRoot = Files.createDirectories(root.resolve("control"))
        val workspaceRoot = Files.createDirectories(root.resolve("workspace"))
        val runId = "r-cross-plane"
        val opId = runSh(controlDirRoot, workspaceRoot, "echo cross-plane", runId)

        // evt-cursor-v1:<runId>:<sequence> is the EVENT plane's token. Handing it to the console
        // reader is the conflation ADR-M1 D3 exists to prevent, and it must fail loudly rather
        // than resume at an offset that happens to be a valid byte position.
        assertNull(
            OutputCursor.decode("evt-cursor-v1:$runId:3"),
            "an event cursor must not decode as an output cursor",
        )
        assertNull(
            MainConsoleCli.decodeForTest("evt-cursor-v1:$runId:3"),
            "the console reader must not accept an event cursor token",
        )
    }

    @Test
    fun `a continuation token naming another stream is refused`(@TempDir root: Path) {
        linuxOnly()
        val controlDirRoot = Files.createDirectories(root.resolve("control"))
        val workspaceRoot = Files.createDirectories(root.resolve("workspace"))
        runSh(controlDirRoot, workspaceRoot, "echo stream-a", "r-a")
        runSh(controlDirRoot, workspaceRoot, "echo stream-b", "r-b")

        val foreign = OutputCursor(MainConsoleCli.streamIdFor("r-a", OpId("r-a", 0, 0).format()), 0L)
        assertEquals(
            OutputRefusal.ForeignStream(
                expected = MainConsoleCli.streamIdFor("r-b", OpId("r-b", 0, 0).format()),
                actual = MainConsoleCli.streamIdFor("r-a", OpId("r-a", 0, 0).format()),
            ),
            refusal(ConsoleReadService.read(controlDirRoot, "r-b", OpId("r-b", 0, 0).format(), after = foreign)),
        )
    }

    @Test
    fun `an unknown operation is refused as unknown, not served as empty`(@TempDir root: Path) {
        linuxOnly()
        val controlDirRoot = Files.createDirectories(root.resolve("control"))
        Files.createDirectories(root.resolve("workspace"))
        runSh(controlDirRoot, root.resolve("workspace"), "echo x", "r-present")

        val result = ConsoleReadService.read(controlDirRoot, "r-present", "op-never-ran", after = null)
        assertEquals(
            OutputRefusal.UnknownStream(MainConsoleCli.streamIdFor("r-present", "op-never-ran")),
            refusal(result),
        )
        // The rendered refusal is stable and greppable, so a shell script can branch on it.
        assertTrue(
            ConsoleReadService.renderRefusal(refusal(result)).startsWith("console-refused: unknown-stream"),
        )
    }

    // -------------------------------------------------------------- the CLI seam

    @Test
    fun `the CLI writes transcript bytes to stdout and the token to stderr`(@TempDir root: Path) {
        linuxOnly()
        val controlDirRoot = Files.createDirectories(root.resolve("control"))
        val workspaceRoot = Files.createDirectories(root.resolve("workspace"))
        val runId = "r-cli"
        val opId = runSh(controlDirRoot, workspaceRoot, "seq 1 500", runId)

        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = PrintStream(out, true, StandardCharsets.UTF_8).use { o ->
            PrintStream(err, true, StandardCharsets.UTF_8).use { e ->
                MainConsoleCli.emit(
                    ConsoleReadService.read(controlDirRoot, runId, opId.format(), after = null, maxBytes = 256),
                    o,
                    e,
                )
            }
        }

        assertEquals(0, code)
        assertEquals(256, out.size(), "stdout must carry exactly the requested page and nothing else")
        val tokenLine = MainConsoleCli.utf8(err.toByteArray()).trim()
        assertTrue(tokenLine.startsWith(OutputCursor.TOKEN_PREFIX), "stderr must carry the continuation token, got: $tokenLine")

        // The token the CLI printed must be one the CLI accepts back.
        val resumed: OutputCursor? = OutputCursor.decode(tokenLine)
        assertNotNull(resumed, "the CLI printed a token it cannot read back: $tokenLine")
        assertEquals(256L, resumed!!.committedOffset)
    }

    @Test
    fun `a refusal makes the CLI exit non-zero rather than printing nothing successfully`(@TempDir root: Path) {
        linuxOnly()
        val controlDirRoot = Files.createDirectories(root.resolve("control"))
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = PrintStream(out, true, StandardCharsets.UTF_8).use { o ->
            PrintStream(err, true, StandardCharsets.UTF_8).use { e ->
                MainConsoleCli.emit(
                    ConsoleReadService.Result.Refused(OutputRefusal.UnknownStream(MainConsoleCli.streamIdFor("nope", "op"))),
                    o,
                    e,
                )
            }
        }
        assertEquals(1, code, "a refusal is not a success with no output")
        assertEquals(0, out.size(), "a refusal must not print transcript bytes")
        assertTrue(MainConsoleCli.utf8(err.toByteArray()).contains("console-refused"))
    }

    @Test
    fun `the CLI --range branch returns the same bytes the service range does`(@TempDir root: Path) {
        linuxOnly()
        val controlDirRoot = Files.createDirectories(root.resolve("control"))
        val workspaceRoot = Files.createDirectories(root.resolve("workspace"))
        val runId = "r-cli-range"
        val opId = runSh(controlDirRoot, workspaceRoot, "seq 100 400", runId)
        val op = opId.format()

        val expected = page(ConsoleReadService.readRange(controlDirRoot, runId, op, 10L, 42L)).bytes

        // Drive the real argv parser, not just the service: the --range branch of main() had no
        // test at all until a mutation proved it could serve the whole stream undetected.
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val previousOut = System.out
        val previousErr = System.err
        val code = try {
            System.setOut(PrintStream(out, true, StandardCharsets.UTF_8))
            System.setErr(PrintStream(err, true, StandardCharsets.UTF_8))
            MainConsoleCli.main(
                arrayOf("--control-dir", controlDirRoot.toString(), runId, op, "--range", "10:42"),
            )
        } finally {
            System.setOut(previousOut)
            System.setErr(previousErr)
        }

        assertEquals(0, code, "the CLI failed: ${MainConsoleCli.utf8(err.toByteArray())}")
        assertArrayEquals(expected, out.toByteArray(), "--range served different bytes than the service range")
    }

    // ------------------------------------------------------------- token shape

    @Test
    fun `a cursor token round-trips a stream id containing a path separator`() {
        val cursor = OutputCursor(OutputStreamId("run-1/run-1-s0-0/transcript"), 4_096L)
        val decoded = OutputCursor.decode(cursor.encode())
        assertEquals(cursor, decoded, "the token must survive the path separator in the stream id")
    }

    @Test
    fun `a malformed token decodes to null rather than to a plausible offset`() {
        for (bad in listOf("", "out-cursor-v1", "out-cursor-v1:only-two", "out-cursor-v1:x:notanumber",
            "out-cursor-v1::0", "out-cursor-v1:%20:0", "evt-cursor-v1:r:1", "out-cursor-v2:a:0")) {
            assertNull(OutputCursor.decode(bad), "token should not decode: '$bad'")
        }
    }
}

/** Small bridge so the test can run a real shell without importing the whole ShExecution surface. */
private object ShExecutionBridge {
    suspend fun invoke(
        command: ShellCommand,
        opId: OpId,
        runId: String,
        controlDirRoot: Path,
        workspaceRoot: Path,
    ) {
        dev.rubentxu.pipeline.v2.application.durable.ShExecution.invokeShell(
            command = command,
            opId = opId,
            runId = runId,
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions(
                workspaceRoot = workspaceRoot,
                captureStdout = false,
                timeoutMs = 60_000,
                env = emptyMap(),
                sandbox = SandboxConfig.NONE,
            ),
            controlDirRoot = controlDirRoot,
            eventSink = InMemoryEventStore(),
        )
    }
}
