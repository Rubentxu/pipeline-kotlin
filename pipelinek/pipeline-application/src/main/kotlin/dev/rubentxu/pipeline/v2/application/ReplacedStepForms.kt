package dev.rubentxu.pipeline.v2.application

/**
 * The form of this product that replaces a step the canonical bridge refuses.
 *
 * ## WHAT THIS TABLE IS
 *
 * The bridge refuses a step that no registered definition owns. That refusal
 * says the RULE and names the STEP, and it is a complete answer for a step
 * this product does not have at all.
 *
 * It is NOT a complete answer for a step this product has under ANOTHER NAME:
 * the author wrote the form of Jenkins, and the product owns the form that
 * replaces it. For those steps the refusal says which form to write.
 *
 * ## WHAT THIS TABLE IS NOT
 *
 * It is not the reason the two products differ. That reason is PUBLISHED, and
 * it lives in the divergence of the entry that declares the form: the
 * divergence of the `agent` directive, for the node of Jenkins. This table
 * names only the FORM, so the two texts cannot drift apart and the catalogue
 * does not publish the same sentence twice.
 *
 * ## WHY IT LIVES HERE AND NOT IN THE DOMAIN
 *
 * Because this table is a fact of the REFUSAL, and the refusal is composed by
 * the application, in the module that prints it. The published ABI of the
 * domain is not moved for a sentence that only the refusal reads.
 */
internal data class ReplacedStepForm(

    /** The key of the refused step, as the compiled step carries it. */
    val pluginStepId: String,

    /** How Jenkins writes the form this product replaces. */
    val jenkinsForm: String,

    /** How this product writes the form that replaces it. */
    val pipelineKForm: String,

    /** What the refusal says, after the line that names the step. */
    val suggestion: String,
)

/**
 * The forms this product owns, for the steps the canonical bridge refuses.
 *
 * ## WHY THE LIST IS SHORT
 *
 * Because a row here is a PROMISE: the form it names has to WORK. The table
 * carries only the forms that were measured running, and a form that was not
 * measured does not go in: a suggestion that names a form that fails is worse
 * than no suggestion, because the author trusts it and loses a second time.
 *
 * ## WHY A REFUSED STEP CAN HAVE A FORM AT ALL
 *
 * Because the refusal is about the STEP, not about the form. The node of
 * Jenkins lowers to a key no registered definition owns, so the bridge refuses
 * it — and the agent of a stage, which is the form that replaces it, is a
 * DIRECTIVE and not a step: it is resolved before the body runs and it never
 * reaches the bridge.
 */
internal object ReplacedStepForms {

    /**
     * The form that replaces the refused step, or null when this product has none.
     *
     * It is null and not an exception: a step this product does not have at
     * all is a refusal that has nothing else to say, and a refusal that has
     * nothing else to say is still a refusal.
     */
    fun of(pluginStepId: String): ReplacedStepForm? =
        global().firstOrNull { form -> form.pluginStepId == pluginStepId }

    /**
     * The forms, in the order a reader meets them.
     */
    fun global(): List<ReplacedStepForm> = listOf(
        ReplacedStepForm(
            pluginStepId = "core.node",
            jenkinsForm = "node { ... }",
            pipelineKForm = "agent(...)",
            suggestion = "In PipelineK: the node { ... } of Jenkins is the agent directive of the " +
                "stage: write agent(...) on the stage.",
        ),
    )
}
