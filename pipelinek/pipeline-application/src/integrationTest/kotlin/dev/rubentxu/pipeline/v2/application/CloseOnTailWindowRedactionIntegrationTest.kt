package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.OutputChunk
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.domain.durable.TaskStream
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.runtime.durable.OutputPlaneProvider
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.ProcessDurableTaskRuntime
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * The piece of a secret that is still in the redactor's window when a channel CLOSES never
 * reaches the durable console in the clear.
 *
 * ## The guarantee under trial
 *
 * A step whose LAST word on each channel is a piece of a registered secret — the window holds
 * bytes nobody has decided when the source hits EOF — leaves a persisted console in which neither
 * piece survives: the close flush decides the pending bytes through the SAME matcher that decided
 * every byte before them, and a window that was emptied raw would put the piece on disk at the
 * one moment no reader is watching. This is the producer-side law behind the consumer-side
 * finding that a split value reformed in a console at the channels' close: the reforming happened
 * because a closing channel published its pending window unredacted, and it was intermittent —
 * only some runs ended with the window still holding the tail.
 *
 * ## Why this is an INTEGRATION test with a real child
 *
 * The defect lives in the interaction between the pump and the channel's END: a desk trace of the
 * state machine over a fixed byte sequence cannot see whether the production pump feeds ALL the
 * bytes into the state that finishes them. The law therefore runs the REAL CLI child, with the
 * real sh, the real per-channel pumps and the real store, and reads the persisted bytes back
 * through the product's own read side. Several attempts per row because the finding was measured
 * as a race (~2 of 5 runs), and one green attempt certifies nothing — the row is the loop, not
 * the run.
 *
 * ## What this does not freeze
 *
 * The mask's shape, the frame count, the ordinal order: the assertion is the ABSENCE of the
 * pieces, and it survives any change to how a masked window is rendered.
 */
@Timeout(value = 600, unit = java.util.concurrent.TimeUnit.SECONDS)
class CloseOnTailWindowRedactionIntegrationTest {

    @TempDir
    lateinit var root: Path

    /**
     * The law, over [ATTEMPTS] real runs: each channel's transcript, and the console the two
     * channels make when merged in the observed order, never carry a piece of the secret that
     * was still pending when the channel closed.
     */
    @Test
    fun `a channel that closes on a pending piece of the secret publishes it through the matcher`() {
        repeat(ATTEMPTS) { attempt ->
            val control = Files.createDirectories(root.resolve("cli-control-$attempt"))
            val script = root.resolve("close-$attempt.pipeline.kts")
            Files.writeString(script, SCRIPT)

            val receipt = runCliChild(script, control, "pkh250-cli-$attempt")
            val plane = transcriptOf(control, receipt.runId)

            val stdoutText = plane.text(OutputChannel.STDOUT)
            val stderrText = plane.text(OutputChannel.STDERR)
            assertTrue(
                stdoutText.contains(PREFIX),
                "attempt $attempt: the stdout transcript lost the text written before the piece " +
                    "(redaction must not destroy the stream that holds it): '$stdoutText'",
            )
            assertFalse(
                stdoutText.contains(STDOUT_PIECE),
                "attempt $attempt: the stdout channel closed with the window still holding the " +
                    "first piece and published it raw: '$stdoutText'",
            )
            assertFalse(
                stderrText.contains(STDERR_PIECE),
                "attempt $attempt: the stderr channel closed ON its piece — the step's last word " +
                    "was the tail of the secret — and the close published the window raw: " +
                    "'$stderrText'",
            )
            val merged = plane.mergedText()
            assertFalse(
                merged.contains(SECRET),
                "attempt $attempt: the merged console reformed the secret at the channels' " +
                    "close: '$merged'",
            )
            // The window was really pending, not incidentally decided: the step wrote NOTHING
            // after either piece, so a console without the pieces is the close flush working and
            // not a mid-run decision.
            assertTrue(
                stderrText.isNotEmpty(),
                "attempt $attempt: stderr never reached the plane; the close of that channel is " +
                    "unmeasured",
            )
        }
    }

    // ------------------------------------------------------------------ the CLI path

    /**
     * The CLI's own entry, in a child JVM of its own: the same `main` the launcher of the
     * distribution starts, with the `run` verb and the redaction canaries requested.
     */
    private fun runCliChild(script: Path, control: Path, runIdHint: String): Receipt {
        val argv = listOf(
            javaBinary(),
            "-cp",
            System.getProperty("java.class.path"),
            MAIN_CLASS,
            "run",
            "--db",
            root.resolve("$runIdHint.db").toString(),
            "--control-root",
            control.toString(),
            script.toString(),
        )
        val captured = CapturedOutput()
        val runtime = ProcessDurableTaskRuntime(root.resolve("$runIdHint-supervisions"), clock) { builder ->
            builder.environment()["JAVA_TOOL_OPTIONS"] = "-D$CANARY_PROPERTY=true"
            builder.start()
        }
        val request = TaskExecutionRequest(
            task = TaskSpec.ExecTask(argv),
            runId = RunId(runIdHint),
            opId = runIdHint,
            timeoutMs = CHILD_DEADLINE_MILLIS,
            workspaceRoot = script.parent?.toString(),
        )
        val result = runBlocking { runtime.execute(request, captured) }
        assertEquals(
            0,
            result.exitCode,
            "the CLI child did not finish well: ${captured.text()}",
        )
        return receiptOf(captured.text())
    }

