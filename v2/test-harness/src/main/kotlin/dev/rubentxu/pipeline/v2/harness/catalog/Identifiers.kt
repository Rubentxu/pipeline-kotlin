package dev.rubentxu.pipeline.v2.harness.catalog

import dev.rubentxu.pipeline.v2.harness.plan.HarnessFidelity

/** Stable identifier of a product capability (the promise to the user). */
@JvmInline
value class CapabilityId(val value: String) {
    init {
        require(value.isNotBlank()) { "CapabilityId must not be blank" }
    }

    override fun toString(): String = "CapabilityId($value)"
}

/** Stable identifier of a contract that protects a capability. */
@JvmInline
value class ContractId(val value: String) {
    init {
        require(value.isNotBlank()) { "ContractId must not be blank" }
    }

    override fun toString(): String = "ContractId($value)"
}

/**
 * Static execution/durability metadata required BEFORE typed input decode.
 * Mirrors `StepDescriptor` semantics for tests, not for Steps — a contract may
 * apply to an entire capability (with multiple suites) rather than to a single
 * Step.
 */
data class ContractMetadata(
    val minimumFidelity: HarnessFidelity,
    val requiresInstalledBinary: Boolean,
    val requiresExternalCertifier: Boolean,
)

/**
 * One contract entry: the contract, the metadata, and the suite FQCNs that
 * exercise it. FQCNs are module-qualified by convention (`module + FQCN`) to
 * keep two same-named suites in different modules distinct.
 */
data class ContractEntry(
    val id: ContractId,
    val capability: CapabilityId,
    val owner: String,
    val metadata: ContractMetadata,
    val suiteFqcn: String,
    val sourcePatterns: List<String>,
) {
    init {
        require(suiteFqcn.isNotBlank()) { "ContractEntry.suiteFqcn must not be blank" }
        require(sourcePatterns.isNotEmpty()) { "ContractEntry.sourcePatterns must be non-empty" }
    }
}
