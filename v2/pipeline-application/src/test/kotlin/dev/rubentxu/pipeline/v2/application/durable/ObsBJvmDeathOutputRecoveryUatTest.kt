package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * OBS-B — the JVM-death UAT the design made a precondition for closing the block.
 *
 * ## Why this is the one that could have stopped OBS-B
 *
 * Before OBS-B2 a transcript lived in a file, so a `kill -9` left a partial-but-complete file and a
 * later run could read whatever was there. Now the bytes are in the Output Plane, which must make a
 * stronger promise across the same crash:
 *
 * ```text
 * acknowledged before the crash  -> MUST survive
 * never acknowledged            -> MUST NOT appear
 * a reservation open at crash   -> MUST be released, keeping the order dense
 * a commit claim without bytes  -> MUST be reported, not read around
 * ```
 *
 * A store that lost acknowledged bytes, or that served a byte nobody ever committed, would pass
 * every liveness row in [ObsBLiveOutputIngressTest] and still be wrong. Those rows have a live child
 * and a graceful exit; this one has neither.
 *
 * ## Why the producer is a forked JVM and not the CLI
 *
 * The first version drove `MainKt` and then read the stream identity out of the operation journal.
 * That reintroduced a harness assumption — it needed a journal row to exist mid-step — and when the
 * read came back empty the row could not distinguish "nothing was committed" from "I was looking at
 * a stream the engine never opened". [ObsBJvmDeathProducer] runs the genuine `ShExecution`, pump,
 * redactor and store in a JVM the test kills, while the run identity stays a value the test owns.
 *
 * ## Fidelity
 *
 * `kill -9` on a real process holding a real open reservation; the store is then re-read through a
 * **fresh** recovered instance (`OutputPlaneProvider.forgetAll()` first), so recovery genuinely runs
 * rather than returning a cached handle. Deterministic barriers, `@TempDir`, no wall-clock
 * liveness assertions, and no pipe the harness has to drain.
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class ObsBJvmDeathOutputRecoveryUatTest {

    private val secret = "GHS6_CANARY_7f3a9c2e1b4d5e6f"
    private val runId = "r-obsb-crash"

    /** Unique per run, so the orphan's cleanup can never match anything else on the box. */
    private val marker = "obsb-jvmdeath-${System.nanoTime()}"

    @TempDir
    lateinit var root: Path

    private var producer: Process? = null
    private lateinit var workspace: Path
    private lateinit var controlRoot: Path

    @BeforeEach
    fun setUp() {
        OutputPlaneProvider.forgetAll()
        assumeTrue(
            !System.getProperty("os.name").orEmpty().lowercase().contains("win"),
            "the durable shell substrate requires a POSIX host",
        )
        workspace = Files.createDirectories(root.resolve("workspace"))
        controlRoot = Files.createDirectories(root.resolve("control"))
    }

    @AfterEach
    fun tearDown() {
        producer?.let { if (it.isAlive) it.destroyForcibly() }
        producer = null
        // The JVM dies with the cookie-scan watchdog inside it, so the wrapper and the script can be
        // orphaned. Left alone they would hold pipes for the rest of the run, which is the kind of
        // self-inflicted noise a harness must not create.
        Files.deleteIfExists(workspace.resolve("BARRIER.release"))
        ProcessBuilder("pkill", "-9", "-f", marker).start().waitFor(5, TimeUnit.SECONDS)
    }

    private fun launchProducer(payloadLines: Int): Process {
        val barrier = workspace.resolve("BARRIER")
        val process = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            "dev.rubentxu.pipeline.v2.application.durable.ObsBJvmDeathProducer",
            *ObsBJvmDeathProducer.argv(
                controlDirRoot = controlRoot,
                runId = runId,
                workspace = workspace,
                barrier = barrier,
                secret = secret,
                payloadLines = payloadLines,
            ),
        )
            // Output to a FILE, never a pipe: roughly a third of this module's tests deadlock
            // because they waitFor() before draining a pipe, and a file cannot deadlock. This row is
            // about a JVM kill, not about the harness's own plumbing.
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.to(workspace.resolve("producer.log").toFile()))
            .start()
        producer = process
        return process
    }

    /** Committed bytes for this run's stream, read the way a brand-new process would read them. */
    private fun committedTranscript(): ByteArray {
        val store = OutputPlaneProvider.storeFor(controlRoot)
        // OBS-C2.3: the producer emits on stdout, so that is the channel-addressed stream it now
        // writes. Reading the old merged stream would report zero bytes for a producer that is
        // durably committing them, and this row's whole claim is about those bytes surviving a kill.
        val stream = OutputPlaneProvider
            .streamsOf(runId, OpId(runId, 0, 0).format())
            .stdout
            .stream
        if (store.read(stream, OutputCursor.start(stream), 4096) is OutputReadResult.Refused) {
            // A refusal here is a fact, not a silent empty: the caller below waits for committed
            // bytes before the crash, so reaching the crash path with a refusal means the ingress was
            // never live and the row has nothing to say about durability.
            return ByteArray(0)
        }
        val out = java.io.ByteArrayOutputStream()
        var cursor: OutputCursor? = OutputCursor.start(stream)
        while (cursor != null) {
            val page = (store.read(stream, cursor, 4096) as OutputReadResult.Page).page
            out.write(page.bytes)
            cursor = page.next
        }
        return out.toByteArray()
    }

    /**
     * The law, at a crash boundary.
     *
     * The order IS the property: read what is committed while the child is alive, then `kill -9`,
     * then read again from a store that has genuinely recovered.
     */
    @Test
    fun `acknowledged sanitized bytes survive a JVM kill with the child alive`() {
        // 400 matched lines is comfortably past the 1 KiB live window, so several chunks are
        // acknowledged before the crash and the assertion is not about a single reservation.
        val process = launchProducer(payloadLines = 400)

        val barrierDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (!Files.exists(workspace.resolve("BARRIER")) && System.nanoTime() < barrierDeadline) {
            Thread.sleep(50)
        }
        assertTrue(
            Files.exists(workspace.resolve("BARRIER")),
            "the child never reached its barrier, so nothing observed after the kill would mean anything",
        )

        var acknowledged: ByteArray
        val ackDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (true) {
            acknowledged = committedTranscript()
            if (acknowledged.isNotEmpty() || System.nanoTime() > ackDeadline) break
            Thread.sleep(100)
        }
        assertTrue(
            acknowledged.isNotEmpty(),
            "no output was ever committed while the step ran. Without this the rest of the row would " +
                "prove only that an empty store survives an empty crash.",
        )
        val beforeCrash = acknowledged.toString(Charsets.UTF_8)
        assertEquals(0, beforeCrash.split(secret).size - 1, "live bytes must already be sanitized")
        assertTrue(
            beforeCrash.contains("****"),
            "the live transcript must carry the marker, not merely omit the secret: a truncated " +
                "transcript would also pass the previous assertion",
        )

        // Kill the JVM outright, and deliberately NOT its process tree. The child's surviving is the
        // premise; a test that killed the tree would be measuring the clean shutdown that already
        // works.
        assertTrue(process.isAlive, "the producer exited before the crash, so this is not a crash test")
        ProcessBuilder("kill", "-9", process.pid().toString()).start().waitFor(5, TimeUnit.SECONDS)
        process.waitFor(30, TimeUnit.SECONDS)
        assertTrue(!process.isAlive, "the producer did not die from SIGKILL")

        // A NEW process's view. forgetAll() forces a real recovery rather than a cached handle.
        OutputPlaneProvider.forgetAll()
        val afterCrash = committedTranscript().toString(Charsets.UTF_8)

        assertTrue(
            afterCrash.startsWith(beforeCrash),
            "bytes acknowledged before the crash must survive it. The crash kept only: " +
                "[${afterCrash.take(160)}]",
        )
        assertEquals(
            0,
            afterCrash.split(secret).size - 1,
            "a recovered transcript must carry zero raw secret bytes",
        )
        assertEquals(
            0,
            rawSecretBytesOnDisk(),
            "no durable segment may hold the raw secret after the crash. This is the white-box half " +
                "of at-rest safety: a store that scrubbed on read would pass every behavioural row " +
                "while still having written the secret to disk.",
        )

        // The recovery report is the oracle for the two claims a read cannot make on its own.
        val store = OutputPlaneProvider.storeFor(controlRoot)
        val report = store.recover()
        assertEquals(
            0L,
            report.bytesUnbacked,
            "recovery found a commit record claiming bytes the payload does not hold. A reader that " +
                "trusted it would return a short page that looks complete.",
        )

        // Recovery is itself crash-prone, so running it twice must be safe: the stable field is the
        // same, and the second pass has nothing left to repair.
        val second = store.recover()
        assertEquals(
            report.committedBytes,
            second.committedBytes,
            "recover() is not idempotent on the stable field",
        )
        assertEquals(0L, second.bytesReleased, "a second pass released work the first should have finished")
        assertEquals(0, second.reservationsReleased, "a second pass released reservations the first should have")

        assertEquals(
            afterCrash,
            committedTranscript().toString(Charsets.UTF_8),
            "re-running recovery changed what a reader sees, so recovery is not a pure reconciliation",
        )
    }

    /** Raw occurrences of the canary in the PHYSICAL segments. Evidence only, never a product API. */
    private fun rawSecretBytesOnDisk(): Int {
        val planeDir = controlRoot.resolve(OutputPlaneProvider.OUTPUT_DIR)
        if (!Files.isDirectory(planeDir)) return 0
        val needle = secret.toByteArray(Charsets.UTF_8)
        return Files.walk(planeDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .mapToInt { file -> countOccurrences(Files.readAllBytes(file), needle) }
                .sum()
        }
    }

    private fun countOccurrences(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return 0
        var count = 0
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            count++
        }
        return count
    }
}
