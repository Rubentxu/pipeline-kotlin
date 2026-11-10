package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.RuntimeApiVersion
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionPort
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import dev.rubentxu.pipeline.v2.service.ServiceInputDecision
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
    private val cancellations: ServiceRunCancellation?,
    private val inputs: ServiceInputAnswers? = null,
    private val providers: ConfigurationProviderSessions? = null,
    private val configurations: ServiceRunConfigurations? = null,
    credentialSessions: CredentialProviderSessions? = null,
) {

    private val credentials = CredentialProviderOperations(credentialSessions)

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
        is ServiceRequest.CancelRun -> cancelOf(request)
        is ServiceRequest.ListInputs -> listInputsOf(request)
        is ServiceRequest.AnswerInput -> answerInputOf(request, credentialDigest)
        is ServiceRequest.DescribeParameters -> describeParametersOf()
        is ServiceRequest.ProviderRegister -> providerRegisterOf(request, credentialDigest)
        is ServiceRequest.ProviderPoll -> providerPollOf(request, credentialDigest)
        is ServiceRequest.ProviderAnswer -> providerAnswerOf(request, credentialDigest)
        is ServiceRequest.ProviderClose -> providerCloseOf(request, credentialDigest)
        is ServiceRequest.RunConfigurationQuery -> runConfigurationOf(request)
        is ServiceRequest.CredentialRegister -> credentials.register(request, credentialDigest)
        is ServiceRequest.CredentialPoll -> credentials.poll(request, credentialDigest)
        is ServiceRequest.CredentialAnswer -> credentials.answer(request, credentialDigest)
        is ServiceRequest.CredentialClose -> credentials.close(request, credentialDigest)
    }

    /**
     * The answer to a request to DESCRIBE the parameters of a script.
     *
     * The answer is a REFUSAL, and it is the same refusal for every script: this service
     * does not compile the scripts it is asked about, and describing the parameters of a
     * script is compiling it.
     *
     * ## Why the name is not the one the VALUES are refused with
     *
     * Because the client's two actions are different and neither of them is a retry: the
     * values of a request that break a rule are fixed and sent again, and a description this
     * service cannot produce is asked of somebody else. A word that covered both would make
     * the client read the reason to tell them apart.
     */
    private fun describeParametersOf(): ServiceResponse = ServiceResponse.Refused(
        ServiceProtocol.SCHEMA,
        ServiceRefusal.DescribeParametersRefused(
            "the-service-does-not-describe-parameters",
            "describing the parameters of a script means compiling it, and this service does " +
                "not compile the scripts it is asked about; the description comes from the " +
                "compiler, or from the contract the client already carries",
        ),
    )

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
     *
     * It NEVER throws, even when the supervisor does: the promise of
     * [answer] is that a connection gets a line, and a start that fails in
     * an unforeseen way is a refusal with a name and not a closed
     * connection.
     */
    private fun startOf(
        request: ServiceRequest.StartRun,
        credentialDigest: String?,
    ): ServiceResponse {
        val outcome = try {
            starts.start(
                ServiceStartRequest(
                    script = request.script,
                    workspace = request.workspace,
                    idempotencyKey = request.idempotencyKey,
                    credentialDigest = credentialDigest.orEmpty(),
                    configuration = request.configuration,
                    parameters = request.parameters,
                    parameterContract = request.parameterContract,
                    configurationProvider = request.configurationProvider,
                    credentialProvider = request.credentialProvider,
                ),
            )
        } catch (failure: Exception) {
            return ServiceResponse.Refused(
                ServiceProtocol.SCHEMA,
                ServiceRefusal.RunStartRefused(
                    "the-start-failed",
                    failure.message ?: "the start of the run failed",
                ),
            )
        }
        return when (outcome) {
            is ServiceStartOutcome.Accepted -> answered(
                buildJsonObject {
                    put("runId", JsonPrimitive(outcome.runId))
                    put("correlationId", JsonPrimitive(outcome.correlationId))
                    put("ownerId", JsonPrimitive(outcome.ownerId))
                    put("startedAtMs", JsonPrimitive(outcome.startedAtMs))
                    // The configuration comes back on EVERY path, and it is
                    // the configuration of the request that is being
                    // answered. A client that sent one compares it with what
                    // it sent, and a difference is a fact the client can
                    // act on instead of a run that quietly has another
                    // configuration.
                    put(
                        "configuration",
                        buildJsonObject {
                            outcome.configuration.values.forEach { (name, value) ->
                                put(name, JsonPrimitive(value))
                            }
                        },
                    )
                    // The PARAMETERS come back for the same reason the
                    // configuration does: a service that does not know the
                    // field ignores it in silence and starts the run without
                    // the values, and the client that sent them has to be
                    // able to tell the two apart. The echo carries the
                    // DELIVERED values, because those are the ones the
                    // service knows; the defaults of the script's own
                    // declaration are the child's business.
                    put(
                        "parameters",
                        buildJsonObject {
                            outcome.parameters.forEach { (name, value) ->
                                put(name, JsonPrimitive(value))
                            }
                        },
                    )
                },
            )

            is ServiceStartOutcome.Refused -> ServiceResponse.Refused(
                ServiceProtocol.SCHEMA,
                outcome.refusal,
            )
        }
    }

    /**
     * The answer to a request to CANCEL a run.
     *
     * The answer is the TERMINAL of the run, read from the durable facts and
     * not from the moment the answer was written: a client that asks twice
     * reads the same terminal, and a client that asks once knows when the
     * run really ended.
     *
     * The two successful answers differ in a WORD —`cancelled` and
     * `already-cancelled`— because a cancellation repeated is not an error:
     * the run is closed, once, and the second call says so.
     *
     * It NEVER throws, for the same reason [answer] never throws: a
     * cancellation that fails in an unforeseen way is a refusal with a name
     * and not a closed connection.
     */
    private fun cancelOf(request: ServiceRequest.CancelRun): ServiceResponse {
        val cancellation = cancellations
        if (cancellation == null) {
            // The service was composed without the ability to cancel: no
            // authorization carries the right, so the substrate was not
            // opened for writing and there is no port to write the terminal
            // with. The server refuses such a request before it arrives
            // here, and this is the safety net under it.
            return ServiceResponse.Refused(
                ServiceProtocol.SCHEMA,
                ServiceRefusal.RunCancelRefused(
                    "the-service-may-not-cancel",
                    "this service was not composed to cancel runs",
                ),
            )
        }
        val outcome = try {
            cancellation.cancel(request.runId, request.reason)
        } catch (failure: Exception) {
            return ServiceResponse.Refused(
                ServiceProtocol.SCHEMA,
                ServiceRefusal.RunCancelRefused(
                    "the-cancel-failed",
                    failure.message ?: "the cancellation of the run failed",
                ),
            )
        }
        return when (outcome) {
            is ServiceCancelOutcome.Cancelled -> answered(
                buildJsonObject {
                    put("runId", JsonPrimitive(request.runId))
                    put("outcome", JsonPrimitive(ServiceRunCancellation.OUTCOME_CANCELLED))
                    put("terminalAtMs", JsonPrimitive(outcome.terminalAtMs))
                },
            )

            is ServiceCancelOutcome.AlreadyCancelled -> answered(
                buildJsonObject {
                    put("runId", JsonPrimitive(request.runId))
                    put("outcome", JsonPrimitive(ServiceRunCancellation.OUTCOME_ALREADY_CANCELLED))
                    put("terminalAtMs", JsonPrimitive(outcome.terminalAtMs))
                },
            )

            is ServiceCancelOutcome.Refused -> ServiceResponse.Refused(
                ServiceProtocol.SCHEMA,
                ServiceRefusal.RunCancelRefused(outcome.reason, outcome.detail),
            )
        }
    }

    /**
     * The answer to a request to LIST the questions a run is waiting on.
     *
     * The answer holds the questions and nothing else: who may read them was decided before
     * the request arrived here, and the text of a question is the pipeline author's own.
     *
     * It NEVER throws, for the reason [answer] never throws.
     */
    private fun listInputsOf(request: ServiceRequest.ListInputs): ServiceResponse {
        val answers = inputs ?: return notComposedToAnswer()
        val outcome = try {
            answers.pending(request.runId)
        } catch (_: Exception) {
            // The message of a failure over the file system names paths: it does not travel.
            return inputRefused(ServiceInputAnswers.REASON_FAILED, "the questions could not be listed")
        }
        return when (outcome) {
            is ServiceInputListOutcome.Listed -> answered(
                buildJsonObject {
                    put(
                        "inputs",
                        JsonArray(outcome.questions.map(ServiceInputAnswers::questionJson)),
                    )
                },
            )

            is ServiceInputListOutcome.Refused -> inputRefused(outcome.reason, outcome.detail)
        }
    }

    /**
     * The answer to a request to ANSWER a question of a run.
     *
     * The answer names the decision that was RECORDED, and its `outcome` differs in a word
     * —`answered` and `already-answered`— because the same answer repeated by the same
     * authorization is not an error. A different answer to a question that has one is a
     * refusal.
     *
     * It NEVER throws, for the reason [answer] never throws.
     */
    private fun answerInputOf(request: ServiceRequest.AnswerInput, credentialDigest: String?): ServiceResponse {
        val answers = inputs ?: return notComposedToAnswer()
        val outcome = try {
            answers.answer(request.runId, request.opId, request.decision, request.message, credentialDigest)
        } catch (_: Exception) {
            return inputRefused(ServiceInputAnswers.REASON_FAILED, "the answer could not be recorded")
        }
        return when (outcome) {
            is ServiceInputAnswerOutcome.Answered ->
                recorded(request, outcome.decision, ServiceInputAnswers.OUTCOME_ANSWERED)

            is ServiceInputAnswerOutcome.AlreadyAnswered ->
                recorded(request, outcome.decision, ServiceInputAnswers.OUTCOME_ALREADY_ANSWERED)

            is ServiceInputAnswerOutcome.Refused -> inputRefused(outcome.reason, outcome.detail)
        }
    }

    private fun recorded(
        request: ServiceRequest.AnswerInput,
        decision: ServiceInputDecision,
        outcome: String,
    ): ServiceResponse = answered(
        buildJsonObject {
            put("runId", JsonPrimitive(request.runId))
            put("opId", JsonPrimitive(request.opId))
            put("decision", JsonPrimitive(decision.word))
            put("outcome", JsonPrimitive(outcome))
        },
    )

    private fun notComposedToAnswer(): ServiceResponse = inputRefused(
        // The service was composed without the ability to answer: no authorization carries the
        // right. The server refuses such a request before it arrives here, and this is the
        // safety net under it.
        ServiceInputAnswers.REASON_NOT_COMPOSED,
        "this service was not composed to answer the questions of runs",
    )

    private fun inputRefused(reason: String, detail: String): ServiceResponse =
        ServiceResponse.Refused(ServiceProtocol.SCHEMA, ServiceRefusal.InputAnswerRefused(reason, detail))

    // ------------------------------------------------------------ configuration providers

    /**
     * The answer to a request to REGISTER a configuration provider session.
     *
     * The session belongs to the credential that registered it: the credential is the owner the
     * resolution of a start is matched against, so a provider of the same name under another
     * credential is never reached. It NEVER throws, for the reason [answer] never throws.
     */
    private fun providerRegisterOf(request: ServiceRequest.ProviderRegister, credentialDigest: String?): ServiceResponse =
        withProviders(credentialDigest) { sessions, owner ->
            val keys = request.keys.map { ProviderKeyDeclaration(it.name, it.type, it.sensitivity) }
            when (val outcome = sessions.register(owner, request.provider, keys)) {
                is ProviderRegistrationOutcome.Registered -> answered(
                    buildJsonObject {
                        put("sessionId", JsonPrimitive(outcome.sessionId))
                        put("leaseMillis", JsonPrimitive(outcome.leaseMillis))
                    },
                )

                is ProviderRegistrationOutcome.Refused -> providerRefused(outcome.reason, outcome.detail)
            }
        }

    /** The answer to a POLL of a provider session: the keys a run waits for, or an idle answer. */
    private fun providerPollOf(request: ServiceRequest.ProviderPoll, credentialDigest: String?): ServiceResponse =
        withProviders(credentialDigest) { sessions, owner ->
            when (val outcome = sessions.poll(owner, request.sessionId, request.waitMillis)) {
                is ProviderPollOutcome.Request -> answered(
                    buildJsonObject {
                        put("state", JsonPrimitive(POLL_ASKED))
                        put("requestId", JsonPrimitive(outcome.requestId))
                        put("keys", JsonArray(outcome.keys.map(::JsonPrimitive)))
                    },
                )

                is ProviderPollOutcome.Idle -> answered(buildJsonObject { put("state", JsonPrimitive(POLL_IDLE)) })
                is ProviderPollOutcome.Refused -> providerRefused(outcome.reason, outcome.detail)
            }
        }

    /** The answer to the ANSWER of a provider: the values of the keys a poll asked for. */
    private fun providerAnswerOf(request: ServiceRequest.ProviderAnswer, credentialDigest: String?): ServiceResponse =
        withProviders(credentialDigest) { sessions, owner ->
            val outcome = sessions.answer(owner, request.sessionId, request.requestId, request.revision, request.values)
            providerAcknowledged(request.sessionId, outcome, OUTCOME_ANSWERED)
        }

    /** The answer to the CLOSE of a provider session. */
    private fun providerCloseOf(request: ServiceRequest.ProviderClose, credentialDigest: String?): ServiceResponse =
        withProviders(credentialDigest) { sessions, owner ->
            providerAcknowledged(request.sessionId, sessions.close(owner, request.sessionId), OUTCOME_CLOSED)
        }

    private fun providerAcknowledged(
        sessionId: String,
        outcome: ProviderSessionOutcome,
        word: String,
    ): ServiceResponse = when (outcome) {
        is ProviderSessionOutcome.Accepted -> answered(
            buildJsonObject {
                put("sessionId", JsonPrimitive(sessionId))
                put("outcome", JsonPrimitive(word))
            },
        )

        is ProviderSessionOutcome.Refused -> providerRefused(outcome.reason, outcome.detail)
    }

    /**
     * Runs [action] for the credential that sent the request, or refuses.
     *
     * A service composed without provider sessions refuses, and so does a request that arrives
     * without a credential: a session with no owner would be one every anonymous start could
     * reach. A failure inside the action is a refusal with a fixed text — the message of a
     * failure may name paths and it does not travel.
     */
    private fun withProviders(
        credentialDigest: String?,
        action: (ConfigurationProviderSessions, String) -> ServiceResponse,
    ): ServiceResponse {
        val sessions = providers
            ?: return providerRefused(REASON_PROVIDERS_NOT_COMPOSED, "this service was not composed with configuration providers")
        val owner = credentialDigest?.takeIf { it.isNotBlank() }
            ?: return providerRefused(REASON_PROVIDER_CREDENTIAL_MISSING, "a provider session belongs to a credential")
        return try {
            action(sessions, owner)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            providerRefused(REASON_PROVIDER_FAILED, "the operation on the provider session failed")
        } catch (_: Exception) {
            providerRefused(REASON_PROVIDER_FAILED, "the operation on the provider session failed")
        }
    }

    private fun providerRefused(reason: String, detail: String): ServiceResponse =
        ServiceResponse.Refused(ServiceProtocol.SCHEMA, ServiceRefusal.ConfigurationProviderRefused(reason, detail))

    /**
     * The answer to the question of where the configuration of a run came from.
     *
     * The answer says, key by key, which origin set it and which type it was validated as, and
     * it carries the digest of the snapshot and, when a provider took part, its name, revision
     * and the digest of what it answered. It carries NO value: the values are the run's, and a
     * client that needs to know whether two runs were given the same configuration compares
     * digests. It NEVER throws, for the reason [answer] never throws.
     */
    private fun runConfigurationOf(request: ServiceRequest.RunConfigurationQuery): ServiceResponse {
        val source = configurations
            ?: return configurationRefused("run-configuration-unavailable", "this service does not describe the configuration of runs")
        val lookup = try {
            source.describe(request.runId)
        } catch (_: Exception) {
            return configurationRefused("run-configuration-unreadable", "the configuration of the run could not be read")
        }
        return when (lookup) {
            is RunConfigurationLookup.Unknown ->
                configurationRefused("run-configuration-unknown", "this service did not start a run with that identity")

            is RunConfigurationLookup.Unreadable -> configurationRefused("run-configuration-unreadable", lookup.detail)
            is RunConfigurationLookup.Found -> answered(describedConfiguration(request.runId, lookup.description))
        }
    }

    private fun describedConfiguration(runId: String, description: RunConfigurationDescription): JsonObject =
        buildJsonObject {
            put("runId", JsonPrimitive(runId))
            put("digest", JsonPrimitive(description.digest))
            put("precedence", JsonArray(RunConfigurationReader.PRECEDENCE.map(::JsonPrimitive)))
            put(
                "entries",
                JsonArray(
                    description.entries.map { entry ->
                        buildJsonObject {
                            put("name", JsonPrimitive(entry.name))
                            put("origin", JsonPrimitive(entry.origin))
                            put("type", JsonPrimitive(entry.type))
                        }
                    },
                ),
            )
            description.provider?.let { put("provider", JsonPrimitive(it)) }
            description.revision?.let { put("revision", JsonPrimitive(it)) }
            description.sourceDigest?.let { put("sourceDigest", JsonPrimitive(it)) }
        }

    private fun configurationRefused(reason: String, detail: String): ServiceResponse =
        ServiceResponse.Refused(ServiceProtocol.SCHEMA, ServiceRefusal.RunConfigurationRefused(reason, detail))

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

        /** The state word of a poll that found a run waiting for values. */
        const val POLL_ASKED: String = "asked"

        /** The state word of a poll that found nothing to ask before its wait ended. */
        const val POLL_IDLE: String = "idle"

        /** The outcome word of an answer a provider gave. */
        const val OUTCOME_ANSWERED: String = "answered"

        /** The outcome word of a session its provider closed. */
        const val OUTCOME_CLOSED: String = "closed"

        /** The service was composed without configuration provider sessions. */
        const val REASON_PROVIDERS_NOT_COMPOSED: String = "provider-sessions-not-composed"

        /** A provider operation arrived without the credential that would own the session. */
        const val REASON_PROVIDER_CREDENTIAL_MISSING: String = "provider-credential-missing"

        /** An operation on a provider session failed in a way nobody foresaw. */
        const val REASON_PROVIDER_FAILED: String = "provider-operation-failed"

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
