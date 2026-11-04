package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.LiveSpanAttributes
import dev.rubentxu.pipeline.v2.runtime.LiveSpanExporter
import dev.rubentxu.pipeline.v2.runtime.LiveSpanNames
import dev.rubentxu.pipeline.v2.runtime.LiveSpanParent
import dev.rubentxu.pipeline.v2.runtime.LiveSpanStatus
import dev.rubentxu.pipeline.v2.runtime.LiveSpans
import dev.rubentxu.pipeline.v2.runtime.PipelineTelemetryFactory
import dev.rubentxu.pipeline.v2.runtime.parseTraceParent
import dev.rubentxu.pipeline.v2.runtime.sdkWith
import dev.rubentxu.pipeline.v2.runtime.telemetryAttachedTo
import dev.rubentxu.pipeline.v2.runtime.telemetryWith
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanId
import io.opentelemetry.api.trace.StatusCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * The live spans are a TREE, and this file observes that the tree is there.
 *
 * ## The guarantee
 *
 * A trace that reports a list of unrelated events is not a trace: a reader can
 * see that a step ran and that a run happened, and nothing about which step
 * belonged to which attempt, nor which run followed which operator's request.
 * The parent link is what turns the list into a tree, and this file holds the
 * tree honest:
 *
 * ```text
 * linked       a span that was caused by another span is EXPORTED under it —
 *             the link is in what the collector receives, not in what the
 *             product intended
 * rooted       a span with no cause is exported as a root, and says so
 * attached     a run an operator attached to an existing trace is exported
 *             inside that trace, and nowhere else
 * disposable   a discarded span reaches no collector at all
 * ```
 *
 * ## The capacity this serves
 *
 * An operator reading a trace answers "what caused this?" without reading the
 * run's logs. That question only has an answer when the parent is in the
 * exported bytes, so the property that matters is not "the product asked for
 * a parent" but "the collector received one". A fake observer can only ever
 * witness the first.
 *
 * ## Why this instrument and not the fake
 *
 * The rows below run the real adapter against the real SDK and read the spans
 * the SDK would have sent to a collector, with the exporter replaced by a
 * list. That is still cheap — an in-process exporter, no network, no collector,
 * no clock of its own — but it is the ONLY instrument that can fail when the
 * adapter drops the parent on the way to the wire. The fake observer
 * ([LiveSpanLedger]) stays the instrument for the boundaries that
 * open spans, because there the question is WHICH span was opened and not
 * what the collector received.
 *
 * ## Why the trace context is read from the environment
 *
 * The operator's context arrives as one string in the environment, and it is
 * the only channel this product reads it from: the operator controls that
 * channel and the pipeline content does not. A value that is not well formed
 * is not an error — the run still happens and simply starts its own trace —
 * but it is refused loudly, and WITHOUT being echoed, because a value an
 * operator typed is still a value this product does not print.
 */
class LiveSpanLineageTest {

    /**
     * A span that was caused by another span is exported UNDER it.
     *
     * This is the row the whole change exists for. Before it, every span the
     * engine opened was a root: the run, the stage and the step were
     * siblings, and the trace said nothing about which was which. The
     * assertion reads the exported bytes, because the defect lived in the
     * passage between the product's decision and the collector's inbox.
     */
    @Test
    @DisplayName("a child span is exported under the span that caused it")
    fun aChildSpanIsExportedUnderTheSpanThatCausedIt() {
        val exporter = LiveSpanExporter()
        val telemetry = telemetryWith(exporter)

        val run = requireNotNull(
            LiveSpans.open(
                telemetry = telemetry,
                name = LiveSpanNames.RUN,
                parent = LiveSpanParent.Root,
                attributes = listOf(LiveSpanAttributes.runId("run-1")),
            ),
        ) { "an enabled telemetry opens a span" }
        val stage = requireNotNull(
            LiveSpans.open(
                telemetry = telemetry,
                name = LiveSpanNames.STAGE,
                parent = LiveSpanParent.Local(run),
                attributes = listOf(LiveSpanAttributes.runId("run-1"), LiveSpanAttributes.stageIndex(0)),
            ),
        ) { "an enabled telemetry opens a span" }
        LiveSpans.end(stage, LiveSpanStatus.Completed)
        LiveSpans.end(run, LiveSpanStatus.Completed)

        val exportedRun = exporter.exported.single { it.name == LiveSpanNames.RUN }
        val exportedStage = exporter.exported.single { it.name == LiveSpanNames.STAGE }
        assertEquals(exportedRun.spanId, exportedStage.parentSpanId, "the stage hangs under the run")
        assertEquals(exportedRun.traceId, exportedStage.traceId, "the stage belongs to the run's trace")
    }

