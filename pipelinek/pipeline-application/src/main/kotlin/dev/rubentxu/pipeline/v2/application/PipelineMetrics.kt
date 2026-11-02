package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryPolicy
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetrySignal
import dev.rubentxu.pipeline.v2.runtime.telemetry.redactAndLimit

/**
 * The vocabulary of the LIVE metrics: the boundaries the engine measures, the
 * classes it measures them by, and how an outcome becomes a class.
 *
 * ## WHY A VOCABULARY AND NOT A STRING AT EVERY CALL SITE
 *
 * A metric label is a CONTRACT with whoever reads the metric. Written as a
 * literal at five call sites it becomes five spellings of the same fact, and
 * the day one of them is renamed the metric silently splits in two. The names
 * live here, once, and the boundaries refer to them.
 *
 * ## WHY THE DIMENSIONS ARE THE PUBLISHED ONES
 *
 * The three dimension keys are NOT invented here. They are the keys the
 * published identity table already names — `operation_kind`, `status` and
 * `failure_class` — because that table is the allowlist the published
 * redaction rule enforces. A key this file invented would be dropped by the
 * redaction on the way out, and the metric would silently lose its fact.
 *
 * ## WHY NO METRIC CARRIES AN IDENTITY
 *
 * A metric is AGGREGATED. A label that carries a run id, an operation id, a
 * path, a step name or a worker id does not aggregate: it produces one series
 * per run, and the collector grows until it cannot answer a question. So
 * every value in this file is a word from a closed set, and the sets are
 * small enough that the number of distinct values a key can ever carry is
 * known before the first run happens — which is what "bounded cardinality"
 * means when it is a fact rather than a promise.
 *
 * ## WHY THE CLASSES SAY WHO FAILED
 *
 * The product distinguishes five things a reader of a metric must not confuse:
 * the pipeline's own content failed, the admission refused before anything
 * ran, the machinery that runs the effect broke, somebody stopped the work,
 * and the telemetry itself could not leave. Collapsing them into one
 * "failure" word makes every one of them look like the same event, and an
 * operator cannot act on a class that does not say what to do.
 *
 * ## WHY THERE ARE NO JVM OR GARBAGE-COLLECTOR METRICS
 *
 * The instrument has to answer a question somebody actually asks. A batch
 * command is a short-lived process: how much memory it used before it exited
 * is not a question an operator asks of it, and the machinery to answer it
 * would be another library in the distribution, carrying its own licence and
 * its own behaviour, for a number nobody reads. So the metrics here are the
 * ones about the WORK — how long each boundary took, how it ended, and how
 * much of the telemetry did not survive the trip out.
 */
internal enum class MetricBoundary(val operationKind: String) {
    /** The compilation of the run's script. */
    Compile("compile"),

    /** The admission of one step: the typed preparation that decided to run it or to refuse it. */
    StepPreparation("step.preparation"),

    /** The dispatch of one step: everything between the decision to run it and its result. */
    StepDispatch("step.dispatch"),

    /** One attempt of a retrying body. */
    RetryAttempt("retry.attempt"),

    /** The recovery of an operation whose terminal state was not observed. */
    Recovery("recovery"),

    /** The export of the telemetry itself. */
    TelemetryExport("telemetry.export"),

    /**
     * One observation a plugin made of its own work.
     *
     * It is a boundary of the same kind as the others: something that happened
     * once, that took a time, and that ended in a class. The plugin's own name
     * is NOT a dimension — it is the instrument's name — so the dimension this
     * boundary contributes keeps its place in the product's own bounded set.
     */
    PluginExecute("plugin.execute"),
}

/**
 * How a measured boundary ended.
 *
 * The words are the product's own coarse classes, and they are deliberately
 * finer than the run's own outcome: a run that failed because one step was
 * refused is not the same event as a run that failed because the script was
 * wrong, and a metric that merges them answers neither question.
 */
internal enum class MetricStatus(val value: String) {
    /** The work ran and succeeded. */
    Success("success"),

    /** The work ran and finished with a warning. */
    Unstable("unstable"),

    /** The work ran and failed because of what the pipeline asked for. */
    ProductFailure("failure"),

    /** The work was refused before it ran. */
    AdmissionRefused("refused"),

    /** The work failed for a reason outside the pipeline: the machinery broke. */
    WorkerError("worker_error"),

    /** The work was cancelled. */
    Cancelled("cancelled"),

    /** The telemetry could not leave the process. */
    ExportError("export_error"),

    /** The outcome could not be classified. */
    Unknown("unknown"),
}

/**
 * The reason an export was lost.
 *
 * Each reason names a step of the way out, so a reader knows WHERE the
 * telemetry died and not merely THAT it died. A loss has no duration, which
 * is exactly why it is counted on its own instead of riding on the histogram
 * of a boundary that did finish.
 */
internal enum class MetricExportLoss(val value: String) {
    /** The spans did not leave. */
    TracerFlush("export.tracer_flush"),

    /** The metrics did not leave. */
    MeterFlush("export.meter_flush"),