    /**
     * What the producer persisted for [runId], per channel, plus the merged view the frame index
     * orders — the same two readings a consumer of the console asks.
     */
    private fun transcriptOf(control: Path, runId: String): Plane {
        val store = OutputPlaneProvider.storeForReading(control)
        val frames = store.frameIndex().framesOfRun(runId, -1L, FRAME_LIMIT)
        assertTrue(
            frames.isNotEmpty(),
            "the run $runId holds no frame at all; nothing of its close was persisted",
        )
        return Plane(store, frames)
    }

    /** The persisted bytes of one run, read through the product's own read side. */
    private class Plane(
        private val store: dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore,
        private val frames: List<dev.rubentxu.pipeline.v2.output.OutputFrame>,
    ) {
        /** The transcript of [channel], in the byte order of that channel's stream. */
        fun text(channel: OutputChannel): String {
            val channelFrames = frames.filter { it.channel == channel }.sortedBy { it.from }
            if (channelFrames.isEmpty()) return ""
            val stream = channelFrames.first().stream
            val committed = store.committedExtent(stream) ?: return ""
            return readRange(stream, 0L, committed).toString(Charsets.UTF_8)
        }

        /** Both channels, interleaved by the order the run produced them — what a console reads. */
        fun mergedText(): String {
            val out = ByteArrayOutputStream()
            for (frame in frames.sortedBy { it.ordinal }) {
                out.write(readRange(frame.stream, frame.from, frame.to))
            }
            return out.toString(Charsets.UTF_8)
        }

        private fun readRange(stream: dev.rubentxu.pipeline.v2.output.OutputStreamId, from: Long, to: Long): ByteArray =
            when (val page = store.readRange(stream, from, to)) {
                is OutputReadResult.Page -> page.page.bytes
                is OutputReadResult.Refused -> error("the range [$from, $to) was refused: ${page.reason}")
            }
    }

    /**
     * The receipt of a launch, read from what the child printed.
     */
    private fun receiptOf(text: String): Receipt {
        val line = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("{") && it.contains(LaunchReceipt.SCHEMA) }
        requireNotNull(line) { "the child printed no launch receipt: $text" }
        val document = kotlinx.serialization.json.Json.parseToJsonElement(line)
            as kotlinx.serialization.json.JsonObject
        return Receipt(
            document.getValue("runId").let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: it.toString()
            },
        )
    }

    /**
     * The identity of the run a receipt names.
     */
    private class Receipt(val runId: String)

    /**
     * What a child printed, collected by the sink the run wrote to.
     */
    private class CapturedOutput : ExecutionOutputSink {
        private val chunks = ConcurrentHashMap<TaskStream, MutableList<ByteArray>>()

        override suspend fun append(chunk: OutputChunk) {
            chunks.computeIfAbsent(chunk.stream) { java.util.concurrent.CopyOnWriteArrayList() }
                .add(chunk.data)
        }

        /** Everything the child printed, on either stream. */
        fun text(): String = chunks.values.flatten()
            .fold(ByteArrayOutputStream()) { sink, bytes -> sink.write(bytes); sink }
            .toString(Charsets.UTF_8)
    }

    /** The JVM this test runs in, and the one its children run in. */
    private fun javaBinary(): String =
        Path.of(System.getProperty("java.home"), "bin", "java").toString()

    /** The clock this lane stamps its material with: it does not move. */
    private object clock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    private companion object {
        /** The entry point the launcher of the distribution starts. */
        private const val MAIN_CLASS: String = "dev.rubentxu.pipeline.v2.application.MainKt"

        /** The property that makes the product register the synthetic secret below. */
        private const val CANARY_PROPERTY: String = "pipelinek.redaction.canaries"

        /** The synthetic canary the product registers when the property is set. */
        private const val SECRET: String = "GHS6_CANARY_7f3a9c2e1b4d5e6f"

        /** The text the step writes before the first piece. */
        private const val PREFIX = "head:"

        /** The piece stdout ends on: it is the channel's LAST word, so it is still pending at close. */
        private const val STDOUT_PIECE = "GHS6_CANARY_7f3a"

        /** The piece stderr ends on: it is the STEP's last word, pending when the step exits. */
        private const val STDERR_PIECE = "9c2e1b4d5e6f"

        /**
         * The attempts per row. The finding was measured on 2 of 5 runs; a single attempt is a
         * coin, the loop is the law.
         */
        private const val ATTEMPTS = 4

        /** How long a child of this test may take. */
        private const val CHILD_DEADLINE_MILLIS: Long = 240_000L

        /** How many frames of one run are read. */
        private const val FRAME_LIMIT: Int = 4096

        /**
         * The script under trial: the close-on-tail shape — each channel's final bytes are a
         * piece of the secret and NOTHING follows on either channel.
         */
        private val SCRIPT: String = """
            pipeline {
                stages {
                    stage("close") {
                        sh("printf '$PREFIX'; printf '%s' '$STDOUT_PIECE'; sleep 0.4; printf '%s' '$STDERR_PIECE' 1>&2")
                    }
                }
            }
        """.trimIndent()
    }
}
