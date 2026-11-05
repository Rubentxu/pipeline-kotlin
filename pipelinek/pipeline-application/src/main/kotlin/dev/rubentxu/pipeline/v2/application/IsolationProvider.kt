package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.isolation.ExecutionRequirements
import dev.rubentxu.pipeline.v2.domain.isolation.IsolationAttestation

/**
 * What answers "what can this host PROVE about a run's confinement?".
 *
 * ## Why the question is asked of a provider and not of the host
 *
 * The admission core is pure: it decides on the attestation it is given. What
 * PRODUCES that attestation is an effect — it measures a host — and this is the
 * only place in the product where a boundary is measured rather than claimed.
 * A provider that reported a capability because the tool that applies it was
 * installed would be promising exactly what the specification forbids.
 *
 * ## Why a request can be answered without measuring anything
 *
 * A request that demands nothing beyond the legacy behaviour is answered by the
 * legacy posture, and nothing is measured to answer it: the legacy words start
 * no child, read no script and probe no host. A provider that measured anyway
 * would make every run pay for a boundary nobody asked for.
 *
 * ## What the provider may NOT do
 *
 * It may not read the user's script, evaluate the user's Kotlin or start the
 * user's step. What it starts is its OWN disposable child, with its own
 * disposable workspace, and that child measures the boundary and leaves
 * nothing behind.
 */
internal fun interface IsolationProvider {

    /**
     * The attestation this host offers for [request].
     *
     * @param request the request the operator made.
     * @return the attestation, with the evidence for each capability that was proven.
     */
    fun attest(request: ExecutionRequirements): IsolationAttestation
}
