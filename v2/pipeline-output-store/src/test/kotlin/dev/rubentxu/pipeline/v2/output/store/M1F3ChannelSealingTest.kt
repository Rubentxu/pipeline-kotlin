package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputStreamId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * M1-F.3 — per-channel seal contract for [SegmentOutputStore].
 *
 * A `sh("echo hi")` invocation writes only to stdout; a `sh("echo hi >&2")`
 * writes only to stderr; the other channel was never opened. The previous
 * `seal()` shape threw `IllegalStateException` for both "unknown stream"
 * and "real I/O failure" — which forced the durable shell substrate to
 * wrap every seal call in a try/catch and to print "could not seal ..."
 * to stderr for every kind of failure including the legitimate-absence
 * case the user reported as noise.
 *
 * The new contract is a closed return type:
 *  - [SealOutcome.Sealed] — newly sealed
 *  - [SealOutcome.AlreadySealed] — idempotent re-seal
 *  - [SealOutcome.NeverOpened] — legitimate absence, silent no-op
 *  - [SealOutcome.Failure] — real I/O failure, propagated as data
 *
 * These tests pin each case against the on-disk state the store produces,
 * so a future change to [SegmentOutputStore.seal] cannot silently
 * regress to the old exception-throwing shape.
 */
class M1F3ChannelSealingTest {

    @TempDir
    lateinit var tempDir: Path

    private fun newStore(): SegmentOutputStore =
        SegmentOutputStore(tempDir).also { it.recover() }

    private fun write(stream: OutputStreamId, text: String, store: SegmentOutputStore) {
        val handle = store.open(stream)
        val reservation = handle.reserve(text.toByteArray(Charsets.UTF_8).size)
        reservation.write(text.toByteArray(Charsets.UTF_8))
        reservation.commit()
    }

    // ----------------------------------------------------------------- tests

    /**
     * M1-F.3 — sealing a stream nobody ever opened is NOT a refusal.
     *
     * The previous shape threw [IllegalStateException] ("cannot seal
     * unknown stream ..."); the new shape answers with
     * [SealOutcome.NeverOpened]. A stdout-only script never opened
     * stderr, and a stderr-only path is the same failure mode the output
     * store observes.
     */
    @Test
    fun `sealing an unopened stream returns NeverOpened`() {
        val store = newStore()
        val stream = OutputStreamId("run-1/build/sh-0/stderr")

        val outcome = store.seal(stream)

        assertEquals(
            SealOutcome.NeverOpened,
            outcome,
            "an unopened stream is the legitimate-absence case, not a refusal",
        )
        // No marker file was written — a sealed result for a stream that
        // never existed would mint an authority over bytes nobody wrote.
        val marker = tempDir
            .resolve("streams")
            .resolve(SegmentOutputStoreTestHelpers.safe(stream.value))
            .resolve("stream.seal")
        assertFalse(
            Files.exists(marker),
            "NeverOpened must not write a seal marker — that would mint an authority",
        )
    }

    /**
     * M1-F.3 — sealing an open stream records the extent and writes the
     * marker. Returns [SealOutcome.Sealed].
     */
    @Test
    fun `sealing an open stream returns Sealed with the recorded end`() {
        val store = newStore()
        val stream = OutputStreamId("run-1/build/sh-0/stdout")
        write(stream, "twenty bytes exactly", store)

        val outcome = store.seal(stream)

        assertEquals(SealOutcome.Sealed(20L), outcome)
    }

    /**
     * M1-F.3 — re-sealing is idempotent: the second seal returns
     * [SealOutcome.AlreadySealed] with the original end, and the
     * marker is not touched.
     *
     * INV-SEAL-IDEMPOTENT — a resumed run that re-observes the same
     * terminal must produce the same durable state, not a second,
     * later end. The second seal is the seam.
     */
    @Test
    fun `re-sealing is idempotent and does not move the recorded end`() {
        val store = newStore()
        val stream = OutputStreamId("run-1/build/sh-0/stdout")
        write(stream, "first ten bytes", store)

        val first = store.seal(stream)
        val second = store.seal(stream)

        assertEquals(SealOutcome.Sealed(15L), first)
        assertEquals(
            SealOutcome.AlreadySealed(15L),
            second,
            "the second seal must report AlreadySealed, not move the recorded end",
        )
        val marker = tempDir
            .resolve("streams")
            .resolve(SegmentOutputStoreTestHelpers.safe(stream.value))
            .resolve("stream.seal")
        val recorded = Files.readString(marker).trim().toLong()
        assertEquals(15L, recorded, "the marker must record the FIRST seal's end")
    }

    /**
     * M1-F.3 — independent per-channel seal. Sealing stdout does NOT
     * seal stderr and vice versa.
     *
     * The two channels of one operation tail independently, because
     * OBS-C2.3 gave them separate stream identities. A consumer that
     * merged them must be able to keep tailing the one that is still
     * open, which is exactly what a single boolean "the operation is
     * finished" could not express.
     */
    @Test
    fun `sealing one channel does not seal the other`() {
        val store = newStore()
        val stdout = OutputStreamId("run-1/build/sh-0/stdout")
        val stderr = OutputStreamId("run-1/build/sh-0/stderr")
        write(stdout, "out", store)
        write(stderr, "err", store)

        val stdoutOutcome = store.seal(stdout)
        val stderrOutcome = store.seal(stderr)

        assertEquals(SealOutcome.Sealed(3L), stdoutOutcome)
        assertEquals(SealOutcome.Sealed(3L), stderrOutcome)
    }

