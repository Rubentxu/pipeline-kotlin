package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * The ONE owner of the "capability not supported" reason the product writes.
 *
 * Two places write it: the registry admission, which refuses an invocation whose contract
 * declares a capability the engine does not supply, and the fallback route of `core.waitUntil`,
 * which refuses because the capability it needs to reach the predicate is absent. They are the
 * same rule with the same wording, so they share this owner: two spellings of one rule would
 * let the two refusals drift apart, and a consumer that recognises one would not recognise the
 * other.
 *
 * The refusal is a TYPED value the run ends with, not a thrown exception: the vocabulary has
 * to travel with the verdict, and a reason that only exists as a stack trace is a reason no
 * consumer can read.
 */
internal object MissingCapabilityReason {

    /**
     * The reason for a step that cannot run because a capability it needs is absent.
     *
     * @param stepKey the step the refusal is about.
     * @param missing the absent capabilities, rendered as their keys in iteration order.
     */
    fun of(stepKey: PluginStepId, missing: Set<StepCapability>): String =
        "missing required capabilities for step '${stepKey.value}': ${missing.joinToString { it.key }}"
}
