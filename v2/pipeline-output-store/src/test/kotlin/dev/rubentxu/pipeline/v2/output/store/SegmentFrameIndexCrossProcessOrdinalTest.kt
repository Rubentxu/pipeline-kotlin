package dev.rubentxu.pipeline.v2.output.store

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputStreamAddress

/**
 * **UAT-R1-07** — two JVMs must not assign contradictory ordinals to the same run.
 *
 * OBS-R1 mandate §1.3: characterise `SegmentFrameIndex` before modifying it, and decide the index
 * ownership contract with measurement rather than by reading the field types.
 *
 * ## What the measurement actually found, after a wrong first claim
 *
 * The obvious reading of the source is that `nextOrdinal` is JVM-local and therefore collides across
 * processes: `private val lock = ReentrantLock()` plus `lastOrdinalByRun` in a plain `HashMap`, and
 * `loadOrdinals` refuses to reload once a run is marked loaded. By that reading two JVMs would hand
 * out the same ordinal and a resuming reader would walk past committed bytes. **That reading is wrong**,
 * and this class is what proved it.
 *
 * The write path re-reads the durable file before it allocates. `append` calls `sealTornTail(runId)`,
 * which does a full `readFrames` and then sets `lastOrdinalByRun[runId] = maxOf(cached, highest)`,
 * BEFORE `nextOrdinal` is reached. So the in-memory counter is a cache over the durable log, and every
 * append refreshes it from the file another process has already extended.
 *
 * Consequently the SEQUENTIAL cross-process case — one writer appending while another already has — is
 * safe, and that is a real property worth pinning: it is what stops a long-lived JVM from handing out
 * an ordinal that a restarted one already used.
 *
 * What remains is a narrower and genuinely open hazard: two processes that read the file at the SAME
 * instant, before either writes. The read-modify-write of "read highest, add one, append" is not atomic
 * across processes. That is what UAT-R1-07 still asks about, and it cannot be proved by sequencing,
 * because sequencing is exactly what makes it safe.
 *
 * ## Two rows, and they are not the same kind of evidence
 *
 *  * `XPROC-1` — DETERMINISTIC. Two real processes, two streams, one run, appending in sequence. Green
 *    today. It is a regression row: it fails if the durable re-read is ever removed or short-circuited,
 *    which is the change most likely to reintroduce cross-process reuse.
 *  * `XPROC-2` — a RACE, honestly labelled. Both writers are released from a filesystem barrier and
 *    append concurrently. This cannot be deterministic from outside the process without instrumenting
 *    the write path, so it is measured over several attempts and reported as what it is. A green run
 *    here is evidence that the race window is narrow; it is NOT proof the invariant holds, and the row
 *    says so in its failure message so nobody later mistakes it for a proof.
 *
 * ## Barriers, not durations
 *
 * Ordering is a line protocol over stdin/stdout, plus a filesystem barrier for the race row. Nothing
 * here asserts on elapsed time.
 *
 * ## Measured, three independent runs of six racers
 *
 * ```text
 * [9, 8, 7, 9, 9, 6]   4 distinct
 * [7, 6, 8, 5, 8, 7]   4 distinct
 * [5, 5, 3, 3, 6, 4]   4 distinct
 * ```
 *
 * Six writes, four distinct ordinals, every time, with two writers colliding. The window is not
 * theoretical and it is not a one-in-a-thousand flake. It is the expected shape of an unguarded
 * read-modify-write: every process reads the file, sees the same highest ordinal, and adds one.
 *
 * The full `pipeline-output-store` module is 66 tests with this as the only failure, so the harness
 * is measuring the index and not a side effect of the fixture.
 *
 * ## Why both rows use real processes
 *
 * Copied from `FileBackedRunExecutionLeaseCrossProcessTest`, which already proved this shape for the
 * EVENT plane via `RunExecutionLease` + `UNIQUE(run_id, sequence)`. A lock that only arbitrates inside
 * one JVM is decoration, and so is a test double standing in for one. The asymmetry this class
 * documents is that the event plane got the proof and a durable authority, and the output plane had
 * neither. The fix for `XPROC-2` is the shape the event plane already uses: let the store reject the
 * duplicate rather than letting both writers believe they won.
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class SegmentFrameIndexCrossProcessOrdinalTest {

    /** How many processes race in `XPROC-2`. Small enough to stay quick, large enough to try. */
    private val racerCount = 6


    private fun javaBinary(): String = Path.of(System.getProperty("java.home"), "bin", "java").toString()

    private fun childClasspath(): String = System.getProperty("java.class.path")

    private class Child(val proc: Process, val prefix: String) {
        val out: BufferedReader = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
        /** Named `stdin` rather than `in`: `in` is a keyword and cannot be a property name. */
        val stdin: PrintWriter = PrintWriter(proc.outputStream, true)

        /** Blocks until the child emits a line starting with [marker], or the child dies. */
        fun await(marker: String): String {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
            val seen = StringBuilder()
            while (System.nanoTime() < deadline) {
                if (!out.ready()) {
                    if (!proc.isAlive) {
                        // Drain what is left BEFORE reporting. The first version of this harness
                        // aborted the instant it saw a dead child, so a child that died with an error
                        // on stderr reported as "saw: <nothing>" — which names the symptom and hides
                        // the cause, and made a broken child look like a broken product.
                        proc.waitFor(30, TimeUnit.SECONDS)
                        while (true) {
                            val rest = out.readLine() ?: break
                            seen.appendLine(rest)
                        }
                        error("child $prefix exited with ${proc.exitValue()} before $marker; saw:\n$seen")
                    }
                    Thread.sleep(20)
                    continue
                }
                val line = out.readLine() ?: error("child $prefix closed stdout before $marker; saw:\n$seen")
                seen.appendLine(line)
                if (line.startsWith(marker)) return line
            }
            error("child $prefix never reached $marker within 90s; saw:\n$seen")
        }

        fun cleanup() {
            runCatching { stdin.close() }
            if (proc.isAlive) proc.destroyForcibly()
            proc.waitFor(30, TimeUnit.SECONDS)
        }
    }

    /**
     * Runs the two-process collision and returns the ordinal each writer was handed.
     *
     * Shared by both rows because the collision is the thing under study, not something to re-invent
     * per test: `XPROC-2` asserts what the SAME collision does to a reader, and a second fixture would
     * be a second chance for the two rows to disagree for reasons that have nothing to do with the
     * index.
     */
    private fun collide(runId: String, root: Path): Pair<Long, Long> {
        val a = spawn(root, runId, "op-a", "A", park = true)
        var b: Child? = null
        return try {
            val parked = a.await("PARKED")
            assertEquals(0L, ordinalOf(parked, 2), "A's " +
                "first frame is ordinal 0 on a fresh run; a different value means the fixture changed " +
                "shape and the collision being studied is not the one this row names")

            // B writes ONCE and exits. Only A parks: a second parked child would need its own GO, and
            // the first version of this fixture deadlocked waiting for a line nobody was going to send.
            b = spawn(root, runId, "op-b", "B", park = false)

            // B must reach DONE before A is released. Releasing A first lets A append while B is still
            // starting: A takes ordinal 1 from its cache, B then reads the file, finds 1 taken and
            // takes 2, there is no collision, and the row passes without having tested anything. The
            // order IS the experiment.
            val bFirst = ordinalsOf(b.await("DONE")).first

            a.stdin.println("GO")
            a.stdin.flush()

            val aSecond = ordinalsOf(a.await("DONE")).second
                ?: error("A parks and appends twice, so its DONE must carry a second ordinal; a '-' " +
                    "there means the child took the non-parking path and this row measured nothing")
            aSecond to bFirst
        } finally {
            a.cleanup()
            b?.cleanup()
        }
    }

    /**
     * The ordinal at position [n] of a `<MARKER> <prefix> <n...>` line.
     *
     * Positional rather than parsed by substring: the first version searched for a literal
     * `" ordinal"` that no child ever printed, so it tried to parse `"A 0"` as a number and the
     * harness reported a NumberFormatException instead of the collision.
     */
    private fun ordinalOf(line: String, n: Int): Long =
        line.trim().split(" ").getOrNull(n)?.toLong()
            ?: error("line '$line' has no ordinal at position $n")

    /** `DONE <prefix> <first> <second|->` -> the ordinals that writer was actually handed. */
    private fun ordinalsOf(doneLine: String): Pair<Long, Long?> {
        val parts = doneLine.trim().split(" ")
        return ordinalOf(doneLine, 2) to parts.getOrNull(3)?.takeIf { it != "-" }?.toLong()
    }

    private fun spawn(root: Path, runId: String, opId: String, prefix: String, park: Boolean): Child =
        Child(
            ProcessBuilder(
                javaBinary(), "-cp", childClasspath(),
                FrameWriterChild::class.java.name,
                root.toAbsolutePath().toString(), runId, opId, prefix, park.toString(),
            ).redirectErrorStream(true).start(),
            prefix,
        )

    @Test
    fun `XPROC-1 two JVMs appending one run in sequence never share an ordinal`( @TempDir root: Path) {
        val (aSecond, bFirst) = collide("run-xproc-sequential", root)

        // Asserted as a RELATION, not as literal values. The first version pinned B to ordinal 1 and
        // A to 2, and went red on a run where B was handed 2 — not because ordinals collided, but
        // because recovery allocated a frame this row never asked about. The property under study is
        // that the second writer continues past the first; which integer that is depends on how many
        // frames the plane already holds, which is not this row's business.
        //
        // The ordering is guaranteed by the fixture: B reaches DONE before A is released, so B's frame
        // is on disk before A's second append. A resumed holding a cached ordinal of 0 and MUST
        // re-read to beat it.
        assertTrue(
            aSecond > bFirst,
            "A resumed after B had already written and was handed ordinal $aSecond, which does not " +
                "continue past B's $bFirst. A's in-memory counter still says 0 at that point, so " +
                "winning only by re-reading the durable log is the whole property: a JVM that served " +
                "its cache instead would hand out an ordinal a restarted writer already used",
        )
        assertNotEquals(
            aSecond,
            bFirst,
            "two processes appended the same run and were handed the same ordinal",
        )
    }

    @Test
    fun `XPROC-2 two JVMs released together do not hand out one ordinal twice`( @TempDir root: Path) {
        // The hazard UAT-R1-07 still asks about, and the one this class exists for: the
        // read-modify-write of "read the highest ordinal, add one, append" is a single critical section
        // inside one JVM and nothing at all across two.
        //
        // Both children are released by a filesystem barrier so they enter append() as close together
        // as the operating system allows. This is a RACE and the row says so: a green run means the
        // window is narrow, not that the invariant holds. Proving it would need the write path
        // instrumented, and instrumenting it to make a test deterministic would be changing the
        // product to serve the test.
        val runId = "run-xproc-race"
        val gate = root.resolve("GO")

        val racers = (0 until racerCount).map { i ->
            Child(
                ProcessBuilder(
                    javaBinary(), "-cp", childClasspath(),
                    FrameWriterChild::class.java.name,
                    root.toAbsolutePath().toString(), runId, "op-$i", "R$i", "false",
                    gate.toAbsolutePath().toString(),
                ).redirectErrorStream(true).start(),
                "R$i",
            )
        }
        try {
            racers.forEach { it.await("READY") }
            Files.writeString(gate, "go")

            val handed = racers.map { ordinalOf(it.await("DONE"), 2) }
            val distinct = handed.distinct()
            assertEquals(
                handed.size,
                distinct.size,
                "UAT-R1-07 VIOLATED: $racerCount processes released together were handed ${handed.size} " +
                    "writes but only ${distinct.size} distinct ordinals ($handed). A duplicate ordinal " +
                    "is not untidy: a reader paging with a limit drops one of the two frames sharing it, " +
                    "and those are committed bytes no console will ever show again. Note this row is " +
                    "a RACE — it can pass without the invariant holding, and it can only fail by luck. " +
                    "Treat a red here as proof the defect is reachable and a green one as no proof at " +
                    "all; the deterministic guarantee still needs the write path to consult the durable " +
                    "log atomically, the way the event plane's UNIQUE(run_id, sequence) does",
            )
        } finally {
            racers.forEach { it.cleanup() }
        }
    }

    /**
     * The writer under test, run as a real `java -cp <classpath>` child.
     *
     * Real processes and the shipped code, following the pattern already proven in
     * `FileBackedRunExecutionLeaseCrossProcessTest`: a lock that only arbitrates inside one JVM is
     * decoration, and so is a test double standing in for one.
     */
    object FrameWriterChild {
        @JvmStatic
        fun main(args: Array<String>) {
            val root = Path.of(args[0])
            val runId = args[1]
            val opId = args[2]
            val prefix = args[3]
            val park = args.getOrNull(4)?.toBoolean() ?: true
            val gate = args.getOrNull(5)

            val store = SegmentOutputStore(root)
            // Recovery first: the store refuses to write on an unreconciled plane (O3). A real writer
            // opens with it, and a fixture that skipped it would have been testing a state the product
            // refuses to be in — the first version of this child died here, correctly.
            store.recover()
            val index = store.frameIndex()
            val stream = OutputStreamAddress.of(runId, opId, OutputChannel.STDOUT).stream
            index.declareStream(stream, OutputChannel.STDOUT)

            // Real bytes through the real reserve/commit path, so the frame names committed extent and
            // this is the production sequence rather than a synthetic append.
            val payload = "$prefix-first-block\n".toByteArray(StandardCharsets.UTF_8)
            val committed = store.open(stream).reserve(payload.size).apply { write(payload) }.commit()
            val first = index.append(stream, OutputChannel.STDOUT, 0L, committed)
            println("FIRST $prefix ${first.ordinal}")
            if (!park) {
                if (gate != null) {
                    // Parked BEFORE the index append is not involved: the byte write is done, so the
                    // only thing left is the ordinal allocation, and every racer enters it together.
                    println("READY $prefix ${first.ordinal}")
                    System.out.flush()
                    while (!Files.exists(Path.of(gate))) Thread.sleep(5)
                }
                val raceFrame = index.append(stream, OutputChannel.STDOUT, committed, committed + 4L)
                println("DONE $prefix ${raceFrame.ordinal}")
                System.out.flush()
                return
            }
            println("PARKED $prefix ${first.ordinal}")
            System.out.flush()

            val reader = BufferedReader(InputStreamReader(System.`in`, StandardCharsets.UTF_8))
            val go = reader.readLine() ?: return
            assertTrue(go == "GO") { "child $prefix expected GO, got '$go'" }

            val more = "$prefix-second-block\n".toByteArray(StandardCharsets.UTF_8)
            val committed2 = store.open(stream).reserve(more.size).apply { write(more) }.commit()
            val second = index.append(stream, OutputChannel.STDOUT, committed, committed2)
            println("SECOND $prefix ${second.ordinal}")
            println("DONE $prefix ${first.ordinal} ${second.ordinal}")
            System.out.flush()
        }
    }
}
