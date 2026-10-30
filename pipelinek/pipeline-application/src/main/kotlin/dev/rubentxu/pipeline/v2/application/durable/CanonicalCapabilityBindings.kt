package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.MilestoneStateStore
import dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor
import dev.rubentxu.pipeline.v2.domain.step.artifact.ArtifactIndexCapability

/**
 * The ONE holder of the collaborators a capability table is built from.
 *
 * ## Why the collaborators live here and not in the bridge
 * The bridge ([CanonicalRuntimeCapabilityAccess]) derives most of its table
 * from the runtime context, but three capabilities come from collaborators the
 * runtime context does not carry: the milestone state store, the per-run
 * artifact index and the plugin capability contributor. Those three were
 * constructor parameters of the bridge, and the bridge was constructed in
 * several places — so "which capabilities exist" was decided in more than one
 * place, each with its own idea of which collaborators were bound.
 *
 * This holder makes the decision site single: the collaborators are chosen
 * once, at composition, and every consumer of the table asks this holder for
 * the access to the same collaborators. The canonical coordinator, the typed
 * preparation, the registry boundary and the scripted path all go through it.
 *
 * ## Why the same holder serves PREPARE and EXECUTE
 * Admission (PREPARE) and execution (EXECUTE) must see the SAME table, or a
 * Step can pass admission and then find its capability missing. Sharing the
 * holder is what makes that agreement structural rather than a matter of
 * passing the same three arguments twice.
 */
class CanonicalCapabilityBindings(
    /**
     * Milestone state store for MILESTONE_OPERATIONS_CAPABILITY.
     * Always non-null in production (the coordinator provides it); nullable for
     * the surfaces that bind no milestone collaborator at all.
     */
    private val milestoneStateStore: MilestoneStateStore? = null,
    /**
     * Per-run artifact index, shared between the producer
     * (core.archiveArtifacts) and the consumer (core.artifact.query). When
     * null the capability stays unexposed and BOTH Steps fail closed.
     */
    private val artifactIndex: ArtifactIndexCapability? = null,
    /**
     * Behaviour supplying the capabilities an OFFICIAL_PLUGIN contributes.
     * A CONTRIBUTOR rather than a map, so the plugin seam never becomes
     * ambient configuration.
     */
    private val capabilityContributor: RuntimeCapabilityContributor = RuntimeCapabilityContributor { emptyMap() },
) {

    /**
     * The capability access for one invocation, built from these collaborators
     * and the invocation's own runtime context.
     */
    fun access(context: CanonicalRuntimeContext): CanonicalRuntimeCapabilityAccess =
        CanonicalRuntimeCapabilityAccess(
            context,
            milestoneStateStore = milestoneStateStore,
            artifactIndex = artifactIndex,
            capabilityContributor = capabilityContributor,
        )
}
