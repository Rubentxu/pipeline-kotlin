package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.output.RetainUntil
import dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * B1a — CHARACTERISATION of the Output Plane's store lifecycle (triage AUD-08, "no verificado").
 *
 * ## The subject, exactly
 *
 * `OutputPlaneProvider` (`:pipeline-application`, `application/durable/OutputPlaneProvider.kt`) is a
 * Kotlin `object` holding `private val stores = ConcurrentHashMap<Path, SegmentOutputStore>()`, and
 * `storeFor(root)` is `stores.computeIfAbsent(root.normalize()) { SegmentOutputStore(...).also { it.recover() } }`.
 * `forget(root)` and `forgetAll()` are the only two removals in the type.
 *
 * ## Fidelity (HARNESS FIDELITY LAW §1)
 *
 * **HF1 — in-process, through the production authorities, named:** every soak run enters through
 * `ShExecution.ingestTranscriptIntoOutputPlane(...)`, which is the durable route's own ingest
 * (`invokeShell` → `ingestTranscriptIntoOutputPlane` → `OutputPlaneProvider.storeFor` →
 * `SegmentOutputStore.open(...).appendFrom(...)`), and the release arm enters through
 * `RunOutputRetention.onRunTerminal` with the same `retention = { OutputPlaneProvider.storeFor(root) }`
 * supplier `CompositionRoot` wires at production (`CompositionRoot.kt:211`). No cache is
 * re-implemented: [cachedStoreCount] and [perStreamLockCount] READ the production object's own state
 * through its declared private fields, so a store that is subtly dropped still cannot pass.
 *
 * This is a **characterisation**, not a certification. It asserts the measured current lifecycle and
 * says which of the readings are deliberate design (documented in `OutputPlaneProvider`'s KDoc and in
 * the B2/S4 receipts) and which are the auditor's concern (a process that runs many runs grows a
 * per-root, never-released cache).
 *
 * ## Hermeticity
 *
 * Every scratch path is under `@TempDir`; the soak uses `@TempDir` children for "a fresh control root
 * per run" instead of the CLI's `Files.createTempDirectory` so that nothing is written outside the
 * temporary directory. No network, no wall-clock assertion. Heap probes force GC and say so; the
 * descriptor probe reads `/proc/self/fd` and carries a positive control, because a probe that cannot
 * see a retained descriptor would make "no descriptors are retained" vacuous.
 *
 * The provider is process-global, so every count is measured as a **delta** around this method's own
 * soak: another test class in the same JVM may legitimately have left a store cached, and an absolute
 * count would then be a statement about the whole JVM rather than about this soak.
 */
@Timeout(600)
class B1aOutputPlaneProviderLifecycleCharacterizationTest {

    private val soakRuns = 40
    private val payloadBytes = 256 * 1024

    /** Held only for the heap positive control, released immediately after it is measured. */
    @Volatile private var heapBallast: Any? = null

    // ------------------------------------------------------------------ A: one store per root

