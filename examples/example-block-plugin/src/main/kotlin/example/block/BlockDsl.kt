package example.block

import dev.rubentxu.pipeline.v2.dsl.StageScope

/**
 * External DSL facade for a body-bearing Step (WP-035, slice D).
 *
 * The plugin teaches the DSL its own block Step. Core, compiler and coordinator never learn
 * the name `repeat`; the generic `registryBlock` primitive lowers to `StepSpec.RegistryBlockSpec`
 * and the engine resolves the declared body policy from the open registry.
 *
 * ```kotlin
 * import example.block.repeatBlock
 *
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
 *
 * Everything this facade does is declarative: it encodes a typed input and hands a body to
 * the primitive. It does not resolve the runtime registry, does not execute the handler, does
 * not access capabilities and does not know how the body will run.
 */
fun StageScope.repeatBlock(times: Int, block: StageScope.() -> Unit) {
    registryBlock(
        stepKey = RepeatBodyStepDefinition.KEY,
        encodedInput = RepeatInputCodec.encode(RepeatInput(times)),
        block = block,
    )
}