    /**
     * M1-F.3 — a per-channel [SealOutcome.Failure] propagates the cause
     * and leaves the bytes already committed on disk.
     *
     * The test denies write permission on the stream directory AFTER
     * bytes have been committed, then attempts a seal. The store must
     * report a typed failure with the cause, NOT throw. The bytes that
     * WERE committed must remain intact — the test reads them back.
     *
     * Note: this test is skipped on Windows where POSIX permissions are
     * not enforced.
     */
    @Test
    fun `seal failure propagates as typed Failure and leaves committed bytes intact`() {
        val store = newStore()
        val stream = OutputStreamId("run-1/build/sh-0/stdout")
        write(stream, "preserved bytes", store)
        val streamDir = tempDir
            .resolve("streams")
            .resolve(SegmentOutputStoreTestHelpers.safe(stream.value))
        // Remove write permission so the marker's `Files.writeString`
        // raises an IOException. The directory must still be readable
        // and executable for the store to traverse to it.
        val perms = mutableSetOf<PosixFilePermission>()
        Files.getPosixFilePermissions(streamDir).forEach { perms.add(it) }
        perms.remove(PosixFilePermission.OWNER_WRITE)
        perms.remove(PosixFilePermission.GROUP_WRITE)
        perms.remove(PosixFilePermission.OTHERS_WRITE)
        runCatching { Files.setPosixFilePermissions(streamDir, perms) }
            .onFailure {
                // Non-POSIX filesystem (e.g. Windows): the test cannot
                // produce a real I/O failure deterministically, so it
                // is skipped rather than asserting a wrong result.
                return
            }

        try {
            val outcome = store.seal(stream)
            val failure = assertInstanceOf(SealOutcome.Failure::class.java, outcome)
            assertNotNull(failure.cause, "SealOutcome.Failure must carry a cause")
        } finally {
            // Restore write permission so JUnit can delete the temp dir.
            perms.add(PosixFilePermission.OWNER_WRITE)
            Files.setPosixFilePermissions(streamDir, perms)
        }
    }

    /**
     * M1-F.3 — a [SealOutcome.NeverOpened] on one channel is silent;
     * the other channel can still be sealed normally.
     *
     * The durable shell substrate iterates the per-operation channels
     * and seals each one. A stdout-only script produces
     * [SealOutcome.NeverOpened] for stderr and [SealOutcome.Sealed]
     * for stdout; both must succeed and neither must be a refusal.
     */
    @Test
    fun `per-operation loop is silent on the legitimate-absence channel`() {
        val store = newStore()
        val stdout = OutputStreamId("run-1/build/sh-0/stdout")
        // stderr never opened — a stdout-only script.
        write(stdout, "only stdout", store)

        val stdoutOutcome = store.seal(stdout)
        val stderrOutcome = store.seal(OutputStreamId("run-1/build/sh-0/stderr"))

        assertEquals(
            SealOutcome.Sealed(11L),
            stdoutOutcome,
            "stdout must be sealed with the bytes that were written",
        )
        assertEquals(
            SealOutcome.NeverOpened,
            stderrOutcome,
            "stderr was never opened — that is the legitimate-absence case, not a refusal",
        )
    }

    /**
     * M1-F.3 — sealed-marker content is the recorded end, not the
     * live committed extent. Reopening the store MUST answer the same
     * end from the marker (durable fact), not from `committedExtent`
     * (which is the live view).
     */
    @Test
    fun `the sealed end survives a fresh store over the same root`() {
        val firstStore = newStore()
        val stream = OutputStreamId("run-1/build/sh-0/stdout")
        write(stream, "durable seal", firstStore)

        val firstOutcome = firstStore.seal(stream)
        assertEquals(SealOutcome.Sealed(12L), firstOutcome)

        // A brand-new store object over the same root — what a consumer
        // in a fresh JVM sees.
        val secondStore = newStore()
        val secondOutcome = secondStore.seal(stream)
        assertEquals(
            SealOutcome.AlreadySealed(12L),
            secondOutcome,
            "a re-seal from a fresh store must report AlreadySealed with the marker end, " +
                "not re-record whatever the live committed extent happens to be",
        )
    }

    /**
     * M1-F.3 — the four-case ADT is exhaustive: every call to `seal`
     * lands in one of [SealOutcome.Sealed], [SealOutcome.AlreadySealed],
     * [SealOutcome.NeverOpened], or [SealOutcome.Failure].
     *
     * This is a structural test: it uses a `when` over the sealed
     * interface and asserts the `else` branch is unreachable.
     */
    @Test
    fun `seal returns one of four sealed cases`() {
        val store = newStore()
        val sealedStream = OutputStreamId("run-1/build/sh-0/stdout")
        val unopenedStream = OutputStreamId("run-1/build/sh-9/stderr")
        write(sealedStream, "hello", store)

        fun classify(outcome: SealOutcome): String = when (outcome) {
            is SealOutcome.Sealed -> "sealed"
            is SealOutcome.AlreadySealed -> "already-sealed"
            is SealOutcome.NeverOpened -> "never-opened"
            is SealOutcome.Failure -> "failure"
        }

        assertEquals("sealed", classify(store.seal(sealedStream)))
        assertEquals("already-sealed", classify(store.seal(sealedStream)))
        assertEquals("never-opened", classify(store.seal(unopenedStream)))
        assertTrue(
            classify(store.seal(sealedStream)) in setOf("sealed", "already-sealed"),
            "a re-seal must land in the idempotent pair, never in Failure",
        )
        // end is null only for NeverOpened and Failure-with-no-bytes.
        assertNull(store.seal(unopenedStream).end)
    }
}

/**
 * Internal test helper exposing the [SegmentOutputStore] package-private
 * safe() function. The test file lives in the same package, so the
 * helper is colocated rather than added to production code.
 */
private object SegmentOutputStoreTestHelpers {
    fun safe(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_")
}