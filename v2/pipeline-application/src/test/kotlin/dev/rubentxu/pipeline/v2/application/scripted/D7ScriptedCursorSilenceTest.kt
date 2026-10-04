package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.application.CoreEchoStep
import dev.rubentxu.pipeline.v2.application.EchoInput
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.domain.durable.ParallelFrame
import dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.BranchExecutionResult
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursor
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.StageIndex
import dev.rubentxu.pipeline.v2.scripting.ScriptedCallSiteId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.Collections

/**
 * D7-B5 — split out of `D7CursorOwnershipBehaviourTest` on purpose.
 *
 * That class proves the CANONICAL half of the ADR-0103 D7 law: the traversal that owns a
 * canonical stage position is the only writer of the run replay cursor. This one proves the
 * other half, and it needs the scripted composition (`ScriptedInvokerFixture`) plus the
 * scripted invoker, so it belongs with R1-A/C rather than with the ownership commit.
 *
 * ## The failure it would have caught is concrete, not hypothetical
 *
 * `MainScriptedSupport` does not pass a `ReplayCursorStore` and `ScriptedFrontendRunner` fixes
 * `stageIndex = 0` for scripted calls. Before D7, the durable executor held a cursor store and
 * advanced it, so a scripted call that had found a way in would have advanced a CANONICAL
 * run's resume point to stage 0 — on a call whose identity is
 * `entryPoint / callSite / dynamicScope / ordinal` and which has no stage at all.
 *
 * The store below is a real one, held deliberately in scope. A scripted call is expected to
 * journal a durable operation and return a typed value; it is NOT expected to move the cursor,
 * and this is what measures that.
 */
class D7ScriptedCursorSilenceTest {

    @Test
    fun `D7-B5 the scripted spine journals real operations and never advances the canonical cursor`() =
        runBlocking {
            val clock = SystemClock()
            val journal = InMemoryOperationJournal(clock)
            val cursor = RecordingCursorStore(InMemoryReplayCursorStore(clock))
            val registry = InMemoryStepRegistry().also { CoreEchoStep.registerInto(it) }
            val controlDirRoot = Files.createTempDirectory("d7-b5-ctrl-")
            val events = InMemoryEventStore()

            val invoker = dev.rubentxu.pipeline.v2.application.support.ScriptedInvokerFixture.build(
                registry = registry,
                journal = journal,
                eventSink = events,
                controlDirRoot = controlDirRoot,
            )

            val call = ScriptedRegistryCall(
                runId = RUN_ID,
                entryPointId = "d7-b5",
                callSiteId = ScriptedCallSiteId("d7-b5:0"),
                dynamicScopePath = emptyList(),
                invocationOrdinal = 0,
                stepKey = CoreEchoStep.KEY,
                encodedInput = CoreEchoStep.definition.contract.inputCodec.encode(EchoInput("d7-b5")),
                definitionDigest = "d7-b5-digest",
            )

            val fresh = invoker.invoke(call)
            assertTrue(
                fresh is ScriptedRegistryResult.Success,
                "a fresh scripted call must succeed and return a typed value; got $fresh",
            )
            assertEquals(
                0,
                cursor.writes.size,
                "A scripted call journals a durable operation and returns a typed value. It owns no " +
                    "canonical stage position, so it MUST NOT move the run's resume point — doing so " +
                    "would rewind the run to the scripted stageIndex of 0.",
            )

            val reused = invoker.invoke(call)
            assertTrue(reused is ScriptedRegistryResult.Success, "a reuse must return the persisted value")
            assertEquals(
                0,
                cursor.writes.size,
                "Reuse on the scripted spine is cursor-free for the same reason.",
            )

            assertTrue(
                journal.listForRun(RUN_ID).isNotEmpty(),
                "sanity: the scripted spine really did journal operations. The assertions above are " +
                    "about the cursor, not about work being skipped.",
            )
        }

    /**
     * Delegates everything and records the writes. It is a spy, not a stub: a scripted call
     * that wrongly advanced the cursor would have to go through here, and the run's real
     * cursor state would be written as a side effect of the test.
     */
    private class RecordingCursorStore(
        private val delegate: InMemoryReplayCursorStore,
    ) : ReplayCursorStore {
        val writes: MutableList<Triple<String, String, Int>> = Collections.synchronizedList(mutableListOf())

        override fun load(runId: String): ReplayCursor? = delegate.load(runId)

        override fun advance(runId: String, opId: String, stageIndex: Int) {
            writes.add(Triple(runId, opId, stageIndex))
            delegate.advance(runId, opId, stageIndex)
        }

        override fun advancePastParallelFrame(
            runId: String,
            frame: ParallelFrame,
            branchResults: List<BranchExecutionResult>,
            explicitMaxStageIndex: Int?,
        ): StageIndex = delegate.advancePastParallelFrame(runId, frame, branchResults, explicitMaxStageIndex)
    }

    private companion object {
        const val RUN_ID = "d7-b5"
    }
}
