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
 *             )
 *         }
 *     }
 * }
 * ```
 *
 * A thin wrapper over the generic `registryStep` primitive: it lowers to
 * `StepSpec.RegistryStepSpec` and is executed by the canonical spine.
 */
fun StageScope.sagaObligation(
    sagaId: String,
    actionId: String,
    compensationId: String,
    resourceRef: String,
) {
    registryStep(
        stepKey = SagaObligationStepDefinition.KEY,
        encodedInput = SagaObligationCodec.encode(
            SagaObligationInput(
                sagaId = sagaId,
                actionId = actionId,
                compensationId = compensationId,
                resourceRef = resourceRef,
            ),
        ),
    )
}