    /**
     * AUD-08: does a store per root survive the run, and does the run terminal release it?
     *
     * The CLI's default path (`Main.kt`, no `--control-root`) creates a **fresh** control root per
     * run, so each run's identity is new. This arm reproduces that identity shape under `@TempDir` and
     * then asks the production release path (`RunOutputRetention.onRunTerminal`) to release one run.
     *
     * The differential at the end is what gives the row teeth: `forget(root)` is the only removal in
     * the type, and the count must drop by exactly one when it is called and by nothing when the run
     * terminal releases bytes.
     */
    @Test
    @Timeout(300)
    fun `a - a fresh control root per run leaves one never-released store per run`(@TempDir root: Path) {
        val parent = Files.createDirectories(root.resolve("fresh-roots"))
        val payloadFile = writePayload(root)
        val storesBefore = cachedStoreCount()

        for (i in 1..soakRuns) {
            val runRoot = Files.createDirectories(parent.resolve("run-$i"))
            ingestRun(runRoot, runId = "run-$i", opId = "run-$i-s0-0", payloadFile = payloadFile)
        }

        val storesAfterSoak = cachedStoreCount()
        val filesAfterSoak = filesUnder(parent)
        val firstRoot = parent.resolve("run-1")
        val lastRunId = "run-$soakRuns"
        assertTrue(
            OutputPlaneProvider.storeFor(parent.resolve(lastRunId))
                .hasOutputFor(lastRunId),
            "the soak must have committed real streams; a provider that cached stores without " +
                "bytes would make the counts below meaningless",
        )

        // ---- the run terminal releases BYTES, not the cache entry -------------------------------
        val retention = RunOutputRetention(
            retention = { OutputPlaneProvider.storeFor(parent.resolve(lastRunId)) },
            policy = RetainUntil.RunTerminalPlus,
        )
        val disposition = retention.onRunTerminal(RunId(lastRunId))
        val released = disposition as? RunOutputDisposition.Released
        val storesAfterRelease = cachedStoreCount()
        val filesAfterRelease = filesUnder(parent)

        println(
            "B1a(AUD-08,a) N=$soakRuns storesBefore=$storesBefore afterSoak=$storesAfterSoak " +
                "afterRelease=$storesAfterRelease | filesUnderRoots afterSoak=$filesAfterSoak " +
                "afterRelease=$filesAfterRelease | disposition=$disposition",
        )

        assertEquals(
            storesBefore + soakRuns,
            storesAfterSoak,
            "CHARACTERISATION (AUD-08): one recovered store per distinct control root is held for the " +
                "life of the process; nothing removes it when the run ends",
        )
        assertTrue(
            released != null && released.report.streamsRemoved == 1,
            "the production run-terminal path must release the finished run's stream; observed=$disposition",
        )
        assertFalse(
            OutputPlaneProvider.storeFor(parent.resolve(lastRunId)).hasOutputFor(lastRunId),
            "the released run's stream must be gone from the plane after its terminal state",
        )
        assertTrue(
            filesAfterRelease < filesAfterSoak,
            "the release must delete files (before=$filesAfterSoak after=$filesAfterRelease)",
        )
        assertEquals(
            storesAfterSoak,
            storesAfterRelease,
            "CHARACTERISATION (AUD-08): the run terminal releases the bytes and does NOT release the " +
                "store object; the cache entry survives it",
        )

        // ---- forget() is the only removal, and it is per root -----------------------------------
        OutputPlaneProvider.forget(parent.resolve(lastRunId))
        val storesAfterForget = cachedStoreCount()
        println("B1a(AUD-08,a) storesAfterForget(one root)=$storesAfterForget")
        assertEquals(
            storesAfterRelease - 1,
            storesAfterForget,
            "forget(root) is the only removal in OutputPlaneProvider; if this row ever fails the " +
                "probe is not reading the production cache and every count above is void",
        )

        // ---- the store outlives the bytes it wrote ----------------------------------------------
        assertTrue(
            firstRoot.toFile().exists(),
            "the first run's root still exists on disk; only the released run's stream was pruned",
        )

        OutputPlaneProvider.forgetAll()
        assertEquals(0, cachedStoreCount(), "forgetAll() must empty the cache (process-restart seam)")
    }

    // ------------------------------------------------------------- B: one root, N runs

