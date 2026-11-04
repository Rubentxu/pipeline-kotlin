package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.post.StageOutcome
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryAttribute
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryPolicy
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetrySignal
import dev.rubentxu.pipeline.v2.runtime.telemetry.redactAndLimit

/**
 * The vocabulary of the LIVE spans: the names the engine gives them, the
 * attributes they may carry, and how an outcome becomes a status.
 *
 * ## WHY A VOCABULARY AND NOT A STRING AT EVERY CALL SITE
 *
 * A span name is a CONTRACT with whoever reads the trace. Written as a literal
 * at ten call sites it becomes ten spellings of the same fact, and the day
 * one of them is renamed the trace silently splits in two. The names live
 * here, once, and the boundaries refer to them.
 *
 * ## WHY THE ATTRIBUTES ARE THE SHIPPED ONES
 *
 * The attribute keys are NOT invented here. They are the keys the published
 * identity table already names — `run.id`, `stage.index` and `step.index` —
 * because that table is the allowlist the published redaction rule enforces.
 * A key this file invented would be dropped by the redaction on the way out,
 * and the trace would silently lose the fact.
 *
 * How a span ENDED is not an attribute: it is the span's status, which the
 * exporter already carries, and a second copy of the same fact in an
 * attribute would be a second place for the two to disagree.
 *
 * ## WHY NOTHING HERE CARRIES PAYLOAD
 *
 * No attribute in this file is a command, an argument, a path, a stdout
 * fragment or a secret. The values are indices, an outcome word and a run
 * id: the shape of a run, never its content. That is not a style choice, it
 * is the whole point of the redaction rule the live path delegates to.
 */
object LiveSpanNames {
    /** The whole run. */
    const val RUN: String = "pipelinek.run"

    /** The admission of the run's plugin artifacts. */
    const val ADMISSION: String = "pipelinek.admission"

    /** The compilation of the run's script. */
    const val COMPILE: String = "pipelinek.compile"

    /** One stage of the pipeline. */
    const val STAGE: String = "pipelinek.stage"

    /** The dispatch of one step: everything between the decision to run it and its result. */
    const val STEP_DISPATCH: String = "pipelinek.step.dispatch"

    /** The invocation of one plugin step: the effect itself. */
    const val STEP_INVOKE: String = "pipelinek.step.invoke"

    /** One attempt of a retrying body. */
    const val RETRY_ATTEMPT: String = "pipelinek.retry.attempt"

    /** A waitUntil block and its polls. */
    const val WAIT_UNTIL: String = "pipelinek.waituntil"

    /** The recovery of an operation whose terminal state was not observed. */
    const val RECOVERY: String = "pipelinek.recovery"

    /** The release of the run's output plane. */
    const val RELEASE: String = "pipelinek.release"
}

/**
 * The attributes a live span may carry.
 *
 * ## WHY THE BUILDERS AND NOT THE RAW TYPES
 *
 * Each builder names the key AND the shape of the value, so a boundary says
 * `stageIndex(2)` and never `TelemetryAttribute.Count("stage.index", 2)`. A
 * boundary that could pick its own key could pick one the allowlist does not
 * name, and the fact would disappear at export with no error anywhere.
 */
object LiveSpanAttributes {
    /** The durable identity of the run. */
    const val RUN_ID: String = "run.id"

    /** The index of the stage inside the pipeline. */
    const val STAGE_INDEX: String = "stage.index"

    /** The index of the step inside the stage. */
    const val STEP_INDEX: String = "step.index"

    /** The coarse class of a failure. */
    const val FAILURE_CLASS: String = "failure.class"

    /** The run this span belongs to. */
    fun runId(value: String): TelemetryAttribute = TelemetryAttribute.Text(RUN_ID, value)

    /** The stage this span belongs to. */
    fun stageIndex(value: Int): TelemetryAttribute = TelemetryAttribute.Count(STAGE_INDEX, value.toLong())

    /** The step this span belongs to. */
    fun stepIndex(value: Int): TelemetryAttribute = TelemetryAttribute.Count(STEP_INDEX, value.toLong())
}

/**
 * How a live span ended.
 *
 * ## WHY A REFUSAL IS NOT A FAILURE
 *
 * The product distinguishes two things that a status code alone would merge:
 * an execution that RAN and did not succeed, and an execution that was
 * REFUSED before it ran. A refused plugin admission is not a failed run: no
 * run happened. Reporting it as a failure would tell an operator that their
 * pipeline broke when in fact the pipeline never started.
 *
 * The three shapes are closed and carry no payload: the failure class is a
 * word from this file, never a message from the run.
 */
enum class LiveSpanStatus(val failureClass: String?) {
    /** The work ran and succeeded. */
    Completed(null),

    /** The work ran and did not succeed. */
    Failed("failed"),

    /** The work was refused and never ran. */
    Rejected("rejected"),
}

/**
 * The status of a run that ended in [outcome].
 *
 * An aborted run is not a failure either: it is a run somebody stopped. It
 * ends the span as [LiveSpanStatus.Failed] because the span says the RUN did
 * not complete successfully, and the class word keeps the distinction
 * readable in a trace.
 */
fun liveStatusOfRunOutcome(outcome: RunOutcome): LiveSpanStatus = when (outcome) {
    is RunOutcome.Success -> LiveSpanStatus.Completed
    is RunOutcome.Unstable -> LiveSpanStatus.Failed
    is RunOutcome.Failure -> LiveSpanStatus.Failed
    is RunOutcome.Aborted -> LiveSpanStatus.Failed
}

