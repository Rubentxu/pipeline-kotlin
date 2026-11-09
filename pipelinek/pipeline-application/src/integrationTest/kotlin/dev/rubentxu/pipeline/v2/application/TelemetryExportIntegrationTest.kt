package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.runtime.telemetry.TELEMETRY_SCHEMA
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryTrace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.time.Instant

/**
 * The telemetry verb exports a run that already happened, and the export is
 * produced INSIDE the read of the journal: the run is read twice —once to
 * learn the cardinality cap and once to produce the signals— and the store
 * is admitted only while the export is being written.
 *
 * These rows drive the verb itself, against a real store, because that is
 * where the two facts meet. A verb that exported outside the read would
 * find the journal closed, and a verb that wrote to the journal would
 * change the run it exports.
 */
class TelemetryExportIntegrationTest {

    @TempDir
    lateinit var root: Path

    /** The durable store the product writes to, and the verb reads from. */
    private lateinit var db: Path

    private val runId = "run-telemetry-1"

    /** The moment the run happened, so the export carries real timestamps. */
    private val epoch = 1_700_000_000_000L

    @BeforeEach
    fun material() {
        db = root.resolve("store.db")
        val admission = admitEventStore(db.toString(), EventStorePurpose.Produce)
        val store = (admission as EventStoreAdmission.Admitted).store
        // The sequence is the STORE's to assign: a sequence written here
        // would be a second authority over the order of the run's history.
        store.append(
            RunStarted(
                eventId = "event-1",
                runId = runId,
                sequence = 0L,
                occurredAt = Instant.ofEpochMilli(epoch),
                scriptPath = root.resolve("pipeline.kts").toString(),
            ),
        )
        store.append(
            StageStarted(
                eventId = "event-2",
                runId = runId,
                sequence = 0L,
                occurredAt = Instant.ofEpochMilli(epoch + 10),
                stageIndex = 0,
                stageName = "build",
            ),
        )
        store.append(
            StepStarted(
                eventId = "event-3",
                runId = runId,
                sequence = 0L,
                occurredAt = Instant.ofEpochMilli(epoch + 20),
                stageIndex = 0,
                stepIndex = 0,
                stepName = "build",
                stepType = "core.sh",
            ),
        )
        store.append(
            StepFinished(
                eventId = "event-4",
                runId = runId,
                sequence = 0L,
                occurredAt = Instant.ofEpochMilli(epoch + 30),
                stageIndex = 0,
                stepIndex = 0,
                stepName = "build",
                stepType = "core.sh",
                outcome = "success",
            ),
        )
        store.append(
            StageFinished(
                eventId = "event-5",
                runId = runId,
                sequence = 0L,
                occurredAt = Instant.ofEpochMilli(epoch + 40),
                stageIndex = 0,
                stageName = "build",
                outcome = "success",
            ),
        )
        // The run FAILED: the export has to say so, and the verb has to say
        // it without changing anything the run did.
        store.append(
            RunFinished(
                eventId = "event-6",
                runId = runId,
                sequence = 0L,
                occurredAt = Instant.ofEpochMilli(epoch + 50),
                outcome = "failure",
                diagnostics = emptyList(),
            ),
        )
        // The writer is asynchronous: a reader that opened the store now
        // would find a run whose last event had not landed yet.
        store.flush()
        store.close()
    }

    @Test
    fun `the telemetry verb exports the run it reads`() {
        val answer = ask("inspect", runId, "--db", db.toString(), "--telemetry")

        assertEquals(0, answer.exit.code, "the telemetry verb failed: ${answer.stderr}")
        val lines = answer.stdout.trim().lines()
        assertTrue(
            lines.first().contains("\"schema\":\"$TELEMETRY_SCHEMA\""),
            "the export does not say which schema it speaks: ${lines.first()}",
        )
        assertTrue(
            lines.any { it.contains("\"traceId\":\"${TelemetryTrace.traceIdOf(runId)}\"") },
            "the export does not name the trace it belongs to",
        )
        assertTrue(
            lines.any { it.contains("\"kind\":\"span_started\"") },
            "the export carries no signals: the run opened a span",
        )
    }

    @Test
    fun `the otlp shape of the telemetry verb writes the two standard documents`() {
        val answer = ask("inspect", runId, "--db", db.toString(), "--telemetry", "--otlp")

        assertEquals(0, answer.exit.code, "the telemetry verb failed: ${answer.stderr}")
        val documents = answer.stdout.trim().lines().map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(
            2,
            documents.size,
            "an export OTLP son dos documentos estándar: uno de trazas y otro de métricas",
        )
        assertTrue(documents[0].containsKey("resourceSpans"))
        assertTrue(documents[1].containsKey("resourceMetrics"))
    }

    @Test
    fun `the telemetry verb does not change the run it exports`() {
        val before = eventsOfTheRun()

        val answer = ask("inspect", runId, "--db", db.toString(), "--telemetry")

        assertEquals(0, answer.exit.code, "the telemetry verb failed: ${answer.stderr}")
        assertEquals(
            before,
            eventsOfTheRun(),
            "el export es una LECTURA: el diario que lee es el diario que deja",
        )
        assertTrue(
            answer.stdout.contains("\"status\":\"failure\""),
            "el export dice como termino el run: un run que fallo no se exporta como un run que no fallo",
        )
    }

    /**
     * The events the store holds for this run, in the store's own order.
     */
    private fun eventsOfTheRun(): List<DomainEvent> {
        val admission = admitEventStore(db.toString(), EventStorePurpose.Read)
        val store = (admission as EventStoreAdmission.Admitted).store
        store.use {
            val events = mutableListOf<DomainEvent>()
            var page = it.readSlice(runId, after = null, limit = 256)
            while (true) {
                events.addAll(page.events)
                if (!page.hasMore) break
                page = it.readSlice(runId, page.nextCursor, 256)
            }
            return events
        }
    }

    /**
     * The answer of a verb, and what it printed.
     */
    private data class Answer(val exit: CliExit, val stdout: String, val stderr: String)

    /**
     * Runs the read-only dispatcher over [words] and reads its answer.
     *
     * The dispatcher is the product's own door, so the answer is the answer
     * the operator would get, and the process is not ended by a test.
     */
    private fun ask(vararg words: String): Answer {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val out = PrintStream(outBytes, true, Charsets.UTF_8.name())
        val err = PrintStream(errBytes, true, Charsets.UTF_8.name())

        val outcome = handleInventoryVerb(Array(words.size) { words[it] }, out, err)
        out.flush()
        err.flush()

        val answered = outcome as? InventoryVerbOutcome.Answered
            ?: error("the verb did not answer at all: $outcome")
        return Answer(answered.exit, outBytes.toString(Charsets.UTF_8), errBytes.toString(Charsets.UTF_8))
    }
}