    /**
     * A span with no cause is exported as a root.
     *
     * The control row. Without it, the row above would also pass if the
     * adapter parented EVERY span under the same arbitrary span, and the
     * guarantee would be "spans have parents" rather than "spans have THE
     * parent that caused them". A root span says nothing caused it, and that
     * is what the outside of a run is.
     */
    @Test
    @DisplayName("a span with no parent is exported as a root")
    fun aSpanWithNoParentIsExportedAsARoot() {
        val exporter = LiveSpanExporter()
        val telemetry = telemetryWith(exporter)

        val run = requireNotNull(
            LiveSpans.open(
                telemetry = telemetry,
                name = LiveSpanNames.RUN,
                parent = LiveSpanParent.Root,
                attributes = listOf(LiveSpanAttributes.runId("run-1")),
            ),
        ) { "an enabled telemetry opens a span" }
        LiveSpans.end(run, LiveSpanStatus.Completed)

        val exported = exporter.exported.single()
        assertEquals(
            SpanId.getInvalid(),
            exported.parentSpanId,
            "a span with no cause carries the invalid parent in the exported bytes",
        )
    }

    /**
     * A run an operator attached to an existing trace is exported INSIDE it.
     *
     * The operator hands the process one string, and the run's outermost span
     * lands under the span that string names. The run does not become part of
     * the operator's trace by accident: it is the operator's decision, and the
     * decision is exercised here end to end, from the environment to the
     * exported bytes.
     */
    @Test
    @DisplayName("the operator's trace context parents the outermost span")
    fun theOperatorsTraceContextParentsTheOutermostSpan() {
        val exporter = LiveSpanExporter()
        val telemetry = telemetryAttachedTo(exporter, TRACE_PARENT)

        val run = requireNotNull(
            LiveSpans.open(
                telemetry = telemetry,
                name = LiveSpanNames.RUN,
                parent = telemetry.sessionParent,
                attributes = listOf(LiveSpanAttributes.runId("run-1")),
            ),
        ) { "an enabled telemetry opens a span" }
        LiveSpans.end(run, LiveSpanStatus.Completed)

        val exported = exporter.exported.single()
        assertEquals(TRACE_ID, exported.traceId, "the run belongs to the operator's trace")
        assertEquals(SPAN_ID, exported.parentSpanId, "the run hangs under the operator's span")
    }

    /**
     * The trace context is a LINK, and it never travels as data.
     *
     * The operator's identifiers decide WHICH trace the run belongs to. If
     * they also travelled as attributes, the run would carry them as facts
     * about itself — and a fact about a run is decided by the run, not by the
     * channel it arrived through. The row asserts that the only attribute the
     * exported span carries is the run's own identity.
     */
    @Test
    @DisplayName("the operator's trace context never travels as an attribute")
    fun theOperatorsTraceContextNeverTravelsAsAnAttribute() {
        val exporter = LiveSpanExporter()
        val telemetry = telemetryAttachedTo(exporter, TRACE_PARENT)

        val run = requireNotNull(
            LiveSpans.open(
                telemetry = telemetry,
                name = LiveSpanNames.RUN,
                parent = telemetry.sessionParent,
                attributes = listOf(LiveSpanAttributes.runId("run-1")),
            ),
        ) { "an enabled telemetry opens a span" }
        LiveSpans.end(run, LiveSpanStatus.Completed)

        val exported = exporter.exported.single()
        val carried = exported.attributes.asMap().keys.map { it.key }
        assertEquals(listOf(LiveSpanAttributes.RUN_ID), carried, "the only attribute is the run's own identity")
        assertTrue(
            exported.attributes.asMap().values.none { it.toString().contains(TRACE_ID) },
            "the operator's trace identifier is a link and not a payload",
        )
    }

