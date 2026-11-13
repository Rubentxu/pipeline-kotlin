package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue

/**
 * The contract a plugin writes steps through.
 *
 * ## What it is
 *
 * The MINIMAL surface a constructor of steps needs, and nothing else. A plugin
 * publishes its own steps —`httpRequest`, `uppercase`, `repeatBlock`, a
 * checkout— by declaring them through this contract, and the contract knows
 * nothing about the stage that hosts them: no agent, no options, no post
 * conditions and no `when` predicates. Those belong to the stage, and a
 * constructor of steps never used them.
 *
 * ## Why it exists
 *
 * The step constructors used to be extensions on `StageScope`, and that made a
 * plugin depend on the whole stage: a plugin could only publish a step where a
 * stage was, and the stage's configuration travelled with the constructor even
 * though the constructor never read it. The contract separates the two: the step
 * travels, the stage's configuration stays with the stage.
 *
 * ## What the two members mean
 *
 * - [registryStep] declares a step the plugin's own handler runs.
 * - [registryBlock] declares a step that carries a BODY, and the body is
 *   written in the same contract: the body of a plugin's block is a body of
 *   steps, and it is written by the author of the pipeline.
 *
 * The type parameter is the SCOPE that hosts the body, and it is the scope
 * itself and not a copy of it: the body of a block written in a stage is
 * written with the stage's own capabilities, and the body of a block written in
 * a branch is written with the branch's. That is what keeps a plugin's block
 * from losing the capabilities its context had.
 *
 * ## The SCHEMA VERSION is not a parameter of the contract
 *
 * The version of the envelope is the DSL's business and not the plugin's: the
 * payload of a registry step is in the DSL's generic envelope, and the plugin
 * states the STEP and the PAYLOAD. So the contract declares the two members
 * without the version, and the scope that implements the contract supplies its
 * own.
 *
 * ## How a scope implements it
 *
 * - A `StageScope`, and the scopes it opens for a nested block, implement it:
 *   a nested block is a lexical block of the same stage.
 * - A `BranchScope` implements it: a branch is a body of steps of its own.
 * - The scopes of the post conditions implement it too, and they REFUSE the
 *   declaration with a message that names the restriction: a post condition
 *   hosts the steps the DSL provides and not the steps of a plugin, and a
 *   refusal that says so is worth more than a call that does not resolve.
 *
 * ## What it does NOT do
 *
 * It does not resolve the runtime registry, does not execute a handler, does
 * not decide whether a step may run and does not add a dispatcher per plugin
 * to the compiler: the steps it declares are the generic registry steps the
 * compiler already projects, and the runtime already dispatches them.
 */
interface StepEmitter<B : StepEmitter<B>> {

    /**
     * Declares a step the plugin's own handler runs.
     *
     * @param stepKey the key the plugin registered its step under.
     * @param encodedInput the step's input, in the plugin's own encoding.
     */
    fun registryStep(
        stepKey: PluginStepId,
        encodedInput: EncodedStepValue,
    )

    /**
     * Declares a step that carries a body of steps.
     *
     * @param stepKey the key the plugin registered its step under.
     * @param encodedInput the step's input, in the plugin's own encoding.
     * @param block the steps the block governs, written in the scope that
     *   hosts the body.
     */
    fun registryBlock(
        stepKey: PluginStepId,
        encodedInput: EncodedStepValue,
        block: B.() -> Unit,
    )
}
