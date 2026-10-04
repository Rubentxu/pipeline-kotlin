package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxConfig
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * M1-P2 — single byte authority for process output.
 *
 * ## The law, and why it is stated per-execution
 *
 * ```text
 * For any one execution, the transcript bytes exist in exactly ONE place.
 * ```
 *
 * Not "the event type is never emitted". [ShExecution] has a **non-durable** fallback that runs
 * with no control directory at all, where there is no Output Plane to write into. A guard phrased
 * as "no `EchoOutputCaptured` for sh" would have forced someone to delete the only observable
 * console that path has, which is a regression dressed as a fix. The second-authority problem is
 * never "two renderings exist" — it is "**both** exist for the same bytes".
 *
 * So the durable path is held to the strict form, and the non-durable path is held to being the
 * only one.
 *
 * ## What this replaces
 *
 * Two test files died with the emitters they tested (`TranscriptChunkingTest`,
 * `TranscriptStreamingEmissionTest`). The property they guarded — contiguous, ordered, lossless
 * chunks — did not die with them: it moved to the store, and the windowing that used to be
 * `MAX_TRANSCRIPT_CHUNK_CHARS` is now `appendFrom`'s per-window reservation. That property is
 * re-asserted here from the outside, by reading a real transcript back through a cursor.
 */
@Timeout(180)
class OutputSingleAuthorityFitnessTest {

    @BeforeEach
    fun resetProvider() {
        // The provider caches one recovered store per control-dir root, which is the point; a test
        // that forgot this would inherit another test's store and read the wrong bytes.
        OutputPlaneProvider.forgetAll()
    }

    private fun linuxOnly() {
        assumeTrue(
            !System.getProperty("os.name").orEmpty().lowercase().contains("win"),
            "the durable shell substrate requires a POSIX host",
        )
    }

    private suspend fun runSh(
        controlDirRoot: Path,
        workspaceRoot: Path,
        script: String,
        runId: String,
        returnStdout: Boolean = false,
    ): Pair<dev.rubentxu.pipeline.v2.domain.ShellInvocationResult, InMemoryEventStore> {
        val sink = InMemoryEventStore()
        val result = ShExecution.invokeShell(
            command = ShellCommand(
                script = script,
                returnMode = if (returnStdout) ShellReturnMode.STDOUT else ShellReturnMode.NONE,
            ),
            opId = OpId(runId = runId, stageIndex = 0, stepIndex = 0),
            runId = runId,
            stageIndex = 0,
            stepIndex = 0,
            shOptions = ShOptions(
                workspaceRoot = workspaceRoot,
                captureStdout = returnStdout,
                timeoutMs = 60_000,
                env = emptyMap(),
                sandbox = SandboxConfig.NONE,
            ),
            controlDirRoot = controlDirRoot,
            eventSink = sink,
        )
        return result to sink
    }

