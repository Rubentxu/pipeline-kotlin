package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.PluginStepId

/**
 * Resolves the pre-decode durable metadata of an invocation from its structural step key
 * (CDE.2-b2). The durable protocol consumes only the typed properties of the returned
 * [StepMetadata]; it never interprets a concrete Step name. Identity selects metadata; metadata
 * expresses properties.
 *
 * Today the only implementation is the legacy core catalog ([CoreLegacyStepMetadataResolver]). The
 * registry/definition metadata composite (migrated definitions + this legacy table) belongs to
 * CDE.3/CDE.5; this interface is the seam it will implement, so durable resolution does not need to
 * change again.
 */
fun interface StepMetadataResolver {
    /** Durable metadata for [stepKey], or `null` when the key is not known to this resolver. */
    fun resolve(stepKey: PluginStepId): StepMetadata?
}

/**
 * Production default [StepMetadataResolver] backed by [CanonicalCoreStepMetadata], the compatibility
 * authority of the legacy core world. Mirrors the previous fail-fast behaviour of
 * [CanonicalCoreStepCommand.defaultMetadata]: an unregistered key is a hard defect, not a lookup miss.
 */
object CoreLegacyStepMetadataResolver : StepMetadataResolver {
    override fun resolve(stepKey: PluginStepId): StepMetadata = CanonicalCoreStepMetadata.metadata(stepKey.value)
}
