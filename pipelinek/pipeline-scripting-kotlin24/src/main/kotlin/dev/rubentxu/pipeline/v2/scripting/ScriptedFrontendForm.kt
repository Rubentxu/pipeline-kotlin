package dev.rubentxu.pipeline.v2.scripting

/**
 * Which of the two FORMS a `.pipeline.kts` source takes, and the rule that
 * decides it.
 *
 * ## The two forms
 *  - [Form.GENERATOR_LEVEL]: the source's body IS the program. It is lowered
 *    into a generated entry point and executed step by step.
 *  - [Form.EAGER_DSL]: the source BUILDS a `PipelineSpec` through the eager
 *    DSL (`pipeline { stages { stage { ... } } }`), and that spec is what runs.
 *
 * ## What decides it, and what used to decide it
 *
 * The form used to be decided by a REGEX over the script's TEXT: the source
 * was the generator-level form iff it held a runtime-returning call AND its
 * text did not contain `pipeline {`. The text is the wrong owner of that
 * decision. A comment that mentions `pipeline {`, a string that contains it,
 * or the same program spelled with another spacing all changed the form of a
 * program that had not changed at all — the same structure, two execution
 * forms, decided by letters.
 *
 * The decision now reads the STRUCTURE the compiler already parsed: the
 * source is the eager form iff it CALLS the DSL's entry point. A mention of
 * the word is prose, and prose does not declare a DSL.
 *
 * ## What did not change
 *
 * The RULE is the same one the text was reading: a source with no
 * runtime-returning call is the eager form, and a source that declares the
 * eager DSL entry point is the eager form whatever else it holds. What
 * changed is the EVIDENCE the rule reads, not the set of forms: this object
 * moves the DECISION. It adds no form, removes none, and moves no authority
 * from one backend to another.
*/
object ScriptedFrontendForm {
    /** The two forms a scripted source can take. */
    enum class Form { GENERATOR_LEVEL, EAGER_DSL }

    /**
     * The form of [source], read from its structure.
     *
     * The source is read ONCE: the mapping and the eager-DSL declaration come
     * from the same parse, because the compiler already knows both.
    */
    fun select(source: ScriptedSource): Form {
        val structure = KotlinScriptedSourceMapper().structure(source)
        val runtimeReturningCalls = (structure.mapping as? ScriptedSourceMapping.Mapped)
            ?.calls
            ?.count { it.kind.isRuntimeReturning() }
            ?: 0
        return if (runtimeReturningCalls > 0 && !structure.declaresEagerDslEntryPoint) {
            Form.GENERATOR_LEVEL
        } else {
            Form.EAGER_DSL
        }
    }
}