    /**
     * AUD-08: with a **fixed** control root (the `--control-root` path), does per-run state accumulate
     * inside the one store that survives?
     *
     * Two different quantities are counted, and the difference is the point: the provider still holds
     * exactly one store, while the store's own `perStream` lock map (and the plane's directories) grow
     * with every run that shares the root.
     */
    @Test
    @Timeout(300)
    fun `b - a shared control root accumulates one stream entry per run inside the single store`(
        @TempDir root: Path,
    ) {
        val sharedRoot = Files.createDirectories(root.resolve("shared-root"))
        val payloadFile = writePayload(root)
        val storesBefore = cachedStoreCount()

        for (i in 1..soakRuns) {
            ingestRun(sharedRoot, runId = "shared-$i", opId = "shared-$i-s0-0", payloadFile = payloadFile)
        }

        val storesAfterSoak = cachedStoreCount()
        val store = OutputPlaneProvider.storeFor(sharedRoot)
        val locksAfterSoak = perStreamLockCount(store)
        val filesAfterSoak = filesUnder(root.resolve("shared-root"))

        val released = (1..soakRuns).map { i ->
            RunOutputRetention(
                retention = { OutputPlaneProvider.storeFor(sharedRoot) },
                policy = RetainUntil.RunTerminalPlus,
            ).onRunTerminal(RunId("shared-$i"))
        }
        val filesAfterRelease = filesUnder(root.resolve("shared-root"))
        val locksAfterRelease = perStreamLockCount(OutputPlaneProvider.storeFor(sharedRoot))

        println(
            "B1a(AUD-08,b) N=$soakRuns storesBefore=$storesBefore afterSoak=$storesAfterSoak " +
                "| perStreamLocks afterSoak=$locksAfterSoak afterRelease=$locksAfterRelease " +
                "| filesUnderRoot afterSoak=$filesAfterSoak afterRelease=$filesAfterRelease " +
                "| releasedRuns=${released.count { it is RunOutputDisposition.Released }}",
        )

        assertEquals(
            storesBefore + 1,
            storesAfterSoak,
            "CHARACTERISATION (AUD-08): a fixed root means one store, shared by every run of the " +
                "process that uses it",
        )
        assertEquals(
            soakRuns,
            locksAfterSoak,
            "per-stream bookkeeping inside the surviving store grows with the number of runs " +
                "(one ReentrantLock per stream id), and nothing removes it",
        )
        assertTrue(
            released.all { it is RunOutputDisposition.Released && it.report.streamsRemoved == 1 },
            "every run's terminal state must release its own stream; observed=$released",
        )
        assertEquals(
            0,
            filesAfterRelease,
            "after every run is released, no stream directory may remain under the shared root " +
                "(before=$filesAfterSoak)",
        )
        assertEquals(
            soakRuns,
            locksAfterRelease,
            "NON-REGRESSION (AUD-08, was a characterisation of a defect until B1c): after releasing " +
                "N runs the store's perStream map holds N entries, one per stream, not 2N. Until B1c " +
                "the writer locked the RAW stream id (\"run/op/transcript\") and prune locked the " +
                "on-disk directory name (safe() folds '/' onto '_'), so releasing a run ADDED a " +
                "second, permanently retained lock per stream. The keying is now unified on the " +
                "folded directory name (SegmentOutputStore.streamKey), so prune reuses the writer's " +
                "lock. This assertion was inverted deliberately when the defect closed, per Harness " +
                "Fidelity Law §5; it is no longer a characterisation of a defect. " +
                "observed afterSoak=$locksAfterSoak afterRelease=$locksAfterRelease N=$soakRuns. " +
                "A value of ${soakRuns * 2} here is the AUD-08 lock-keying defect returning.",
        )
    }

    // -------------------------------------------------------- C: descriptors and heap