    private fun readAll(stream: dev.rubentxu.pipeline.v2.output.OutputStreamId, store: dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var cursor: OutputCursor? = OutputCursor.start(stream)
        while (cursor != null) {
            val page = assertInstanceOf(
                OutputReadResult.Page::class.java,
                store.read(stream, cursor, 4096),
                "read must page, not refuse",
            ).page
            out.write(page.bytes)
            cursor = page.next
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------ the law

    @Test
    fun `durable sh writes its transcript to the Output Plane and emits no console event`(
        @TempDir root: Path,
    ) = runBlocking {
        linuxOnly()
        val controlDirRoot = Files.createDirectories(root.resolve("control"))
        val workspaceRoot = Files.createDirectories(root.resolve("workspace"))

        val (result, sink) = runSh(
            controlDirRoot = controlDirRoot,
            workspaceRoot = workspaceRoot,
            script = "echo alpha; echo beta 1>&2",
            runId = "r-single-authority",
        )

        assertNotNull(result, "the invocation must return a typed result")
        val echoEvents = sink.eventsFor("r-single-authority").filterIsInstance<EchoOutputCaptured>().toList()
        assertTrue(
            echoEvents.isEmpty(),
            "process output must not produce a console event; got ${echoEvents.size}: " +
                echoEvents.map { it.content.take(80) },
        )

        val store = OutputPlaneProvider.storeFor(controlDirRoot)
        val stream = OutputPlaneProvider.streamId("r-single-authority", OpId("r-single-authority", 0, 0).format())
        val transcript: String = readAll(stream, store).toString(StandardCharsets.UTF_8)

        assertTrue(transcript.contains("alpha"), "stdout must reach the Output Plane, got: $transcript")
        assertTrue(transcript.contains("beta"), "stderr must reach the Output Plane, got: $transcript")

        // console.log is a STAGING BUFFER, not an authority. If it survives a successful exit then
        // the same bytes exist in two durable places and D2 is violated by the filesystem rather
        // than by the event plane - a subtler failure than the one this slice removed, and the one a
        // guard that only counts events would never notice.
        val controlDir = controlDirRoot.resolve(OpId("r-single-authority", 0, 0).format())
        assertFalse(
            Files.exists(controlDir.resolve("console.log")),
            "console.log survived a successful exit: the bytes now exist in two durable places",
        )
    }

    @Test
    fun `the transcript read back is byte-exact, contiguous and lossless`(@TempDir root: Path) = runBlocking {
        linuxOnly()
        val controlDirRoot = Files.createDirectories(root.resolve("control"))
        val workspaceRoot = Files.createDirectories(root.resolve("workspace"))

        // A payload long enough to cross several appendFrom windows, so contiguity is a real
        // property and not an artefact of a single reservation.
        val line = "x".repeat(200)
        val expected = (1..300).joinToString("\n") { "line-$it-$line" } + "\n"

        runSh(
            controlDirRoot = controlDirRoot,
            workspaceRoot = workspaceRoot,
            script = "cat <<'PAYLOAD_EOF'\n${expected}PAYLOAD_EOF\n",
            runId = "r-contiguous",
        )

        val store = OutputPlaneProvider.storeFor(controlDirRoot)
        val stream = OutputPlaneProvider.streamId("r-contiguous", OpId("r-contiguous", 0, 0).format())
        val actual: String = readAll(stream, store).toString(StandardCharsets.UTF_8)

        // Read it a second time through a different page size: a cursor that only worked for one
        // page size would pass the first read and fail here.
        val secondPass = java.io.ByteArrayOutputStream()
        var cursor: OutputCursor? = OutputCursor.start(stream)
        while (cursor != null) {
            val page = (store.read(stream, cursor, 1000) as OutputReadResult.Page).page
            secondPass.write(page.bytes)
            cursor = page.next
        }

        assertArrayEquals(expected.toByteArray(), actual.toByteArray(), "transcript was not lossless")
        assertArrayEquals(
            actual.toByteArray(),
            secondPass.toByteArray(),
            "a different page size changed what a reader saw",
        )
        assertTrue(actual.endsWith("\n"), "a trailing newline must not be dropped at a window edge")
    }

    @Test
    fun `an arbitrary byte range returns the same byte as the full read`(@TempDir root: Path) = runBlocking {
        linuxOnly()
        val controlDirRoot = Files.createDirectories(root.resolve("control"))
        val workspaceRoot = Files.createDirectories(root.resolve("workspace"))
        runSh(
            controlDirRoot = controlDirRoot,
            workspaceRoot = workspaceRoot,
            script = "printf 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'",
            runId = "r-range",
        )

        val store = OutputPlaneProvider.storeFor(controlDirRoot)
        val stream = OutputPlaneProvider.streamId("r-range", OpId("r-range", 0, 0).format())
        val all: String = readAll(stream, store).toString(StandardCharsets.UTF_8)

        for (from in listOf(0L, 3L, 11L, 25L)) {
            val to = minOf(from + 4, all.length.toLong())
            val slice = (store.readRange(stream, from, to) as OutputReadResult.Page).page
            assertEquals(
                all.substring(from.toInt(), to.toInt()),
                slice.bytes.toString(StandardCharsets.UTF_8),
                "range [$from, $to) disagreed with the whole read",
            )
        }
    }

    // ------------------------------------------------- returnStdout separation

    @Test
    fun `returnStdout stays an exact typed value and is not the transcript`(@TempDir root: Path) = runBlocking {
        linuxOnly()
        val controlDirRoot = Files.createDirectories(root.resolve("control"))
        val workspaceRoot = Files.createDirectories(root.resolve("workspace"))

        val (result, sink) = runSh(
            controlDirRoot = controlDirRoot,
            workspaceRoot = workspaceRoot,
            // stdout is the captured value; stderr is the observable transcript. Two different
            // channels, and the transcript must contain the stderr and NOT the stdout.
            script = "echo THE-VALUE; echo THE-TRANSCRIPT 1>&2",
            runId = "r-return-stdout",
            returnStdout = true,
        )

        val value = assertInstanceOf(
            dev.rubentxu.pipeline.v2.domain.ShellInvocationResult.Stdout::class.java,
            result,
            "returnStdout must produce a typed Stdout value, got $result",
        )
        // The captured value is the process stdout with its trailing newline trimmed. That is the
        // product's pre-existing returnStdout contract and M1 does not change it - the point of this
        // assertion is separation, not formatting.
        assertEquals("THE-VALUE", value.value.trim())

        val store = OutputPlaneProvider.storeFor(controlDirRoot)
        val stream = OutputPlaneProvider.streamId("r-return-stdout", OpId("r-return-stdout", 0, 0).format())
        val transcript: String = readAll(stream, store).toString(StandardCharsets.UTF_8)

        assertTrue(transcript.contains("THE-TRANSCRIPT"), "stderr belongs in the transcript: $transcript")
        assertFalse(
            transcript.contains("THE-VALUE"),
            "the typed value must not be re-emitted into the transcript; got: $transcript",
        )
        assertTrue(
            sink.eventsFor("r-return-stdout").filterIsInstance<EchoOutputCaptured>().toList().isEmpty(),
            "the typed value must not ride a console event either",
        )
    }

}
