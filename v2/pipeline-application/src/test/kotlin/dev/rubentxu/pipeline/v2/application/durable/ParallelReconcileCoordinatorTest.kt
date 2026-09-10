package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.support.CoordinatorFixture
import dev.rubentxu.pipeline.v2.domain.BlockSegment
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.CompositeOperation
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.ParallelBranchFinished
import dev.rubentxu.pipeline.v2.events.ParallelBranchStarted
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Instant
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal

/**
 * PAR-D D2 — focused coordinator tests (HF1, production-like composition via
 * CoordinatorFixture). Proves:
 *  - fresh parallel launches all branches, closes aggregate with typed fold;
 *  - a fully-terminal aggregate row is REUSED: zero child dispatch, zero new
 *    ParallelBranchStarted/Finished (reused terminal branch -> no fabricated events);
 *  - all children terminal + stale aggregate -> CloseFromChildren reconstructs
 *    success with zero child executions;
 *  - aggregate journal row is kind COMPOSITE from birth.
 * No sleeps; deterministic journal seeding.
 */
@Timeout(20)
class ParallelReconcileCoordinatorTest {

    private fun echo(text: String) = OpaqueStepNode(
        id = StepId("b/echo"),
        pluginStepId = PluginStepId("core.echo"),
        payload = VersionedStepPayload("dsl-v1", """{"kind":"echo","text":"$text"}"""),
    )

    private fun parallelPipeline(branchCount: Int = 2): CompiledPipeline = CompiledPipeline(
        id = DefinitionId("par-d2-pipeline"),
        source = SourceDescriptor("Pipeline.kts", Digest("source")),
        pluginLockDigest = Digest("lock"),
        stages = listOf(
            StageNode(
                id = StageId("par"),
                name = "par",
                body = StageBody.Parallel(
                    (0 until branchCount).map { b ->
                        StageNode(id = StageId("br$b"), name = "br$b", body = StageBody.Steps(listOf(echo("branch-$b"))))
                    },
                ),
            ),
        ),
    )

    private fun controlOpId(runId: String) = "${runId}-s0--1-bp1-0:parallel-control"

    private fun aggregateInput(runId: String) = OperationInput(
        stepId = "core.parallel",
        params = mapOf("control" to JsonPrimitive("aggregate")),
        runId = runId,
        attempt = 1,
    )

    private fun terminalAggregate(runId: String, outcomeText: String): CompositeOperation {
        val branches = parallelPipeline().stages.single().let { (it.body as StageBody.Parallel).branches }
        val fp = Fingerprint.compute(
            aggregateInput(runId),
            "core.parallel[0]" + branches.joinToString("|") { it.name },
            ReplayPolicy.MEMOIZED,
            1,
        )
        return CompositeOperation(
            id = controlOpId(runId),
            fingerprint = fp,
            input = aggregateInput(runId),
            output = dev.rubentxu.pipeline.v2.domain.durable.OperationOutput(
                buildJsonObject { put("outcome", JsonPrimitive(outcomeText)) },
                durationMs = 0L,
                finishedAt = 1L,
            ),
            status = if (outcomeText == "success") OperationStatus.SUCCEEDED else OperationStatus.FAILED,
            attempt = 1,
            subOperations = emptyList(),
        )
    }

    private fun seedTerminalChild(journal: InMemoryOperationJournal, runId: String, branch: Int) {
        val bodyPath = listOf(BlockSegment("b$branch:branch"), BlockSegment(0, PluginStepId("core.echo")))
        val opId = OpId(runId, 0, 0, bodyPath = bodyPath).format()
        val input = OperationInput(
            stepId = "core.echo",
            params = mapOf("payload" to JsonPrimitive("""{"kind":"echo","text":"branch-$branch"}""")),
            runId = runId,
            attempt = 1,
        )
        journal.append(
            RerunOperation(
                id = opId,
                fingerprint = Fingerprint.compute(input, "core.echo", ReplayPolicy.MEMOIZED, 1),
                input = input,
                output = null,
                status = OperationStatus.SUCCEEDED,
                attempt = 1,
            ),
        )
    }

    @Test
    fun `fresh parallel runs all branches and closes the aggregate row`() = runBlocking {
        val clock = SystemClock()
        val journal = InMemoryOperationJournal(clock)
        val sink = InMemoryEventStore()
        val coord = CoordinatorFixture.default(clock, journal, sink)
        val runId = RunId("par-d2-fresh")

        val outcome = coord.run(parallelPipeline(), runId)

        assertEquals(dev.rubentxu.pipeline.v2.domain.RunOutcome.Success, outcome)
        val aggregate = journal.get(controlOpId(runId.value), 1)
        assertTrue(aggregate is CompositeOperation, "aggregate row must be kind COMPOSITE, was ${aggregate?.let { it::class.simpleName }}")
        assertEquals(OperationStatus.SUCCEEDED, aggregate!!.status)
        assertEquals(2, sink.eventsFor(runId.value).filterIsInstance<ParallelBranchStarted>().count())
        assertEquals(2, sink.eventsFor(runId.value).filterIsInstance<ParallelBranchFinished>().count())
    }

    @Test
    fun `terminal aggregate is reused with zero child dispatch and zero fabricated branch events`() = runBlocking {
        val clock = SystemClock()
        val runId = RunId("par-d2-reuse")
        val journal = InMemoryOperationJournal(clock)
        journal.append(terminalAggregate(runId.value, "success"))
        val sink = InMemoryEventStore()
        val coord = CoordinatorFixture.default(clock, journal, sink)

        val outcome = coord.run(parallelPipeline(), runId)

        assertEquals(dev.rubentxu.pipeline.v2.domain.RunOutcome.Success, outcome)
        assertEquals(0, sink.eventsFor(runId.value).filterIsInstance<ParallelBranchStarted>().count(), "reuse must not fabricate ParallelBranchStarted")
        assertEquals(0, sink.eventsFor(runId.value).filterIsInstance<ParallelBranchFinished>().count(), "reuse must not fabricate ParallelBranchFinished")
        // Only stage bookends projected, no child dispatch rows beyond the aggregate:
        val childRows = journal.listForRun(runId.value).filter { it.id != controlOpId(runId.value) }
        assertEquals(0, childRows.size, "reuse must not create child journal rows")
    }

    @Test
    fun `all children terminal with stale aggregate reconstructs success with zero child executions`() = runBlocking {
        val clock = SystemClock()
        val runId = RunId("par-d2-close")
        val journal = InMemoryOperationJournal(clock)
        // Aggregate stale RUNNING; both branches' children terminal SUCCEEDED.
        journal.append(
            CompositeOperation(
                id = controlOpId(runId.value),
                fingerprint = terminalAggregate(runId.value, "success").fingerprint,
                input = aggregateInput(runId.value),
                output = null,
                status = OperationStatus.RUNNING,
                attempt = 1,
                subOperations = emptyList(),
            ),
        )
        seedTerminalChild(journal, runId.value, 0)
        seedTerminalChild(journal, runId.value, 1)
        val sink = InMemoryEventStore()
        val coord = CoordinatorFixture.default(clock, journal, sink)

        val outcome = coord.run(parallelPipeline(), runId)

        assertEquals(dev.rubentxu.pipeline.v2.domain.RunOutcome.Success, outcome)
        assertEquals(0, sink.eventsFor(runId.value).filterIsInstance<ParallelBranchStarted>().count(), "close-from-children must not fabricate branch events")
        val aggregate = journal.get(controlOpId(runId.value), 1)
        assertEquals(OperationStatus.SUCCEEDED, aggregate!!.status)
    }
}
