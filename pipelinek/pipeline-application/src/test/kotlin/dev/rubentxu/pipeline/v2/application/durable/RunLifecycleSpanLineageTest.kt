package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.LiveSpanAttributes
import dev.rubentxu.pipeline.v2.application.LiveSpanHandle
import dev.rubentxu.pipeline.v2.application.LiveSpanNames
import dev.rubentxu.pipeline.v2.application.LiveSpanParent
import dev.rubentxu.pipeline.v2.application.LiveSpans
import dev.rubentxu.pipeline.v2.application.RecordedLiveSpan
import dev.rubentxu.pipeline.v2.application.RecordingLiveSpanObserver
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.SourceDescriptor
import dev.rubentxu.pipeline.v2.domain.post.StageOutcome
import dev.rubentxu.pipeline.v2.events.NullEventSink
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryAttribute
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

/**
 * A run's stages hang under the run, and two runs never share a tree.
 *
 * ## The guarantee
 *
 * The lineage of a run is a fact about the run, and it has two halves:
 *
 * ```text
 * nested     a stage is the CHILD of the run that owns it, and the work of a
 *            stage is the child of the stage
 * separate   two runs alive at the same time keep two trees: the stage of one
 *            run is never the child of the other run's span
 * ```
 *
 * The second half is the one that concurrency threatens. The engine
 * dispatches branches on another dispatcher, and a durable run resumes in
 * another process, so the parent of a span cannot live in a thread-local and
 * cannot be re-derived in the next process. It travels in the value the
 * dispatch already carries, which is why two runs cannot mix: there is nothing
 * ambient for them to mix through.
 *
 * ## The capacity this serves
 *
 * An operator with two runs in flight has to be able to read each of them
 * without them bleeding into one another. A trace in which the stage of one run
 * hangs under the other run's span is not a slower trace to read — it is a
 * trace that says the wrong thing, and it says it in a way no collector can
 * detect.
 *
 * ## Why this instrument
 *
 * The fake observer ([RecordingLiveSpanObserver]) is the right one here: the
 * question is not what a collector receives but WHICH span the engine named as
 * the parent, and the fake records exactly that. The passage from the
 * product's decision to the collector's inbox is covered elsewhere, by the
 * rows that export through the real adapter.
 *
 * The interleaving is forced with barriers rather than with time: each run
 * waits for the other to have opened its stage before it closes its own. Two
 * runs are therefore provably alive at the same time, and the row is
 * deterministic instead of hoping the scheduler cooperates.
 */
class RunLifecycleSpanLineageTest {

    /**
     * A stage is the child of the run that owns it.
     *
     * The run opens its span first, and the stage opens under it. Before the
     * lineage existed the two were siblings, and the trace of a run said that
     * a run happened and that a stage happened, with nothing relating them.
     */
    @Test
    @DisplayName("a stage hangs under the run that owns it")
    fun aStageHangsUnderTheRunThatOwnsIt() {
        val observer = RecordingLiveSpanObserver()
        val engine = RunLifecycleEngine(NullEventSink, observer)

        val runSpan = openRunSpan(observer, "run-1")
        engine.openRun(pipeline(), RunId("run-1"), runSpan)
        engine.stageStarted(RunId("run-1"), 0, "build")

        val stage = observer.spanNamed(LiveSpanNames.STAGE, "run-1")
        assertSame(observer.spanNamed(LiveSpanNames.RUN, "run-1"), observer.recordedParentOf(stage))
    }

