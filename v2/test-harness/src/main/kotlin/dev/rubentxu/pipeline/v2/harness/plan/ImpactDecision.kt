package dev.rubentxu.pipeline.v2.harness.plan

import dev.rubentxu.pipeline.v2.harness.catalog.CapabilityId
import dev.rubentxu.pipeline.v2.harness.catalog.ContractId

/**
 * The outcome of the impact decider. Closed: there is no "default" case.
 *
 * `Refused` is the correct answer for an unclassified production change. It is
 * NOT a failure: it is the planner's signal that the human owner must add the
 * path to the catalogue before any plan can be issued. The decider does not
 * guess.
 */
sealed interface ImpactDecision {
    /**
     * The plan: the set of contracts that MUST be exercised for the change.
     * The set is never empty unless all inputs are `NonProduction`.
     */
    data class Selected(
        val obligations: Set<Obligation>,
        val minFidelity: HarnessFidelity,
    ) : ImpactDecision {
        init {
            // Law: when obligations exist, minFidelity must reach at least
            // HF0 (sanity). The real ceiling comes from the obligations.
            require(obligations.isNotEmpty() || minFidelity == HarnessFidelity.HF0) {
                "An empty Selected must be paired with HF0 (non-production only)"
            }
        }
    }

    /**
     * The plan could not be produced. The reasons enumerate which input
     * categories failed; the planner does not stop at the first one.
     */
    data class Refused(val reasons: List<Refusal>) : ImpactDecision {
        init {
            require(reasons.isNotEmpty()) { "Refused must carry at least one reason" }
        }
    }
}

/**
 * A single obligation derived from one changed input. Carries the
 * contract, the minimum fidelity the contract demands, and whether the
 * obligation requires an installed binary or the external certifier.
 */
data class Obligation(
    val contract: ContractId,
    val capability: CapabilityId,
    val minimumFidelity: HarnessFidelity,
    val requiresInstalledBinary: Boolean,
    val requiresExternalCertifier: Boolean,
    val sourcePath: String,
) {
    init {
        require(sourcePath.isNotBlank()) { "Obligation.sourcePath must not be blank" }
    }
}

/** Closed enumeration of refusal causes. Adding one is an API break. */
sealed interface Refusal {
    val path: String

    data class UnclassifiedInput(override val path: String) : Refusal {
        init {
            require(path.isNotBlank()) { "UnclassifiedInput.path must not be blank" }
        }
    }

    data class UnknownCapability(override val path: String, val capability: CapabilityId) : Refusal

    data class CatalogueGap(override val path: String, val reason: String) : Refusal
}