    /** The logs did not leave. */
    LoggerFlush("export.logger_flush"),

    /** The SDK did not shut down cleanly. */
    Shutdown("export.shutdown"),
}

/**
 * The class of a failure, drawn from the closed sets this product already owns.
 *
 * There is no third source: a failure is either the product's own (and the
 * product already names its kinds) or a loss on the way out (and the way out
 * has a fixed number of steps). A class that could be written freely would
 * be a label that grows one value per distinct message.
 */
internal sealed interface MetricFailureClass {
    /** The word this class travels as. */
    val value: String

    /** A failure of the pipeline's own content, or of the effect it asked for. */
    data class Product(val kind: FailureKind) : MetricFailureClass {
        override val value: String get() = kind.name
    }

    /** A loss on the way out: the telemetry could not be exported. */
    data class Export(val reason: MetricExportLoss) : MetricFailureClass {
        override val value: String get() = reason.value
    }
}

/**
 * How a measured boundary ended, and what class of failure it carried.
 *
 * The class is absent when there was no failure: a refusal and a cancellation
 * are not failures, and a metric that counted them as one would make the
 * number of failures larger than the number of things that failed.
 */
internal data class MetricOutcome(
    val status: MetricStatus,
    val failureClass: MetricFailureClass? = null,
)

/**
 * The seam the engine measures through.
 *
 * ## WHY A PORT
 *
 * The domain and the DSL must not know that OpenTelemetry exists, and the
 * engine's boundaries must not build a metric by hand: the metric names, the
 * dimension keys and the redaction of the values are one vocabulary, and this
 * port is where that vocabulary is applied.
 *
 * ## WHY THE PORT KNOWS WHETHER IT IS ENABLED
 *
 * A disabled runtime must cost NOTHING: no measurement, no clock reading, no
 * allocation, no call into an exporter. The only way to guarantee that is for
 * the boundary to be able to ask before it builds anything, so [enabled] is
 * part of the port and the first thing every boundary checks.
 */
internal interface PipelineMetrics {
    /** Whether this runtime produces metrics at all. */
    val enabled: Boolean

    /**
     * Opens a measurement of [boundary], or returns null when there is nothing to measure.
     */
    fun start(boundary: MetricBoundary): MetricHandle?

    /**
     * Records that an export was lost.
     *
     * The loss is counted with the same dimensions as any other measurement,
     * so a reader sees it in the same place as the boundaries whose telemetry
     * it was — and never as a silent gap.
     */
    fun exportLoss(reason: MetricExportLoss)
}

/**
 * What an implementation does with a measurement.
 *
 * The two endings are different on purpose: [end] counts the work and how it
 * ended, [discard] counts nothing at all.
 */
internal interface MetricMeasurement {
    /** Ends the measurement with [outcome], so it becomes countable. */
    fun end(outcome: MetricOutcome)

    /** Abandons the measurement: the work it described did NOT happen here. */
    fun discard()
}

/**
 * A measurement in flight.
 *
 * ## WHY THE HANDLE EXISTS AND NOT THE MEASUREMENT ITSELF
 *
 * Two things can end a measurement — finishing it and abandoning it — and a
 * boundary has to be able to say which. A raw measurement could be ended
 * twice by two different branches of the same call, and the second end would
 * overwrite the first outcome. The handle owns that state: the first ending
 * wins and the second is a no-op, so a boundary that ends a measurement on two
 * paths cannot produce two outcomes.
 */
internal class MetricHandle internal constructor(private val measurement: MetricMeasurement) {
    private var closed: Boolean = false

    /** Whether this measurement has already been ended or discarded. */
    val isClosed: Boolean get() = closed

    /** Ends the measurement with [outcome]. A measurement already ended is left alone. */
    fun end(outcome: MetricOutcome) {
        if (closed) return
        closed = true
        runCatching { measurement.end(outcome) }
    }

    /**
     * Abandons the measurement: the work it described did NOT happen.
     *
     * Nothing is counted for it. A replay that reused a terminal state must
     * not leave a measurement saying the effect ran here.
     */
    fun discard() {
        if (closed) return
        closed = true
        runCatching { measurement.discard() }
    }
}

/**
 * The metrics that produce NOTHING.
 *
 * It is the default for a run that did not opt in, and it is what a telemetry
 * that does not measure hands to its boundaries. A boundary never has to ask
 * whether there is an observer: it asks [PipelineMetrics.enabled], and a
 * disabled one answers before anything is built.
 */
internal object NoopPipelineMetrics : PipelineMetrics {
    override val enabled: Boolean = false

    override fun start(boundary: MetricBoundary): MetricHandle? = null

    override fun exportLoss(reason: MetricExportLoss) = Unit
}

/**
 * The dimension keys the live metrics carry.
 *
 * They are the keys the published identity table names, spelled once here. The
 * list is closed: a key that is not in it does not travel, and the published
 * redaction rule enforces that on the way out.
 */
internal object MetricDimensions {
    /** What was measured. */
    const val OPERATION_KIND: String = "operation_kind"

    /** How it ended. */
    const val STATUS: String = "status"

