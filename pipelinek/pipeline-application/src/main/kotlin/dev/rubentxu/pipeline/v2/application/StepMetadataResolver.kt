package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.PluginStepId

/**
 * Resolves the pre-decode durable metadata of an invocation from its structural step key.
 * The durable protocol consumes only the typed properties of the returned
 * [StepMetadata]; it never interprets a concrete Step name. Identity selects metadata; metadata
 * expresses properties.
 * The production authority is [RegistryStepMetadataResolver.composite], which reads the open
 * registry: a key the registry does not know is a hard defect, not a lookup miss.
*/
fun interface StepMetadataResolver {
    /** Durable metadata for [stepKey], or `null` when the key is not known to this resolver. */
    fun resolve(stepKey: PluginStepId): StepMetadata?
}
