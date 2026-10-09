package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider
import dev.rubentxu.pipeline.v2.application.durable.ShOperationsAdapter
import dev.rubentxu.pipeline.v2.application.support.ConsolePlaneProbe
import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * OBS-2 Nivel A — the two rows whose absence would let a real regression through unseen.
 *
 * `OBS2_LEVEL_A_COVERAGE_AUDIT.md` found four gaps. These are two of them, chosen because a failure in
 * either would be **invisible**: both behaviours are correct today, so every existing row is green, and
 * both are the kind of property that a plausible later change breaks while the suite stays quiet.
 *
 * ## OBS-PC-204 — `returnStdout` does not duplicate into the console
 *
 * `Lpr011r2SecretRedactionAtRestUatTest` already pins that the typed value is exact and that the
 * transcript is sanitised. Both are assertions about what is **present**. The row that is missing is the
 * one about what is **absent**, and it is the half of the UAT that carries the compatibility guarantee:
 * in `returnStdout` mode stdout is the typed value, and mirroring it into the console would show a user
 * every byte twice.
 *
 * The behaviour is right today — `DurableShellExecutor` redirects stdout to `output.txt` and pumps only
 * stderr in capture mode — and nothing was protecting it.
 *
 * ## OBS-PC-206 — a timeout keeps the whole acknowledged prefix
 *
 * `UatTimeoutBlockDurableTest` proves the deadline cancels the child, fails the run, and does not
 * duplicate a rerun. The UAT's own wording is different and was entirely unpinned: *"timeout y
 * cancelación **con retención de todo el prefijo reconocido**"*. A grep for the prefix, for
 * acknowledgements and for retention across the timeout tests returns nothing, so **a timeout that
 * truncated the transcript to zero passes the suite today.** That is the worst of the four gaps and it
 * is why this row exists.
 *
 * ## Fidelity
 *
 * Both rows go through the genuine `ShOperationsAdapter` → `ShExecution` → pumps → `RedactingOutputIngress`
 * → `SegmentOutputStore`, and read back through `ConsolePlaneProbe`, which reads the plane rather than a
 * file. Barriers are files. `206` waits for the plane to actually contain the prefix before letting the
 * deadline fire, so the claim is "this survives the timeout", not "this was fast enough". No wall-clock
 * assertions, `@TempDir`, no ambient state.
 *
 * ## Mutations — measured, and two of them are a lesson
 *
 * - **M-A1** (mirror stdout into the console: pipe stdout AND pump it in capture mode) REDS **204**
 *   alone. 1:1.
 * - **M-A3** (cross the two pump descriptors — give the STDOUT channel the error stream and vice versa)
 *   REDS **202** alone, leaving 204, 205 and 206 green. 1:1.
 * - **M-A2** (remove the EOF drain, i.e. never close the redacting stream) REDS **nothing**, in
 *   either scenario.
 *
 * Both failures are informative and neither is papered over here.
 *
 * **Why M-A2 does not kill 206.** By the time the deadline fires those 400 lines were already
 * committed in earlier live windows, so the redactor's pending buffer held nothing to lose. The row
 * proves what it says — *a timeout retains the prefix* — but it is not demonstrated to have teeth.
 *
 * **Why M-A2 does not kill 205 either, and why that matters more.** `205` was written specifically to
 * put a fragment inside that pending buffer at child exit, and removing the close still lost nothing.
 * So the comment in `DurableShellExecutor` — *"Close the redacting stream FIRST: its pending buffer
 * (EOF drain) is what flushes the final sanitized bytes"* — states a causal claim that **these
 * measurements do not support**. The bytes arrive through the read loop reaching EOF, not through
 * `close()`. That is a claim in a code comment about the mechanism protecting the last thing a process
 * said, and it is currently unverified; it is recorded as open rather than quietly repeated.
 *
 * What remains true: `205` and `206` are **different properties**, which is why one row could not
 * stand for both. Neither has a mutation that kills it yet. `206` needs one aimed at the deadline path
 * (one that discards committed bytes when it cancels); `205` needs one that removes whatever actually
 * flushes the tail, which this block has not identified.
 *
 * `204` and `202` are the two that are demonstrably load-bearing today, and they are guarded by two
 * different mechanisms — the composition that decides which channels exist, and the mapping that decides
 * which descriptor feeds which channel. A single guard could not have covered both.
 */
