package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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

/**
 * OBS-2 Nivel B — the spike, and the defect it measured.
 *
 * ## The question
 *
 * OBS-B established that bytes a child acknowledged before a `kill -9` survive it. That question is
 * already answered and green. The Level B question is the next one, and it has never been asked on
 * this branch:
 *
 * > When the JVM that owns the child's stdout/stderr pipes dies while the child keeps running, does the
 * > output the child produces **afterwards** reach the Output Plane?
 *
 * ## What was measured — and it is not what the framing expected
 *
 * ```text
 * resumed_after_jvm_death=true              the child DID survive and carried on
 * alive_proof_after_jvm_death=true          it wrote a FILE successfully: no watchdog killed it
 * after_lines_written=0/50                 it produced NONE of its post-death stdout
 * bytes_after_release == bytes_after_kill   and the Output Plane gained nothing
 * ```
 *
 * The failure is therefore **not** "output is lost". The child is *killed* — by the kernel — on its
 * first write to stdout, because that descriptor is a pipe whose read end belonged to the JVM that is
 * now gone, so the write returns `EPIPE` and raises `SIGPIPE`. A user's `sh` does not go quiet: it dies
 * mid-script, and the step reports nothing about why.
 *
 * That distinction decides the architecture, and it is why the row spends two assertions separating "a
 * watchdog killed it" from "it died on its pipe". Both present as an empty tail, and repairing one does
 * not repair the other.
 *
 * ## Why the barriers are files and not sleeps
 *
 * `BARRIER` proves the child finished its first emission. `GO` is released **only after the JVM is
 * dead**, which is what makes the observation meaningful: the child's second emission cannot have
 * started early. `DONE` is the child's own proof that it emitted after the kill and survived it.
 *
 * Without `GO` the child races ahead, the test sees an empty tail, and cannot distinguish "the child
 * died from a broken pipe" from "the child had not written yet". A harness that cannot separate those
 * two produces a result that reads like an answer.
 *
 * ## These assertions are a DEFECT, not a guarantee
 *
 * They pin what was measured. They are green today, and a green row here must not be read as a pass:
 * this is the broken behaviour held in place so that closing it becomes a deliberate inversion rather
 * than a silent rewrite of the expectation. Each message names the value a fix must produce. The full
 * observation is additionally written to a report file outside the temp tree, which JUnit would
 * otherwise delete along with the result.
 *
 * ## Fidelity
 *
 * HF3. A real `kill -9` on a real JVM, a real child, the genuine `ShExecution`, pump, redactor and
 * store. The child's process tree is deliberately NOT killed. Barriers are files, so "the child has
 * finished emitting" and "the child has resumed" are events rather than sleeps — an earlier version of
 * this harness released the child with no post-death barrier at all and could not tell a killed child
 * from a slow one. `@TempDir`, no pipes, no wall-clock assertions.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
@DisplayName("OBS-2 Nivel B — spike: qué ocurre con la salida del hijo tras morir su JVM")
class ObsPc2ProducerSurvivalSpikeTest {

    private val secret = "GHS9_LEVELB_CANARY_4b8e1d7a2c6f"
    private val runId = "r-obs-pc2-levelb"

    @TempDir
    lateinit var root: Path

    private var producer: Process? = null
    private lateinit var workspace: Path
    private lateinit var controlRoot: Path

    @BeforeEach
    fun setUp() {
        OutputPlaneProvider.forgetAll()
        assumeTrue(
            !System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true),
            "the durable shell substrate requires a POSIX host",
        )
        workspace = Files.createDirectories(root.resolve("workspace"))
        controlRoot = Files.createDirectories(root.resolve("control"))
    }

    @AfterEach
    fun tearDown() {
        producer?.let { if (it.isAlive) it.destroyForcibly() }
        producer = null
        Files.deleteIfExists(workspace.resolve("RELEASE"))
    }

    @Test
    fun `spike - output produced by the child AFTER the owning JVM dies`() {
        val barrier = workspace.resolve("BARRIER")
        val go = workspace.resolve("GO")
        val done = workspace.resolve("DONE")
        // OUTSIDE @TempDir on purpose: JUnit deletes the temp tree when the row ends, and a spike whose
        // whole output is deleted on success is a spike that reports nothing.
        val reportDir = Files.createDirectories(Path.of(System.getProperty("user.home"), "obsPc2diag"))
        val report = reportDir.resolve("LEVELB-REPORT.txt")

        val linesBefore = 300
        val linesAfter = 50

        val process = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            "dev.rubentxu.pipeline.v2.application.durable.ObsPc2JvmOwnerDeathProducer",
            *ObsPc2JvmOwnerDeathProducer.argv(
                controlDirRoot = controlRoot,
                runId = runId,
                workspace = workspace,
                barrier = barrier,
                go = go,
                done = done,
                secret = secret,
                linesBefore = linesBefore,
                linesAfter = linesAfter,
            ),
        )
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.to(workspace.resolve("producer.log").toFile()))
            .start()
        producer = process

        await(workspace.resolve("BARRIER"), 120, "the child never reached its barrier")
        // Wait for a MEANINGFUL prefix, not merely for one byte. An earlier version of this spike killed
        // as soon as anything had been acknowledged and recorded bytes_before_kill=1, which proves the
        // ingress had flushed its first window and nothing about durability.
        val beforeKill = awaitPrefix("plain-before-", 90)

        // Kill the JVM only. The child is a `setsid` session leader and must not be taken with it.
        assertTrue(process.isAlive, "the producer exited before the crash, so this is not a crash measurement")
        ProcessBuilder("kill", "-9", process.pid().toString()).start().waitFor(5, TimeUnit.SECONDS)
        process.waitFor(30, TimeUnit.SECONDS)
        assertTrue(!process.isAlive, "the producer did not die from SIGKILL")

        // Level A, already guaranteed by OBS-B: what was acknowledged before the kill is intact.
        OutputPlaneProvider.forgetAll()
        val afterKill = committedTranscript()
        val afterKillText = afterKill.toString(Charsets.UTF_8)
        assertTrue(
            afterKillText.startsWith(beforeKill.toString(Charsets.UTF_8)),
            "acknowledged bytes must survive the kill; that is Level A and OBS-B already holds it",
        )
        assertEquals(0, afterKillText.split(secret).size - 1, "and no raw secret may be readable")

        // NOW release the child. Nothing it does from here on can reach a pump, because there are no
        // pumps left: they were daemon threads in the JVM that is now gone.
        Files.writeString(go, "go")
        val childFinished = await({ Files.exists(done) }, 90)

        // Whether the child finished, and whether its post-death output reached the store, is the
        // measurement. It is recorded, not asserted.
        OutputPlaneProvider.forgetAll()
        val afterGo = committedTranscript()
        val afterGoText = afterGo.toString(Charsets.UTF_8)

        val facts = buildString {
            appendLine("OBS-2 LEVEL B SPIKE — runId=$runId")
            appendLine("--- discriminadores, leidos en este orden ---")
            appendLine("resumed_after_jvm_death=${Files.exists(workspace.resolve("RESUMED"))}")
            appendLine("alive_proof_after_jvm_death=${Files.exists(workspace.resolve("ALIVE_MARK"))}")
            appendLine("after_lines_written=${afterLineMarkers(linesAfter)}/$linesAfter")
            appendLine("done_marker_exists=${Files.exists(done)}")
            appendLine("child_finished_after_jvm_death=$childFinished")
            appendLine("--- plano de salida ---")
            appendLine("bytes_before_kill=${beforeKill.size}")
            appendLine("bytes_after_kill=${afterKill.size}")
            appendLine("bytes_after_release=${afterGo.size}")
            appendLine("after_bytes_reached_plane=${afterGo.size > afterKill.size}")
            appendLine("has_before_lines=${afterGoText.contains("plain-before-")}")
            appendLine("has_after_lines=${afterGoText.contains("plain-after-")}")
            appendLine("canary_redacted=${afterGoText.contains("****")}")
            appendLine("raw_secret_on_disk=${rawSecretBytesOnDisk()}")
        }
        Files.writeString(report, facts)

        // The two Level A assertions above are OBS-B's, already green before this row existed.
        assertTrue(
            Files.exists(report),
            "the spike must always leave its observation on disk, even when the child dies",
        )

        // ---- the defect characterisation -------------------------------------------------
        //
        // These assertions pin what the spike MEASURED, not what the product should do. They are green
        // today and a green row here must NOT be read as a passing guarantee: it is the defect held in
        // place so that closing it becomes a deliberate inversion rather than a silent rewrite of what
        // the test expects. Each message names the value a fix must produce.
        val resumed = Files.exists(workspace.resolve("RESUMED"))
        val aliveProof = Files.exists(workspace.resolve("ALIVE_MARK"))
        val written = afterLineMarkers(linesAfter)

        assertTrue(
            resumed,
            "PREMISE FAILED: the child never resumed after its JVM died, so this row says nothing about " +
                "Level B. A child killed by something other than the pipe needs a different investigation.",
        )
        assertTrue(
            aliveProof,
            "PREMISE FAILED: the child could not write a file after the JVM died, so something other " +
                "than the stdout pipe is killing it. The whole EPIPE reading rests on this being true.",
        )
        assertEquals(
            0,
            written,
            "DEFECT OBSERVED — the child survives its JVM and dies on its first stdout write. " +
                "The child wrote a file successfully moments ago (ALIVE_MARK) and then produced $written of " +
                "$linesAfter output lines. The only thing between those two facts is fd 1: its write end " +
                "is a pipe whose read end the dead JVM owned, so the kernel answered EPIPE and SIGPIPE " +
                "terminated it. This is not 'output is lost', it is 'the user's process is killed'. " +
                "A FIX MUST PRODUCE $linesAfter of $linesAfter here.",
        )
        assertEquals(
            afterKill.size,
            afterGo.size,
            "DEFECT OBSERVED — the Output Plane gained nothing after the child was released. Whatever " +
                "produced, the plane cannot receive it without an owner of the child's descriptors that " +
                "outlives the JVM. A FIX MUST PRODUCE more committed bytes than $${afterKill.size} here.",
        )
    }

    /** Committed transcript as a brand-new process would read it, after a genuine recovery. */
    private fun committedTranscript(): ByteArray {
        val store = OutputPlaneProvider.storeForReading(controlRoot)
        val stream = OutputPlaneProvider.streamsOf(runId, OpId(runId, 0, 0).format()).stdout.stream
        val out = java.io.ByteArrayOutputStream()
        var cursor: OutputCursor? = OutputCursor.start(stream)
        while (cursor != null) {
            when (val result = store.read(stream, cursor, 65536)) {
                is OutputReadResult.Page -> {
                    out.write(result.page.bytes)
                    cursor = result.page.next
                }
                is OutputReadResult.Refused -> cursor = null
            }
        }
        return out.toByteArray()
    }

    private fun await(marker: Path, seconds: Long, message: String) {
        if (!await({ Files.exists(marker) }, seconds)) error(message)
    }

    /** The first committed transcript that actually contains [marker], or the longest seen. */
    private fun awaitPrefix(marker: String, seconds: Long): ByteArray {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        var best = ByteArray(0)
        while (System.nanoTime() < deadline) {
            val current = committedTranscript()
            if (current.toString(Charsets.UTF_8).contains(marker)) return current
            if (current.size > best.size) best = current
            Thread.sleep(100)
        }
        return best
    }

    /** How many of the child's per-line post-death markers exist. Zero means it died on the first write. */
    private fun afterLineMarkers(linesAfter: Int): Int =
        (1..linesAfter).count { Files.exists(workspace.resolve("AFTER-$it")) }

    private fun await(predicate: () -> Boolean, seconds: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(50)
        }
        return predicate()
    }

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