    /**
     * The work of a stage hangs under the stage.
     *
     * The stage is not a label: it is the span the step that runs inside it
     * belongs to. A reader of the trace has to be able to answer "which step
     * of which stage failed" from the tree alone.
     */
    @Test
    @DisplayName("the work of a stage hangs under the stage")
    fun theWorkOfAStageHangsUnderTheStage() {
        val observer = RecordingLiveSpanObserver()
        val engine = RunLifecycleEngine(NullEventSink, observer)

        val runSpan = openRunSpan(observer, "run-1")
        engine.openRun(pipeline(), RunId("run-1"), runSpan)
        engine.stageStarted(RunId("run-1"), 0, "build")

        val step = LiveSpans.open(
            telemetry = observer,
            name = LiveSpanNames.STEP_DISPATCH,
            parent = engine.parentFor(0),
            attributes = listOf(LiveSpanAttributes.runId("run-1")),
        )
        assertSame(observer.spanNamed(LiveSpanNames.STAGE, "run-1"), observer.parentOf(requireNotNull(step)))
    }

    /**
     * A stage that opened no span falls back to the run.
     *
     * A stage whose span could not be opened — a collector that is down, an
     * observer that throws — still belongs somewhere, and the closest thing
     * that really caused it is the run. Falling back to the run keeps the
     * trace connected; falling back to nothing would file the stage outside
     * the run it belongs to, and an invented link would be worse still.
     */
    @Test
    @DisplayName("a stage that opened no span falls back to the run")
    fun aStageThatOpenedNoSpanFallsBackToTheRun() {
        val observer = RecordingLiveSpanObserver(
            onStart = { name, _ -> if (name == LiveSpanNames.STAGE) error("the collector is unreachable") },
        )
        val engine = RunLifecycleEngine(NullEventSink, observer)

        val runSpan = openRunSpan(observer, "run-1")
        engine.openRun(pipeline(), RunId("run-1"), runSpan)
        engine.stageStarted(RunId("run-1"), 0, "build")

        val step = LiveSpans.open(
            telemetry = observer,
            name = LiveSpanNames.STEP_DISPATCH,
            parent = engine.parentFor(0),
            attributes = listOf(LiveSpanAttributes.runId("run-1")),
        )
        assertSame(observer.spanNamed(LiveSpanNames.RUN, "run-1"), observer.parentOf(requireNotNull(step)))
    }

    /**
     * Two runs on ONE thread keep two trees.
     *
     * This is the arrangement in which a thread-local would leak. Both runs
     * are alive at the same time and, because the barriers hold them there,
     * the second run opens its stage while the first run's stage is still
     * open on the very same thread. A parent read from the ambient context
     * would give the second run's stage the first run's stage as its parent,
     * and the trace would claim a causality that never happened.
     */
    @Test
    @DisplayName("two runs interleaved on one thread keep two trees")
    fun twoRunsInterleavedOnOneThreadKeepTwoTrees() {
        val observer = RecordingLiveSpanObserver()
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            runBlocking { interleaveTwoRuns(observer, dispatcher) }
        } finally {
            dispatcher.close()
        }

