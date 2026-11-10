package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.RuntimeApiVersion
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionPort
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import dev.rubentxu.pipeline.v2.service.ServiceProtocol
import dev.rubentxu.pipeline.v2.service.ServiceRequest
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import dev.rubentxu.pipeline.v2.service.ServiceResponse
import dev.rubentxu.pipeline.v2.service.ServiceCodec
import dev.rubentxu.pipeline.v2.service.ServiceRunState

/**
 * The substrate the service was composed with, and whether its parts are still there.
 *
 * ## What this is
 *
 * The two things a run's state is read from — the event store and the control
 * root — and the fact of whether they are still on disk. The health of the
 * SERVICE is exactly this: the service is up, it speaks a schema, and the
 * material it serves is still where it was.
 *
 * ## Why it is a value and not a reading of the filesystem
 *
 * The presence of a file is a fact about the world, and the world changes. So
 * the presence is asked FOR, every time the health is asked, through the
 * supplier that owns the paths. An operations object that stat'ed the files
 * itself would be an object that touches the filesystem, and the unit lane
 * cannot have that.
 */
internal data class ServiceSubstrate(
    /** The path of the event store the service reads. */
    val eventStore: String,
    /** The path of the control root the service reads. */
    val controlRoot: String,
    /** Whether the event store is still there. */
    val eventStorePresent: Boolean,
    /** Whether the control root is still there. */
    val controlRootPresent: Boolean,
)

/**
 * The operations the local service answers.
 *
 * ## What this owns
 *
 * The MEANING of a request: which answer it produces, and which refusal it
 * produces when it cannot. The state of a run is answered by
 * [RunStateQuery] — the same operation the command line uses — so the socket
 * and the CLI cannot disagree about a run, because they ask the same thing.
 *
 * ## What it does not own
 *
 * The substrate, the transport and the format of the wire. The substrate
 * arrives as a supplier (the service asks the world for the presence of its
 * material), and the format is [ServiceCodec]'s.
 *
 * ## Why the words are here
 *
 * Every answer carries a `state` and every refusal carries a `kind`, and both
 * are a closed vocabulary. A client tells a finished run from a going one
 * from an unreadable one by reading a word, and a client that reads prose has
 * to be changed every time the prose is reworded.
 */
