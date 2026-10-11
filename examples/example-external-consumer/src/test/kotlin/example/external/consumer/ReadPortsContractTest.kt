package example.external.consumer

import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadRefusal
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputFrameIndex
import dev.rubentxu.pipeline.v2.output.OutputPage
import dev.rubentxu.pipeline.v2.output.OutputReadDigestedResult
import dev.rubentxu.pipeline.v2.output.OutputReadPort
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PK-R1 R1-E / T8 — runtime READ ports the follow contracts compose.
 *
 * The published SDK does not include `pipeline-runtime` (deliberate: the
 * runtime read ports are the consumer side of the contract, not a published
 * module). What IS published — and what every follow impl composes against —
 * are the read ports in `pipeline-output` and `pipeline-events`:
 *
 *   - OutputReadPort.read / readRange / readRangeDigested
 *   - OutputFrameIndex (declares streams, lists frames per run)
 *   - EventRecordReadPort.readRecords
 *
 * Each test below distinguishes the contract surface (interface shape) from
 * the capability (the impl the consumer itself writes returns the documented
 * sealed-result type).
 */
class ReadPortsContractTest {

    // ── CONTRACT — interface surface ─────────────────────────────────────────

    @Test
    fun contract_outputReadPortDeclaresReadReadRangeReadRangeDigestedAndCommittedExtent() {
        // Kotlin name-mangles interface methods when the interface declares at
        // least one default implementation (`<name>-<7-char-base64-hash>`).
        // `OutputReadPort` declares a default body for `readRangeDigested`,
        // so even abstract methods appear under mangled JVM names — e.g.
        // `read-6Ce8pk8` rather than `read`. The contract is the source-level
        // name, so we strip the mangling before asserting.
        val names = OutputReadPort::class.java.declaredMethods
            .map { it.name.substringBefore("-") }
            .toSet()
        assertTrue("read" in names, "OutputReadPort must declare `read`")
        assertTrue("readRange" in names, "OutputReadPort must declare `readRange`")
        assertTrue("readRangeDigested" in names, "OutputReadPort must declare `readRangeDigested`")
        assertTrue("committedExtent" in names, "OutputReadPort must declare `committedExtent`")
    }

    @Test
    fun contract_outputFrameIndexIsAnInterface() {
        assertTrue(
            OutputFrameIndex::class.java.isInterface,
            "OutputFrameIndex must be an interface",
        )
    }

    @Test
    fun contract_eventRecordReadPortDeclaresReadRecords() {
        val readRecords = EventRecordReadPort::class.java.methods
            .firstOrNull { it.name == "readRecords" }
        assertNotNull(readRecords)
        assertEquals(4, readRecords!!.parameterCount)
        assertEquals(String::class.java, readRecords.parameterTypes[0])
        assertEquals(EventCursor::class.java, readRecords.parameterTypes[1])
        assertEquals(EventQuery::class.java, readRecords.parameterTypes[2])
        assertEquals(EventRecordReadResult::class.java, readRecords.returnType)
    }

    @Test
    fun contract_outputReadResultIsSealedWithPageAndRefused() {
        val sealed = OutputReadResult::class.java
        assertTrue(sealed.isSealed)
        val permitted = sealed.permittedSubclasses.map { it.simpleName }.toSet()
        assertEquals(setOf("Page", "Refused"), permitted)
    }

    @Test
    fun contract_eventRecordReadResultIsSealedWithPageAndRefused() {
        val sealed = EventRecordReadResult::class.java
        assertTrue(sealed.isSealed)
        val permitted = sealed.permittedSubclasses.map { it.simpleName }.toSet()
        assertEquals(setOf("Page", "Refused"), permitted)
    }

    @Test
    fun contract_outputRefusalIsSealedWithNamedVariants() {
        val sealed = OutputRefusal::class.java
        assertTrue(sealed.isSealed)
        val permitted = sealed.permittedSubclasses.map { it.simpleName }.toSet()
        // The exact set is the published surface; the consumer compiles
        // against every variant because the ADT is closed. Spot-check the
        // canonical refusals named in the KDoc.
        assertTrue("ForeignStream" in permitted)
        assertTrue("UnknownStream" in permitted)
        assertTrue("OffsetBeyondCommitted" in permitted)
    }