@Timeout(300)
@DisplayName("OBS-2 Nivel A — returnStdout sin duplicar y timeout que conserva el prefijo")
class ObsPc2LevelAUatTest {

    private val secret = "GHS2_LEVELA_CANARY_6b1f8d04a9e3"

    @TempDir
    lateinit var tempDir: Path

    private lateinit var workspace: Path

    private companion object {
        const val PAGE_BYTES = 64 * 1024
    }

    @BeforeEach
    fun setUp() {
        OutputPlaneProvider.forgetAll()
        assumeTrue(
            !System.getProperty("os.name").orEmpty().lowercase().contains("win"),
            "the durable shell substrate requires a POSIX host",
        )
        workspace = Files.createDirectories(tempDir.resolve("workspace"))
    }

    private fun controlRoot(name: String): Path = Files.createDirectories(tempDir.resolve(name))

    private fun registry(): SecretPatternRegistry =
        SecretPatternRegistry().apply { addSecret(SecretHandle.plain(secret)) }

    private fun adapter(
        runId: String,
        root: Path,
        options: ShOptions = ShOptions.EMPTY,
    ): ShOperationsAdapter = ShOperationsAdapter(
        runIdString = runId,
        opId = OpId(runId, 0, 0),
        shOptions = options,
        controlDirRoot = root,
        eventSink = InMemoryEventStore(),
        secretPatternRegistry = registry(),
    )

    private fun transcriptOrNull(root: Path, runId: String): String? =
        ConsolePlaneProbe.transcriptOrAbsent(root, runId, stageIndex = 0, stepIndex = 0)

    /**
     * OBS-PC-204 — stdout belongs to the typed value, stderr to the console, and neither is duplicated.
     *
     * The script writes a distinctive marker to each channel, so both claims are observable at once and
     * neither can be satisfied by a probe aimed at the wrong stream.
     */
    @Test
    fun `OBS-PC-204 returnStdout keeps stdout in the typed value and out of the transcript`() {
        val runId = "r-pc2-204"
        val root = controlRoot("pc2-204")
        val stdoutMarker = "PC204-STDOUT-ONLY-9f2c7a1e"
        val stderrMarker = "PC204-STDERR-4a71bd03"

        val result = runBlocking {
            adapter(runId, root, ShOptions.EMPTY.copy(captureStdout = true))
                .invoke(
                    command = ShellCommand(
                        script = "echo '$stdoutMarker'; echo '$stderrMarker' 1>&2",
                        returnMode = ShellReturnMode.STDOUT,
                    ),
                    runId = RunId(runId),
                    stepIndex = 0,
                )
        }

        // 1. The typed value is exact, and it is read from the RETURN VALUE rather than from
        //    `output.txt`. An earlier version of this row asserted on that file and failed: it is
        //    `READ_THEN_DELETE`, so the runtime consumes it on success and the file is legitimately
        //    gone by the time a test looks. The public typed contract is the returned value, and an
        //    assertion aimed at a transient artefact tests the retention policy instead of the claim.
        val typed = (result as? ShellInvocationResult.Stdout)?.value
        assertTrue(
            typed != null && typed.contains(stdoutMarker),
            "the typed value must carry the child's stdout verbatim. Got: ${result::class.simpleName}",
        )

        // 2. The console carries stderr. Asserted positively, because an empty console would otherwise
        //    satisfy assertion 3 below — and "stderr is absent" would read as "no duplication".
        val console = transcriptOrNull(root, runId)
        assertTrue(
            console != null && console.contains(stderrMarker),
            "the console must carry the stderr line; if it is absent, the negative assertion below is " +
                "vacuous rather than true. Actual console: ${console?.let { it.take(200) } ?: "<no stream>"}",
        )

        // 3. THE MISSING HALF. stdout must not reach the console.
        assertFalse(
            console!!.contains(stdoutMarker),
            "stdout was mirrored into the console transcript while also being the typed value, so a " +
                "user sees every byte twice. In returnStdout mode stdout is the typed value and stderr " +
                "is the console; the transcript must never contain stdout.",
        )
    }

