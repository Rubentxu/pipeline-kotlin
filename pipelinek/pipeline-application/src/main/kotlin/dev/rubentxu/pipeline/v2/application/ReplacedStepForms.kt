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
 * says what to WRITE, so the two texts cannot drift apart and the catalogue
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

    /** How Jenkins writes the form this product does not have. */
    val jenkinsForm: String,

    /** What this product does with the form, when it has no form of its own. */
    val pipelineKForm: String,

    /** What the refusal says, after the line that names the step. */
    val suggestion: String,
)

/**
 * The forms this product owns, for the steps the canonical bridge refuses.
 *
 * ## WHY THE LIST IS SHORT
 *
 * Because a row here is a PROMISE: what it names has to WORK. The table
 * carries only the forms that were measured running, and a form that was not
 * measured does not go in: a suggestion that names a form that fails is worse
 * than no suggestion, because the author trusts it and loses a second time.
 *
 * The same rule, in the other direction: a suggestion that names a form this
 * product DOES NOT HAVE is the same defect wearing a different hat. The
 * author of a migrated script reads the sentence and writes the form, and a
 * form that is not there is a second refusal they did not ask for. So a step
 * this product refuses because it has no counterpart says SO, and it says what
 * to write instead.
 *
 * ## WHY A REFUSED STEP CAN HAVE A FORM AT ALL
 *
 * Because the refusal is about the STEP, not about the form. The node of
 * Jenkins lowers to a key no registered definition owns, so the bridge refuses
 * it — and what replaced it in this product was a DIRECTIVE of the stage, not a
 * step: it was resolved before the body ran and it never reached the bridge.
 * The language does not author that directive any more, so the refusal names
 * the ABSENCE and the way out.
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
            pipelineKForm = "no agent block: the stage runs on the machine where the run started",
            suggestion = "In PipelineK: this product does not allocate an executor on an agent, " +
                "and there is no `node { ... }` block to write. The stage runs on the machine " +
                "where the run started: write the steps of the stage directly.",
        ),
    )
}
