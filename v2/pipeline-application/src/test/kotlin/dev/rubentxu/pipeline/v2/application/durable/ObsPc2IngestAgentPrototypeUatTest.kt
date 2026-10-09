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
import java.nio.file.attribute.PosixFileAttributes
import java.util.concurrent.TimeUnit

/**
 * OBS-PC-208 — the **prototype** of the ingest agent, and the row that decides whether `ADR-OBS-003`'s
 * shape works at all.
 *
 * ## The claim being tested
 *
 * `ObsPc2ProducerSurvivalSpikeTest` measured the defect: the child survives its JVM and then dies on its
 * first stdout write, because that descriptor is a pipe whose read end belonged to the dead JVM. The ADR
 * proposes a process that outlives it. **This row tests whether the proposal works**, before any production
 * wiring is written, so that a wrong shape is found here rather than after it is wired into the executor.
 *
 * ## What runs
 *
 * ```text
 * test JVM ──spawns──> runtime JVM ──spawns──> ingest agent JVM  (owns both read ends)
 *                         │  └──spawns──> child (setsid, FIFOs for stdout/stderr)
 *                         └── parks, waiting to be killed
 * ```
 *
 * The runtime is killed with `SIGKILL`. The child is not: it is a `setsid` session leader, so its survival
 * is a premise of the row and the test says so rather than assuming it.
 *
 * ## What would make this pass for the wrong reason
 *
 * Two, both closed here:
 *
 * - **the child dying instead of being served.** `DONE` is the child's own proof that it emitted after
 *   the kill and survived; a run that lost the child fails rather than reporting a clean absence.
 * - **the bytes arriving from somewhere else.** `rawSecretBytesOnDisk == 0` alongside a positive
 *   `canary` line, so the transcript can only exist if a drainer read the FIFO and sanitised it. A FIFO
 *   is not a file, so there is no path by which the child's raw bytes reach durable storage.
 *
 * ## Fidelity, and what this does NOT prove
 *
 * Three real JVMs and a real child, real FIFOs, the genuine redactor and the genuine
 * `RedactingOutputIngress` into a real store. The barrier is a file.
 *
 * It does **not** prove the production wiring: `DurableShellExecutor` still hands the child
 * `Redirect.PIPE` to its own JVM, and closing that gap is the implementation, not this row. Nor does it
 * prove the agent can observe a child's EOF — by construction it cannot, because it holds a writer end
 * (see the agent's own KDoc), so a production agent needs an explicit seal this prototype does not have.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
@DisplayName("OBS-PC-208 — prototipo: un drenador que sobrevive a su runtime sigue entregando bytes")
class ObsPc2IngestAgentPrototypeUatTest {

    private val secret = "GHS2_L208_CANARY_7d3a9c14f806"

    @TempDir
    lateinit var root: Path

    private var runtime: Process? = null
    private var fifoDirForCleanup: Path? = null
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
        runtime?.let { if (it.isAlive) it.destroyForcibly() }
        runtime = null
        // The agent is detached from the runtime and would otherwise hold the FIFOs for the rest of the
        // run. It is matched by the FIFO directory, which is unique to this test and IS in its argv —
        // matching on the class name alone would kill any concurrently running sibling run.
        fifoDirForCleanup?.let { dir ->
            ProcessBuilder("pkill", "-9", "-f", dir.toString()).start().waitFor(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `OBS-PC-208 output produced after the runtime JVM dies still reaches the Output Plane`() {
        val runId = "r-obs-pc2-208"
        val opId = OpId(runId, 0, 0).format()
        val fifoDir = workspace.resolve("fifo")
        fifoDirForCleanup = fifoDir
        val readyMarker = workspace.resolve("AGENT-READY-MARKER")
        val linesBefore = 300
        val linesAfter = 50

        val process = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java",
            "-cp",
            System.getProperty("java.class.path"),
            "dev.rubentxu.pipeline.v2.application.durable.ObsPc2RuntimeLauncher",
            *ObsPc2RuntimeLauncher.argv(
                controlDirRoot = controlRoot,
                runId = runId,
                opId = opId,
                workspace = workspace,
                fifoDir = fifoDir,
                readyMarker = readyMarker,
                secret = secret,
                linesBefore = linesBefore,
                linesAfter = linesAfter,
            ),
        )
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.to(workspace.resolve("runtime.log").toFile()))
            .start()
        runtime = process

        assertTrue(
            await({ Files.exists(workspace.resolve("AGENT-READY")) }, 120),
            "the ingest agent never attached to the FIFOs, so nothing observed after the kill would mean " +
                "anything. The runtime log says: ${readLogOrEmpty()}",
        )
        assertTrue(
            await({ Files.exists(workspace.resolve("BARRIER")) }, 120),
            "the child never finished its first emission",
        )
        assertTrue(
            await({ transcript().contains("plain-before-") }, 90),
            "no output reached the plane while the runtime was alive; the row needs a prefix that " +
                "demonstrably EXISTED before the kill to say anything about surviving it. " +
                "agent stderr: ${readIfExists("agent.err.log")} | runtime log: ${readLogOrEmpty()}",
        )

        val rendezvousTypes = listOf("stdout.fifo", "stderr.fifo").joinToString { name ->
            val attrs = Files.readAttributes(fifoDir.resolve(name), PosixFileAttributes::class.java)
            "$name=${attrs.isOther}"
        }
        assertTrue(
            ObsPc2IngestAgent.isFifo(fifoDir.resolve("stdout.fifo")) &&
                ObsPc2IngestAgent.isFifo(fifoDir.resolve("stderr.fifo")),
            "the rendezvous is not a named pipe, so the child is spooling raw bytes to a regular file " +
                "and the whole no-plaintext-spool property is void. Type: $rendezvousTypes",
        )

        val before = transcript()
        assertTrue(
            !before.contains(secret),
            "the drainer committed a raw secret before the kill; everything observed after it would be " +
                "measured on an already-broken plane",
        )

        // The runtime dies. The child does not: setsid gave it its own session.
        assertTrue(process.isAlive, "the runtime exited before the kill, so this is not a survival row")
        ProcessBuilder("kill", "-9", process.pid().toString()).start().waitFor(5, TimeUnit.SECONDS)
        process.waitFor(30, TimeUnit.SECONDS)
        assertTrue(!process.isAlive, "the runtime did not die from SIGKILL")

        // Release the child. It writes lines that the ONLY surviving drainer must pick up.
        Files.writeString(workspace.resolve("GO"), "go")
        val childFinished = await({ Files.exists(workspace.resolve("DONE")) }, 120)
        assertTrue(
            childFinished,
            "the child died instead of being served — its output descriptor still had no reader, so the " +
                "agent's shape does not fix the SIGPIPE and ADR-OBS-003 needs revision",
        )

        // `DONE` only says the bytes are IN the FIFO. The drainer still has to read them and commit each
        // chunk, and the child finishing is not a barrier for that. The first version sampled the
        // plane the instant `DONE` appeared: it passed in isolation and failed under the full
        // suite's load, which is what asserting on a race rather than on a fact looks like.
        //
        // Polling for the post-death bytes is the barrier, and it stays a real assertion because it
        // is bounded — a broken drainer makes it expire and the row fails, rather than passing on a
        // sample taken at a lucky moment.
        val delivered = await({ transcript().contains("plain-after-") }, 60)
        val after = transcript()
        assertTrue(
            delivered && after.length > before.length,
            "the child produced $linesAfter more lines and the plane gained nothing (${before.length} -> " +
                "${after.length}). The drainer outlived the runtime but did not deliver, which is a " +
                "different failure from losing the child and must not be reported as success.",
        )
        // The only file allowed to hold the secret is the fixture that produces it: the child's own script
        // embeds the canary as a literal. Everything else — the rendezvous, the agent's logs, the Output
        // Plane — must be free of it, and an exclusion by exact path is honest about being narrow.
        val fixtureThatEmitsIt = workspace.resolve("child.sh")
        assertEquals(
            emptyList<String>(),
            rawSecretLocations() - root.relativize(fixtureThatEmitsIt).toString(),
            "the raw secret reached durable storage somewhere other than the fixture that emits it. " +
                "A FIFO is not a file, so nothing written by the child itself may survive on disk; the " +
                "paths above are where it did.",
        )
        assertTrue(
            !after.contains(secret),
            "the drainer committed a raw secret to the plane",
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun transcript(): String {
        OutputPlaneProvider.forgetAll()
        val store = OutputPlaneProvider.storeForReading(controlRoot)
        val stream = OutputPlaneProvider.streamsOf("r-obs-pc2-208", OpId("r-obs-pc2-208", 0, 0).format())
            .stdout.stream
        val out = java.io.ByteArrayOutputStream()
        var cursor: OutputCursor? = OutputCursor.start(stream)
        while (cursor != null) {
            when (val result = store.read(stream, cursor, 65536)) {
                is OutputReadResult.Page -> {
                    out.write(result.page.bytes)
                    cursor = result.page.next
                }
                is OutputReadResult.Refused -> throw AssertionError(
                    "reading the Output Plane was REFUSED at cursor=$cursor: ${result.reason}. A probe " +
                        "that quietly stops here reports a truncated stream as if it were the whole " +
                        "stream, which is the opposite of what this row measures. The refusal is " +
                        "the fact; it must be named, not swallowed.",
                )
            }
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    /**
     * Paths of every file under this test's root that holds the raw secret.
     *
     * ## Why the whole root, and why it returns paths instead of a count
     *
     * The first version walked `controlRoot/output-plane` only. That directory is exactly where
     * *sanitised* bytes legitimately live, so the walk passed by construction, and it missed the one
     * place the raw bytes actually were: the `fifo/` directory the child was attached to. It reported
     * `0` on a run where the child's canary sat in a regular file named `stdout.fifo` — it reported
     * the absence of a leak in a tree that contained one.
     *
     * Widened to the whole root it reported `1` immediately, which located a second, purely local
     * truth: the harness's own `child.sh` embeds the canary as a literal, because that is the fixture
     * that PRODUCES the bytes under test. So this returns paths rather than a count — a failure then
     * names the offending file instead of leaving the next reader to go looking for it, which is how
     * the original `controlRoot`-only walk stayed wrong for as long as it did.
     */
    private fun rawSecretLocations(): List<String> {
        val needle = secret.toByteArray(Charsets.UTF_8)
        return Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { file -> countOccurrences(Files.readAllBytes(file), needle) > 0 }
                .map { file -> root.relativize(file).toString() }
                .toList()
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

    private fun readLogOrEmpty(): String = readIfExists("runtime.log")

    /** The failing process's own words. Discarding them is how a harness ends up debugging blind. */
    private fun readIfExists(name: String): String {
        val file = workspace.resolve(name)
        return if (Files.exists(file)) Files.readString(file).takeLast(400).ifBlank { "<empty>" } else "<absent>"
    }

    private fun await(predicate: () -> Boolean, seconds: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(50)
        }
        return predicate()
    }
}