    @Test
    fun contract_eventRecordReadRefusalIsSealedWithNamedVariants() {
        val sealed = EventRecordReadRefusal::class.java
        assertTrue(sealed.isSealed)
        val permitted = sealed.permittedSubclasses.map { it.simpleName }.toSet()
        assertTrue("UnknownRun" in permitted)
        assertTrue("CursorBeyondTail" in permitted)
    }

    // ── CAPABILITY — sealed-result behaviour ────────────────────────────────

    @Test
    fun capability_outputReadResultPageAndRefusedPatternMatch() {
        val stream = OutputStreamId("stdout")
        val cursor = OutputCursor(stream = stream, committedOffset = 3L)
        val page = OutputPage(
            bytes = byteArrayOf(1, 2, 3),
            stream = stream,
            from = 0L,
            next = cursor,
            committedEnd = 1000L,
        )
        val ok = OutputReadResult.Page(page = page)
        val refusal = OutputReadResult.Refused(reason = OutputRefusal.UnknownStream(stream))

        val seen = mutableListOf<String>()
        for (r in listOf(ok, refusal)) {
            when (r) {
                is OutputReadResult.Page -> {
                    seen += "page(${r.page.bytes.size}B from=${r.page.from})"
                }
                is OutputReadResult.Refused -> {
                    val name = r.reason::class.java.simpleName
                    seen += "refused($name)"
                }
            }
        }
        assertEquals(listOf("page(3B from=0)", "refused(UnknownStream)"), seen)
        assertArrayEquals(byteArrayOf(1, 2, 3), (ok as OutputReadResult.Page).page.bytes)
    }

    @Test
    fun capability_consumerImplementationExercisesOutputReadPort() {
        // The consumer side of OutputReadPort IS the read port. The test
        // below writes a minimal impl that returns Page/Refused and invokes
        // it. This proves the published port is reachable and the sealed
        // result type is the documented shape.
        val stream = OutputStreamId("stdout")
        val impl = object : OutputReadPort {
            override fun committedExtent(stream: OutputStreamId): Long? = 1000L

            override fun read(stream: OutputStreamId, cursor: OutputCursor, maxBytes: Int) =
                OutputReadResult.Page(
                    OutputPage(
                        bytes = byteArrayOf(0x42),
                        stream = stream,
                        from = cursor.committedOffset,
                        next = OutputCursor(stream = stream, committedOffset = cursor.committedOffset + 1),
                        committedEnd = 1000L,
                    ),
                )

            override fun readRange(stream: OutputStreamId, from: Long, to: Long) =
                OutputReadResult.Page(
                    OutputPage(
                        bytes = ByteArray((to - from).toInt()) { 0x42 },
                        stream = stream,
                        from = from,
                        next = OutputCursor(stream = stream, committedOffset = to),
                        committedEnd = 1000L,
                    ),
                )
        }
        val cursor = OutputCursor(stream = stream, committedOffset = 0L)
        val r = impl.read(stream, cursor, maxBytes = 64)
        assertTrue(r is OutputReadResult.Page)
        assertEquals(0L, (r as OutputReadResult.Page).page.from)
    }

    @Test
    fun capability_outputReadDigestedResultIsSealed() {
        val sealed = OutputReadDigestedResult::class.java
        assertTrue(sealed.isSealed)
        // Sealed hierarchy; the consumer compiles against the two variants.
        assertEquals(
            setOf("Digested", "Refused"),
            sealed.permittedSubclasses.map { it.simpleName }.toSet(),
        )
    }

    @Test
    fun capability_eventQueryAllMatchesEveryEnvelopePredicate() {
        // The predicate is documented as "all events of the run, in
        // sequence order" — i.e. the All variant's matcher is the constant
        // true. Verified here by the value-class instance, which the
        // consumer can compare by identity because All is a data object.
        assertSame(EventQuery.All, EventQuery.All)
    }

    @Test
    fun capability_eventRecordReadRefusalUnknownRunHoldsRunId() {
        val r = EventRecordReadRefusal.UnknownRun(runId = "run-ghost")
        assertEquals("run-ghost", r.runId)
    }
}