    /** The class of the failure, when there was one. */
    const val FAILURE_CLASS: String = "failure_class"
}

/**
 * The dimensions of a measurement of [boundary] that ended in [outcome].
 *
 * The dimensions are built from the closed vocabulary and then handed to the
 * published redaction rule, so a key that the published table does not name
 * cannot travel even if a future caller invents one.
 */
internal fun metricDimensions(boundary: MetricBoundary, outcome: MetricOutcome): Map<String, String> {
    val raw = buildMap {
        put(MetricDimensions.OPERATION_KIND, boundary.operationKind)
        put(MetricDimensions.STATUS, outcome.status.value)
        outcome.failureClass?.let { put(MetricDimensions.FAILURE_CLASS, it.value) }
    }
    return redactMetricDimensions(raw)
}

/**
 * The dimensions after the published redaction rule has had its say.
 *
 * The probe borrows the shape of a counter so the published rule applies its
 * whole rule — the key allowlist and the text budget — and not a part of it.
 * The probe never leaves this process: it exists to be reduced by the
 * published rule and dropped.
 */
internal fun redactMetricDimensions(dimensions: Map<String, String>): Map<String, String> {
    if (dimensions.isEmpty()) return emptyMap()
    val probe = TelemetrySignal.Counter(
        name = METRIC_REDACTION_PROBE,
        value = 1L,
        dimensions = dimensions,
    )
    val redacted = runCatching { redactAndLimit(probe, TelemetryPolicy.Default) }.getOrNull()
    return (redacted as? TelemetrySignal.Counter)?.dimensions.orEmpty()
}

/**
 * The name the redaction probe carries.
 *
 * The probe never leaves this process, so its name is a constant and says so.
 */
private const val METRIC_REDACTION_PROBE: String = "pipelinek.live.metric-redaction-probe"

/**
 * The metric outcome of a step that ended in [outcome].
 */
internal fun metricOutcomeOf(outcome: StepOutcome): MetricOutcome = when (outcome) {
    is StepOutcome.Success -> MetricOutcome(MetricStatus.Success)
    is StepOutcome.Unstable -> MetricOutcome(MetricStatus.Unstable)
    is StepOutcome.Failure -> metricOutcomeOf(outcome.failure)
}

/**
 * The metric outcome of a run that ended in [outcome].
 *
 * An aborted run is a cancellation, not a failure: somebody stopped it, and
 * counting it as a failure would put a stop in the same bucket as a bug.
 */
internal fun metricOutcomeOf(outcome: RunOutcome): MetricOutcome = when (outcome) {
    is RunOutcome.Success -> MetricOutcome(MetricStatus.Success)
    is RunOutcome.Unstable -> MetricOutcome(MetricStatus.Unstable)
    is RunOutcome.Failure -> metricOutcomeOf(outcome.failure)
    is RunOutcome.Aborted -> MetricOutcome(MetricStatus.Cancelled)
}

/**
 * The metric outcome of a body invocation that ended in [outcome].
 *
 * A cancelled body carries no failure class: nothing failed, the work was
 * stopped. The product's own journal says what the cancellation became, and
 * this says what was observed here.
 */
internal fun metricOutcomeOf(outcome: BodyOutcome): MetricOutcome = when (outcome) {
    is BodyOutcome.Completed -> metricOutcomeOf(outcome.outcome)
    is BodyOutcome.Cancelled -> MetricOutcome(MetricStatus.Cancelled)
}

/**
 * The metric outcome of a failure.
 *
 * The class is the product's own failure kind, spelled once, and the status
 * says whether the pipeline or the machinery was at fault.
 */
internal fun metricOutcomeOf(failure: PipelineFailure): MetricOutcome =
    MetricOutcome(statusOf(failure.kind), MetricFailureClass.Product(failure.kind))

/**
 * The class of a failure kind.
 *
 * ## WHY THE SPLIT IS WHERE IT IS
 *
 * The split answers one operator question: can the run be fixed by changing
 * the pipeline, or did the machinery that runs it break?
 *
 * - the content failed: the command's own exit, the input, the shape of the
 *   payload, a replay this runtime cannot perform, or a plugin that could not
 *   honour its own contract. The pipeline is what has to change.
 * - the machinery failed: the infrastructure, the network, the clock, or an
 *   invariant of the engine itself. The pipeline may be perfectly correct and
 *   the run still failed.
 *
 * The two are kept apart because a dashboard that merges them sends the
 * operator to the wrong place: every content failure looks like an
 * infrastructure problem.
 */
private fun statusOf(kind: FailureKind): MetricStatus = when (kind) {
    FailureKind.SCRIPT, FailureKind.USER, FailureKind.SCHEMA,
    FailureKind.REPLAY_COMPATIBILITY, FailureKind.PLUGIN,
    -> MetricStatus.ProductFailure

    FailureKind.INFRASTRUCTURE, FailureKind.NETWORK, FailureKind.TIMEOUT, FailureKind.ENGINE,
    -> MetricStatus.WorkerError

    FailureKind.UNKNOWN -> MetricStatus.Unknown
}
