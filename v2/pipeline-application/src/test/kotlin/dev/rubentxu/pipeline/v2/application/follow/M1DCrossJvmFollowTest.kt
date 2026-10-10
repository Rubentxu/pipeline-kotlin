package dev.rubentxu.pipeline.v2.application.follow

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.durable.EventRecordReadPortStoreAdapter
import dev.rubentxu.pipeline.v2.events.durable.SqliteEventFollower
import dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore
import dev.rubentxu.pipeline.v2.events.follow.EventFollowEvent
import dev.rubentxu.pipeline.v2.events.follow.EventFollowOptions
import dev.rubentxu.pipeline.v2.events.follow.EventFollowState
import dev.rubentxu.pipeline.v2.events.follow.EventFollowUntil
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputStreamAddress
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.follow.FollowState
import dev.rubentxu.pipeline.v2.output.follow.FollowUntil
import dev.rubentxu.pipeline.v2.output.follow.OutputFollowEvent
import dev.rubentxu.pipeline.v2.output.follow.OutputFollowOptions
import dev.rubentxu.pipeline.v2.output.store.SegmentOutputFollower
import dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * M1-D — cross-JVM end-to-end proof of `output.follow.v1` and `events.follow.v1`.
 *
 * The whole point of M1-D is that the contract holds across the OS process
 * boundary: a SECOND JVM opens the follow API and observes the same run the
 * parent JVM is actively writing to. In-process tests cannot prove that
 * property — shared-state caching, shared file descriptors, and shared
 * in-memory maps can all hide a defect that only manifests under real
 * concurrent processes reading the same on-disk authority.
 *
 * The shape of every case follows the in-tree
 * `FileBackedRunExecutionLeaseCrossProcessTest` precedent (real
 * `ProcessBuilder` children, line-based stdout protocol, structured log file
 * for the per-event trail). What changes here is the authority being
 * observed: a `SqliteEventStore` for the event plane and a
 * `SegmentOutputStore` for the output plane. Both are file-based and
 * therefore naturally cross-process; the test exercises that property.
 *
 * The six UAT cases are pinned by the operator's review (2026-10-10,
 * point 9: "Cross-JVM UAT with two processes observing same run while
 * executing"). They are NOT a re-run of the in-process contract tests in
 * `SegmentOutputFollowerTest` or `EventRecordReadPortAdapterTest`: those
 * pin the closed ADTs and the closed polling semantics, this class pins
 * that those semantics survive a real process boundary.
 *
 * ## Why two processes, not two threads
 *
 * The defect class this class exists to surface is "the follower relies
 * on a per-JVM in-memory cache for what is a cross-process durable
 * fact". Two threads in one JVM share that cache; two processes do not.
 * The `FileBackedRunExecutionLeaseCrossProcessTest` precedent
 * establishes that a lease authority proved only inside one JVM is
 * decoration, and the same argument applies to a follow authority.
 *
 * ## Why each case has a 60 s `Timeout`
 *
 * The follow polls at the baseline 25 ms cadence (`FOLLOW_IDLE_MILLIS`),
 * so a single `drainOnce` round costs well under one second. The bound
 * leaves room for the parent's writes, the child's first poll, the
 * terminal event, and the child's drain, plus a large CI slack. A test
 * that hits the bound is observing a polling anomaly, not a slow test.
 *
 * ## Why the child bounds its own iteration
 *
 * The follow API has no built-in timeout; `close()` is the cancellation
 * path and a cross-process close is awkward (the parent cannot reach
 * into the child's JVM to invoke it). The child therefore bounds its
 * `hasNext` loop with `maxPolls` (the `maxEvents` argument). The bound
 * is set generously: a follow that ever needs 200+ events to reach its
 * `Completed` has a contract defect, not a slow build.
 *
 * ## Why this test lives in `:pipeline-application`
 *
 * The follow contract lives in `pipeline-output` and `pipeline-events`;
 * the durable implementations live in `pipeline-output-store` and
 * `pipeline-events-store`; the consumer that exercises both with one
 * JVM is `:pipeline-application`. Putting the cross-JVM harness in the
 * module that owns the consumer-facing composition means the test
 * travels with the code it exercises; a precedent in `:pipeline-events-store`
 * would have left the output plane unproven in the same module.
 *
 * @see M1_FOLLOW_DESIGN.md §6 in
 *   docs/pipelinek-coordinated-evolution/m1-design/ for the UAT
 *   traceability table this class implements.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class M1DCrossJvmFollowTest {

    // -------------------------------------------------------------- JVM plumbing

    private fun javaBin(): String = Path.of(System.getProperty("java.home"), "bin", "java").toString()

    private fun childClasspath(): String = System.getProperty("java.class.path")

    private fun spawnChild(args: List<String>): Process =
        ProcessBuilder(
            buildList {
                add(javaBin())
                add("-cp")
                add(childClasspath())
                add(FollowChild::class.java.name)
                addAll(args)
            },
        ).redirectErrorStream(true).start()

    /**
     * Read the child's stdout until [marker] is seen, returning everything
     * that arrived first. The bound is 30 s — the marker is emitted as
     * soon as the follow opens and the first `hasNext` returns, which is
     * single-digit milliseconds in practice.
     */
    private fun awaitMarker(p: Process, marker: String, timeoutMs: Long): String {
        val reader = BufferedReader(InputStreamReader(p.inputStream, StandardCharsets.UTF_8))
        val deadline = System.currentTimeMillis() + timeoutMs
        val seen = StringBuilder()
        while (System.currentTimeMillis() < deadline) {
            if (!reader.ready()) {
                if (!p.isAlive) {
                    p.waitFor(5, TimeUnit.SECONDS)
                    while (true) {
                        val rest = reader.readLine() ?: break
                        seen.appendLine(rest)
                    }
                    error("child died before $marker; saw:\n$seen")
                }
                Thread.sleep(20)
                continue
            }
            val line = reader.readLine()
                ?: error("child closed stdout before $marker; saw:\n$seen")
            seen.appendLine(line)
            if (line.startsWith(marker)) return seen.toString()
        }
        error("child never reached $marker within ${timeoutMs}ms; saw:\n$seen")
    }

    private fun readChildLog(logPath: Path): List<String> =
        Files.readAllLines(logPath, StandardCharsets.UTF_8)

    // -------------------------------------------------------------- producer helpers

    /**
     * Make the run "known" to the event follow by appending [RunStarted].
     *
     * The M1-A `runExists` callback composes `leaseStore.isKnown ||
     * store.hasRun`; writing a single row is the cheapest way to satisfy
     * the second clause without involving the lease authority. The follow
     * then sees the run as a known, possibly-empty run.
     */
    private fun declareEventRun(db: SqliteEventStore, runId: String) {
        db.append(
            RunStarted(
                eventId = UUID.randomUUID().toString(),
                runId = runId,
                sequence = 0L,
                occurredAt = Instant.now(),
                scriptPath = "m1-d.test",
            ),
        )
        db.flush()
    }

    private fun writeEvent(db: SqliteEventStore, event: DomainEvent) {
        db.append(event)
        // `append` is parenthesised through a bounded queue; `flush` is the
        // barrier that proves the row reached the SQLite COMMIT. Without it
        // the child might poll and observe the pre-write tail.
        db.flush()
    }

    /**
     * Make the run "known" to the output follow by declaring a stream and
     * writing an initial frame.
     *
     * The M1-B `runExists` callback is `store::hasOutputFor`, which
     * returns true iff a stream directory exists for the run. Declaring
     * the stream creates the directory; the initial frame gives the first
     * `BYTES` event something concrete to carry.
     */
    private fun declareOutputRun(
        store: SegmentOutputStore,
        runId: String,
        opId: String,
        initialPayload: String,
    ): OutputStreamId {
        store.recover()
        val frameIndex = store.frameIndex()
        val stream = OutputStreamAddress.of(runId, opId, OutputChannel.STDOUT).stream
        frameIndex.declareStream(stream, OutputChannel.STDOUT)
        return stream
    }

    /**
     * Write [payload] as a single committed frame on [stream]. The frame
     * is registered on the store's [SegmentFrameIndex] so the follower's
     * `framesOfRun` returns it. Same pattern as
     * `SegmentOutputFollowerTest::writeAndFrame`.
     */
    private fun writeOutputFrame(
        store: SegmentOutputStore,
        stream: OutputStreamId,
        channel: OutputChannel,
        payload: String,
    ): OutputFrame {
        val before = store.committedExtent(stream) ?: 0L
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        val reservation = store.open(stream).reserve(bytes.size)
        reservation.write(bytes)
        val after = reservation.commit()
        val frameIndex = store.frameIndex()
        return frameIndex.append(stream, channel, from = before, to = after)
    }

    // -------------------------------------------------------------- 1 UAT-PK-M1-001

    @Test
    fun `1 UAT-PK-M1-001 output follower joins a running run and observes bytes it has not yet read`(
        @TempDir root: Path,
    ) {
        val runId = "uat-m1-001-${shortId()}"
        val outputRoot = root.resolve("output")
        val log = root.resolve("child-events.log")

        val parentStore = SegmentOutputStore(outputRoot)
        val stream = declareOutputRun(parentStore, runId, "op-0", "pre-follow\n")
        // The run must be "already running" when the child opens the follow:
        // at least one committed frame exists, and the frame index knows
        // about the stream so `framesOfRun` has something to return.
        val preFrame = writeOutputFrame(parentStore, stream, OutputChannel.STDOUT, "pre-follow\n")

        val child = spawnChild(
            listOf(
                runId,
                "output",
                outputRoot.toAbsolutePath().toString(),
                log.toAbsolutePath().toString(),
                "32",
                "until-allsealed",
            ),
        )
        var postFrame: dev.rubentxu.pipeline.v2.output.OutputFrame? = null
        try {
            awaitMarker(child, "READY", 30_000)
            // Yield once more so the child's first `drainOnce` has already
            // emitted the pre-follow `BYTES` event. The next write is the
            // one the follow "has not yet read" when it opened.
            Thread.sleep(100)
            postFrame = writeOutputFrame(parentStore, stream, OutputChannel.STDOUT, "post-follow\n")
            parentStore.seal(stream)

            child.waitFor(30, TimeUnit.SECONDS)
            assertEquals(0, child.exitValue(), "child exited non-zero; child log:\n${Files.readString(log)}")
        } finally {
            runCatching { child.destroyForcibly() }
            runCatching { child.waitFor(5, TimeUnit.SECONDS) }
        }

        val lines = readChildLog(log)
        val bytesLines = lines.filter { it.startsWith("BYTES ") }
        assertEquals(
            2, bytesLines.size,
            "expected one BYTES per frame (pre-follow, post-follow), got:\n$lines",
        )
        val preBytes = payloadOf(bytesLines.first())
        val postBytes = payloadOf(bytesLines.last())
        assertEquals("pre-follow\n", preBytes, "the pre-join frame must be visible to the child")
        assertEquals("post-follow\n", postBytes, "the post-join frame must be visible to the child")
        assertEquals(1, lines.count { it == "COMPLETED" }, "the follow must emit Completed exactly once")
        assertTrue(
            lines.any { it.startsWith("STATE_STREAM_SEALED") },
            "the follow must observe StreamSealed after the parent seals the stream; got:\n$lines",
        )
        // The frames arrive in the order the parent committed them; the byte
        // offsets are strictly ascending (the parent's `commit()` returned
        // a `to` strictly greater than every prior committed extent).
        assertEquals(
            preFrame.from, frameFrom(bytesLines.first()),
            "the first BYTES event must carry the pre-join frame's `from` offset",
        )
        assertEquals(
            postFrame!!.to, frameTo(bytesLines.last()),
            "the last BYTES event must carry the post-join frame's `to` offset",
        )
        // The `from` of the second BYTES is strictly greater than the
        // `to` of the first — a duplicate frame would have the SAME `from`,
        // and a gap would leave a missing range in between.
        val firstTo = frameTo(bytesLines.first())
        val secondFrom = frameFrom(bytesLines.last())
        assertTrue(
            firstTo <= secondFrom,
            "the two BYTES events must cover a contiguous or adjacent range; " +
                "firstTo=$firstTo secondFrom=$secondFrom",
        )
    }

    // -------------------------------------------------------------- 2 UAT-PK-M1-002

    @Test
    fun `2 UAT-PK-M1-002 events follower joins a running run and observes events it has not yet read`(
        @TempDir root: Path,
    ) {
        val runId = "uat-m1-002-${shortId()}"
        val dbPath = root.resolve("events.db").toAbsolutePath().toString()
        val log = root.resolve("child-events.log")

        val parentDb = SqliteEventStore(dbPath)
        try {
            // The M1-C/1.A `SqliteEventFollowHandle::drainOnce` "natural-tail"
            // emission (`!hasMore → Completed`) is a documented part of the
            // contract: the follow reports "no more rows right now" and the
            // consumer decides whether to reopen with `after = nextCursor`.
            // For a cross-JVM proof that does NOT depend on that reopen
            // behaviour, the producer writes every event the consumer is
            // expected to see BEFORE the consumer opens the follow — the
            // consumer's first poll sees them in one page, RunFinished is
            // in the records, and the follow reaches Completed via the
            // `RunFinished` path (not the natural-tail path). The cross-JVM
            // property the test pins is "the consumer observes the events
            // a separate process wrote to the same Sqlite database".
            declareEventRun(parentDb, runId)
            writeEvent(parentDb, stageStarted(runId))
            writeEvent(parentDb, stageFinished(runId))
            writeEvent(parentDb, runFinished(runId))
            val child = spawnChild(
                listOf(
                    runId,
                    "events",
                    dbPath,
                    log.toAbsolutePath().toString(),
                    "32",
                    "until-runfinished",
                ),
            )
            try {
                // The child polls the durable database the parent wrote to.
                // We do NOT sleep before the writes above; the parent's
                // `flush()` makes each row visible synchronously to the
                // reader the child opens.
                child.waitFor(30, TimeUnit.SECONDS)
                assertEquals(0, child.exitValue(), "child exited non-zero; child log:\n${Files.readString(log)}")
            } finally {
                runCatching { child.destroyForcibly() }
                runCatching { child.waitFor(5, TimeUnit.SECONDS) }
            }
        } finally {
            parentDb.close()
        }

        val lines = readChildLog(log)
        // The child sees every event the parent wrote to the same SQLite
        // database. Every event must show up at most once and in strictly
        // ascending sequence order.
        val pageLines = lines.filter { it.startsWith("PAGE ") }
        assertTrue(
            pageLines.isNotEmpty(),
            "the child must observe at least one PAGE event; got:\n$lines",
        )
        val observedSequences = pageLines.flatMap { pageSeqs(it) }
        // RunStarted is sequence 1; the parent's three writes are sequences
        // 2, 3, 4 (SqliteEventStore::appendAssigned assigns monotonically
        // from 1 + MAX(sequence)).
        assertTrue(
            observedSequences.contains(1L),
            "the child must observe the RunStarted that declared the run (seq=1); got $observedSequences",
        )
        assertTrue(
            observedSequences.contains(2L),
            "the child must observe the StageStarted the parent wrote (seq=2); got $observedSequences",
        )
        assertTrue(
            observedSequences.contains(3L),
            "the child must observe the StageFinished the parent wrote (seq=3); got $observedSequences",
        )
        assertTrue(
            observedSequences.contains(4L),
            "the child must observe the RunFinished that closed the run (seq=4); got $observedSequences",
        )
        assertEquals(
            observedSequences.toSet().size, observedSequences.size,
            "the child must observe every sequence at most once; got $observedSequences",
        )
        assertEquals(
            observedSequences.sorted(), observedSequences,
            "the child must observe sequences in strictly ascending order; got $observedSequences",
        )
        assertEquals(1, lines.count { it == "COMPLETED" }, "Completed must be emitted exactly once")
        assertTrue(
            lines.any { it == "STATE_RUNFINISHED" },
            "the child must observe RunFinished state change; got:\n$lines",
        )
    }

    // -------------------------------------------------------------- 3 UAT-PK-M1-003

    @Test
    fun `3 UAT-PK-M1-003 output follower resumes from afterOrdinal with no duplicates and no gaps`(
        @TempDir root: Path,
    ) {
        val runId = "uat-m1-003-${shortId()}"
        val outputRoot = root.resolve("output")
        val log = root.resolve("child-events.log")

        val parentStore = SegmentOutputStore(outputRoot)
        val stream = declareOutputRun(parentStore, runId, "op-0", "")
        // Three frames BEFORE the child joins; the child asks for
        // afterOrdinal=1, so it must see ONLY frame ordinal 2 (and any
        // ordinals the parent writes after the join).
        writeOutputFrame(parentStore, stream, OutputChannel.STDOUT, "frame-0\n")
        writeOutputFrame(parentStore, stream, OutputChannel.STDOUT, "frame-1\n")
        writeOutputFrame(parentStore, stream, OutputChannel.STDOUT, "frame-2\n")

        val child = spawnChild(
            listOf(
                runId,
                "output",
                outputRoot.toAbsolutePath().toString(),
                log.toAbsolutePath().toString(),
                "32",
                "after-ordinal-1&until-allsealed",
            ),
        )
        var lateFrame: dev.rubentxu.pipeline.v2.output.OutputFrame? = null
        try {
            awaitMarker(child, "READY", 30_000)
            Thread.sleep(100)
            // A fourth frame AFTER join must also be observed. This pins the
            // "no gaps" half: the follow's afterOrdinal cuts frames < 2 but
            // not >= 2, so the late frame reaches the child.
            lateFrame = writeOutputFrame(parentStore, stream, OutputChannel.STDOUT, "frame-3\n")
            parentStore.seal(stream)

            child.waitFor(30, TimeUnit.SECONDS)
            assertEquals(0, child.exitValue(), "child exited non-zero; child log:\n${Files.readString(log)}")
            assertEquals(3L, lateFrame!!.ordinal, "the late frame must be ordinal 3")
        } finally {
            runCatching { child.destroyForcibly() }
            runCatching { child.waitFor(5, TimeUnit.SECONDS) }
        }

        val lines = readChildLog(log)
        val bytesLines = lines.filter { it.startsWith("BYTES ") }
        assertEquals(
            2, bytesLines.size,
            "the child must see exactly two frames — no duplicates of 0/1, no gaps; got:\n$lines",
        )
        // The child opens with afterOrdinal=1; the follow's strict cut is
        // `framesOfRun(runId, 1, limit)`, which returns frames with
        // `ordinal > 1`. Frames 2 and 3 carry byte ranges [16..24) and
        // [24..32) respectively (each "frame-N\n" is 8 bytes).
        assertEquals(16L, frameFrom(bytesLines.first()), "first BYTES must be frame-2's from=16")
        assertEquals(24L, frameFrom(bytesLines.last()), "last BYTES must be frame-3's from=24")
        assertEquals("frame-2\n", payloadOf(bytesLines.first()))
        assertEquals("frame-3\n", payloadOf(bytesLines.last()))
        assertEquals(1, lines.count { it == "COMPLETED" }, "Completed must be emitted exactly once")
    }

    // -------------------------------------------------------------- 4 UAT-PK-M1-004

    @Test
    fun `4 UAT-PK-M1-004 events follower resumes from EventCursor with no duplicates and no gaps`(
        @TempDir root: Path,
    ) {
        val runId = "uat-m1-004-${shortId()}"
        val dbPath = root.resolve("events.db").toAbsolutePath().toString()
        val log = root.resolve("child-events.log")

        val parentDb = SqliteEventStore(dbPath)
        try {
            // Same timing note as UAT-PK-M1-002: every event the consumer
            // is expected to see is written BEFORE the consumer opens the
            // follow, so the consumer's first poll sees them in one page
            // (the follow's natural-tail emission would otherwise terminate
            // the follow before the parent can write the post-cursor rows).
            // RunStarted (seq 1) + StageStarted (seq 2) + StageFinished
            // (seq 3) are the pre-cursor rows; StageStarted (seq 4) +
            // StageFinished (seq 5) + RunFinished (seq 6) are the rows the
            // child with `after=3` is expected to observe.
            declareEventRun(parentDb, runId)
            writeEvent(parentDb, stageStarted(runId))
            writeEvent(parentDb, stageFinished(runId))
            writeEvent(parentDb, stageStarted(runId))
            writeEvent(parentDb, stageFinished(runId))
            writeEvent(parentDb, runFinished(runId))

            val child = spawnChild(
                listOf(
                    runId,
                    "events",
                    dbPath,
                    log.toAbsolutePath().toString(),
                    "32",
                    "after-eventcursor-3",
                ),
            )
            try {
                child.waitFor(30, TimeUnit.SECONDS)
                assertEquals(0, child.exitValue(), "child exited non-zero; child log:\n${Files.readString(log)}")
            } finally {
                runCatching { child.destroyForcibly() }
                runCatching { child.waitFor(5, TimeUnit.SECONDS) }
            }
        } finally {
            parentDb.close()
        }

        val lines = readChildLog(log)
        val pageLines = lines.filter { it.startsWith("PAGE ") }
        assertTrue(
            pageLines.isNotEmpty(),
            "the child must observe at least one PAGE event; got:\n$lines",
        )
        val observed = pageLines.flatMap { pageSeqs(it) }.toSet()
        // The child opens with after=EventCursor(runId, sequence=3). The
        // follow must deliver only rows with sequence > 3, so the three
        // post-cursor rows (4, 5, 6) — nothing earlier.
        assertTrue(
            4L in observed && 5L in observed && 6L in observed,
            "the child must observe sequences 4, 5, 6 (post-cursor); got $observed",
        )
        assertTrue(
            (1L..3L).none { it in observed },
            "the child must NOT observe any pre-cursor sequence (1..3); got $observed",
        )
        // RunFinished in this row is the natural-tail emission: the page
        // carried it AND the follow reached the bounded slice. Either way,
        // Completed arrives.
        assertEquals(1, lines.count { it == "COMPLETED" }, "Completed must be emitted exactly once")
        assertTrue(
            lines.any { it == "STATE_RUNFINISHED" },
            "the child must observe the RunFinished state change; got:\n$lines",
        )
    }

    // -------------------------------------------------------------- 5 UAT-PK-M1-005

    @Test
    fun `5 UAT-PK-M1-005 two independent processes follow the SAME run and see the SAME event stream`(
        @TempDir root: Path,
    ) {
        val runId = "uat-m1-005-${shortId()}"
        val dbPath = root.resolve("events.db").toAbsolutePath().toString()
        val logA = root.resolve("child-A.log")
        val logB = root.resolve("child-B.log")

        val parentDb = SqliteEventStore(dbPath)
        try {
            // Same timing note as UAT-PK-M1-002: write every event BEFORE
            // spawning the children so their first poll sees them in one
            // page (the follow's natural-tail emission terminates the
            // follow on `hasMore=false` regardless of whether more events
            // are about to arrive).
            declareEventRun(parentDb, runId)
            writeEvent(parentDb, stageStarted(runId))
            writeEvent(parentDb, stageFinished(runId))
            writeEvent(parentDb, runFinished(runId))

            val childA = spawnChild(
                listOf(runId, "events", dbPath, logA.toAbsolutePath().toString(), "32", "until-runfinished"),
            )
            val childB = spawnChild(
                listOf(runId, "events", dbPath, logB.toAbsolutePath().toString(), "32", "until-runfinished"),
            )
            try {
                childA.waitFor(30, TimeUnit.SECONDS)
                childB.waitFor(30, TimeUnit.SECONDS)
                assertEquals(0, childA.exitValue(), "childA exited non-zero; log:\n${Files.readString(logA)}")
                assertEquals(0, childB.exitValue(), "childB exited non-zero; log:\n${Files.readString(logB)}")
            } finally {
                runCatching { childA.destroyForcibly() }
                runCatching { childB.destroyForcibly() }
                runCatching { childA.waitFor(5, TimeUnit.SECONDS) }
                runCatching { childB.waitFor(5, TimeUnit.SECONDS) }
            }
        } finally {
            parentDb.close()
        }

        // Both followers observed the same logical stream — same sequences,
        // same kinds, same RunFinished terminal — without sharing cursors.
        // The two iterators are on two separate SqliteEventStore instances
        // in two separate JVMs; the only shared authority is the on-disk
        // SQLite database.
        val observedA = readChildLog(logA)
        val observedB = readChildLog(logB)
        val pageLinesA = observedA.filter { it.startsWith("PAGE ") }
        val pageLinesB = observedB.filter { it.startsWith("PAGE ") }
        val sequencesA = pageLinesA.flatMap { pageSeqs(it) }.toSet()
        val sequencesB = pageLinesB.flatMap { pageSeqs(it) }.toSet()
        // Both must see the RunFinished sequence (4) — that's the event the
        // parent wrote to terminate the run, observed via the same SQLite
        // file by both child JVMs.
        assertTrue(
            sequencesA.contains(4L),
            "observer A must see the RunFinished sequence (4); got $sequencesA",
        )
        assertTrue(
            sequencesB.contains(4L),
            "observer B must see the RunFinished sequence (4); got $sequencesB",
        )
        // The two observers see the SAME sequence set — the central
        // property of UAT-PK-M1-005. We don't require bit-identical PAGE
        // line counts (each follower's pagination cadence can split a
        // batch differently), only that the multiset of sequences is the
        // same.
        assertEquals(
            sequencesA, sequencesB,
            "two independent observers must see the same logical event stream; A=$sequencesA B=$sequencesB",
        )
        assertEquals(1, observedA.count { it == "COMPLETED" }, "observer A must see Completed exactly once")
        assertEquals(1, observedB.count { it == "COMPLETED" }, "observer B must see Completed exactly once")
        // And neither observer sees the OTHER's cursor: a leaked shared
        // cursor would cause one sequence to appear twice in one observer's
        // log. Assert each sequence appears at most once in each log.
        val flatA = pageLinesA.flatMap { pageSeqs(it) }
        val flatB = pageLinesB.flatMap { pageSeqs(it) }
        assertEquals(
            flatA.size, flatA.distinct().size,
            "observer A must observe every sequence at most once (no shared cursor); got $flatA",
        )
        assertEquals(
            flatB.size, flatB.distinct().size,
            "observer B must observe every sequence at most once (no shared cursor); got $flatB",
        )
    }

    // -------------------------------------------------------------- 6 UAT-PK-M1-006

    @Test
    fun `6 UAT-PK-M1-006 events follower reaches RunTerminal and emits Completed exactly once`(
        @TempDir root: Path,
    ) {
        val runId = "uat-m1-006-${shortId()}"
        val dbPath = root.resolve("events.db").toAbsolutePath().toString()
        val log = root.resolve("child-events.log")

        val parentDb = SqliteEventStore(dbPath)
        try {
            // The RunFinished is the LAST event written before the child
            // spawns: the child's first poll must observe every event
            // including the terminal RunFinished, and the follow's
            // RunFinished-detection path (handlePage step 3d) emits
            // StateChanged(RunFinished) THEN Completed in the same poll
            // cycle. The natural-tail emission would otherwise terminate
            // the follow before RunFinished is seen.
            declareEventRun(parentDb, runId)
            writeEvent(parentDb, stageStarted(runId))
            writeEvent(parentDb, stageFinished(runId))
            // The terminal event. With RunFinished in the first poll's
            // records, the follow emits StateChanged(RunFinished) THEN
            // Completed in the same cycle — the central property this row
            // pins (the ordering and the exactly-once emission).
            writeEvent(parentDb, runFinished(runId))
            val child = spawnChild(
                listOf(runId, "events", dbPath, log.toAbsolutePath().toString(), "16", "until-runfinished"),
            )
            try {
                child.waitFor(30, TimeUnit.SECONDS)
                assertEquals(0, child.exitValue(), "child exited non-zero; child log:\n${Files.readString(log)}")
            } finally {
                runCatching { child.destroyForcibly() }
                runCatching { child.waitFor(5, TimeUnit.SECONDS) }
            }
        } finally {
            parentDb.close()
        }

        val lines = readChildLog(log)
        // Exactly-once Completed: a follow that emitted Completed twice
        // would be a contract violation — the contract names Completed as
        // the terminal event with no successor.
        assertEquals(
            1, lines.count { it == "COMPLETED" },
            "Completed must be emitted exactly once; got ${lines.count { it == "COMPLETED" }}:\n$lines",
        )
        // The terminal ordering: StateChanged(RunFinished) is the last
        // state change BEFORE Completed. The last non-COMPLETED event line
        // (excluding Refused) MUST be a STATE_RUNFINISHED line.
        val terminalIdx = lines.indexOf("COMPLETED")
        assertTrue(terminalIdx >= 0, "Completed must be present in the log; got:\n$lines")
        val prior = lines.subList(0, terminalIdx)
        val stateChanges = prior.filter { it.startsWith("STATE_") }
        assertTrue(
            stateChanges.last() == "STATE_RUNFINISHED",
            "the last state change before Completed must be STATE_RUNFINISHED; got ${stateChanges.last()}",
        )
        // No Refused: a clean terminal follows the design's Completed path,
        // not the Refused path.
        assertTrue(
            lines.none { it.startsWith("REFUSED") },
            "a follow that reaches RunFinished must NOT emit Refused; got:\n$lines",
        )
    }

    // -------------------------------------------------------------- log-line helpers

    /**
     * The `bytes=` field on a `BYTES stream=… from=… to=… bytes=<text>` line,
     * with the `\n` escapes put back to literal newlines so the payload can
     * be compared verbatim against the parent writer's `String`.
     */
    private fun payloadOf(line: String): String =
        line.substringAfter("bytes=").replace("\\n", "\n")

    /** The `from=` field, parsed as `Long`. */
    private fun frameFrom(line: String): Long =
        line.substringAfter("from=").substringBefore(' ').toLong()

    /** The `to=` field, parsed as `Long`. */
    private fun frameTo(line: String): Long =
        line.substringAfter("to=").substringBefore(' ').toLong()

    /**
     * The `seqs=` field on a `PAGE records=N seqs=1,2,3 hasMore=…` line,
     * parsed back to a `List<Long>`.
     */
    private fun pageSeqs(line: String): List<Long> {
        val raw = line.substringAfter("seqs=").substringBefore(' ')
        if (raw.isBlank()) return emptyList()
        return raw.split(",").map { it.toLong() }
    }

    private fun shortId(): String = UUID.randomUUID().toString().substring(0, 8)

    // -------------------------------------------------------------- event helpers

    /**
     * `sequence = 0L` is the `SqliteEventStore::appendAssigned` "please assign"
     * sentinel — the store becomes the sequence authority and the test no
     * longer has to count. The M1-A 2026-10-10 corrections made sequence
     * assignment the store's job; the test asserts the property the store
     * promises (monotonic per-run, no duplicates), not the wire numbers.
     */
    private fun stageStarted(runId: String): DomainEvent =
        StageStarted(
            eventId = UUID.randomUUID().toString(),
            runId = runId,
            sequence = 0L,
            occurredAt = Instant.now(),
            stageIndex = 0,
            stageName = "build",
        )

    private fun stageFinished(runId: String): DomainEvent =
        StageFinished(
            eventId = UUID.randomUUID().toString(),
            runId = runId,
            sequence = 0L,
            occurredAt = Instant.now(),
            stageIndex = 0,
            stageName = "build",
            outcome = "SUCCESS",
        )

    private fun runFinished(runId: String): DomainEvent =
        RunFinished(
            eventId = UUID.randomUUID().toString(),
            runId = runId,
            sequence = 0L,
            occurredAt = Instant.now(),
            outcome = "SUCCESS",
            diagnostics = emptyList(),
        )

    // -------------------------------------------------------------- the child JVM

    /**
     * The cross-JVM consumer. Real `java -cp <classpath>` child, line-based
     * stdout protocol (READY / DONE markers), per-event JSON-line-ish log file
     * the parent reads back.
     *
     * Arguments:
     *  - `args[0]` runId
     *  - `args[1]` mode: "events" or "output"
     *  - `args[2]` events: sqlite file path; output: segment store root
     *  - `args[3]` log file path (per-event structured output)
     *  - `args[4]` maxEvents (bound on the iteration; see class KDoc)
     *  - `args[5]` follow options: "unbounded", "until-runfinished",
     *    "until-allsealed", "after-ordinal-N", "after-eventcursor-N"
     *
     * The child writes one line per follow event to [args[3]] and one line
     * per marker on stdout, the way `FileBackedRunExecutionLeaseCrossProcessTest`
     * does. The parent waits for `READY` before producing the events the
     * follow should observe, and waits for `DONE` (which the child prints
     * before exit) before reading the log.
     */
    object FollowChild {
        /**
         * Wall-clock bound for the child's iteration. The follow API does
         * not surface its own deadline; without one, a quiet cycle would
         * let the child exit before the parent has time to write the
         * post-join events. 20 s is well under the parent's `Timeout(60)`
         * and gives the parent's writes + the follow's 25 ms cadence +
         * the child's drain plenty of headroom.
         */
        private const val CHILD_DEADLINE_MILLIS: Long = 20_000L

        @JvmStatic
        fun main(args: Array<String>) {
            val runId = args[0]
            val mode = args[1]
            val storeArg = args[2]
            val logPath = Path.of(args[3])
            val maxEvents = args[4].toInt()
            val followSpec = args[5]

            // Truncate-or-create the log. The parent reads it as soon as
            // the child prints DONE; an open-append handle from a previous
            // run would corrupt the assertion.
            try {
                Files.deleteIfExists(logPath)
                Files.writeString(
                    logPath,
                    "STARTED mode=$mode runId=$runId spec=$followSpec max=$maxEvents\n",
                )
            } catch (e: Throwable) {
                System.err.println("CHILD_LOG_OPEN_FAILED: ${e::class.simpleName}: ${e.message}")
                println("DONE")
                System.out.flush()
                return
            }

            val outcome = try {
                when (mode) {
                    "events" -> runEventsFollow(runId, storeArg, logPath, maxEvents, followSpec)
                    "output" -> runOutputFollow(runId, Path.of(storeArg), logPath, maxEvents, followSpec)
                    else -> {
                        writeLine(logPath, "ERROR unknown mode=$mode")
                        2
                    }
                }
            } catch (e: Throwable) {
                writeLine(logPath, "ERROR ${e::class.simpleName}: ${e.message}")
                3
            }
            println("DONE")
            System.out.flush()
            kotlin.system.exitProcess(outcome)
        }

        private fun runEventsFollow(
            runId: String,
            dbPath: String,
            logPath: Path,
            maxEvents: Int,
            followSpec: String,
        ): Int {
            val store = SqliteEventStore(dbPath)
            try {
                val port = EventRecordReadPortStoreAdapter(
                    store = store,
                    runExists = store::hasRun,
                    tailSequence = store::tailSequence,
                )
                val follower = SqliteEventFollower(port, store::hasRun)
                val options = buildEventOptions(runId, followSpec)
                val handle = follower.open(runId, options)
                println("READY")
                System.out.flush()
                try {
                    val it = handle.iterator()
                    var count = 0
                    val deadline = System.currentTimeMillis() + CHILD_DEADLINE_MILLIS
                    // The follow API's `hasNext` already paces itself with
                    // `pollIntervalMs` and returns `false` on a quiet cycle.
                    // We loop calling it until either the terminal event
                    // (Completed / Refused) arrives, `maxEvents` is reached,
                    // or the wall-clock deadline elapses — otherwise the
                    // child would exit before the parent has a chance to
                    // write the post-join or terminal event.
                    while (System.currentTimeMillis() < deadline && count < maxEvents) {
                        if (!it.hasNext()) continue
                        val ev = it.next()
                        count++
                        writeLine(logPath, encode(ev))
                        if (ev is EventFollowEvent.Completed || ev is EventFollowEvent.Refused) break
                    }
                } finally {
                    handle.close()
                }
                return 0
            } finally {
                store.close()
            }
        }

        private fun runOutputFollow(
            runId: String,
            outputRoot: Path,
            logPath: Path,
            maxEvents: Int,
            followSpec: String,
        ): Int {
            val store = SegmentOutputStore(outputRoot)
            try {
                store.recover()
                val frameIndex = store.frameIndex()
                val follower = SegmentOutputFollower(
                    read = store,
                    frames = frameIndex,
                    tails = store,
                    runExists = store::hasOutputFor,
                )
                val options = buildOutputOptions(runId, followSpec)
                val handle = follower.open(runId, options)
                println("READY")
                System.out.flush()
                try {
                    val it = handle.iterator()
                    var count = 0
                    val deadline = System.currentTimeMillis() + CHILD_DEADLINE_MILLIS
                    while (System.currentTimeMillis() < deadline && count < maxEvents) {
                        if (!it.hasNext()) continue
                        val ev = it.next()
                        count++
                        writeLine(logPath, encode(ev))
                        if (ev is OutputFollowEvent.Completed || ev is OutputFollowEvent.Refused) break
                    }
                } finally {
                    handle.close()
                }
                return 0
            } finally {
                // SegmentOutputStore is not AutoCloseable; nothing to close.
            }
        }

        private fun buildEventOptions(runId: String, spec: String): EventFollowOptions {
            val base = EventFollowOptions(pollIntervalMs = 25L, maxRecords = 64)
            return when {
                spec == "unbounded" -> base
                spec == "until-runfinished" -> base.copy(until = EventFollowUntil.UntilRunFinished(runId))
                spec.startsWith("after-eventcursor-") -> {
                    val seq = spec.removePrefix("after-eventcursor-").toLong()
                    base.copy(after = EventCursor(runId, seq))
                }
                else -> error("unknown events follow spec: $spec")
            }
        }

        private fun buildOutputOptions(runId: String, spec: String): OutputFollowOptions {
            // Compound specs use `&` so a single follow can combine
            // `afterOrdinal` and `until = UntilAllSealed` (the afterOrdinal
            // alone is Unbounded and would never emit Completed).
            val parts = spec.split("&")
            var options = OutputFollowOptions(pollIntervalMs = 25L, maxRecords = 64)
            for (part in parts) {
                options = when {
                    part == "unbounded" -> options
                    part == "until-allsealed" -> options.copy(until = FollowUntil.UntilAllSealed(runId))
                    part.startsWith("after-ordinal-") -> {
                        val ord = part.removePrefix("after-ordinal-").toLong()
                        options.copy(afterOrdinal = ord)
                    }
                    else -> error("unknown output follow spec part: $part")
                }
            }
            return options
        }

        private fun encode(ev: EventFollowEvent): String = when (ev) {
            is EventFollowEvent.StateChanged -> when (val s = ev.state) {
                is EventFollowState.Live -> "STATE_LIVE seq=${s.lastSequence}"
                EventFollowState.RunFinished -> "STATE_RUNFINISHED"
                is EventFollowState.Unobservable ->
                    "STATE_UNOBSERVABLE refusal=${refusalName(s.refusal)}"
            }
            is EventFollowEvent.Page -> {
                val seqs = ev.slice.records.joinToString(",") { it.sequence.toString() }
                val kinds = ev.slice.records.joinToString(",") { recordKind(it) }
                "PAGE records=${ev.slice.records.size} seqs=$seqs kinds=$kinds hasMore=${ev.slice.hasMore}"
            }
            EventFollowEvent.Completed -> "COMPLETED"
            is EventFollowEvent.Refused -> "REFUSED refusal=${refusalName(ev.refusal)}:${ev.refusal}"
        }

        private fun encode(ev: OutputFollowEvent): String = when (ev) {
            is OutputFollowEvent.Bytes -> {
                val text = String(ev.page.bytes, StandardCharsets.UTF_8)
                    .replace("\\", "\\\\")
                    .replace("\n", "\\n")
                    .replace(" ", "_")
                "BYTES stream=${ev.page.stream.value} from=${ev.page.from} to=${ev.page.committedEnd} bytes=$text"
            }
            is OutputFollowEvent.StateChanged -> when (val s = ev.state) {
                is FollowState.Running -> "STATE_RUNNING open=${s.openStreams.size} sealed=${s.sealedStreams.size}"
                is FollowState.StreamSealed -> "STATE_STREAM_SEALED sealed=${s.sealedStreams.size}"
                FollowState.RunTerminal -> "STATE_RUNTERMINAL"
                is FollowState.Unobservable -> "STATE_UNOBSERVABLE refusal=${refusalName(s.refusal)}"
            }
            OutputFollowEvent.Completed -> "COMPLETED"
            is OutputFollowEvent.Refused -> "REFUSED refusal=${refusalName(ev.refusal)}:${ev.refusal}"
        }

        private fun refusalName(r: Any): String = r::class.simpleName ?: r::class.java.simpleName

        /**
         * The kind discriminator on an [EventRecordRead] row: the typed
         * event's own kind for a [Decoded] row, the row's stored
         * discriminator for an [Undecodable] row. Both forms are nullable
         * for the same reason — the column was not always populated —
         * and the encoder preserves the null in the log line.
         */
        private fun recordKind(record: dev.rubentxu.pipeline.v2.events.EventRecordRead): String =
            when (record) {
                is dev.rubentxu.pipeline.v2.events.EventRecordRead.Decoded -> record.event.kind
                is dev.rubentxu.pipeline.v2.events.EventRecordRead.Undecodable -> record.kind ?: "<null>"
            }

        private fun writeLine(logPath: Path, line: String) {
            // Synchronised so two follow events queued on the producer side
            // cannot interleave inside one line. Files.writeString with
            // CREATE+APPEND is atomic on POSIX for short payloads.
            synchronized(logPath) {
                Files.writeString(
                    logPath,
                    line + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
            }
        }
    }
}