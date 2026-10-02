package dev.rubentxu.pipeline.v2.sdk.http

import dev.rubentxu.pipeline.v2.credentials.api.BASIC_CREDENTIALS_CAPABILITY
import dev.rubentxu.pipeline.v2.credentials.api.BasicCredentialSource
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.NETWORK_EGRESS_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.NetworkEgressPolicy
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry

/**
 * The canonical StepKey of the HTTP OFFICIAL_PLUGIN (LFC-2E3 / WU-093).
 *
 * It is namespaced under the PLUGIN, not under `core`. That is the whole
 * delivery classification in one string: `http.request` belongs to
 * `pipeline-plugin-http` (STEP_ECOSYSTEM_POLICY) and the matrix calls it an
 * `OFFICIAL_PLUGIN candidate`. A `core.httpRequest` would have been core closed
 * by accident, which is the drift the ARCHITECTURAL_STOP recorded.
 */
object HttpRequestKey {
    val VALUE: PluginStepId = PluginStepId("http.request")
}

/**
 * `http.request` — one request to a remote service (LFC-2E3 / WU-093).
 *
 * ## Why this is a StepDefinition and not a `StepSpec` variant
 *
 * The compiler has no branch for this key, and there must never be one. The
 * DSL façade ([httpRequest] in `HttpDsl.kt`) builds a typed
 * [HttpRequestInput], encodes it with [HttpRequestCodec] — the single authority
 * for this wire format — and lowers to the generic `registryStep(...)`
 * primitive. `core.sh`, `core.echo` and `example.uppercase` all travel the
 * same path, which is the property that proves the seam is real rather than
 * aspirational.
 *
 * ## Handler discipline
 *
 * The handler uses ONLY declared capabilities:
 * - [HTTP_TRANSPORT_CAPABILITY] for the socket (supplied by the plugin's own
 *   transport implementation, wired by the composition root);
 * - [NETWORK_EGRESS_CAPABILITY] for permission (supplied by the runtime ONLY
 *   under `--allow-network`).
 *
 * It never reaches `CanonicalRuntimeContext`, the journal, the event sink, the
 * filesystem, or `java.net` directly. It resolves no credential: the transport
 * receives an already-resolved [HttpAuthorization] and never a [CredentialsId],
 * so a secret cannot leak through a typed value by accident.
 */
object HttpRequestStep {

    val KEY: PluginStepId get() = HttpRequestKey.VALUE

    private val descriptor = StepDescriptor(
        stepId = "http.request",
        name = "httpRequest",
        configRef = "",
        executionLocation = ExecutionLocation.AGENT,
        effects = listOf(Effect.NETWORKS),
        // NEVER, not MEMOIZED and not RERUN: a request may have an effect the
        // runtime cannot observe or undo (a charge, a mail, a mutation). Reusing
        // a recorded response would silently skip that effect, and re-running
        // blindly would repeat it. The author retries with the `retry` block,
        // where the decision is visible and theirs.
        replayPolicy = ReplayPolicy.NEVER,
        // Nothing to recover: there is no subprocess and no filesystem artefact
        // a resume could reattach to. A half-sent request is not resumable, and
        // pretending otherwise would make a resume look safer than it is.
        recoveryPolicy = RecoveryPolicy.None,
    )