internal class ServiceOperations(
    private val introspection: RuntimeIntrospectionPort,
    private val substrate: () -> ServiceSubstrate,
    private val starts: ServiceRunStarter,
) {

    /**
     * The answer to [request].
     *
     * Never throws: a question the service cannot answer is an answer, not an
     * exception, and an exception would close the connection without saying
     * why.
     */
    fun answer(request: ServiceRequest, credentialDigest: String? = null): ServiceResponse = when (request) {
        is ServiceRequest.Version -> answered(versionResult())
        is ServiceRequest.Health -> answered(healthResult())
        is ServiceRequest.RunState -> stateOf(request.runId)
        is ServiceRequest.StartRun -> startOf(request, credentialDigest)
    }

    /**
     * The version of the product and the version of the protocol.
     *
     * The version of the product comes from [RuntimeApiVersion], which is the
     * authority the `version` verb reads: the socket is not a second source of
     * the version, and a socket and a CLI that disagree about the version are
     * two products.
     */
    private fun versionResult(): JsonObject = buildJsonObject {
        put("schema", JsonPrimitive(ServiceProtocol.SCHEMA))
        put("productVersion", JsonPrimitive(RuntimeApiVersion.readImplementationVersion() ?: "unknown"))
    }

    /**
     * The health of the SERVICE.
     *
     * Not the health of the installation: that is `doctor`'s, and it talks about
     * the machine. This one talks about the service and about the material it
     * was composed with.
     */
    private fun healthResult(): JsonObject {
        val substrate = substrate()
        return buildJsonObject {
            put("schema", JsonPrimitive(ServiceProtocol.SCHEMA))
            put("productVersion", JsonPrimitive(RuntimeApiVersion.readImplementationVersion() ?: "unknown"))
            put("eventStore", JsonPrimitive(substrate.eventStore))
            put("eventStorePresent", JsonPrimitive(substrate.eventStorePresent))
            put("controlRoot", JsonPrimitive(substrate.controlRoot))
            put("controlRootPresent", JsonPrimitive(substrate.controlRootPresent))
        }
    }

    /**
     * The state of [runId].
     *
     * The question is asked through [RunStateQuery], the same operation the
     * `inspect --state` verb uses, so the answer here and the answer there
     * are the same fact read over the same store.
     */
    private fun stateOf(runId: String): ServiceResponse =
        when (val answer = RunStateQuery(introspection).stateOf(runId)) {
            is RunStateAnswer.Observed -> answered(observationResult(answer.observation))
            is RunStateAnswer.Refused -> ServiceResponse.Refused(
                ServiceProtocol.SCHEMA,
                refusalOf(answer.refusal),
            )
        }

    /**
     * The answer to a request to START a run.
     *
     * The answer is the identity of the run and not its outcome: the run is
     * still going when the answer is written, and what it concludes is
     * another authority's answer — the one the client asks for with
     * `run.state`.
     *
     * The refusal is the supervisor's own: it knows whether the names
     * resolved, whether the key was already used, and whether the child
     * took its ownership.
     */
    private fun startOf(
        request: ServiceRequest.StartRun,
        credentialDigest: String?,
    ): ServiceResponse =
        when (
            val outcome = starts.start(
                ServiceStartRequest(
                    script = request.script,
                    workspace = request.workspace,
                    idempotencyKey = request.idempotencyKey,
                    credentialDigest = credentialDigest.orEmpty(),
                ),
            )
        ) {
            is ServiceStartOutcome.Accepted -> answered(
                buildJsonObject {
                    put("runId", JsonPrimitive(outcome.runId))
                    put("correlationId", JsonPrimitive(outcome.correlationId))
                    put("ownerId", JsonPrimitive(outcome.ownerId))
                    put("startedAtMs", JsonPrimitive(outcome.startedAtMs))
                },
            )

            is ServiceStartOutcome.Refused -> ServiceResponse.Refused(
                ServiceProtocol.SCHEMA,
                outcome.refusal,
            )
        }

    /**
     * An answer, with the schema this build speaks.
     */
    private fun answered(result: JsonObject): ServiceResponse =
        ServiceResponse.Answered(ServiceProtocol.SCHEMA, result)

    /**
     * The document an observation travels as.
     *
     * The shape is NOT written here: it is
     * [dev.rubentxu.pipeline.v2.service.ServiceRunState]'s, the artifact the
     * client resolves. The service projects and the client de-projects
     * through the SAME definition, so the two cannot drift apart — and a
     * field name written in two places is a field name that will be written
     * two ways.
     */
    private fun observationResult(observation: RuntimeObservation): JsonObject =
        ServiceRunState.encode(observation)

    companion object {

        /**
         * The refusal of the wire that [refusal] becomes.
         *
         * The mapping is TOTAL: every refusal the run-state question can
         * answer has a case here, and the `when` says so at compile time. A
         * refusal that had no case would have to be invented at the last
         * moment, and an invented refusal is usually a generic one that says
         * less than the refusal it replaced.
         */
        fun refusalOf(refusal: RunStateRefusal): ServiceRefusal = when (refusal) {
            is RunStateRefusal.UnknownRun -> ServiceRefusal.UnknownRun(refusal.runId)
            is RunStateRefusal.NotConfigured -> when (refusal.missing) {
                RunStateRefusal.Missing.CONTROL_ROOT -> ServiceRefusal.NotConfiguredControlRoot
                RunStateRefusal.Missing.EVENT_PLANE -> ServiceRefusal.NotConfiguredEventPlane
                RunStateRefusal.Missing.OUTPUT_PLANE -> ServiceRefusal.NotConfiguredOutputPlane
            }
            is RunStateRefusal.StorageFailed -> ServiceRefusal.StorageFailed(refusal.cause)
            is RunStateRefusal.HeldByAnother ->
                ServiceRefusal.HeldByAnother(refusal.heldBy, refusal.fencingToken)
            is RunStateRefusal.Inconsistent -> ServiceRefusal.Inconsistent(refusal.details)
            is RunStateRefusal.UnknownOperation ->
                ServiceRefusal.UnknownRunOperation(refusal.operationId, refusal.attempt)
        }

        /**
         * The run-state refusal [refusal] names, or null when it names none.
         *
         * The INVERSE of [refusalOf], and it lives beside it on purpose: the
         * two vocabularies meet here and nowhere else, so the pair is one
         * piece of code instead of two that could drift.
         *
         * The remote path needs it because the wire's refusal vocabulary is
         * the one a client receives, and the command line's sentence for a
         * refused run is the one the local path prints. Mapping back to the
         * application's own refusal is what lets both paths print the SAME
         * sentence for the SAME fact.
         *
         * Null when the refusal names the PROTOCOL and not the run: those
         * have no local counterpart, and the remote path renders them by
         * their own word.
         */
        fun runStateRefusalOf(refusal: ServiceRefusal): RunStateRefusal? = when (refusal) {
            is ServiceRefusal.UnknownRun -> RunStateRefusal.UnknownRun(refusal.runId)
            ServiceRefusal.NotConfiguredControlRoot ->
                RunStateRefusal.NotConfigured(RunStateRefusal.Missing.CONTROL_ROOT)
            ServiceRefusal.NotConfiguredEventPlane ->
                RunStateRefusal.NotConfigured(RunStateRefusal.Missing.EVENT_PLANE)
            ServiceRefusal.NotConfiguredOutputPlane ->
                RunStateRefusal.NotConfigured(RunStateRefusal.Missing.OUTPUT_PLANE)
            is ServiceRefusal.StorageFailed -> RunStateRefusal.StorageFailed(refusal.cause)
            is ServiceRefusal.HeldByAnother ->
                RunStateRefusal.HeldByAnother(refusal.heldBy, refusal.fencingToken)
            is ServiceRefusal.Inconsistent -> RunStateRefusal.Inconsistent(refusal.details)
            is ServiceRefusal.UnknownRunOperation ->
                RunStateRefusal.UnknownOperation(refusal.operationId, refusal.attempt)
            else -> null
        }
    }
}