    /**
     * AUD-08: do descriptors, or retained heap, grow with the number of runs?
     *
     * Both probes carry a **positive control** in the same test method, because a probe that cannot
     * see a retained descriptor, or a retained byte, would make a "no growth" reading vacuous:
     *
     * - the descriptor probe is shown a held `InputStream` under the measured root and must see it;
     * - the heap probe is shown 24 MiB of strongly reachable ballast and must see it, then shown its
     *   release and must see it go.
     *
     * Only then is the soak's own reading interpreted.
     */
    @Test
    @Timeout(600)
    fun `c - descriptors and retained heap do not grow with the soak, and both probes are proven able to see growth`(
        @TempDir root: Path,
    ) {
        val parent = Files.createDirectories(root.resolve("runs"))
        val payloadFile = writePayload(root)

        // ---- descriptor probe, with its positive control ----------------------------------------
        // Measured over the whole @TempDir root, because the payload the positive control holds open
        // lives under it as well; nothing else in this JVM touches that tree.
        val fdsBefore = openFdsUnder(root).size
        val held = Files.newInputStream(payloadFile)
        val fdsWithHeld = try {
            openFdsUnder(root).size
        } finally {
            held.close()
        }
        val fdsAfterClose = openFdsUnder(root).size
        println(
            "B1a(AUD-08,c) fd probe: idle=$fdsBefore withHeldStream=$fdsWithHeld afterClose=$fdsAfterClose",
        )
        assertTrue(
            fdsWithHeld >= fdsBefore + 1,
            "positive control: the probe must see a held descriptor under the measured root " +
                "(idle=$fdsBefore, held=$fdsWithHeld); otherwise 'no fds are retained' cannot be read",
        )
        assertEquals(
            fdsBefore,
            fdsAfterClose,
            "positive control teardown: closing the stream must return the count",
        )

        // ---- heap probe, with its positive control ---------------------------------------------
        // The ballast is allocated and released through helpers so that no local in this frame ever
        // holds the array: a reachable local (or a compiler's live oop slot) would keep it alive
        // through the GC request and make the "release is visible" control fail for a harness reason.
        val baseline = usedHeapAfterGc()
        allocateHeapBallast()
        val withBallast = usedHeapAfterGc() - baseline
        releaseHeapBallast()
        val afterBallastRelease = usedHeapAfterGc() - baseline
        println(
            "B1a(AUD-08,c) heap probe: baselineUsed=$baseline with24MiBBallast=$withBallast " +
                "afterRelease=$afterBallastRelease",
        )
        assertTrue(
            withBallast >= 16L * 1024 * 1024,
            "positive control: the heap probe must see 24 MiB of strongly reachable ballast " +
                "(observed=$withBallast); otherwise the soak's retained number means nothing",
        )
        assertTrue(
            afterBallastRelease < 8L * 1024 * 1024,
            "positive control teardown: releasing the ballast must be visible (observed=$afterBallastRelease)",
        )

        // ---- the soak ---------------------------------------------------------------------------
        // A fresh baseline, taken after the ballast phase, so a heap that never shrank keeps that
        // history inside the baseline instead of inside the soak's own number.
        val storesBefore = cachedStoreCount()
        val heapBefore = usedHeapAfterGc()
        for (i in 1..soakRuns) {
            ingestRun(
                controlRoot = Files.createDirectories(parent.resolve("run-$i")),
                runId = "fd-$i",
                opId = "fd-$i-s0-0",
                payloadFile = payloadFile,
            )
        }
        val storesAfter = cachedStoreCount()
        val heapAfter = usedHeapAfterGc() - heapBefore
        val fdsAfterSoak = openFdsUnder(root).size
        val filesAfterSoak = filesUnder(parent)
        val bytesWritten = soakRuns.toLong() * payloadBytes

        println(
            "B1a(AUD-08,c) soak: N=$soakRuns bytesWritten=$bytesWritten stores=$storesBefore->" +
                "$storesAfter retainedHeapDelta=$heapAfter fdsAfterSoak=$fdsAfterSoak filesAfterSoak=$filesAfterSoak",
        )

        assertEquals(storesBefore + soakRuns, storesAfter, "soak store count")
        assertEquals(
            0,
            fdsAfterSoak,
            "CHARACTERISATION (AUD-08): the store holds no descriptor between runs; SegmentOutputStore " +
                "opens and closes a channel per write (Files.write) and the ingest closes its source " +
                "(proven able to detect one by the positive control above)",
        )
        assertTrue(
            heapAfter < bytesWritten / 2,
            "CHARACTERISATION (AUD-08): the soak's retained heap must be less than half the bytes it " +
                "wrote ($heapAfter vs $bytesWritten written), which is only meaningful because the " +
                "positive control shows this probe sees a 24 MiB retention",
        )
        assertTrue(
            filesAfterSoak > 0,
            "the soak must have left on-disk stream state under the measured root (files=$filesAfterSoak); " +
                "a zero would mean the soak wrote nothing and the probe measured an empty tree",
        )
    }

