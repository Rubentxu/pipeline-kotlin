package dev.rubentxu.pipeline.v2.runtime.recover

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.events.durable.FileBackedRunExecutionLeaseStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.LeaseRequest
import dev.rubentxu.pipeline.v2.events.durable.RunOwnerId
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputFrameIndex
import dev.rubentxu.pipeline.v2.output.OutputPinPort
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.OutputTailPort
import dev.rubentxu.pipeline.v2.output.OutputTailState
import dev.rubentxu.pipeline.v2.output.store.OutputPinPortStoreAdapter
import dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionPort
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionPortStoreAdapter
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * M3 — `RecoverRefusal.PinnedBytesOutsideRecoveredRegion` extension tests
 * against the real `RuntimeRecoverPortStoreAdapter` wired with an
 * `OutputPinPort`. Three cases cover the design §10.4 surface:
 *
 *  1. Recover a run with an active pin in the region being released
 *     returns `FailClosed(PinnedBytesOutsideRecoveredRegion)`.
 *  2. Recover a run with the pin on a DIFFERENT stream (outside the
 *     recovered region) returns `RecoveredTerminal`.
 *  3. Recover a run with an expired pin in the released region returns
 *     `RecoveredTerminal` (expired pin is not a pin).
 */
class RecoverRefusalPinExtensionTest {

    private val runId = "run-m3-pin-recover"

    private class TestBundle(
        tempDir: Path,
        val runId: String,
        private val pinAdapter: OutputPinPortStoreAdapter,
    ) : AutoCloseable {
        val clock: Clock = object : Clock {
            override fun now(): Instant = Instant.parse("2026-01-01T00:00:00Z")
        }
        val eventStore = InMemoryEventStore()
        val journal = InMemoryOperationJournal(clock)
        val cursors = InMemoryReplayCursorStore(clock)
        val leaseDir = tempDir.resolve("leases")
        val outputDir = tempDir.resolve("output")
        val lease = FileBackedRunExecutionLeaseStore(leaseDir)
        val outputStore = SegmentOutputStore(outputDir)
        val frames: OutputFrameIndex = object : OutputFrameIndex {
            override fun declareStream(stream: OutputStreamId, channel: OutputChannel) = Unit
            override fun append(stream: OutputStreamId, channel: OutputChannel, from: Long, to: Long) =
                throw UnsupportedOperationException()
            override fun framesOfRun(runId: String, afterOrdinal: Long, limit: Int) = emptyList<OutputFrame>()
            override fun lastOrdinal(runId: String): Long? = null
            override fun streamsOfRun(runId: String): List<OutputStreamId> =
                outputStore.let { _ ->
                    // Mirror the on-disk stream dirs of this run.
                    val prefix = "${runId.replace(Regex("[^A-Za-z0-9._-]"), "_")}_"
                    val streamsRoot = outputDir.resolve("streams")
                    if (!java.nio.file.Files.isDirectory(streamsRoot)) emptyList()
                    else java.nio.file.Files.newDirectoryStream(streamsRoot).use { entries ->
                        entries.asSequence()
                            .filter { java.nio.file.Files.isDirectory(it) }
                            .filter { it.fileName.toString().startsWith(prefix) }
                            .map { dir ->
                                val safe = dir.fileName.toString()
                                // Reverse the safe() fold to recover the stream id.
                                OutputStreamId(safe.replace("_", "/"))
                            }
                            .toList()
                    }
                }
            override fun recoverUnframedBytes(): List<OutputFrame> = emptyList()
        }
        val tails: OutputTailPort = object : OutputTailPort {
            override fun tailState(stream: OutputStreamId): OutputTailState? = null
        }
        val introspectPort: RuntimeIntrospectionPort = RuntimeIntrospectionPortStoreAdapter(
            eventReads = dev.rubentxu.pipeline.v2.events.durable.EventRecordReadPortStoreAdapter(
                store = eventStore,
                runExists = eventStore::hasRun,
                tailSequence = eventStore::tailSequence,
            ),
            tails = tails,
            frames = frames,
            journal = journal,
            cursors = cursors,
            lease = lease,
        )
        val port: RuntimeRecoverPort = RuntimeRecoverPortStoreAdapter(
            journal = journal,
            cursors = cursors,
            lease = lease,
            outputRecovery = outputStore,
            frames = frames,
            introspect = RuntimeRecoverPortIntrospect { runId -> introspectPort.inspect(runId) },
            clock = clock,
            pinPort = pinAdapter,
            json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
        )

        fun acquireLeaseAs(ownerId: String) {
            val owner = RunOwnerId.of(ownerId)!!
            lease.acquire(LeaseRequest(runId, owner))
        }

        fun writeTerminalOp(): RerunOperation = RerunOperation(
            id = "op-$runId",
            fingerprint = Fingerprint("ff".repeat(32)),
            input = OperationInput(
                stepId = "test-step",
                params = emptyMap(),
                runId = runId,
                attempt = 1,
            ),
            output = null,
            status = OperationStatus.SUCCEEDED,
            attempt = 1,
        )

        override fun close() {
            lease.close()
            pinAdapter.close()
        }
    }