    /**
     * A discarded span reaches NO collector.
     *
     * A discarded span says the work did NOT happen — it is how a replay
     * that skipped an effect avoids leaving behind a span claiming the effect
     * ran. If the discarded span were published anyway, the trace would carry
     * both claims and the reader would have to pick one. The absence is the
     * whole guarantee, so the row asserts the absence.
     */
    @Test
    @DisplayName("a discarded span never reaches the exporter")
    fun aDiscardedSpanNeverReachesTheExporter() {
        val exporter = LiveSpanExporter()
        val telemetry = telemetryWith(exporter)

        val run = requireNotNull(
            LiveSpans.open(
                telemetry = telemetry,
                name = LiveSpanNames.STEP_DISPATCH,
                parent = LiveSpanParent.Root,
                attributes = listOf(LiveSpanAttributes.runId("run-1")),
            ),
        ) { "an enabled telemetry opens a span" }
        LiveSpans.discard(run)

        assertTrue(exporter.exported.isEmpty(), "a discarded span is never exported")
    }

    /**
     * Two attempts of one run are TWO spans, tied only by the run's identity.
     *
     * A resumed run happens in another process, and the process that resumes
     * it cannot know the identifiers of the spans the first process opened.
     * What crosses the boundary is the durable run identity, and that is what
     * the row asserts: two traces, two span identifiers, no parent between
     * them, and the same run identity on both. Reusing an identifier across
     * processes would be a lie about causality, and a lie a collector cannot
     * detect.
     */
    @Test
    @DisplayName("two attempts of one run are two spans tied by the run identity")
    fun twoAttemptsOfOneRunAreTwoSpansTiedByTheRunIdentity() {
        val first = LiveSpanExporter()
        val second = LiveSpanExporter()

        val firstRun = requireNotNull(
            LiveSpans.open(
                telemetry = telemetryWith(first),
                name = LiveSpanNames.RUN,
                parent = LiveSpanParent.Root,
                attributes = listOf(LiveSpanAttributes.runId("run-7")),
            ),
        ) { "an enabled telemetry opens a span" }
        LiveSpans.end(firstRun, LiveSpanStatus.Completed)

        val secondRun = requireNotNull(
            LiveSpans.open(
                telemetry = telemetryWith(second),
                name = LiveSpanNames.RUN,
                parent = LiveSpanParent.Root,
                attributes = listOf(LiveSpanAttributes.runId("run-7")),
            ),
        ) { "an enabled telemetry opens a span" }
        LiveSpans.end(secondRun, LiveSpanStatus.Completed)

        val firstSpan = first.exported.single()
        val secondSpan = second.exported.single()
        assertNotEquals(firstSpan.traceId, secondSpan.traceId, "each process starts its own trace")
        assertNotEquals(firstSpan.spanId, secondSpan.spanId, "an attempt never reuses a span identifier")
        assertEquals(SpanId.getInvalid(), firstSpan.parentSpanId, "the first attempt is the root of its own trace")
        assertEquals(SpanId.getInvalid(), secondSpan.parentSpanId, "the resumed attempt is the root of its own trace")
        assertEquals(
            "run-7",
            firstSpan.attributes.asMap()[AttributeKey.stringKey(LiveSpanAttributes.RUN_ID)],
            "the first attempt carries the durable run identity",
        )
        assertEquals(
            "run-7",
            secondSpan.attributes.asMap()[AttributeKey.stringKey(LiveSpanAttributes.RUN_ID)],
            "the resumed attempt carries the same durable run identity",
        )
    }

