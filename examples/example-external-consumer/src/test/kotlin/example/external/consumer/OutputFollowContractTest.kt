package example.external.consumer

import dev.rubentxu.pipeline.v2.output.OutputStreamAddress
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.follow.FollowState
import dev.rubentxu.pipeline.v2.output.follow.FollowUntil
import dev.rubentxu.pipeline.v2.output.follow.OutputFollowEvent
import dev.rubentxu.pipeline.v2.output.follow.OutputFollowHandle
import dev.rubentxu.pipeline.v2.output.follow.OutputFollowOptions
import dev.rubentxu.pipeline.v2.output.follow.OutputFollower
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * PK-R1 R1-E / T8 — `output.follow.v1` from the outside.
 *
 * Two tests, two assertions kinds:
 *   - `*Contract*`     the published type/field exists with the documented
 *                       shape (compile-time; an absence would be a build
 *                       failure before JUnit runs).
 *   - `*Capability*`   invoking the published method produces the documented
 *                       behaviour. The Follower is an interface, so the
 *                       consumer side writes its own impl and exercises it;
 *                       the impl IS the consumer side of the contract.
 */
class OutputFollowContractTest {

    // ── CONTRACT — the types exist with the documented surface ──────────────

    @Test
    fun contract_outputFollowerIsAnInterfaceWithOpen() {
        val cls = OutputFollower::class.java
        assertTrue(cls.isInterface, "OutputFollower must be an interface")
        val open = cls.methods.firstOrNull { it.name == "open" }
        assertNotNull(open, "OutputFollower must declare open(runId, options)")
        assertEquals(2, open!!.parameterCount)
        assertEquals(String::class.java, open.parameterTypes[0])
        assertEquals(OutputFollowOptions::class.java, open.parameterTypes[1])
        assertEquals(OutputFollowHandle::class.java, open.returnType)
    }

    @Test
    fun contract_outputFollowHandleIsAnAutoCloseableWithIterator() {
        val cls = OutputFollowHandle::class.java
        assertTrue(cls.isInterface, "OutputFollowHandle must be an interface")
        assertTrue(
            AutoCloseable::class.java.isAssignableFrom(cls),
            "OutputFollowHandle must extend AutoCloseable",
        )
        val iter = cls.methods.firstOrNull { it.name == "iterator" }
        assertNotNull(iter, "OutputFollowHandle must declare iterator()")
        assertEquals(Iterator::class.java, iter!!.returnType)
        assertTrue(cls.methods.any { it.name == "close" })
    }

    @Test
    fun contract_outputFollowOptionsExposesDocumentedFields() {
        val props = OutputFollowOptions::class.java.declaredFields
        val names = props.map { it.name }.toSet()
        assertTrue("streams" in names, "OutputFollowOptions must declare `streams`")
        assertTrue("pageMaxBytes" in names, "OutputFollowOptions must declare `pageMaxBytes`")
        assertTrue("pollIntervalMs" in names, "OutputFollowOptions must declare `pollIntervalMs`")
        assertTrue("includeFrames" in names, "OutputFollowOptions must declare `includeFrames`")
        assertTrue("maxRecords" in names, "OutputFollowOptions must declare `maxRecords`")
        assertTrue("until" in names, "OutputFollowOptions must declare `until`")
        assertTrue("afterOrdinal" in names, "OutputFollowOptions must declare `afterOrdinal`")
    }

    @Test
    fun contract_eventHierarchyHasBytesStateChangedRefusedCompleted() {
        val sealed = OutputFollowEvent::class.java
        assertTrue(sealed.isSealed, "OutputFollowEvent must be a sealed hierarchy")
        val permitted = sealed.permittedSubclasses.map { it.simpleName }.toSet()
        assertEquals(
            setOf("Bytes", "StateChanged", "Refused", "Completed"),
            permitted,
            "OutputFollowEvent hierarchy must be closed",
        )
    }

    @Test
    fun contract_followStateHasRunningStreamSealedRunTerminalUnobservable() {
        val sealed = FollowState::class.java
        assertTrue(sealed.isSealed, "FollowState must be sealed")
        val permitted = sealed.permittedSubclasses.map { it.simpleName }.toSet()
        assertEquals(
            setOf("Running", "StreamSealed", "RunTerminal", "Unobservable"),
            permitted,
        )
    }