        assertDisjointTrees(observer)
    }

    /**
     * Two runs on MANY threads keep two trees.
     *
     * The same property under real parallelism, where the two runs may be on
     * different threads and the observer is written to from both. The
     * observer's list is copy-on-write precisely so that a row can read it
     * after two coroutines have written to it without the read racing the
     * write.
     *
     * The pool is sized here rather than taken from the machine, because the
     * row is about what the ENGINE does when its branches land on different
     * threads, and the number of cores the host happens to have is not part
     * of that question. Four threads are enough for two runs to be genuinely
     * apart, and enough for the run that waits to release its thread back.
     */
    @Test
    @DisplayName("two runs interleaved on many threads keep two trees")
    fun twoRunsInterleavedOnManyThreadsKeepTwoTrees() {
        val observer = RecordingLiveSpanObserver()
        val dispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        try {
            runBlocking { interleaveTwoRuns(observer, dispatcher) }
        } finally {
            dispatcher.close()
        }

        assertDisjointTrees(observer)
    }

    /**
     * Runs two runs at once, each one alive while the other opens its stage.
     *
     * The barriers are the whole point: without them the two coroutines would
     * interleave whenever the scheduler felt like it, and the row would pass
     * on an arrangement in which the two runs never overlapped at all.
     */
    private suspend fun CoroutineScope.interleaveTwoRuns(
        observer: RecordingLiveSpanObserver,
        dispatcher: CoroutineDispatcher,
    ) {
        val compiled = pipeline()
        val firstEngine = RunLifecycleEngine(NullEventSink, observer)
        val secondEngine = RunLifecycleEngine(NullEventSink, observer)
        val firstStageOpened = CompletableDeferred<Unit>()
        val secondStageOpened = CompletableDeferred<Unit>()

        val first = async(dispatcher) {
            val runSpan = openRunSpan(observer, "run-a")
            firstEngine.openRun(compiled, RunId("run-a"), runSpan)
            firstEngine.stageStarted(RunId("run-a"), 0, "build")
            firstStageOpened.complete(Unit)
            secondStageOpened.await()
            firstEngine.stageEnded(0, StageOutcome.Succeeded)
        }
        val second = async(dispatcher) {
            val runSpan = openRunSpan(observer, "run-b")
            secondEngine.openRun(compiled, RunId("run-b"), runSpan)
            secondStageOpened.complete(Unit)
            firstStageOpened.await()
            secondEngine.stageStarted(RunId("run-b"), 0, "build")
            secondEngine.stageEnded(0, StageOutcome.Succeeded)
        }

        first.await()
        second.await()
    }

    /**
     * Asserts that each run's stage hangs under that run's own span.
     *
     * The assertion is by RUN IDENTITY and not by position: the two runs are
     * indistinguishable in shape, and the only thing that tells their spans
     * apart is the durable identity they carry.
     */
    private fun assertDisjointTrees(observer: RecordingLiveSpanObserver) {
        val firstStage = observer.spanNamed(LiveSpanNames.STAGE, "run-a")
        val secondStage = observer.spanNamed(LiveSpanNames.STAGE, "run-b")

        assertSame(observer.spanNamed(LiveSpanNames.RUN, "run-a"), observer.recordedParentOf(firstStage))
        assertSame(observer.spanNamed(LiveSpanNames.RUN, "run-b"), observer.recordedParentOf(secondStage))
    }

    /** Opens the span of a run, under nothing: a run is the outside of itself. */
    private fun openRunSpan(observer: RecordingLiveSpanObserver, runId: String) = LiveSpans.open(
        telemetry = observer,
        name = LiveSpanNames.RUN,
        parent = LiveSpanParent.Root,
        attributes = listOf(LiveSpanAttributes.runId(runId)),
    )

    /**
     * The one span the observer recorded with this name for this run.
     *
     * A run identity is the only thing that tells the two runs' spans apart,
     * so it is what the lookup uses.
     */
    private fun RecordingLiveSpanObserver.spanNamed(name: String, runId: String): RecordedLiveSpan =
        opened.single { it.name == name && it.runId() == runId }

    /**
     * The recorded span that caused the work opened under [handle].
     *
     * The handle is what the boundary holds; the recorded span is what the
     * observer saw, and the two are the same object here because the fake
     * hands its own span back.
     */
    private fun RecordingLiveSpanObserver.parentOf(handle: LiveSpanHandle): RecordedLiveSpan? =
        (handle.span as? RecordedLiveSpan)?.let { recordedParentOf(it) }

    /** The durable run identity the span carries, when it carries one. */
    private fun RecordedLiveSpan.runId(): String? =
        (attributes.firstOrNull { it.key == LiveSpanAttributes.RUN_ID } as? TelemetryAttribute.Text)?.value

    /** The compiled pipeline the run is about; its content does not matter here. */
    private fun pipeline(): CompiledPipeline = CompiledPipeline(
        id = DefinitionId("definition"),
        source = SourceDescriptor("pipeline.kts", Digest("digest")),
        stages = emptyList(),
        pluginLockDigest = Digest("lock"),
    )
}