    /**
     * OBS-PC-205 — bytes still inside the pump when the child exits are committed, not dropped.
     *
     * This is the row that gives [M-A2] something to kill. `DurableShellExecutor` closes the redacting
     * stream before the sink precisely so its pending buffer drains, and that buffer holds up to
     * `maxLiteral` bytes of lookahead: the last fragment of a stream is always still inside it when the
     * child exits. The script therefore ends with a fragment **no longer than the lookahead and with no
     * trailing newline**, so the only thing that can release it is the EOF drain.
     *
     * `206` cannot be the row for this, and measuring it is what proved it: there the payload was already
     * committed in earlier live windows when the deadline fired, so the buffer had nothing to lose. The
     * two rows are different properties with different mutations, which is exactly what an audit that
     * grouped them by topic would have got wrong.
     */
    @Test
    fun `OBS-PC-205 bytes pending in a pump at child exit are committed`() {
        val runId = "r-pc2-205"
        val root = controlRoot("pc2-205")
        val tail = "PC205-TAIL-FRAGMENT-no-newline"

        runBlocking {
            adapter(runId, root)
                .invoke(
                    command = ShellCommand(
                        script = "for i in \$(seq 1 200); do printf 'line-%05d\\n' \$i; done; printf '$tail'",
                        returnMode = ShellReturnMode.NONE,
                    ),
                    runId = RunId(runId),
                    stepIndex = 0,
                )
        }

        val console = transcriptOrNull(root, runId)
        assertTrue(console != null, "the child emitted 200 lines, so the plane must hold a stream")
        assertEquals(
            (1..200).joinToString(separator = "") { "line-%05d\n".format(it) } + tail,
            console,
            "the tail fragment was still inside the pump's pending buffer when the child exited and was " +
                "never committed. A step that seals its console while bytes sit in a pump loses exactly " +
                "the bytes a human is most likely to be watching for — the last thing the process said.",
        )
    }

    /**
     * OBS-PC-202 — both channels at volume, through the real composition, with no deadlock and no
     * cross-contamination.
     *
     * The audit's finding was a *layer* finding, not a volume finding: `Lpr040OutputObservationHarnessTest`'s
     * `P2 mixed streams 20MiB each` is the right experiment, but it drives `ProcessDurableTaskRuntime`
     * with a `collectingSink()`. This composition puts a `StreamingRedactor`, a `RedactingOutputIngress`
     * and a durable `reserve → write → commit` per chunk on a per-channel lock in front of the same pipes,
     * and the redaction stage is precisely the one that holds bytes back across a chunk boundary.
     *
     * The line count is not invented: it is the one the suite already uses for "large" in
     * `Lpr011r2SecretRedactionAtRestUatTest` (`large output streams through the ingress within budget`),
     * applied to both channels at once. That row tolerates `> 15_000` of 20 000 newlines, so this row
     * applies the same tolerance rather than tightening a threshold that was never measured here.
     *
     * Both channels are read **separately**, from their own channel-addressed streams. A single merged
     * read would make "both channels arrived" true even if the pump fused them.
     */
    @Test
    @Timeout(value = 600, unit = TimeUnit.SECONDS)
    fun `OBS-PC-202 both channels flow at volume through the plane without deadlock`() {
        val runId = "r-pc2-202"
        val root = controlRoot("pc2-202")
        val lines = 20_000

        val script = """
            for i in ${'$'}(seq 1 $lines); do
              printf 'OUT-%08d-abcdefghijklmnopqrstuvwxyz\n' ${'$'}i
              printf 'ERR-%08d-0123456789abcdef0123456789\n' ${'$'}i 1>&2
            done
        """.trimIndent()

        runBlocking {
            adapter(runId, root).invoke(
                command = ShellCommand(script = script, returnMode = ShellReturnMode.NONE),
                runId = RunId(runId),
                stepIndex = 0,
            )
        }

        val store = OutputPlaneProvider.storeForReading(root)
        val streams = OutputPlaneProvider.streamsOf(runId, OpId(runId, 0, 0).format())
        val out = String(readAllBytes(store, streams.stdout.stream), Charsets.UTF_8)
        val err = String(readAllBytes(store, streams.stderr.stream), Charsets.UTF_8)

        // Identity first: a fused pump would make the volume assertions below pass anyway.
        assertFalse(
            err.contains("OUT-"),
            "stdout bytes reached the stderr stream, so --channel stderr would answer with the wrong " +
                "channel's bytes. No volume assertion matters once this holds.",
        )
        assertFalse(
            out.contains("ERR-"),
            "stderr bytes reached the stdout stream. The two pumps are not independent.",
        )

        assertTrue(
            out.count { it == '\n' } > 15_000,
            "stdout lost lines at volume through the composition: ${out.count { it == '\n' }} newlines " +
                "for $lines emitted",
        )
        assertTrue(
            err.count { it == '\n' } > 15_000,
            "stderr lost lines at volume through the composition: ${err.count { it == '\n' }} newlines " +
                "for $lines emitted",
        )
    }

