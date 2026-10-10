package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputPinResult
import dev.rubentxu.pipeline.v2.output.OutputPruneIntent
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.PruneAuthorisation
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * M3 — `OutputRetentionPort.canPrune` contract tests against the real
 * `SegmentOutputStore` + `OutputPinPortStoreAdapter`. Five cases cover
 * the design §10.6 surface:
 *
 *  1. canPrune with no pins returns `Granted`.
 *  2. canPrune with one active pin returns `Consulted(stream, range, [pin])`.
 *  3. canPrune with an expired pin returns `Granted` (expired pin is not a pin).
 *  4. canPrune after release returns `Granted`.
 *  5. canPrune on a store with no matching runId returns `Refused(StorageError)`.
 */
class PruneAuthorisationAdapterTest {

    private fun bytes(s: String) = s.toByteArray(StandardCharsets.UTF_8)

    private fun buildStoreWithPin(
        root: Path,
    ): Triple<SegmentOutputStore, OutputPinPortStoreAdapter, dev.rubentxu.pipeline.v2.output.OutputPruneIntent> {
        val outputDir = root.resolve("output")
        val pinsDir = root.resolve("pins")
        // Build the pin adapter first with a stub-readable store, then
        // replace the store with the wired one. The pin adapter holds a
        // reference to the store for `committedExtent`, so the wired
        // store must be the one in the adapter's hands.
        val stub = SegmentOutputStore(outputDir)
        stub.recover()
        val pinAdapter = OutputPinPortStoreAdapter(pinsDir, stub)
        val wiredStore = SegmentOutputStore(outputDir, pinPort = pinAdapter)
        wiredStore.recover()
        return Triple(wiredStore, pinAdapter, OutputPruneIntent.OperatorReleased("dummy", "op"))
    }

    @Test
    fun `canPrune with no active pins returns Granted`(@TempDir root: Path) {
        val (store, _, _) = buildStoreWithPin(root)
        val stream = OutputStreamId("run-m3-canprune-granted/op/transcript")
        store.open(stream).reserve(64).apply { write(bytes("0123456789")) }.commit()

        val intent = OutputPruneIntent.OperatorReleased(runId = "run-m3-canprune-granted", requestedBy = "op")
        val result = store.canPrune(intent)
        assertInstanceOf(PruneAuthorisation.Granted::class.java, result, "expected Granted, got $result")
    }

    @Test
    fun `canPrune with one active pin returns Consulted`(@TempDir root: Path) {
        val (store, pinPort, _) = buildStoreWithPin(root)
        val stream = OutputStreamId("run-m3-canprune-consulted/op/transcript")
        store.open(stream).reserve(64).apply { write(bytes("0123456789ABCDEF")) }.commit()

        val pinned = pinPort.pin(stream, 2L..7L, "holder-m3", "active pin") as OutputPinResult.Pinned

        val intent = OutputPruneIntent.OperatorReleased(
            runId = "run-m3-canprune-consulted",
            requestedBy = "op",
        )
        val result = store.canPrune(intent)
        val consulted = assertInstanceOf(
            PruneAuthorisation.Consulted::class.java, result,
            "expected Consulted, got $result",
        )
        assertEquals(stream, consulted.stream)
        assertEquals(2L..7L, consulted.range)
        assertEquals(1, consulted.pinsAtConsult.size, "expected one pin, got ${consulted.pinsAtConsult.size}")
        assertEquals(pinned.pinId, consulted.pinsAtConsult.first().pinId)
    }

    @Test
    fun `canPrune with only an expired pin returns Granted`(@TempDir root: Path) {
        val (store, pinPort, _) = buildStoreWithPin(root)
        val stream = OutputStreamId("run-m3-canprune-expired/op/transcript")
        store.open(stream).reserve(64).apply { write(bytes("0123456789")) }.commit()

        pinPort.pin(
            stream = stream,
            range = 0L..9L,
            holder = "holder-m3",
            reason = "expired",
            expiresAtMs = System.currentTimeMillis() - 60_000L,
        )

        val intent = OutputPruneIntent.OperatorReleased(
            runId = "run-m3-canprune-expired",
            requestedBy = "op",
        )
        val result = store.canPrune(intent)
        assertInstanceOf(
            PruneAuthorisation.Granted::class.java, result,
            "expected Granted (expired pin is not a pin), got $result",
        )
    }

    @Test
    fun `canPrune after release returns Granted`(@TempDir root: Path) {
        val (store, pinPort, _) = buildStoreWithPin(root)
        val stream = OutputStreamId("run-m3-canprune-released/op/transcript")
        store.open(stream).reserve(64).apply { write(bytes("0123456789")) }.commit()

        val pinned = pinPort.pin(stream, 0L..9L, "holder-m3", "before release") as OutputPinResult.Pinned
        pinPort.release(pinned.pinId)

        val intent = OutputPruneIntent.OperatorReleased(
            runId = "run-m3-canprune-released",
            requestedBy = "op",
        )
        val result = store.canPrune(intent)
        assertInstanceOf(PruneAuthorisation.Granted::class.java, result, "expected Granted, got $result")
    }

    @Test
    fun `canPrune on a run with no output returns Refused`(@TempDir root: Path) {
        val (store, _, _) = buildStoreWithPin(root)
        val intent = OutputPruneIntent.OperatorReleased(
            runId = "run-that-never-existed",
            requestedBy = "op",
        )
        val result = store.canPrune(intent)
        assertInstanceOf(
            PruneAuthorisation.Refused::class.java, result,
            "expected Refused, got $result",
        )
    }
}