/** The status of a step that ended in [outcome]. */
fun liveStatusOfStepOutcome(outcome: StepOutcome): LiveSpanStatus = when (outcome) {
    is StepOutcome.Success -> LiveSpanStatus.Completed
    is StepOutcome.Unstable -> LiveSpanStatus.Failed
    is StepOutcome.Failure -> LiveSpanStatus.Failed
}

/** The status of a stage that ended in [outcome]. */
internal fun liveStatusOfStageOutcome(outcome: StageOutcome): LiveSpanStatus = when (outcome) {
    is StageOutcome.Succeeded -> LiveSpanStatus.Completed
    is StageOutcome.Unstable -> LiveSpanStatus.Failed
    is StageOutcome.Failed -> LiveSpanStatus.Failed
    is StageOutcome.Aborted -> LiveSpanStatus.Failed
    is StageOutcome.Skipped -> LiveSpanStatus.Completed
}

/**
 * A live span in flight.
 *
 * ## WHY THE HANDLE EXISTS AND NOT THE SPAN ITSELF
 *
 * Two things can end a span — finishing it and discarding it — and a boundary
 * has to be able to say which. A raw span could be ended twice by two
 * different branches of the same call, and the second end would overwrite the
 * first outcome. The handle owns that state: the first ending wins and the
 * second is a no-op, so a boundary that ends a span on two paths cannot
 * produce two outcomes.
 */
class LiveSpanHandle internal constructor(val span: PipelineSpan) {
    private var closed: Boolean = false

    /** Whether this span has already been ended or discarded. */
    val isClosed: Boolean get() = closed

    /** Ends the span with [status]. A span already ended or discarded is left alone. */
    fun end(status: LiveSpanStatus) {
        if (closed) return
        closed = true
        runCatching { span.end(status) }
    }

    /**
     * Discards the span: the work it described did NOT happen.
     *
     * The span is never ended, so it is never exported. That is the whole
     * point: a replay that skipped an effect must not leave behind a span
     * that says the effect ran.
     */
    fun discard() {
        if (closed) return
        closed = true
        runCatching { span.discard() }
    }
}

/**
 * The ONE place a live span is opened, and the ONE place its attributes are
 * redacted.
 *
 * ## WHY THE REDACTION IS DELEGATED, NOT REWRITTEN
 *
 * The published `redactAndLimit` is the product's redaction authority. It
 * already knows which attribute keys may travel, how long a text may be and
 * how many attributes a signal may carry. A live path that filtered its
 * attributes with a second rule would be a second authority — and two
 * authorities disagree, usually by accident, and the difference is a secret
 * in a trace.
 *
 * So the live path asks the published rule, through a probe signal shaped
 * exactly like a started span. The probe is thrown away; only the surviving
 * attributes travel. What an exporter sees is what the published rule
 * admitted, which is the property the redaction exists to provide.
 *
 * ## WHY A FAILURE HERE CANNOT FAIL A RUN
 *
 * A telemetry that cannot be constructed, or a redactor that throws, must
 * not change what the pipeline does. Every entry point here is wrapped, and
 * a failure yields no span rather than an exception. The run's result is the
 * run's result, whatever the trace says.
 */
object LiveSpans {
    /**
     * Opens a span under [parent], or returns null when there is nothing to open.
     *
     * A null telemetry and a disabled telemetry both mean the same thing —
     * no span — and both cost nothing: no redaction probe, no allocation, no
     * call into the exporter. That is what "disabled adds no I/O" means in
     * practice, and it is why the check is first rather than last.
     *
     * The parent is REQUIRED and has no default. A default would be a
     * silent invitation to open a root span by accident, and a root span is
     * a span that claims nothing caused it — which is exactly the kind of
     * claim that is wrong when the span actually has a parent.
     */
    fun open(
        telemetry: PipelineTelemetry?,
        name: String,
        parent: LiveSpanParent,
        attributes: List<TelemetryAttribute> = emptyList(),
    ): LiveSpanHandle? {
        val port = telemetry ?: return null
        if (!port.enabled) return null
        return runCatching {
            LiveSpanHandle(port.startSpan(name, redactLiveSpanAttributes(attributes), parent))
        }.getOrNull()
    }

    /** Ends [handle] with [status], if there is a handle. */
    fun end(handle: LiveSpanHandle?, status: LiveSpanStatus) {
        handle?.end(status)
    }

    /** Discards [handle], if there is a handle. */
    fun discard(handle: LiveSpanHandle?) {
        handle?.discard()
    }
}

/**
 * The attributes a live span may carry, after the published redaction rule
 * has had its say.
 *
 * The probe borrows the shape of a started span so the published rule
 * applies its whole rule — the key allowlist, the text budget and the
 * attribute budget — and not a part of it.
 */
internal fun redactLiveSpanAttributes(attributes: List<TelemetryAttribute>): List<TelemetryAttribute> {
    if (attributes.isEmpty()) return emptyList()
    val probe = TelemetrySignal.SpanStarted(
        name = LIVE_SPAN_REDACTION_PROBE,
        spanId = LIVE_SPAN_REDACTION_PROBE,
        parentSpanId = null,
        startedAtMs = 0L,
        attributes = attributes,
    )
    val redacted = runCatching { redactAndLimit(probe, TelemetryPolicy.Default) }.getOrNull()
    return (redacted as? TelemetrySignal.SpanStarted)?.attributes.orEmpty()
}

/**
 * The name the redaction probe carries.
 *
 * The probe never leaves this process — it exists to be reduced by the
 * published rule and dropped — so its name is a constant and says so.
 */
private const val LIVE_SPAN_REDACTION_PROBE: String = "pipelinek.live.redaction-probe"
