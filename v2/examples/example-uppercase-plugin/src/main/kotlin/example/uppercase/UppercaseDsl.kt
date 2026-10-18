package example.uppercase

import dev.rubentxu.pipeline.v2.dsl.StageScope

/**
 * External DSL facade (EP-5). The plugin teaches the DSL its own step; core,
 * compiler and coordinator never learn the name `uppercase`.
 *
 * The user-visible script becomes:
 *
 * ```kotlin
 * import example.uppercase.uppercase
 *
 * pipeline {
 *     stages {
 *         stage("External") {
 *             uppercase("hello")
 *         }
 *     }
 * }
 * ```
 *
 * Implementation: a thin wrapper over the generic `registryStep` primitive.
 * It lowers to `StepSpec.RegistryStepSpec` (declarative IR) and is executed by
 * the canonical spine; nothing here touches PipelineRun or any direct-execution
 * path.
 */
fun StageScope.uppercase(text: String) {
    registryStep(
        stepKey = UppercaseStepDefinition.KEY,
        encodedInput = UppercaseCodec.encode(UppercaseInput(text)),
    )
}

/**
 * The observing sibling of [uppercase] (P3-D): same work, plus an event this plugin owns.
 *
 * Separate rather than a flag on `uppercase` because the reference `uppercase` is deliberately
 * capability-free, and a `boolean observe` on it would quietly make every caller depend on a seam
 * the plain form never needed. Two entry points, two contracts, one plugin.
 *
 * ```kotlin
 * import example.uppercase.uppercaseObserved
 *
 * pipeline {
 *     stages {
 *         stage("External") {
 *             uppercaseObserved("hello")
 *         }
 *     }
 * }
 * ```
 */
fun StageScope.uppercaseObserved(text: String) {
    registryStep(
        stepKey = UppercaseObservedStepDefinition.KEY,
        encodedInput = UppercaseCodec.encode(UppercaseInput(text)),
    )
}
