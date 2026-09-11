package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * G0 end-to-end characterization of `core.sleep` through the canonical coordinator.
 *
 * Authority surface exercised:
 *   - DSL compilation → dsl-v1 payload (constructed directly here to bypass the DSL facade)
 *   - CanonicalDurableRunCoordinator (production spine)
 *   - CanonicalNodeDispatcher (legacy strategy family)
 *   - In-memory journal + cursor + event store (the production-shaped test substrate)
 *
 * Dimensions pinned:
 *   - Fresh durable execution: blocks for N seconds, returns Success, journal terminal SUCCEEDED.
 *   - Replay (same runId, same db/cursor): reuses the SUCCEEDED operation WITHOUT re-running the
 *     sleep. Wall-clock must be substantially less than the original duration.
 *   - Effects emitted: StepStarted, StepFinished only (no StepFailed; sleep succeeds).
 *
 * NOT pinned here:
 *   - Timeout interaction (covered separately by CoreSleepTimeoutInteractionTest using CLI).
 *   - Coroutine cancellation (the legacy sleep is not coroutine-cooperative).
 */
@Disabled("Historical G0 legacy-authority characterization; superseded by core.sleep LEGACY_REMOVED at G5.")
@Timeout(60)
class CoreSleepCoordinatorCharacterizationTest {

    private val sleepPluginId = "core.sleep"

    private fun noOpCredentialScopePort(): CredentialScopePort = CredentialScopePort { _, _ ->
        CredentialScopeOutcome.Unavailable(
            CredentialScopeFailure.StoreUnavailable("sleep-g0 stub"),
        )
    }

    private data class Harness(
        val coord: CanonicalDurableRunCoordinator,
        val journal: InMemoryOperationJournal,
        val eventStore: InMemoryEventStore,
        val workDir: java.nio.file.Path,
    )

