package dev.rubentxu.pipeline.v2.sdk.http

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.dsl.StageScope

/**
 * `httpRequest(url, …)` — one request to a remote service
 * (Jenkins `httpRequest`; LFC-2E3 / WU-093).
 *
 * ## The façade is the whole delivery argument
 *
 * This function is the reason `http.request` is an OFFICIAL_PLUGIN and not a
 * core Step. It is an ordinary Kotlin extension on [StageScope] that builds a
 * typed value, encodes it with the plugin's own [HttpRequestCodec] — the single
 * authority for this wire format — and lowers to the GENERIC
 * [StageScope.registryStep] primitive.
 *
 * `DslCompiledPipelineCompiler` has no branch for `httpRequest`, no case in any
 * `when`, and no knowledge that the string exists. That is not an accident of
 * this implementation; it is the property that distinguishes an open Step
 * registry from a closed core, and it is what the LFC-2E3 fitness asserts.
 *
 * Compare with `core.sh` and `core.echo`, which ARE core and therefore DO have
 * a compiler branch. The difference is the classification, and the shape of the
 * code follows it.
 *
 * The extension does NOT resolve the runtime registry, does NOT execute the
 * handler, does NOT inspect global mutable state, and does NOT decide whether
 * egress is permitted.
 *
 * ## Jenkins familiarity
 *
 * Parameter NAMES match Jenkins (`method`, `customHeaders`, `requestBody`,
 * `contentType`, `acceptType`, `validResponseCodes`, `timeout`,
 * `authentication`), so porting a pipeline is a literal-to-typed change on the
 * value. The TYPES are stricter on purpose: Jenkins takes `method` and
 * `validResponseCodes` as strings and parses them AFTER the request has already
 * been sent (`HttpRequest.java:552-589`), so a typo becomes an exception raised
 * against a request that left the machine.
 *
 * ## Why there is no `String` overload
 *
 * Two overloads whose parameters are all defaulted are ambiguous in Kotlin, and
 * a string-accepting door would be a SECOND way to state the same invariant —
 * one that parses late and fails at a different time. One door, typed. The
 * Jenkins spellings stay reachable where a migration genuinely needs them, as
 * the pure total functions [HttpMethod.fromWire] and [StatusRange.parse], both
 * of which return `null` rather than guess.
 *
 * @param url the absolute URL to request. Blank is a typed rejection.
 * @param method defaults to GET, as in Jenkins.
 * @param customHeaders a LIST, not a map: a header may legitimately repeat
 *   (`Set-Cookie`), and a map would silently collapse them.
 * @param body the request body. Only a method whose [HttpMethod.carriesBody]
 *   accepts one; pairing a body with GET is a declaration error, not a request
 *   the server will ignore.
 * @param validResponseCodes the statuses that count as success, defaulting to
 *   the Jenkins default of anything below 400.
 * @param timeoutSeconds bound on the request; `0` means no bound at all.
 * @param authentication a credentials id. Rejected as a typed failure until
 *   credential resolution lands in G6 — the request is NOT silently sent
 *   unauthenticated.
 */
fun StageScope.httpRequest(
    url: String,
    method: HttpMethod = HttpMethod.Get,
    customHeaders: List<HttpHeader> = emptyList(),
    body: String? = null,
    contentType: String? = null,
    acceptType: String? = null,
    validResponseCodes: List<StatusRange> = StatusRange.jenkinsDefault(),
    timeoutSeconds: Int = HttpDefaults.DEFAULT_TIMEOUT_SECONDS,
    authentication: CredentialsId? = null,
) {
    val input = HttpRequestInput(
        url = url,
        method = method,
        customHeaders = customHeaders,
        body = body,
        contentType = contentType,
        acceptType = acceptType,
        validResponseCodes = validResponseCodes,
        timeoutSeconds = timeoutSeconds,
        authentication = authentication,
    )
    val encoded: EncodedStepValue = HttpRequestCodec.encode(input)
    registryStep(
        stepKey = httpRequestStepKey(),
        encodedInput = encoded,
    )
}

/**
 * The canonical plugin StepKey for `http.request`, re-exported so a
 * `.pipeline.kts` needs one import and no string literal.
 */
fun httpRequestStepKey(): PluginStepId = HttpRequestKey.VALUE
