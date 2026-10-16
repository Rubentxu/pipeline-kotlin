package dev.rubentxu.pipeline.v2.harness.plan

import dev.rubentxu.pipeline.v2.harness.catalog.CapabilityId
import dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue
import dev.rubentxu.pipeline.v2.harness.catalog.ContractId

/**
 * Pure decider: a list of [ChangedInput] becomes either a [ImpactDecision.Selected]
 * plan or a [ImpactDecision.Refused] that names every reason.
 *
 * The decider is the only authority for "what does this change imply?". It
 * does not consult the filesystem, the Git history, or the Gradle build; the
 * caller classifies each path into a [ChangedInput] and passes the list in.
 *
 * Laws (enforced by `ImpactResolverPropertyTest`):
 *
 * 1. **Determinism** — `resolve(changes, cat) == resolve(permuted(changes), cat)`.
 * 2. **Conservative monotonicity** — adding a non-`NonProduction` change to a
 *    `Selected` plan never decreases the obligation set. Adding
 *    `NonProduction` never increases it.
 * 3. **Fail-closed on unclassified production** — `UnclassifiedProduction`
 *    ALWAYS becomes `Refused`, even when other inputs have owners.
 * 4. **No vacuum** — `Selected(emptySet())` is only legal when every input
 *    is `NonProduction`. Anything else is `Refused` or a non-empty plan.
 * 5. **Ambiguity is a gap** — when a path matches more than one contract, the
 *    decider refuses the plan with `CatalogueGap`; it does not pick one.
 */
object ImpactResolver {
    fun resolve(changes: List<ChangedInput>, catalogue: ContractCatalogue): ImpactDecision {
        require(changes.isNotEmpty()) {
            "resolve() requires at least one input; the planner is expected to skip empty work"
        }
        val obligations = LinkedHashSet<Obligation>()
        val refusals = ArrayList<Refusal>()
        var minFidelity: HarnessFidelity? = null

        for (change in changes) {
            when (change) {
                is ChangedInput.NonProduction -> {
                    // Law 2: non-production inputs are tracked but never add obligations.
                }
                is ChangedInput.UnclassifiedProduction -> {
                    // Law 3: fail closed. The decider does not "guess" the owner.
                    refusals += Refusal.UnclassifiedInput(change.path)
                }
                is ChangedInput.KotlinSource,
                is ChangedInput.JavaSource,
                is ChangedInput.ProtoSchema,
                is ChangedInput.ServiceDescriptor,
                is ChangedInput.Resource,
                is ChangedInput.BuildLogic,
                is ChangedInput.ExecutableScript -> {
                    val path = changeProductPath(change)
                    when (val match = catalogue.lookupByPath(path)) {
                        is ContractCatalogue.MatchResult.Single -> {
                            val entry = match.entry
                            val obligation = Obligation(
                                contract = entry.id,
                                capability = entry.capability,
                                minimumFidelity = entry.metadata.minimumFidelity,
                                requiresInstalledBinary = entry.metadata.requiresInstalledBinary,
                                requiresExternalCertifier = entry.metadata.requiresExternalCertifier,
                                sourcePath = path,
                            )
                            obligations.add(obligation)
                            minFidelity = maxFidelity(minFidelity, entry.metadata.minimumFidelity)
                        }
                        ContractCatalogue.MatchResult.None -> {
                            // A production path without a contract owner. Refused.
                            refusals += Refusal.CatalogueGap(
                                path = path,
                                reason = "no contract claims this production path",
                            )
                        }
                        is ContractCatalogue.MatchResult.Ambiguous -> {
                            val ids = match.entries.joinToString { it.id.value }
                            refusals += Refusal.CatalogueGap(
                                path = path,
                                reason = "ambiguous owner: $ids",
                            )
                        }
                    }
                }
            }
        }

        if (refusals.isNotEmpty()) {
            return ImpactDecision.Refused(refusals)
        }
        if (obligations.isEmpty()) {
            // Law 4: the only legal empty plan is "every input was NonProduction".
            val allNonProduction = changes.all { it is ChangedInput.NonProduction }
            require(allNonProduction) {
                "Empty obligations but not every input is NonProduction — internal bug"
            }
            return ImpactDecision.Selected(obligations, HarnessFidelity.HF0)
        }
        return ImpactDecision.Selected(obligations, minFidelity ?: HarnessFidelity.HF0)
    }

    private fun changeProductPath(change: ChangedInput): String = when (change) {
        is ChangedInput.KotlinSource -> change.path
        is ChangedInput.JavaSource -> change.path
        is ChangedInput.ProtoSchema -> change.path
        is ChangedInput.ServiceDescriptor -> change.path
        is ChangedInput.Resource -> change.path
        is ChangedInput.BuildLogic -> change.path
        is ChangedInput.ExecutableScript -> change.path
        is ChangedInput.UnclassifiedProduction -> change.path
        is ChangedInput.NonProduction -> change.path
    }

    private fun maxFidelity(
        current: HarnessFidelity?,
        next: HarnessFidelity,
    ): HarnessFidelity {
        if (current == null) return next
        return if (current.ordinal >= next.ordinal) current else next
    }

    /** Helper for tests: render a plan as a stable, comparable text. */
    fun fingerprint(decision: ImpactDecision): String = when (decision) {
        is ImpactDecision.Selected -> "Selected{obligations=${decision.obligations.size}, min=${decision.minFidelity}}"
        is ImpactDecision.Refused -> "Refused{reasons=${decision.reasons.size}}"
    }
}

/** Convenience: contract ids of a plan, ignoring the ordering. */
fun ImpactDecision.contracts(): Set<ContractId> = when (this) {
    is ImpactDecision.Selected -> obligations.mapTo(LinkedHashSet()) { it.contract }
    is ImpactDecision.Refused -> emptySet()
}

/** Convenience: capability ids of a plan. */
fun ImpactDecision.capabilities(): Set<CapabilityId> = when (this) {
    is ImpactDecision.Selected -> obligations.mapTo(LinkedHashSet()) { it.capability }
    is ImpactDecision.Refused -> emptySet()
}
