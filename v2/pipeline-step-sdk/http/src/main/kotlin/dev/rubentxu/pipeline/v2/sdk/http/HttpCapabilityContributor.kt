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
    /**
     * H5: contributed UNCONDITIONALLY, like the transport and for the same reason.
     *
     * The default is [NoCredentialSource], which fails every lookup with a
     * diagnosis that says the run has no store. That is deliberately different
     * from "that name is not in the store": one sends an operator to configure
     * the run, the other to fix a typo. Withholding the capability instead would
     * make a request WITHOUT a credential fail admission too, which would be a
     * strictly worse answer to a question nobody asked.
     */
    private val credentials: HttpCredentialResolver = NoCredentialSource,
) : RuntimeCapabilityContributor {

    override fun capabilities(): Map<StepCapability, Any> = mapOf(
        HTTP_TRANSPORT_CAPABILITY to transport,
        HTTP_CREDENTIALS_CAPABILITY to credentials,
    )
}