    private val capabilityRoutedHandler: StepHandler<HttpRequestInput, HttpResponseOutput> =
        StepHandler { input, ctx ->
            // Admission already guaranteed both capabilities; reading them here is
            // the typed access, NOT a second policy check. The policy decision
            // happened at prepare-time, when the runtime decided whether to hand
            // out the egress verdict at all.
            val egress: NetworkEgressPolicy = ctx.capabilities.get(NETWORK_EGRESS_CAPABILITY)
            val transport: HttpTransport = ctx.capabilities.get(HTTP_TRANSPORT_CAPABILITY)
            val credentials: BasicCredentialSource = ctx.capabilities.get(BASIC_CREDENTIALS_CAPABILITY)

            // A verdict that arrives as Denied cannot normally reach here — the
            // runtime withholds the capability instead — but the case is handled
            // rather than assumed away, because a runtime that chooses to hand
            // out the capability with an explicit refusal must be obeyed too.
            if (egress is NetworkEgressPolicy.Denied) {
                return@StepHandler HttpResponseOutput(
                    attempt = HttpAttempt.Failed(
                        url = input.url,
                        method = input.method,
                        durationMs = 0L,
                        failure = HttpFailure.EgressDenied,
                    ),
                )
            }

            // PURE decision, applied once, here at the Step. Neither the
            // compiler nor the transport resolves a rejection: a blank URL or a
            // body on a GET is a DECLARATION error, and it must be decided before
            // a socket exists to regret.
            when (val intent = httpIntentOf(input)) {
                is HttpIntent.Rejected -> HttpResponseOutput(
                    attempt = HttpAttempt.Failed(
                        url = input.url,
                        method = input.method,
                        durationMs = 0L,
                        failure = HttpFailure.Rejected(intent.rejection),
                    ),
                )

                is HttpIntent.Ready -> {
                    // H5: the credential is resolved HERE, before the send request
                    // exists, so a credential that cannot be used means no socket was
                    // ever opened. A declared secret that degraded into an anonymous
                    // request would be the failure this ordering exists to prevent.
                    // The DECISION is pure and lives in `credentialDecisionOf`; this
                    // is only its interpretation at the effect boundary.
                    val decision: CredentialDecision =
                        credentialDecisionOf(intent.authentication, credentials)
                    if (decision is CredentialDecision.Refused) {
                        return@StepHandler rejected(intent, decision.rejection)
                    }

                    val sent: HttpTransportResult = transport.send(
                        intent.toSendRequest(
                            (decision as? CredentialDecision.Authorized)?.authorization,
                        ),
                    )
                    // The transport REPORTS the status; the Step DECIDES whether
                    // that status was acceptable. Splitting it this way is what
                    // keeps `validResponseCodes` an author-facing policy rather
                    // than transport configuration, and it means the transport
                    // cannot be reused for a Step with different acceptance.
                    val attempt: HttpAttempt = when (val outcome = sent.outcome) {
                        is HttpSendOutcome.Answered -> {
                            val response = HttpResponse(
                                url = intent.url,
                                method = intent.method,
                                status = outcome.status,
                                headers = outcome.headers,
                                body = outcome.body,
                                bodySha256 = outcome.bodySha256,
                                bodySizeBytes = outcome.bodySizeBytes,
                                bodyTruncated = outcome.bodyTruncated,
                            )
                            if (response.isAccepted(intent.validResponseCodes)) {
                                HttpAttempt.Answered(
                                    url = intent.url,
                                    method = intent.method,
                                    durationMs = sent.durationMs,
                                    response = response,
                                )
                            } else {
                                HttpAttempt.Refused(
                                    url = intent.url,
                                    method = intent.method,
                                    durationMs = sent.durationMs,
                                    status = outcome.status,
                                    accepted = intent.validResponseCodes,
                                )
                            }
                        }

                        is HttpSendOutcome.Unreachable -> HttpAttempt.Failed(
                            url = intent.url,
                            method = intent.method,
                            durationMs = sent.durationMs,
                            failure = HttpFailure.Unreachable(outcome.reason),
                        )

                        is HttpSendOutcome.Expired -> HttpAttempt.Failed(
                            url = intent.url,
                            method = intent.method,
                            durationMs = sent.durationMs,
                            failure = HttpFailure.Expired(outcome.afterMs),
                        )

                        // The host answered; the body did not finish. Mapping this onto
                        // `Unreachable` would be the one word that erases the fact.
                        is HttpSendOutcome.ResponseInterrupted -> HttpAttempt.Failed(
                            url = intent.url,
                            method = intent.method,
                            durationMs = sent.durationMs,
                            failure = HttpFailure.ResponseInterrupted(
                                reason = outcome.reason,
                                bytesReceived = outcome.bytesReceived,
                            ),
                        )
                    }
                    HttpResponseOutput(attempt = attempt)
                }
            }
        }

    val definition: StepDefinition<HttpRequestInput, HttpResponseOutput> =
        object : StepDefinition<HttpRequestInput, HttpResponseOutput> {
            override val contract: StepContract<HttpRequestInput, HttpResponseOutput> = StepContract(
                key = KEY,
                descriptor = descriptor,
                inputCodec = HttpRequestCodec,
                outputCodec = HttpResponseCodec,
                // BOTH are required, and both are absent on a default run:
                // the runtime withholds the egress verdict unless --allow-network
                // is passed, so admission rejects BEFORE the handler runs. The
                // policy is expressed as a missing capability rather than as an
                // `if` inside the handler, which is the difference between a
                // default that cannot be forgotten and a default somebody has
                // to remember to write.
                requiredCapabilities = setOf<StepCapability>(
                    HTTP_TRANSPORT_CAPABILITY,
                    NETWORK_EGRESS_CAPABILITY,
                    // H5: declared UNCONDITIONALLY, not only when the author asked for
                    // a credential. A conditional requirement would mean deciding the
                    // capability set from the decoded input, and this contract is read
                    // BEFORE decode — that is what makes the refusal below an admission
                    // fact rather than a branch somebody can forget. A run with no
                    // credential store is still admitted, and a Step that never names a
                    // credential never calls the resolver, so the cost of requiring it
                    // is one map lookup.
                    BASIC_CREDENTIALS_CAPABILITY,
                ),
            )

            override val handler: StepHandler<HttpRequestInput, HttpResponseOutput> =
                capabilityRoutedHandler
        }

    /** Registers through the same open registry seam as any external Step. */
    fun registerInto(registry: StepRegistry) {
        registry.register(definition)
    }

    /**
     * A credential that cannot be used is a DECLARATION failure with nothing sent.
     *
     * `durationMs` is zero on purpose: no socket was opened, so no time was spent
     * and no request reached the world. A reader of the journal can tell that
     * apart from "we sent it and it failed", which are very different facts about
     * a pipeline.
     */
    private fun rejected(intent: HttpIntent.Ready, rejection: HttpRejection): HttpResponseOutput =
        HttpResponseOutput(
            attempt = HttpAttempt.Failed(
                url = intent.url,
                method = intent.method,
                durationMs = 0L,
                failure = HttpFailure.Rejected(rejection),
            ),
        )
}