    /**
     * A well formed trace context is accepted, and its case is normalised.
     *
     * The identifiers are opaque strings compared byte for byte, so a value
     * written in upper case and the same value written in lower case name the
     * SAME trace. A reader that kept the case would split one trace into two
     * and neither half would find the other.
     */
    @Test
    @DisplayName("a well formed trace context is accepted and its case is normalised")
    fun aWellFormedTraceContextIsAcceptedAndItsCaseIsNormalised() {
        val parsed = requireNotNull(parseTraceParent(TRACE_PARENT.uppercase())) {
            "a well formed trace context is accepted"
        }

        assertEquals(TRACE_ID, parsed.traceId, "the trace identifier is normalised to lower case")
        assertEquals(SPAN_ID, parsed.spanId, "the span identifier is normalised to lower case")
        assertTrue(parsed.sampled, "the sampled flag is read")
    }

    /**
     * A malformed trace context is refused, and refusal is not an error.
     *
     * The reader answers one question — is this string a trace context — and
     * answers it the same way every time. The row walks the partitions that
     * must all be refused: a value with too few fields, a reserved version,
     * a version that may not carry extra fields, identifiers of the wrong
     * shape, identifiers that say "nothing", and a value that is a payload
     * rather than a context. The last one matters most: a reader that accepted
     * an arbitrary string would put the operator's whole environment into the
     * trace's ancestry.
     */
    @Test
    @DisplayName("a malformed trace context is refused")
    fun aMalformedTraceContextIsRefused() {
        val refused = listOf(
            "not a trace context",
            "00-$TRACE_ID",
            "ff-$TRACE_ID-$SPAN_ID-01",
            "00-$TRACE_ID-$SPAN_ID-01-extra",
            "00-00000000000000000000000000000000-$SPAN_ID-01",
            "00-$TRACE_ID-0000000000000000-01",
            "00-$TRACE_ID-${SPAN_ID.drop(1)}-01",
            "00-$TRACE_ID-$SPAN_ID-0",
            "00-$TRACE_ID-$SPAN_ID-zz",
            "0-$TRACE_ID-$SPAN_ID-01",
            "x".repeat(300),
        )

        refused.forEach { raw ->
            assertNull(parseTraceParent(raw), "a malformed trace context is refused")
        }
    }

    /**
     * A malformed trace context is refused loudly, and WITHOUT being echoed.
     *
     * The operator deserves to know that the value they set was not used, and
     * deserves NOT to have it printed back: the value lives in an
     * environment, and an environment can hold anything. The row asserts both
     * halves — the warning exists, and the refused value is not in it.
     */
    @Test
    @DisplayName("a malformed trace context is refused with a warning that does not echo it")
    fun aMalformedTraceContextIsRefusedWithAWarningThatDoesNotEchoIt() {
        val warnings = mutableListOf<String>()
        val refused = "00-not-a-trace-$SPAN_ID-01"

        val telemetry = PipelineTelemetryFactory.create(
            environment = mapOf(
                PipelineTelemetryFactory.OTEL_ENABLED_ENV to "true",
                PipelineTelemetryFactory.OTEL_TRACEPARENT_ENV to refused,
            ),
            sdkFactory = { parent ->
                PipelineTelemetryFactory.sdkPipelineTelemetry(
                    sdkWith(LiveSpanExporter()),
                    sessionParent = parent,
                )
            },
            warning = { warnings += it },
        )

        assertTrue(telemetry.enabled, "a refused trace context does not disable the telemetry")
        assertEquals(1, warnings.size, "the operator is told that the value was refused")
        assertTrue(warnings.single().contains("not well formed"), "the warning says what happened")
        assertTrue(
            !warnings.single().contains(refused),
            "the warning does not echo the value the operator configured",
        )
    }

