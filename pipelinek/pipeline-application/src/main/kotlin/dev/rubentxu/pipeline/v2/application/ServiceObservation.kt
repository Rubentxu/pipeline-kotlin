package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.runtime.LiveSpanAttributes
import dev.rubentxu.pipeline.v2.runtime.LiveSpanNames
import dev.rubentxu.pipeline.v2.runtime.LiveSpanStatus
import dev.rubentxu.pipeline.v2.runtime.LiveSpans
import dev.rubentxu.pipeline.v2.runtime.MetricBoundary
import dev.rubentxu.pipeline.v2.runtime.MetricFailureClass
import dev.rubentxu.pipeline.v2.runtime.MetricOutcome
import dev.rubentxu.pipeline.v2.runtime.MetricStatus
import dev.rubentxu.pipeline.v2.runtime.PipelineTelemetry
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import dev.rubentxu.pipeline.v2.service.ServiceRequest
import dev.rubentxu.pipeline.v2.service.ServiceResponse

/**
 * What the service measures about the requests it serves.
 *
 * ## What it measures and what it leaves to the Event Plane
 *
 * The service measures what a CLIENT pays: how long a question took, how it
 * ended, and how long a subscription lived. Everything that happened to a run
 * is a fact of the run and it is already in the Event Plane; a metric that
 * restated it would be a second copy that can disagree with the first. So a
 * measurement here carries a boundary word and an outcome word, and at most
 * the identity of the run the request was about.
 *
 * ## What never travels
 *
 * - the credential of the client, and the digest of it: the observer is never
 *   handed either;
 * - the operation id of an input question, the idempotency key, the script,
 *   the workspace and the configuration of a start: the observer is handed a
 *   run id and a kind of request, and nothing else of the request;
 * - the identity of a request that was REFUSED: a client that is not
 *   authorized chooses the text of the run it names, and that text is not
 *   a fact the service vouches for.
 *
 * The run id is an attribute of the SPAN and never a dimension of a metric:
 * a metric is aggregated, and a dimension that carried the run would produce
 * one series per run.
 *
 * ## Why a port
 *
 * The server must not know that OpenTelemetry exists, and a service that is
 * not observed must cost nothing: [NoServiceObserver] hands back an
 * observation that does nothing. An observer never fails a request: whatever
 * it does, the answer the client reads is the answer the operations gave.
 */
internal interface ServiceObserver {

    /** Opens the observation of one request of [kind], about the run [runId] when there is one. */
    fun begin(kind: ServiceRequestKind, runId: String?): ServiceObservation
}

/** One request in flight. Ending it more than once leaves the first ending in place. */
internal fun interface ServiceObservation {

    /** Ends the observation with [outcome]. */
    fun end(outcome: ServiceOutcome)
}

/** The observer of a service that is not observed. */
internal object NoServiceObserver : ServiceObserver {
    private val nothing = ServiceObservation { }

    override fun begin(kind: ServiceRequestKind, runId: String?): ServiceObservation = nothing
}

/**
 * The kinds of request the service serves.
 *
 * They are the three the service already tells apart because they are served
 * differently: a question is answered in the pool of the questions, a change
 * in the pool of the changes, and a subscription holds its connection.
 */
internal enum class ServiceRequestKind(val boundary: MetricBoundary, val spanName: String) {
    /** A read-only question: one line in, one line out. */
    Query(MetricBoundary.ServiceQuery, LiveSpanNames.SERVICE_QUERY),

    /** A request that changes something: a start, a cancellation or an answer to a question. */
    Mutation(MetricBoundary.ServiceMutation, LiveSpanNames.SERVICE_MUTATION),

    /** A subscription: its connection stays open while its street writes. */
    Subscription(MetricBoundary.ServiceSubscription, LiveSpanNames.SERVICE_SUBSCRIPTION),
}

/**
 * How a request ended, in the coarse classes the service can tell apart.
 *
 * The classes say WHO is to act: a refusal asks the client to change what it
 * asked, a fault asks the operator to look at the service, and an abandoned
 * request asks nobody — it ended without its answer being delivered, because
 * the client went away or the service was stopping.
 */
internal sealed interface ServiceOutcome {

    /** The request was answered. */
    data object Answered : ServiceOutcome

    /** The request was refused by a rule of the service: the client asked for what it may not have. */
    data object Refused : ServiceOutcome

    /** The service could not do what it was asked because the machinery broke. */
    data class Faulted(val kind: FailureKind) : ServiceOutcome

    /** The request ended without its answer being delivered. */
    data object Abandoned : ServiceOutcome
}