    /** Paged drain of one channel-addressed stream, as a consumer would. Never returns `""` for a refusal. */
    private fun readAllBytes(store: SegmentOutputStore, stream: OutputStreamId): ByteArray {
        if (store.read(stream, OutputCursor.start(stream), PAGE_BYTES) is OutputReadResult.Refused) {
            return ByteArray(0)
        }
        val out = java.io.ByteArrayOutputStream()
        var cursor: OutputCursor? = OutputCursor.start(stream)
        while (cursor != null) {
            val page = (store.read(stream, cursor, PAGE_BYTES) as? OutputReadResult.Page)?.page ?: break
            out.write(page.bytes)
            cursor = page.next
        }
        return out.toByteArray()
    }

    /**
     * OBS-PC-206 — a timeout retains every byte the child acknowledged before it.
     *
     * The order is the whole point: the plane is read and shown to hold the full prefix **before** the
     * deadline is allowed to fire, so the row proves survival rather than speed. The child then parks
     * until the timeout kills it, and the prefix is read again afterwards.
     */
    @Test
    fun `OBS-PC-206 a timeout retains the whole acknowledged prefix`() {
        val runId = "r-pc2-206"
        val root = controlRoot("pc2-206")
        val barrier = workspace.resolve("PC206-BARRIER")
        val lines = 400

        val script = """
            for i in ${'$'}(seq 1 $lines); do printf 'ack-%05d\n' ${'$'}i; done
            touch $barrier
            sleep 600
        """.trimIndent()

        var thrown: Throwable? = null
        val runner = thread(name = "pc2-206-invocation") {
            thrown = runCatching {
                runBlocking {
                    adapter(runId, root, ShOptions.EMPTY.copy(timeoutMs = 20_000))
                        .invoke(
                            command = ShellCommand(script = script, returnMode = ShellReturnMode.NONE),
                            runId = RunId(runId),
                            stepIndex = 0,
                        )
                }
            }.exceptionOrNull()
        }

        try {
            // The child really finished emitting. Without this, an empty prefix afterwards would be the
            // child's fault rather than the timeout's, and the row would prove nothing.
            assertTrue(
                await({ Files.exists(barrier) }, 120),
                "the child never finished emitting, so nothing could have been acknowledged before the " +
                    "deadline fired and the row would be measuring an empty plane",
            )
            assertTrue(
                await({ transcriptOrNull(root, runId)?.contains("ack-%05d".format(lines)) == true }, 60),
                "the plane never held the child's full prefix while the child was still alive; the " +
                    "row needs a prefix that demonstrably EXISTED before the timeout to say anything " +
                    "about surviving it",
            )

            val before = transcriptOrNull(root, runId)
            assertEquals(expectedPrefix(lines), before, "the acknowledged prefix itself is not in order")

            // Now let the deadline fire.
            runner.join(TimeUnit.SECONDS.toMillis(120))
            assertTrue(!runner.isAlive, "the deadline never cancelled the child, so this row is not a timeout test")

            // The claim.
            val after = transcriptOrNull(root, runId)
            assertEquals(
                before,
                after,
                "a timeout must retain every byte the child acknowledged. The deadline is allowed to end " +
                    "the run and to discard whatever was never acknowledged — discarding the committed " +
                    "prefix is a different failure, and nothing in the suite caught it.",
            )
            assertEquals(
                expectedPrefix(lines),
                after,
                "after a timeout the console must still be exactly the $lines lines the child emitted, " +
                    "in order and without duplication. Lost: ${missingFrom(expectedPrefix(lines), after)}",
            )
        } finally {
            if (runner.isAlive) {
                runner.interrupt()
                runner.join(TimeUnit.SECONDS.toMillis(10))
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun expectedPrefix(lines: Int): String =
        (1..lines).joinToString(separator = "") { "ack-%05d\n".format(it) }

    private fun missingFrom(expected: String, actual: String?): String {
        val present = (actual ?: "").lineSequence().toSet()
        return expected.lineSequence().filterNot { it in present }.take(10).joinToString(", ")
    }

    /** Bounded wait on a predicate. Returns the predicate's last value; never asserts on elapsed time. */
    private fun await(predicate: () -> Boolean, seconds: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(50)
        }
        return predicate()
    }
}