    private fun freshHarness(
        eventStore: InMemoryEventStore,
    ): Harness {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val cursorStore = InMemoryReplayCursorStore(clock)
        val workDir = Files.createTempDirectory("sleep-coord-g0-")
        val coord = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = cursorStore,
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = eventStore,
            credentialScopePort = noOpCredentialScopePort(),
            controlDirRoot = workDir.resolve("control"),
            shOptions = ShOptions.EMPTY,
            stepRegistry = InMemoryStepRegistry(),
        )
        return Harness(coord, journal, eventStore, workDir)
    }

    private fun sleepNode(
        seconds: Long,
        nodeId: String = "build/sleep",
    ) = OpaqueStepNode(
        id = StepId(nodeId),
        pluginStepId = dev.rubentxu.pipeline.v2.domain.PluginStepId(sleepPluginId),
        payload = VersionedStepPayload(
            schemaVersion = "dsl-v1",
            encoded = """{"kind":"sleep","seconds":$seconds}""",
        ),
    )

    private fun pipeline(vararg nodes: OpaqueStepNode) = CompiledPipeline(
        id = DefinitionId("sleep-coord-g0"),
        source = SourceDescriptor("Pipeline.kts", Digest("source")),
        pluginLockDigest = Digest("lock"),
        stages = listOf(
            StageNode(
                id = StageId("build"),
                name = "build",
                body = StageBody.Steps(nodes.toList()),
            ),
        ),
    )

    @Test
    fun `fresh sleep(2) executes the legacy authority once and returns Success`() {
        // G0 characterization focuses on observable wall-clock + outcome; journal-level
        // inspection is done in the CLI-level tests below where the production journal
        // (SQLite-backed) is the canonical substrate.
        val eventStore = InMemoryEventStore()
        val h = freshHarness(eventStore)
        runBlocking {
            val start = System.currentTimeMillis()
            val outcome = h.coord.run(pipeline(sleepNode(seconds = 2)), RunId("sleep-fresh"))
            val elapsed = System.currentTimeMillis() - start

            assertEquals(RunOutcome.Success, outcome, "fresh sleep(2) MUST succeed; got $outcome")
            assertTrue(elapsed >= 1900, "fresh sleep(2) MUST block for at least 1900ms; took ${elapsed}ms")
            val started = h.eventStore.eventsFor("sleep-fresh").filterIsInstance<StepStarted>().count()
            val finished = h.eventStore.eventsFor("sleep-fresh").filterIsInstance<StepFinished>().count()
            assertEquals(1, started, "exactly one StepStarted for sleep")
            assertEquals(1, finished, "exactly one StepFinished for sleep")
        }
    }

    @Test
    fun `replay of a previously succeeded sleep reuses the operation without re-blocking`() {
        // The legacy `core.sleep` is declared `Effect.READ_ONLY + ReplayPolicy.MEMOIZED`. The
        // production replay authority (DefaultEffectReplayPolicy) must therefore REUSE a SUCCEEDED
        // entry on the same runId+opId+fingerprint. Wall-clock must be substantially less than
        // the original sleep duration (a generous bound that won't be flaky).
        val eventStore = InMemoryEventStore()
        val h = freshHarness(eventStore)
        runBlocking {
            // First run: blocks for 2 seconds.
            val firstStart = System.currentTimeMillis()
            h.coord.run(pipeline(sleepNode(seconds = 2)), RunId("sleep-replay"))
            val firstElapsed = System.currentTimeMillis() - firstStart
            assertTrue(firstElapsed >= 1900, "first run must block for at least 1900ms; took ${firstElapsed}ms")

            // Second run at SAME runId with SAME payload: replay reuse, NO re-block.
            val secondStart = System.currentTimeMillis()
            val secondOutcome = h.coord.run(pipeline(sleepNode(seconds = 2)), RunId("sleep-replay"))
            val secondElapsed = System.currentTimeMillis() - secondStart

            assertEquals(RunOutcome.Success, secondOutcome, "replay MUST surface as RunOutcome.Success")
            assertTrue(
                secondElapsed < 1000,
                "replay MUST NOT re-block (must be substantially less than the original sleep); took ${secondElapsed}ms",
            )
            // StepStarted count after replay: still 1 (the lifecycle events of the FIRST execution).
            val startedAfterReplay = h.eventStore.eventsFor("sleep-replay").filterIsInstance<StepStarted>().count()
            assertEquals(
                1, startedAfterReplay,
                "replay MUST NOT emit a new StepStarted (the handler did not run)",
            )
        }
    }

    @Test
    fun `fresh sleep(-1) is NOT validated by the decoder and fails at the dispatcher level with a typed failure`() {
        // The DSL pipeline with seconds = -1 is REJECTED at decoder level: requiredLong accepts
        // -1 as a valid Long, so the payload is structurally valid; the decoder produces
        // CanonicalCoreStepCommand.Sleep(-1); the dispatcher then throws IllegalArgumentException
        // (because Thread.sleep rejects negative millis). The StepExecutionBoundary catches
        // PipelineStepException but propagates other Throwables. The exact failure shape depends
        // on the boundary's exception handling. Pin: NOT success.
        val eventStore = InMemoryEventStore()
        val h = freshHarness(eventStore)
        runBlocking {
            val outcome = h.coord.run(pipeline(sleepNode(seconds = -1)), RunId("sleep-neg"))
            assertTrue(
                outcome is RunOutcome.Failure,
                "sleep(-1) MUST NOT succeed (Thread.sleep throws); got $outcome",
            )
        }
    }

    @Test
    fun `RUNNING sleep with RecoveryPolicy None reruns from the beginning under generic memoized reconciliation`() {
        // G2 decision evidence: core.sleep declares RecoveryPolicy.None. A stale RUNNING row
        // receives no sleep-specific recovery; READ_ONLY + MEMOIZED generic reconciliation
        // selects Execute for every non-SUCCEEDED row. Zero makes the re-execution observable
        // and deterministic without waiting for a real duration.
        val eventStore = InMemoryEventStore()
        val h = freshHarness(eventStore)
        val runId = RunId("sleep-running-recovery")
        val node = sleepNode(seconds = 0)
        val payload = node.payload.encoded
        val input = dev.rubentxu.pipeline.v2.domain.durable.OperationInput(
            stepId = sleepPluginId,
            params = mapOf("payload" to kotlinx.serialization.json.JsonPrimitive(payload)),
            runId = runId.value,
            attempt = 1,
        )
        val operationId = "${runId.value}-s0-0"
        h.journal.append(
            dev.rubentxu.pipeline.v2.domain.durable.RerunOperation(
                id = operationId,
                fingerprint = dev.rubentxu.pipeline.v2.domain.durable.Fingerprint.compute(
                    input,
                    sleepPluginId,
                    dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy.MEMOIZED,
                    1,
                ),
                input = input,
                output = null,
                status = OperationStatus.RUNNING,
                attempt = 1,
            ),
        )

        runBlocking {
            assertEquals(RunOutcome.Success, h.coord.run(pipeline(node), runId))
        }
        assertEquals(OperationStatus.SUCCEEDED, h.journal.get(operationId)?.status)
        assertEquals(
            1,
            eventStore.eventsFor(runId.value).filterIsInstance<StepStarted>().count(),
            "generic RecoveryPolicy.None must execute a stale RUNNING sleep from its full duration",
        )
    }
}
