package dev.rubentxu.pipeline.v2.domain.directive

/**
 * S3-R1-C — resource composition: the PURE decision layer over already-admitted
 * and already-decoded resource directives.
 *
 * ## Why this exists when the only Resource key in production is the agent one
 *
 * Because the law is about the POLICY, not the key. A stage runs on ONE target, so two
 * target requests describe a state the model does not have — and before this planner
 * existed the engine resolved them SEQUENTIALLY, emitting two `ExecutionTargetResolved`
 * events for one stage and letting declaration order decide which requirement was
 * honoured. On the local profile that looked harmless, because both resolve to the same
 * host. In RP-8, when a target is a real lease, it is acquire-acquire on one resource
 * with no defined composition.
 *
 * The tempting fix is to test the key against a hardcoded name, which the
 * kernel fitness rejects by design: a namespaced directive literal must not
 * appear in this module at all, not even in prose. That is the right rule, and
 * the KDoc here is written to respect it rather than to quote the forbidden
 * fix verbatim.
 *
 * and it is wrong in the way this repository has already been wrong twice: a central
 * switch on a concrete name (STEP CONSTITUTION §7), correct today and silently wrong the
 * moment a vendor contributes a second resource directive. The law below reads the
 * [DirectiveExecutionPolicy.Resource] the definition DECLARED, so it holds for a key
 * that does not exist yet — which is why the "different keys are legitimate" half is
 * pinned by a test that contributes a second one.
 *
 * ## Why the decision is `Declared` and not a composition
 *
 * Unlike gates, resources do not combine. There is no AND of two execution targets
 * because a stage occupies exactly one. Inventing a composition law here would be
 * inventing semantics for a case that has never occurred, so the planner states only
 * what it can decide: whether these declarations are individually interpretable. What
 * happens when two DIFFERENT resources are declared together is genuinely open and is
 * left visibly open for whoever defines it, rather than settled by a default.
 *
 * The duplicate half, by contrast, is decidable now and is a real authoring mistake:
 * two writes to the same single-valued slot.
 */
object ResourceCompositionPlanner {

    /** One resource directive already decoded by the interpreter. */
    data class DeclaredResource(
        val key: DirectiveKey,
        val requirement: ExecutionTargetRequirement,
    )

    /**
     * Decide whether [resources] (in declaration order) are individually
     * interpretable, or report a typed conflict.
     */
    fun compose(resources: List<DeclaredResource>): ResourceCompositionDecision {
        val duplicate = resources.groupBy { it.key }.entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) {
            val count = duplicate.value.size
            return ResourceCompositionDecision.Conflicting(
                duplicate.key,
                "directive '${duplicate.key.value}' is declared $count times on the same stage, " +
                    "and a resource request names ONE thing the stage depends on. Two " +
                    "declarations do not narrow a target, they describe two of them and leave " +
                    "the runtime to pick one. Declare it once — a target that needs several " +
                    "labels belongs in a single agent(label = \"…\") declaration.",
            )
        }

        return ResourceCompositionDecision.Declared(resources)
    }
}

/**
 * The typed outcome of checking one stage's resource directives.
 *
 * A coordinator reads this value; it MUST NOT re-derive it itself.
 */
sealed interface ResourceCompositionDecision {

    /**
     * No duplicate: every declaration is individually interpretable and each must be
     * resolved.
     *
     * "Each", not "one": with a single Resource key the list is always 0 or 1 here, and
     * the day a second resource directive arrives this is the case that will have to
     * decide what two distinct resources mean. It is left explicit rather than
     * collapsed into a single "resolved" target, so that decision cannot be made by
     * accident.
     */
    data class Declared(val resources: List<ResourceCompositionPlanner.DeclaredResource>) :
        ResourceCompositionDecision

    /**
     * The same key declared twice on one stage. Fail-closed: the stage never starts, no
     * target is resolved, and [reason] names the offending key.
     */
    data class Conflicting(
        val key: DirectiveKey,
        val reason: String,
    ) : ResourceCompositionDecision
}
