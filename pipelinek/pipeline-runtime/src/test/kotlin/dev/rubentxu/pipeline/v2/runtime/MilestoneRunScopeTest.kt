package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityAccess
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The milestone, and the run its ordinal belongs to.
 *
 * ## The guarantee under trial
 *
 * That a milestone observes the ordinals of ITS OWN run: an ordinal that is
 * not strictly greater than the one already reached aborts the mark, records
 * the fact and leaves the state where it was, and the ordinal of one run is
 * not visible in another.
 *
 * ## Why the guarantee is worth measuring
 *
 * Because the name of the step and its KDoc promised a coordination BETWEEN
 * BUILDS: in Jenkins a milestone is the mark a later build waits for, and the
 * ordinal of the mark is a fact of the history of the pipeline. Here the mark
 * is a fact of the run: the state lives in a carrier the coordinator creates
 * for the run, and nothing in this product reads the milestone of a build
 * that is not running.
 *
 * The row therefore measures three facts in one place: the mark advances only
 * forward, an advance that does not advance leaves the state where it was,
 * and the state of a run is not the state of another run.
 *
 * ## What this file does NOT affirm
 *
 * It does not affirm that an aborted milestone is harmless to the run: the
 * step ends UNSTABLE, and a reader of the run has to see that. It affirms
 * that the abort is RECORDED and that it does not move the state.
 */
class MilestoneRunScopeTest {

    @Test
    fun `the milestone observes the ordinals of its own run and nothing else`() {
        val sink = RecordingEventSink()
        val firstRun = MilestoneOperationsAdapter(MilestoneStateStore())
        val context = context(sink, firstRun)

        val reached = runBlocking {
            CoreMilestoneStep.definition.handler.execute(
                MilestoneInput(ordinal = 1, label = "one"),
                context,
            )
        }

        assertInstanceOf(
            MilestoneStatus.Reached::class.java,
            reached.status,
            "the first ordinal of the run was not reached: the mark has to advance forward",
        )
        assertEquals(StepOutcome.Success, reached.outcome)

        val aborted = runBlocking {
            CoreMilestoneStep.definition.handler.execute(
                MilestoneInput(ordinal = 1, label = "one again"),
                context,
            )
        }

        assertInstanceOf(
            MilestoneStatus.Aborted::class.java,
            aborted.status,
            "an ordinal that is not strictly greater did not abort the mark: the mark would " +
                "accept an ordinal the run already reached",
        )
        assertEquals(
            StepOutcome.Unstable,
            aborted.outcome,
            "an aborted mark does not end the run: it is recorded and the step ends UNSTABLE",
        )
        assertEquals(
            1,
            firstRun.peek(),
            "the aborted advance moved the state: the mark has to stay where it was",
        )
        assertEquals(
            listOf("MilestoneReached", "MilestoneAborted"),
            sink.events.map { event -> event::class.simpleName },
            "the run keeps the record of the mark: the events it published are " +
                "${sink.events.map { event -> event::class.simpleName }}",
        )

        // The other run: the ordinal of the first one is not visible here.
        val otherRun = MilestoneOperationsAdapter(MilestoneStateStore())
        assertNull(
            otherRun.peek(),
            "the ordinal of a run is visible in another run: the mark would then be a mark of " +
                "the BUILDS, which is what Jenkins does and what this product does not do",
        )
    }

    // --- the fixtures ------------------------------------------------------

    /**
     * The context the handler receives: the run it writes to and the two
     * capabilities it declared.
     */
    private fun context(sink: EventSink, operations: MilestoneOperations): StepHandlerContext =
        StepHandlerContext(
            runId = RunId("run-1"),
            stepIndex = 0,
            capabilities = Capabilities(sink, operations),
        )

    /**
     * The capabilities the handler declared, and nothing else.
     *
     * The handler asks for the sink and for the milestone operations, each
     * through its own key, and the seam answers with the value of the declared
     * type.
     */
    private class Capabilities(
        private val sink: EventSink,
        private val operations: MilestoneOperations,
    ) : StepCapabilityAccess {

        override fun available(): Set<StepCapability> =
            setOf(EVENT_SINK_CAPABILITY, MILESTONE_OPERATIONS_CAPABILITY)

        override fun <T : Any> get(key: StepCapability, type: KClass<T>): T {
            val value: Any = when (key) {
                EVENT_SINK_CAPABILITY -> sink
                MILESTONE_OPERATIONS_CAPABILITY -> operations
                else -> throw IllegalArgumentException("capability not bound to this invocation: $key")
            }
            require(type.isInstance(value)) {
                "capability $key is not of the declared type: expected ${type.simpleName}, " +
                    "found ${value::class.simpleName}"
            }
            return type.java.cast(value)
        }
    }

    /** An event sink that keeps whatever it is given. */
    private class RecordingEventSink : EventSink {

        val events: MutableList<DomainEvent> = mutableListOf()

        override fun append(event: DomainEvent) {
            events += event
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> = events.asSequence()
    }
}