/**
 * The outcome of a request whose answer was [response].
 *
 * A refusal is not a fault, with two exceptions: the substrate that failed
 * and the substrate that contradicted itself. Those two are about the
 * machinery and not about what the client asked, and a metric that counted
 * them as refusals would hide the one kind of refusal an operator must act on.
 */
internal fun serviceOutcomeOf(response: ServiceResponse): ServiceOutcome = when (response) {
    is ServiceResponse.Answered -> ServiceOutcome.Answered
    is ServiceResponse.Refused -> when (response.refusal) {
        is ServiceRefusal.StorageFailed -> ServiceOutcome.Faulted(FailureKind.INFRASTRUCTURE)
        is ServiceRefusal.Inconsistent -> ServiceOutcome.Faulted(FailureKind.ENGINE)
        else -> ServiceOutcome.Refused
    }
}

/** The kind of [request]: the three that change something are mutations, every other one is a question. */
internal fun kindOf(request: ServiceRequest): ServiceRequestKind = when (request) {
    is ServiceRequest.StartRun, is ServiceRequest.CancelRun, is ServiceRequest.AnswerInput ->
        ServiceRequestKind.Mutation

    is ServiceRequest.Version, is ServiceRequest.Health, is ServiceRequest.DescribeParameters,
    is ServiceRequest.RunState, is ServiceRequest.ListInputs,
    -> ServiceRequestKind.Query
}

/** The run [request] is about, or null when it is about the service. */
internal fun runOf(request: ServiceRequest): String? = when (request) {
    is ServiceRequest.RunState -> request.runId
    is ServiceRequest.CancelRun -> request.runId
    is ServiceRequest.ListInputs -> request.runId
    is ServiceRequest.AnswerInput -> request.runId
    is ServiceRequest.StartRun, is ServiceRequest.Version, is ServiceRequest.Health,
    is ServiceRequest.DescribeParameters,
    -> null
}

/** The metric outcome of [outcome]. */
internal fun metricOutcomeOf(outcome: ServiceOutcome): MetricOutcome = when (outcome) {
    is ServiceOutcome.Answered -> MetricOutcome(MetricStatus.Success)
    is ServiceOutcome.Refused -> MetricOutcome(MetricStatus.AdmissionRefused)
    is ServiceOutcome.Faulted ->
        MetricOutcome(MetricStatus.WorkerError, MetricFailureClass.Product(outcome.kind))

    is ServiceOutcome.Abandoned -> MetricOutcome(MetricStatus.Cancelled)
}

/** The status a span ends with when its request ended in [outcome]. */
internal fun spanStatusOf(outcome: ServiceOutcome): LiveSpanStatus = when (outcome) {
    is ServiceOutcome.Answered -> LiveSpanStatus.Completed
    is ServiceOutcome.Refused -> LiveSpanStatus.Rejected
    is ServiceOutcome.Faulted, is ServiceOutcome.Abandoned -> LiveSpanStatus.Failed
}

/**
 * The observer that publishes through the telemetry the process was composed with.
 *
 * A request opens ONE span and ONE measurement, and ends both with the same
 * outcome. A disabled telemetry answers [PipelineTelemetry.enabled] before
 * anything is built, so an operator who did not opt in pays for one boolean.
 *
 * The span hangs from the session parent of the telemetry, as the outermost
 * spans of the engine do: a service is not a step of a run, and a span that
 * claimed a run as its parent would say a request is part of that run's work.
 * The run it is ABOUT is the `run.id` attribute, which the published redaction
 * rule bounds like any other.
 */
internal class TelemetryServiceObserver(private val telemetry: PipelineTelemetry) : ServiceObserver {

    override fun begin(kind: ServiceRequestKind, runId: String?): ServiceObservation {
        if (!telemetry.enabled) return NoServiceObserver.begin(kind, runId)
        return runCatching { open(kind, runId) }.getOrElse { NoServiceObserver.begin(kind, runId) }
    }

    private fun open(kind: ServiceRequestKind, runId: String?): ServiceObservation {
        val metric = telemetry.metrics.start(kind.boundary)
        val span = LiveSpans.open(
            telemetry = telemetry,
            name = kind.spanName,
            parent = telemetry.sessionParent,
            attributes = listOfNotNull(runId?.let(LiveSpanAttributes::runId)),
        )
        return ServiceObservation { outcome ->
            metric?.end(metricOutcomeOf(outcome))
            LiveSpans.end(span, spanStatusOf(outcome))
        }
    }
}