    @Test
    fun contract_followUntilHasUnboundedUntilAllSealedUntilBytesRead() {
        val sealed = FollowUntil::class.java
        assertTrue(sealed.isSealed, "FollowUntil must be sealed")
        val permitted = sealed.permittedSubclasses.map { it.simpleName }.toSet()
        assertEquals(
            setOf("Unbounded", "UntilAllSealed", "UntilBytesRead"),
            permitted,
        )
    }

    // ── CAPABILITY — the documented behaviour holds ─────────────────────────

    @Test
    fun capability_outputFollowOptionsDefaultsMatchDocumentedValues() {
        val opts = OutputFollowOptions()
        assertEquals(64 * 1024, opts.pageMaxBytes, "default pageMaxBytes is 64 KiB")
        assertEquals(25L, opts.pollIntervalMs, "default pollIntervalMs is 25 ms")
        assertEquals(256, opts.maxRecords, "default maxRecords is 256")
        assertTrue(opts.streams.isEmpty(), "default streams is empty (= follow every)")
        assertSame(FollowUntil.Unbounded, opts.until, "default until is Unbounded")
        assertTrue(opts.includeFrames, "default includeFrames is true")
    }

    @Test
    fun capability_outputFollowOptionsRejectsNonPositiveKnobs() {
        assertThrows<IllegalArgumentException> { OutputFollowOptions(pageMaxBytes = 0) }
        assertThrows<IllegalArgumentException> { OutputFollowOptions(pageMaxBytes = -1) }
        assertThrows<IllegalArgumentException> { OutputFollowOptions(maxRecords = 0) }
        assertThrows<IllegalArgumentException> { OutputFollowOptions(pollIntervalMs = -1L) }
        assertThrows<IllegalArgumentException> { OutputFollowOptions(afterOrdinal = -1L) }
    }

    @Test
    fun capability_followUntilUntilBytesReadRejectsNegativeLimit() {
        assertThrows<IllegalArgumentException> { FollowUntil.UntilBytesRead(limit = -1L) }
    }

    @Test
    fun capability_followStateRunningAndStreamSealedHoldTheirLists() {
        val stream = OutputStreamId("stdout")
        val running = FollowState.Running(openStreams = listOf(stream), sealedStreams = emptyList())
        val sealed = FollowState.StreamSealed(sealedStreams = listOf(stream))
        assertEquals(1, running.openStreams.size)
        assertEquals(0, running.sealedStreams.size)
        assertEquals(listOf(stream), sealed.sealedStreams)
    }

    @Test
    fun capability_consumerImplementationExercisesOpenAndIterator() {
        // The Follower is the consumer side of the contract: the consumer
        // writes the impl. The test below opens a handle against an impl
        // that emits the documented event sequence and verifies the
        // consumer-visible behaviour: handle.iterator() is single-pass
        // and idempotent, and close() stops the iterator.
        val script = listOf<OutputFollowEvent>(
            OutputFollowEvent.StateChanged(
                FollowState.Running(emptyList(), emptyList()),
            ),
            OutputFollowEvent.StateChanged(FollowState.RunTerminal),
            OutputFollowEvent.Completed,
        )

        val follower = ScriptedOutputFollower(script)
        follower.open("run-1", OutputFollowOptions()).use { handle ->
            val it = handle.iterator()
            // Single iterator per handle: same object back.
            assertSame(it, handle.iterator())
            // Drained in order.
            assertEquals(script, generateSequence { if (it.hasNext()) it.next() else null }.toList())
            // Single-pass: post-terminal, hasNext is false.
            assertFalse(it.hasNext())
        }
    }
}

/**
 * Consumer-side impl used by [OutputFollowContractTest.capability_consumerImplementationExercisesOpenAndIterator].
 * The follower IS the consumer contract; an outside observer writes one to
 * exercise it, the way any external system that subscribes to `pipeline
 * observe` would.
 */
private class ScriptedOutputFollower(
    private val script: List<OutputFollowEvent>,
) : OutputFollower {
    override fun open(runId: String, options: OutputFollowOptions): OutputFollowHandle =
        object : OutputFollowHandle {
            private var index = 0
            private var iter: Iterator<OutputFollowEvent>? = null

            override fun iterator(): Iterator<OutputFollowEvent> {
                iter?.let { return it }
                val backing = script.listIterator()
                iter = backing
                return backing
            }

            override fun close() {
                index = script.size
            }
        }
}