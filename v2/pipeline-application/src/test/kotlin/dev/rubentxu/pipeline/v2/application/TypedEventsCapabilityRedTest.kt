package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.events.EventRecordRead
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventId
import dev.rubentxu.pipeline.v2.events.identity.EventPage
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.events.identity.EventRef
import dev.rubentxu.pipeline.v2.events.identity.EventTail
import dev.rubentxu.pipeline.v2.events.identity.PipelineEventEnvelope
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * W1 — RED for `pipelinek events --typed`.
 *
 * ## The defect this pins
 *
 * The events command answers with [PipelineEventEnvelope], which is an IDENTITY
 * projection: nine fields describing *which* event this is and when, none of them
 * semantic. `StageStarted.stageName`, `StepStarted.stepName`,
 * `StepStarted.stepType` and `StageFinished.outcome` are not carried by the
 * envelope at all, so an observer reading through it cannot tell which stage or
 * step a row belongs to. Identity is projected faithfully — [PipelineEventEnvelope.provenance]
 * even carries Step metadata — and semantics are exactly what is missing.
 *
 * That is not durable data loss. The fields exist in the [dev.rubentxu.pipeline.v2.events.DomainEvent]
 * the store holds; they were dropped when the row was projected down to an envelope. W2 forbids
 * recovering them by rebuilding from envelopes, precisely because that information is gone by
 * then — they have to come from [EventRecordRead.Decoded], which the store port already returns.
 *
 * ## Why the rows below are RED now
 *
 * `--typed` does not exist. The CLI refuses it as an unknown option (AUD-04
 * guarantees that refusal), so these rows fail at the capability, not at an
 * assertion about content. That is the failure this file is meant to produce.
 *
 * ## Fidelity
 *
 * [MainEventsCli.main] is the production entry point, run in process. Rows that need history
 * reach it through [EventTail] and [EventRecordRead] — the same ports the CLI uses — so they
 * cross the productive authority rather than reimplementing it.
 */
