package example.uppercase

import dev.rubentxu.pipeline.v2.dsl.StepEmitter
import dev.rubentxu.pipeline.v2.dsl.StageScope

/**
 * External DSL facade. The plugin teaches the DSL its own step; core
 * compiler and coordinator never learn the name `uppercase`.
 * The user-visible script becomes:
 * ```kotlin
 * import example.uppercase.uppercase
 * pipeline {
 *     stages {
 *         stage("External") {
 *             uppercase("hello")
 *         }
 *     }
 * }
 * ```
 * Implementation: a thin wrapper over the generic `registryStep` primitive.
 * It lowers to `StepSpec.RegistryStepSpec` (declarative IR) and is executed by
 * the canonical spine; nothing here touches PipelineRun or any direct-execution
 * path.
 *
 * This is the route of the CONTRACT: it applies in every scope that hosts steps, and the
 * historic route on `StageScope` —the one a published script and a precompiled plugin name—
 * delegates to it, so the two declare the SAME step.
*/
fun <B : StepEmitter<B>> StepEmitter<B>.uppercase(text: String) {
    registryStep(
        stepKey = UppercaseStepDefinition.KEY,
        encodedInput = UppercaseCodec.encode(UppercaseInput(text)),
    )
}

/**
 * The HISTORIC route of `uppercase`, and the one a published script and a
 * precompiled plugin name.
 *
 * The receiver of a Kotlin extension is the FIRST parameter of its JVM
 * signature, so the receiver of this function IS part of the descriptor a
 * compiled consumer names. This route keeps the receiver `StageScope` and the
 * descriptor it had, and it delegates to the route of the contract: the two
 * declare the SAME step, and the one that runs is the one of the contract.
 */
fun StageScope.uppercase(text: String) {
    val emitter: StepEmitter<StageScope> = this
    emitter.uppercase(text)
}

/**
 * Same work, plus an event this plugin owns.
 * Separate rather than a flag on `uppercase` because the reference `uppercase` is deliberately
 * capability-free, and a `boolean observe` on it would quietly make every caller depend on a seam
 * the plain form never needed. Two entry points, two contracts, one plugin.
 * ```kotlin
 * import example.uppercase.uppercaseObserved
 * pipeline {
 *     stages {
 *         stage("External") {
 *             uppercaseObserved("hello")
 *         }
 *     }
 * }
 * ```
*/
fun <B : StepEmitter<B>> StepEmitter<B>.uppercaseObserved(text: String) {
    registryStep(
        stepKey = UppercaseObservedStepDefinition.KEY,
        encodedInput = UppercaseCodec.encode(UppercaseInput(text)),
    )
}

/**
 * The HISTORIC route of `uppercaseObserved`, and the one a published script and a
 * precompiled plugin name.
 *
 * The receiver of a Kotlin extension is the FIRST parameter of its JVM
 * signature, so the receiver of this function IS part of the descriptor a
 * compiled consumer names. This route keeps the receiver `StageScope` and the
 * descriptor it had, and it delegates to the route of the contract: the two
 * declare the SAME step, and the one that runs is the one of the contract.
 */
fun StageScope.uppercaseObserved(text: String) {
    val emitter: StepEmitter<StageScope> = this
    emitter.uppercaseObserved(text)
}

/**
 * Same work, watched by the runtime instead of described by the plugin.
 * Separate rather than a flag on either of the other two, because the two owners of an
 * observation are different: `uppercaseObserved` PUBLISHES an event about the work through the
 * Event Plane, and this one ASKS the runtime to observe it through the tracing seam. A flag
 * would have made one of the two shapes conditional, and a conditional seam is a shape the
 * nobody exercises.
 * ```kotlin
 * import example.uppercase.uppercaseMeasured
 * pipeline {
 *     stages {
 *         stage("External") {
 *             uppercaseMeasured("hello")
 *         }
 *     }
 * }
 * ```
*/
fun <B : StepEmitter<B>> StepEmitter<B>.uppercaseMeasured(text: String) {
    registryStep(
        stepKey = UppercaseMeasuredStepDefinition.KEY,
        encodedInput = UppercaseCodec.encode(UppercaseInput(text)),
    )
}

/**
 * The HISTORIC route of `uppercaseMeasured`, and the one a published script and a
 * precompiled plugin name.
 *
 * The receiver of a Kotlin extension is the FIRST parameter of its JVM
 * signature, so the receiver of this function IS part of the descriptor a
 * compiled consumer names. This route keeps the receiver `StageScope` and the
 * descriptor it had, and it delegates to the route of the contract: the two
 * declare the SAME step, and the one that runs is the one of the contract.
 */
fun StageScope.uppercaseMeasured(text: String) {
    val emitter: StepEmitter<StageScope> = this
    emitter.uppercaseMeasured(text)
}
