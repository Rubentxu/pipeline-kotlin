package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry

/**
 * Closed structural execution family of an invocation (CDE.3-e3, reopened per the structural rule:
 * "closed world for execution structure, open world for Step semantics").
 *
 * The durable spine may discriminate ONLY these two closed structural families. A family is NOT an
 * open catalog of concrete Steps: [LegacyCore] is the finite, closed legacy canonical-command world;
 * [Registry] is the open Step-definition world reached by structural key resolution. The coordinator
 * selects its PREPARE strategy on this family token, never by enumerating concrete plugin names
 * (`when (stepKey) { "core.echo" -> ... }` is forbidden at the selection site).
 */
sealed interface StructuralStepFamily {
    /** Closed legacy canonical-command world (core keys). Prepared by [LegacyExecutionBoundary]. */
    data object LegacyCore : StructuralStepFamily

    /** Open Step-definition world (a registered, non-core key). Prepared by [RegistryExecutionPreparation]. */
    data object Registry : StructuralStepFamily
}

/**
 * Classifies an invocation's [StructuralStepFamily] (CDE.3-e3/e4.3).
 *
 * Deterministic and fail-closed:
 *  - a key in the closed legacy authority ([CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS]) is
 *    [LegacyCore], EVEN if a definition with the same key is also registered (core semantics must
 *    not change; the composite metadata resolver mirrors this choice);
 *  - otherwise, when an open [StepRegistry] is injected AND it resolves the key, it is [Registry];
 *  - a key that is neither legacy nor registered never reaches here: the effective metadata resolver
 *    already fails the invocation (EngineInvariantViolation) before Execute. So there is NO silent
 *    fallback registry -> legacy and no lookup miss is hidden.
 *
 * No registry (legacy coordinator) => every resolved step is [LegacyCore], unchanged.
 */
object StructuralFamilyResolver {
    fun classify(stepKey: PluginStepId, registry: StepRegistry?): StructuralStepFamily =
        if (registry != null && stepKey.value !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS) {
            // Non-legacy key: registry family iff the open registry resolves it; a miss is a hard
            // defect surfaced earlier by the metadata resolver, so do not fall back here.
            StructuralStepFamily.Registry
        } else {
            StructuralStepFamily.LegacyCore
        }
}
