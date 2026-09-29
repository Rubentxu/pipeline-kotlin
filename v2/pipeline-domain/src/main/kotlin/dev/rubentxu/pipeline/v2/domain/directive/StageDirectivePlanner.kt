package dev.rubentxu.pipeline.v2.domain.directive

import dev.rubentxu.pipeline.v2.domain.StageDirective
import dev.rubentxu.pipeline.v2.domain.StageNode

/**
 * S1-B — Stage directive planning: the pure decision half of the engine seam.
 *
 * The planner turns a stage's declared directives into a typed decision that a
 * coordinator can interpret at the effect boundary. It is a PURE transformation:
 * no clock, no filesystem, no events, no I/O. The coordinator (application) is
 * the interpreter; this file is the decision.
 *
 * Fail-closed law: the FIRST directive that cannot be admitted denies the whole
 * stage. An unknown key is never skipped, because skipping would turn a
 * misspelled or uninstalled directive into a silent no-op — the exact defect
 * class the Semantic Honesty Gate removes. Admission happens before any effect,
 * so the denial observable is "the stage never started", not "the stage started
 * and something inside failed".
 */

/** One admitted directive, with the policy the engine must later interpret. */
data class AdmittedDirective(
    val invocation: DirectiveInvocation,
    val policy: DirectiveExecutionPolicy,
)

/**
 * The typed outcome of planning one stage's directives.
 *
 * A coordinator reads this value; it MUST NOT re-derive admission itself.
 */
sealed interface StageDirectiveDecision {
    /**
     * Every declared directive is registered. Directives are grouped by their
     * declared [DirectivePhase]; every phase key is present (possibly empty) so
     * the interpreter can exhaustively walk phases without null checks.
     */
    data class Permitted(
        val phases: Map<DirectivePhase, List<AdmittedDirective>>,
    ) : StageDirectiveDecision

    /**
     * At least one directive cannot be admitted. [reason] names the key and the
     * stage; it is the diagnostic the operator sees.
     */
    data class Denied(
        val key: DirectiveKey,
        val reason: String,
    ) : StageDirectiveDecision
}

/** Deterministic, pure planner over the [DirectiveRegistry]. */
object StageDirectivePlanner {

    fun decide(registry: DirectiveRegistry, stage: StageNode): StageDirectiveDecision {
        val byPhase: MutableMap<DirectivePhase, MutableList<AdmittedDirective>> =
            DirectivePhase.entries.associateWithTo(mutableMapOf()) { mutableListOf() }

        for (declared in stage.directives) {
            val key = DirectiveKey(declared.key)
            val invocation = DirectiveInvocation(key, declared.encodedArguments)

            // Phase and policy are registry metadata readable WITHOUT decoding
            // arguments (the StepDescriptor analogue). Admission is the typed,
            // fail-closed gate; the definition view supplies only the phase.
            val definition = registry.find(key)
                ?: return StageDirectiveDecision.Denied(
                    key,
                    "unresolved directive '${key.value}' in stage '${stage.name}': " +
                        "no definition registered; refusing to run the stage",
                )

            when (val admission = registry.admit(invocation)) {
                is DirectiveAdmission.Rejected ->
                    return StageDirectiveDecision.Denied(key, admission.reason)

                is DirectiveAdmission.Admitted ->
                    byPhase.getValue(definition.phase) +=
                        AdmittedDirective(invocation, admission.policy)
            }
        }

        return StageDirectiveDecision.Permitted(byPhase)
    }
}
