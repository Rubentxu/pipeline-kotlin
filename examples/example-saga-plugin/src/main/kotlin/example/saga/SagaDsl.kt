package example.saga

import dev.rubentxu.pipeline.v2.dsl.StageScope

/**
 * The external DSL facade.
 *
 * The plugin teaches the DSL its own Step; core, compiler and coordinator never learn the name
 * `sagaObligation`. The user-visible script becomes:
 *
 * ```kotlin
 * import example.saga.sagaObligation
 *
 * pipeline {
 *     stages {
 *         stage("External") {
 *             sagaObligation(
 *                 sagaId = "checkout",
 *                 actionId = "reserve-stock",
 *                 compensationId = "release-stock",
 *                 resourceRef = "stock/reservation-42",
 *                 forwardSlot = "example.saga.reserve.forward",
 *                 compensateSlot = "example.saga.reserve.compensate",
 *             )
 *         }
 *     }
 * }
 * ```
 *
 * The two slot names are part of the PLAN, and the plan's fingerprint is the only place
 * the engine records them — the durable obligation carries the digest, not the bodies.
 * A promise that did not say which bodies it was about could not be given a plan
 * fingerprint at all, and two steps that spelled the same plan differently would have
 * been refused as a divergence.
 *
 * A thin wrapper over the generic `registryStep` primitive: it lowers to
 * `StepSpec.RegistryStepSpec` and is executed by the canonical spine.
 */
fun StageScope.sagaObligation(
    sagaId: String,
    actionId: String,
    compensationId: String,
    resourceRef: String,
    forwardSlot: String,
    compensateSlot: String,
) {
    registryStep(
        stepKey = SagaObligationStepDefinition.KEY,
        encodedInput = SagaObligationCodec.encode(
            SagaObligationInput(
                sagaId = sagaId,
                actionId = actionId,
                compensationId = compensationId,
                resourceRef = resourceRef,
                forwardSlot = forwardSlot,
                compensateSlot = compensateSlot,
            ),
        ),
    )
}