    @Test
    fun `1 recover a run with an active pin in the recovered region fails closed`(
        @TempDir tempDir: Path,
    ) {
        val stream = OutputStreamId("$runId/stdout/transcript")
        val outputStore = SegmentOutputStore(tempDir.resolve("output"))
        outputStore.recover()
        outputStore.open(stream).reserve(64).apply {
            write("hello-world".toByteArray(Charsets.UTF_8))
        }.commit()

        val pinAdapter = OutputPinPortStoreAdapter(tempDir.resolve("pins"), outputStore)
        TestBundle(tempDir, runId, pinAdapter).use { bundle ->
            bundle.acquireLeaseAs("m2-observer")
            bundle.journal.append(bundle.writeTerminalOp())

            // Pin the run's stream.
            val pinned = pinAdapter.pin(stream, 0L..11L, "holder-m3", "recover-blocker")
            assertInstanceOf(
                dev.rubentxu.pipeline.v2.output.OutputPinResult.Pinned::class.java,
                pinned,
            )

            val result = bundle.port.recover(runId, RecoverOptions.Default)
            val fc = assertInstanceOf(RecoverOutcome.FailClosed::class.java, result)
            assertInstanceOf(
                RecoverRefusal.PinnedBytesOutsideRecoveredRegion::class.java,
                fc.reason,
                "expected PinnedBytesOutsideRecoveredRegion, got ${fc.reason}",
            )
        }
    }

    @Test
    fun `2 recover a run with the pin on a different run succeeds`(
        @TempDir tempDir: Path,
    ) {
        val otherRun = "run-m3-other"
        val outputStore = SegmentOutputStore(tempDir.resolve("output"))
        outputStore.recover()

        // Pin on a DIFFERENT run's stream.
        val otherStream = OutputStreamId("$otherRun/stdout/transcript")
        outputStore.open(otherStream).reserve(64).apply {
            write("other-run-bytes".toByteArray(Charsets.UTF_8))
        }.commit()

        val pinAdapter = OutputPinPortStoreAdapter(tempDir.resolve("pins"), outputStore)
        TestBundle(tempDir, runId, pinAdapter).use { bundle ->
            bundle.acquireLeaseAs("m2-observer")
            bundle.journal.append(bundle.writeTerminalOp())

            pinAdapter.pin(otherStream, 0L..14L, "holder-m3", "different run")

            val result = bundle.port.recover(runId, RecoverOptions.Default)
            // The pin is on a different run; it does NOT block this recover.
            assertInstanceOf(
                RecoverOutcome.AlreadyRecovered::class.java, result,
                "expected AlreadyRecovered (pin is on a different run), got $result",
            )
        }
    }

    @Test
    fun `3 recover a run with an expired pin in the recovered region succeeds`(
        @TempDir tempDir: Path,
    ) {
        val stream = OutputStreamId("$runId/stdout/transcript")
        val outputStore = SegmentOutputStore(tempDir.resolve("output"))
        outputStore.recover()
        outputStore.open(stream).reserve(64).apply {
            write("hello-world".toByteArray(Charsets.UTF_8))
        }.commit()

        val pinAdapter = OutputPinPortStoreAdapter(tempDir.resolve("pins"), outputStore)
        TestBundle(tempDir, runId, pinAdapter).use { bundle ->
            bundle.acquireLeaseAs("m2-observer")
            bundle.journal.append(bundle.writeTerminalOp())

            // Expired pin (in the past).
            pinAdapter.pin(
                stream = stream,
                range = 0L..11L,
                holder = "holder-m3",
                reason = "expired blocker",
                expiresAtMs = System.currentTimeMillis() - 60_000L,
            )

            val result = bundle.port.recover(runId, RecoverOptions.Default)
            // Expired pin is treated as released; it does NOT block.
            assertInstanceOf(
                RecoverOutcome.AlreadyRecovered::class.java, result,
                "expected AlreadyRecovered (expired pin is not a pin), got $result",
            )
        }
    }
}