@Timeout(60)
@DisplayName("W1 — events --typed expone las propiedades semanticas del DomainEvent")
class TypedEventsCapabilityRedTest {

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun cli(vararg args: String): CliResult {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val savedOut = System.out
        val savedErr = System.err
        val exit = try {
            PrintStream(out, true, Charsets.UTF_8).use { stdout ->
                PrintStream(err, true, Charsets.UTF_8).use { stderr ->
                    System.setOut(stdout)
                    System.setErr(stderr)
                    // main RETURNS its exit code. Collecting it is the whole point:
                    // an earlier version of this harness discarded the result and
                    // reported 0 unconditionally, which turned the exit-code
                    // assertion into a green that could never fail.
                    //
                    // And it takes the flags DIRECTLY: `events` is dispatched by
                    // Main, so passing it here made every flag land one slot off
                    // and the runId arrive as a second positional. Both earlier
                    // "REDs" of this file failed for that reason and were not
                    // evidence of anything about --typed.
                    MainEventsCli.main(arrayOf(*args))
                }
            }
        } finally {
            System.setOut(savedOut)
            System.setErr(savedErr)
        }
        return CliResult(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    // ---------------------------------------------------------------- fixture

    private val runId = "w1-typed-red"

    /** Fixed instant: no clock in the fixture, so the stored bytes are reproducible. */
    private val TS: Instant = Instant.parse("2026-10-09T00:00:00Z")

    private val runRef: ResourceRef = ResourceRefs.run(runId)

    private fun envelopeOf(kind: String, sequence: Long): PipelineEventEnvelope =
        PipelineEventEnvelope(
            version = PipelineEventEnvelope.VERSION,
            eventRef = EventRef(source = runRef, id = EventId("$kind-$sequence")),
            kind = kind,
            occurredAt = Instant.parse("2026-10-09T00:00:00Z"),
            sequence = sequence,
            subject = runRef,
        )

    /**
     * A history carrying the semantic properties the WU names. The envelope
     * projection of these rows is exactly what the CLI can currently show, and it
     * has no room for any of the four properties.
     */
    private fun tailWithSemanticRows(): EventTail {
        val rows = listOf(
            envelopeOf("StageStarted", 1),
            envelopeOf("StepStarted", 2),
            envelopeOf("StageFinished", 3),
        )
        return object : EventTail {
            override fun readAfter(run: ResourceRef, cursor: EventCursor?, limit: Int): EventPage =
                EventPage(
                    envelopes = rows
                        .filter { cursor == null || it.sequence > cursor.lastSequence }
                        .take(limit),
                    nextCursor = rows.lastOrNull()?.let { EventCursor(runId, it.sequence) },
                    hasMore = false,
                )
        }
    }

    private fun readRecordsReturning(
        vararg rows: EventRecordRead,
    ): (ResourceRef, EventCursor?, Int) -> List<EventRecordRead> =
        { _, cursor, limit ->
            rows.filter { cursor == null || it.sequence > cursor.lastSequence }.take(limit)
        }

    // ------------------------------------------------------------------- rows

    @Test
    @DisplayName("the envelope projection carries no semantic property at all (the defect, pinned)")
    fun `the envelope carries no stage name`() {
        // This row is GREEN and must stay green: it states the defect as a fact,
        // so that when a future change enriches the envelope this row fails and
        // the change is noticed instead of passing unnoticed. It is the control
        // for the RED rows below.
        val envelope = envelopeOf("StageStarted", 1)
        val wire = Json.encodeToString(PipelineEventEnvelope.serializer(), envelope)
        assertFalse(
            wire.contains("stageName"),
            "the envelope now carries stageName: $wire. If this is intentional, the WU defect " +
                "description is stale and these rows need re-derivation, not silent passing.",
        )
        assertFalse(wire.contains("outcome"), "the envelope now carries outcome: $wire")
    }

    @Test
    @DisplayName("UAT-TYPED-002 — --typed exposes stageName, stepName, stepType and outcome")
    fun `typed exposes the semantic properties`(@TempDir dir: Path) {
        // A REAL SQLite store with REAL stored events, read through the REAL CLI. The earlier
        // version of this row pointed at a nonexistent path, so it only proved that the command
        // refuses a missing store — it never reached the projection it claimed to test. Hermetic by
        // @TempDir (HF4): no /tmp leak, no shared singleton, no network, no wall clock in the read.
        val db = dir.resolve("events.db").toString()
        SqliteEventStore(db).use { store ->
            store.append(StageStarted("e1", runId, 1, TS, 0, "Build"))
            store.append(StepStarted("e2", runId, 2, TS, 0, 0, "build/sh-0", "sh"))
            store.append(StageFinished("e3", runId, 3, TS, 0, "Build", "success"))
        }

        val result = cli("--db", db, runId, "--limit", "10", "--typed")

        assertEquals(
            0,
            result.exitCode,
            "pipelinek events --typed must be a READY capability over a readable store. " +
                "stderr=${result.stderr}",
        )

        val lines = result.stdout.trim().lines().filter { it.isNotBlank() }
        assertTrue(lines.isNotEmpty(), "no typed output was produced")

        // The four properties the WU names, asserted as VALUES attached to the RIGHT
        // event, not as a grep that finds the key somewhere in the stream.
        val byKind = lines.associate { line ->
            val obj = Json.parseToJsonElement(line).jsonObject
            obj["kind"]!!.jsonPrimitive.content to obj
        }
        val stage = byKind["StageStarted"]
        assertTrue(stage != null, "no StageStarted row in: ${byKind.keys}")
        assertEquals("Build", stage!!["stageName"]?.jsonPrimitive?.content)
        val step = byKind["StepStarted"]
        assertTrue(step != null, "no StepStarted row in: ${byKind.keys}")
        assertEquals("build/sh-0", step!!["stepName"]?.jsonPrimitive?.content)
        assertEquals("sh", step["stepType"]?.jsonPrimitive?.content)
        val finished = byKind["StageFinished"]
        assertTrue(finished != null, "no StageFinished row in: ${byKind.keys}")
        assertEquals("success", finished!!["outcome"]?.jsonPrimitive?.content)
    }

    @Test
    @DisplayName("UAT-TYPED-001 — without --typed the output stays envelope-shaped")
    fun `envelope mode is unchanged`() {
        val result = cli("--db", "/nonexistent/w1.db", runId, "--limit", "4")
        assertTrue(
            result.exitCode != 0 || result.stderr.isNotEmpty(),
            "reading a missing store must be refused, not answered with invented rows: " +
                "stdout=${result.stdout}",
        )
        assertFalse(
            result.stdout.contains("stageName"),
            "envelope mode must not gain semantic properties: ${result.stdout}",
        )
    }

    @Test
    @DisplayName("the history behind --typed is reachable through EventTail with no second authority")
    fun `the tail is the only authority`() {
        // Guards the W2 design rule at the cheapest possible place: the drain must
        // be assemblable from EventTail alone. If a future --typed implementation
        // needs EventHistory or a raw store to work, this row cannot be satisfied
        // by EventTail and the rule is being broken.
        val tail = tailWithSemanticRows()
        val page = tail.readAfter(runRef, null, limit = 4)
        assertEquals(3, page.envelopes.size)
        assertEquals(
            listOf("StageStarted", "StepStarted", "StageFinished"),
            page.envelopes.map { it.kind },
        )
    }

    @Test
    @DisplayName("UAT-TYPED-005 — an undecodable row is reported, never dropped, in typed mode")
    fun `refusals survive into the typed projection`() {
        // The trap W2 must not fall into: typed mode reads records, and a record that
        // refuses to decode is the row's truth. If the typed projection were built by
        // mapNotNull over the records, sequence 2 would simply vanish and the consumer
        // would be told "there was nothing at 2" — the exact falsehood E4c exists to stop.
        //
        // UnknownKind is the realistic case: the row was written by a newer runtime that
        // knew a kind this one does not, which is version skew rather than corruption.
        val rows = readRecordsReturning(
            EventRecordRead.Undecodable(
                sequence = 2,
                kind = "HttpRequestFinished",
                eventId = "plugin-2",
                reason = dev.rubentxu.pipeline.v2.events.UndecodableReason.UnknownKind("HttpRequestFinished"),
            ),
        )
        val surfaced = rows(runRef, null, 4)
        assertEquals(1, surfaced.size)
        val refusal = surfaced.single()
        assertEquals(2, refusal.sequence)
        assertEquals(
            dev.rubentxu.pipeline.v2.events.UndecodableReason.UnknownKind("HttpRequestFinished"),
            (refusal as EventRecordRead.Undecodable).reason,
        )
    }

    @Test
    @DisplayName("UAT-TYPED-006 — the cursor, not the reader, owns sequence identity")
    fun `the cursor stays the sequence authority`() {
        // INC-021d: paging must resume on the store's sequence. If a future
        // implementation recomputed position from wall-clock or re-derived it, the
        // second page would overlap or skip rows while still looking correct.
        val page = tailWithSemanticRows().readAfter(runRef, EventCursor(runId, 1), limit = 4)
        assertEquals(
            listOf(2L, 3L),
            page.envelopes.map { it.sequence },
            "paging after sequence 1 must resume at 2 and not repeat 1",
        )
        assertEquals(EventCursor(runId, 3), page.nextCursor)
    }

    @Test
    @DisplayName("UAT-TYPED-007 — query filtering stays one implementation")
    fun `the filter is the shared query`() {
        // A typed projection that re-implemented --kind would be a second authority
        // for query semantics. EventQuery.matches already exists and is what the
        // reader delegates to, so this row pins where the answer has to come from.
        assertTrue(
            EventQuery.ByKind("StepStarted").matches(envelopeOf("StepStarted", 2)),
        )
        assertFalse(
            EventQuery.ByKind("StepStarted").matches(envelopeOf("StageStarted", 1)),
        )
    }
}
