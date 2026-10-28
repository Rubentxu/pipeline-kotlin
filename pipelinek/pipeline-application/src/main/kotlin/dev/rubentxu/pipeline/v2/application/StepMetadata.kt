package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy

/**
 * Metadata bundle for a canonical step — carries the durable execution contract.
 *
 * It is the ONLY shape the durable protocol reads before a step is decoded: the
 * protocol asks a [StepMetadataResolver] for the metadata of a structural step
 * key and consumes the typed properties, never a concrete step name.
 *
 * @property effects The side-effect classification of the step.
 * @property replayPolicy The replay strategy for the step.
 * @property recoveryPolicy Declared recovery behaviour of the operation; the durable
 *   protocol reads this typed property and never a concrete Step name.
 */
data class StepMetadata(
    val effects: Set<Effect>,
    val replayPolicy: ReplayPolicy,
    val recoveryPolicy: RecoveryPolicy = RecoveryPolicy.None,
)
