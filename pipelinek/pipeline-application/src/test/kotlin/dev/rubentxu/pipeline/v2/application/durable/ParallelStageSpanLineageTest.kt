package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.LiveSpanLedger
import dev.rubentxu.pipeline.v2.application.LiveSpanNames
import dev.rubentxu.pipeline.v2.application.LiveSpanParent
import dev.rubentxu.pipeline.v2.application.SequentialIdGenerator
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StageId
import dev.rubentxu.pipeline.v2.domain.StageNode
import dev.rubentxu.pipeline.v2.domain.StepId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.VersionedStepPayload
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.NullEventSink
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A branch that runs on another dispatcher must still belong to its stage.
 *
 * ## THE GUARANTEE
 *
 * The branches of a parallel stage run at the same time, on workers the caller
 * never touches, and the trace has to say which stage each of them belongs
 * to. The parent therefore travels EXPLICITLY, inside the dispatch:
 *
 * ```text
 * carried   the dispatch the branch received names the stage's span as its
 *           parent, so the branch hangs under the stage and not under
 *           whatever the worker thread happened to have
 * ```
 *
 * ## THE CAPACITY THIS SERVES
 *
 * A trace of a parallel stage is only readable if its branches can be told
 * apart AND placed. A collector receives a tree; a stage with two branches
 * that both hang under the run instead of under the stage says that the stage
 * caused nothing, and the reader loses the answer to "which branch of which
 * stage failed?".
 *
 * ## WHY THIS INSTRUMENT
 *
 * The dispatcher is replaced by a recorder, because the question is not "did
 * the step work?" but "what did the branch RECEIVE?". The recorder answers
 * that and records the thread it ran on, which is what makes the row
 * discriminating: a lineage that only worked on the caller's thread would
 * pass a weaker row and fail this one.
 *
 * The rest is in memory too: the clock is fixed, the event sink discards, the
 * journal is the storage-free twin of the durable one, and the workspace and
 * the control root point at paths that DO NOT EXIST — if any of these
 * compositions wrote there, the row would say so instead of passing
 * unnoticed.
 *
 * ## WHAT THIS INSTRUMENT CANNOT SEE
 *
 * It does not observe a real parallel run: no process, no directory, no
 * clock. It observes the dispatch's COORDINATES — the parent, the durable
 * path and the thread — and those are what the guarantee is about.
 */
class ParallelStageSpanLineageTest {

    /**
     * Every branch hangs under the stage it belongs to.
     *
     * The stage's span is opened when the stage starts, and every branch is
     * dispatched under it. A branch dispatched under anything else would be a
     * branch of the run, and the tree would say so.
     */
    @Test
    @DisplayName("every branch of a parallel stage hangs under the stage it belongs to")
    fun everyBranchOfAParallelStageHangsUnderTheStageItBelongsTo() {
        val ledger = LiveSpanLedger()
        val dispatcher = RecordingDispatcher()
        val engine = engine(dispatcher, ledger)

        val outcome = runBlocking {
            val callerThread = Thread.currentThread()
            val result = engine.runParallelStage(
                stage = parallelStage(),
                stageIndex = 0,
                stageShOptions = shOptions(),
                runId = RunId(RUN_ID),
                executionContext = ExecutionContext.EMPTY,
            )
            result to callerThread
        }

        assertEquals(StepOutcome.Success, outcome.first, "both branches succeeded")
        assertEquals(2, dispatcher.requests.size, "every branch dispatched its step")
        val stage = ledger.spanNamed(LiveSpanNames.STAGE, RUN_ID)
        dispatcher.requests.forEach { request ->
            val parent = assertInstanceOf(
                LiveSpanParent.Local::class.java,
                request.spanScope.parent,
                "the branch was dispatched under a span this process opened",
            )
            assertSame(
                stage,
                parent.handle.span,
                "the branch hangs under the stage, not under the run and not under nothing",
            )
        }
        dispatcher.threads.forEach { branchThread ->
            assertNotSame(
                outcome.second,
                branchThread,
                "the branch ran on another dispatcher, and the parent still arrived",
            )
        }
    }

    // --- the fixtures ------------------------------------------------------

    /** The parallel engine under observation, composed over the recorder. */
    private fun engine(
        dispatcher: StepDispatcher,
        ledger: LiveSpanLedger,
    ): ParallelStageEngine = ParallelStageEngine(
        stepDispatch = dispatcher,
        journal = InMemoryOperationJournal(FixedClock),
        eventSink = NullEventSink,
        clock = FixedClock,
        idGenerator = SequentialIdGenerator(),
        runLifecycle = RunLifecycleEngine(NullEventSink, ledger, clock = FixedClock, idGenerator = SequentialIdGenerator()),
        controlDirRoot = Path.of("/nonexistent-control-root"),
        workspaceBase = Path.of("/nonexistent-workspace"),
    )

    /**
     * A stage with two branches, each with one step.
     *
     * The steps are opaque on purpose: this row observes the dispatch's
     * coordinates, and a step that could run would add a process to a
     * guarantee that does not need one.
     */
    private fun parallelStage(): StageNode = StageNode(
        id = StageId("stage-0"),
        name = "fanout",
        body = StageBody.Parallel(
            branches = listOf(
                branch("left", 0),
                branch("right", 1),
            ),
        ),
    )

    /** One branch, with one step. */
    private fun branch(name: String, index: Int): StageNode = StageNode(
        id = StageId("branch-$index"),
        name = name,
        body = StageBody.Steps(
            steps = listOf(
                OpaqueStepNode(
                    id = StepId(STEP_ID),
                    pluginStepId = PluginStepId(STEP_ID),
                    payload = VersionedStepPayload(schemaVersion = "1", encoded = "{}"),
                ),
            ),
        ),
    )

    /** The options the stage runs under, pointing at a path that does not exist. */
    private fun shOptions(): ShOptions = ShOptions(
        workspaceRoot = Path.of("/nonexistent-workspace"),
        captureStdout = false,
        timeoutMs = null,
        env = emptyMap(),
    )

    /**
     * The dispatcher, replaced by a recorder.
     *
     * The recorder keeps the request it was handed and the thread it was
     * called on: the first is the answer to "what did the branch receive?",
     * and the second is what makes the row discriminating.
     */
    private class RecordingDispatcher : StepDispatcher {

        /** Every dispatch the engine asked for. */
        val requests: MutableList<StepDispatchRequest> = CopyOnWriteArrayList()

        /** The thread each dispatch ran on. */
        val threads: MutableList<Thread> = CopyOnWriteArrayList()

        override suspend fun dispatch(request: StepDispatchRequest): DispatchedStep {
            requests += request
            threads += Thread.currentThread()
            return DispatchedStep(
                result = CommonExecutionResult(StepOutcome.Success),
                context = request.spanScope.ambient,
            )
        }
    }

    /**
     * A clock that does not move, so nothing in this row depends on the wall clock.
     *
     * It sits at a POSITIVE instant on purpose: the durable rows the parallel
     * aggregate writes carry the instant they finished at, and a row that
     * refuses a non-positive instant would fail the row for a reason that has
     * nothing to do with the lineage.
     */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.ofEpochMilli(1_700_000_000_000L)
    }

    private companion object {
        const val RUN_ID = "run-1"
        const val STEP_ID = "core.probe"
    }
}
