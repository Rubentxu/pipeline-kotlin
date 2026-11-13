package example.block

import dev.rubentxu.pipeline.v2.dsl.StepBodyScope
import dev.rubentxu.pipeline.v2.dsl.StepEmitter
import dev.rubentxu.pipeline.v2.dsl.StageScope

/**
 * External DSL facade for a body-bearing Step
 * The plugin teaches the DSL its own block Step. Core, compiler and coordinator never learn
 * the name `repeat`; the generic `registryBlock` primitive lowers to `StepSpec.RegistryBlockSpec`
 * and the engine resolves the declared body policy from the open registry.
 * ```kotlin
 * import example.block.repeatBlock
 * pipeline {
 *     stages {
 *         stage("Repeat") {
 *             repeatBlock(3) {
 *                 sh("./gradlew test")
 *             }
 *         }
 *     }
 * }
 * ```
 * Everything this facade does is declarative: it encodes a typed input and hands a body to
 * the primitive. It does not resolve the runtime registry, does not execute the handler, does
 * not access capabilities and does not know how the body will run.
 *
 * This is the route of the CONTRACT: it applies in every scope that hosts steps, and the
 * historic route on `StageScope` —the one a published script and a precompiled plugin name—
 * delegates to it, so the two declare the SAME block.
*/
fun <B : StepEmitter<B>> StepEmitter<B>.repeatBlock(times: Int, block: B.() -> Unit) {
    registryBlock(
        stepKey = RepeatBodyStepDefinition.KEY,
        encodedInput = RepeatInputCodec.encode(RepeatInput(times)),
        block = block,
    )
}

/**
 * The HISTORIC route of `repeatBlock`, and the one a published script and a
 * precompiled plugin name.
 *
 * The receiver of a Kotlin extension is the FIRST parameter of its JVM
 * signature, so the receiver of this function IS part of the descriptor a
 * compiled consumer names. This route keeps the receiver `StageScope` and the
 * descriptor it had, and it delegates to the route of the contract: the two
 * declare the SAME block, and the one that runs is the one of the contract.
 *
 * The body is written in the scope that hosts it, and that scope is the body
 * of steps: the body of a plugin's block is a body of steps of the stage that
 * carries it, and it receives the narrow receiver of a body.
 */
fun StageScope.repeatBlock(times: Int, block: StepBodyScope.() -> Unit) {
    val emitter: StepEmitter<StepBodyScope> = this
    emitter.repeatBlock(times, block)
}