    // ------------------------------------------------------------------ fixtures

    /** Writes the payload once, so the ingest's `source` is a real file stream (as in production). */
    private fun writePayload(root: Path): Path {
        val payload = Files.createDirectories(root.resolve("payload")).resolve("transcript.bin")
        val bytes = ByteArray(payloadBytes) { 'A'.code.toByte() }
        Files.write(payload, bytes)
        return payload
    }

    /**
     * One soak run through the production ingest seam.
     *
     * The run id is embedded in the stream id the same way `OutputPlaneProvider.streamId` builds it,
     * so retention's prefix filter means exactly what it means in production.
     */
    private fun ingestRun(controlRoot: Path, runId: String, opId: String, payloadFile: Path) {
        val source: () -> InputStream? = { Files.newInputStream(payloadFile) }
        ShExecution.ingestTranscriptIntoOutputPlane(
            controlDirRoot = controlRoot,
            runId = runId,
            opId = opId,
            source = source,
            secretPatternRegistry = null,
        )
    }

    /** Every regular file at or below [root], counted the slow-but-honest way. */
    private fun filesUnder(root: Path): Int {
        if (!Files.isDirectory(root)) return 0
        return Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.count().toInt() }
    }

    /**
     * Descriptors of THIS process that point at or below [root], read from `/proc/self/fd`.
     *
     * Returns an empty list where `/proc` is unavailable: the caller's positive control is what makes
     * that harmless, because a probe that cannot see a held descriptor cannot be used to claim
     * "nothing is held" — the row fails instead.
     */
    private fun openFdsUnder(root: Path): List<Path> {
        val fdDir = Path.of("/proc/self/fd")
        if (!Files.isDirectory(fdDir)) return emptyList()
        val prefix = root.toAbsolutePath().normalize()
        return Files.newDirectoryStream(fdDir).use { entries ->
            entries.asSequence()
                .mapNotNull { fd -> runCatching { Files.readSymbolicLink(fd) }.getOrNull() }
                .mapNotNull { target -> runCatching { Path.of(target.toString()) }.getOrNull() }
                .map { it.toAbsolutePath().normalize() }
                .filter { it.startsWith(prefix) }
                .toList()
        }
    }

    /** The provider's own cached store count, read from production state, not re-derived. */
    private fun cachedStoreCount(): Int = readPrivateField(
        target = OutputPlaneProvider,
        fieldName = "stores",
        what = "OutputPlaneProvider.stores",
    ).let { it as Map<*, *> }.size

    /** The store's own per-stream lock count, read from production state, not re-derived. */
    private fun perStreamLockCount(store: SegmentOutputStore): Int = readPrivateField(
        target = store,
        fieldName = "perStream",
        what = "SegmentOutputStore.perStream",
    ).let { it as Map<*, *> }.size

    /**
     * Reads a declared private field of a production object.
     *
     * It fails loudly when the field is gone rather than returning a default: a probe that silently
     * reports zero after a rename would turn every row above into a vacuous pass.
     */
    private fun readPrivateField(target: Any, fieldName: String, what: String): Any {
        val field = try {
            target.javaClass.getDeclaredField(fieldName)
        } catch (missing: NoSuchFieldException) {
            throw AssertionError(
                "the B1a lifecycle probe lost its target: $what no longer exists. Re-point the probe " +
                    "and re-run the measurements; a stored zero would be a fabricated reading.",
                missing,
            )
        }
        field.isAccessible = true
        return field.get(target)
    }

    /** Allocates the heap probe's ballast without letting any caller-held local reference it. */
    private fun allocateHeapBallast() {
        heapBallast = Array(24) { ByteArray(1024 * 1024) }
    }

    private fun releaseHeapBallast() {
        heapBallast = null
    }

    /** Used heap after asking for GC three times, with the request (not a guarantee) stated. */
    private fun usedHeapAfterGc(): Long {
        repeat(3) {
            System.gc()
            runCatching { Thread.sleep(60) }
        }
        return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    }
}
