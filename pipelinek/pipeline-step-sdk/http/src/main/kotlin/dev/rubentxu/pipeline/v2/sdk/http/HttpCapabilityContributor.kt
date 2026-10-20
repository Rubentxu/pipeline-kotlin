package dev.rubentxu.pipeline.v2.sdk.http

import dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor
import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * H2b — how the HTTP plugin contributes its SEAM to the runtime.
 *
 * The plugin owns the transport; the runtime owns the permission. Neither names
 * the other's types: this file says which capability it supplies and builds the
 * value, and the runtime says whether that capability may be USED. The
 * composition root is the only place the two meet, and all it does is hold
 * instances of both.
 *
 * Note what is NOT here. There is no reference to `--allow-network`, no
 * reference to `ShOptions`, and no conditional. Whether the contributed seam is
 * reachable is decided by the runtime withholding `network.egress`, and that
 * decision happens at admission — one layer above, in a module that has never
 * heard of HTTP.
 *
 * A transport is contributed UNCONDITIONALLY, deliberately. Withholding it would
 * collapse "this run may not use the network" and "the plugin was never wired"
 * into one indistinguishable failure. Keeping it always present means every
 * denial of `http.request` is, unambiguously, about egress — which is the thing
 * an operator needs to be told.
 */
class HttpCapabilityContributor(
    private val transport: HttpTransport = JdkHttpTransport(),
) : RuntimeCapabilityContributor {

    /**
     * The transport ONLY.
     *
     * H5-B moved the credential seam out: it is supplied by the runtime through
     * `BasicCredentialsCapabilityContributor`, because the value has to come from
     * the operator's store and the runtime cannot import this plugin's vocabulary
     * to build it. Contributing it from BOTH sides would be a capability collision,
     * which `CompositeCapabilityContributor` refuses by design — and rightly: first
     * wins and last wins both let one side silently shadow the other.
     */
    override fun capabilities(): Map<StepCapability, Any> =
        mapOf(HTTP_TRANSPORT_CAPABILITY to transport)
}