    /**
     * A trace context grants NO authorization.
     *
     * The value arrives through a channel the operator controls, and it says
     * which trace the run belongs to. It says nothing else: it is not a
     * credential, and its presence must not turn a refusal into an admission.
     * The row runs the product's own admission path with a well formed
     * context in place and an artifact whose bytes cannot be read, and asserts
     * the refusal stands.
     */
    @Test
    @DisplayName("a trace context grants no authorization")
    fun aTraceContextGrantsNoAuthorization() {
        val exporter = LiveSpanExporter()
        val telemetry = telemetryAttachedTo(exporter, TRACE_PARENT)

        val admission = withCapturedStandardError { err ->
            admitThenCompose(
                pluginJars = listOf("/nonexistent/pipelinek-live-span-lineage-test/plugin.jar"),
                trust = null,
                journal = null,
                telemetry = telemetry,
                err = err,
            )
        }

        assertTrue(
            admission is PluginAdmission.Refused,
            "an artifact whose bytes cannot be read is refused, whatever the trace context says",
        )
        val exported = exporter.exported.single { it.name == LiveSpanNames.ADMISSION }
        assertEquals(
            TRACE_ID,
            exported.traceId,
            "the refusal is reported inside the operator's trace, and refused all the same",
        )
    }

    /**
     * A refused admission is exported AS A REFUSAL.
     *
     * The product distinguishes an execution that ran and did not succeed from
     * an execution that was refused before it ran, and the collector has to
     * see the same distinction: a refusal carries the failure class
     * "rejected" and an error status, so a dashboard counting failures does
     * not count a refusal as a run that broke.
     */
    @Test
    @DisplayName("a refused admission is exported as a rejection and not as a success")
    fun aRefusedAdmissionIsExportedAsARejectionAndNotAsASuccess() {
        val exporter = LiveSpanExporter()
        val telemetry = telemetryWith(exporter)

        val admission = withCapturedStandardError { err ->
            admitThenCompose(
                pluginJars = listOf("/nonexistent/pipelinek-live-span-lineage-test/plugin.jar"),
                trust = null,
                journal = null,
                telemetry = telemetry,
                err = err,
            )
        }

        assertTrue(
            admission is PluginAdmission.Refused,
            "an artifact whose bytes cannot be read is refused",
        )
        val exported = exporter.exported.single { it.name == LiveSpanNames.ADMISSION }
        assertEquals(
            "rejected",
            exported.attributes.get(AttributeKey.stringKey(LiveSpanAttributes.FAILURE_CLASS)),
            "the exported span says the admission was rejected",
        )
        assertEquals(
            StatusCode.ERROR,
            exported.status.statusCode,
            "the exported span carries an error status, so the refusal is not read as a success",
        )
    }

    /**
     * Runs [block] with an error stream of its own, and hands that stream to the block.
     *
     * The admission boundary tells the operator why it refused, and that message belongs to the
     * operator — not to the test output. The row asserts on the exported span, and the message
     * goes to a stream the row owns so the build log says what the rows say.
     *
     * The stream is a PARAMETER, not a global this test replaces: a row that swapped the
     * process-wide stream would mutate the state every other row shares, and two rows running at
     * the same time would read each other's bytes. The bytes are the ones the CLI shows, because
     * the code under test is the code the CLI runs — only the sink differs.
     */
    private fun <T> withCapturedStandardError(block: (PrintStream) -> T): T {
        val captured = ByteArrayOutputStream()
        val err = PrintStream(captured, true, Charsets.UTF_8.name())
        val value = block(err)
        err.flush()
        return value
    }
}

/** The trace identifier the operator's context names. */
private const val TRACE_ID: String = "4bf92f3577b34da6a3ce929d0e0e4736"

/** The span identifier the operator's context names. */
private const val SPAN_ID: String = "00f067aa0ba902b7"

/** The context the operator hands to the process. */
private const val TRACE_PARENT: String = "00-$TRACE_ID-$SPAN_ID-01"
