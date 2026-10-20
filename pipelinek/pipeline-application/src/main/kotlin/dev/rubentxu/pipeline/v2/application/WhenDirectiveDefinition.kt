package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDefinition
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDecodeResult
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey
import dev.rubentxu.pipeline.v2.domain.directive.DirectivePhase
import dev.rubentxu.pipeline.v2.domain.directive.GateVerdict
import dev.rubentxu.pipeline.v2.domain.directive.WhenPredicate
import dev.rubentxu.pipeline.v2.domain.directive.WhenPredicateCodec
import dev.rubentxu.pipeline.v2.domain.directivekey.WHEN_DIRECTIVE_KEY

/**
 * S2-A — the `core.when` directive definition.
 *
 * This is the FIRST real [DirectiveDefinition] on the S1 kernel, and it is
 * deliberately built with zero engine knowledge of `when`: it contributes
 * through the registry like any external vendor directive would. If adding it
 * required a case in the coordinator, the "open by key" half of the S1 exit
 * criterion would be a lie.
 *
 * It declares [DirectiveExecutionPolicy.Gate], which is the structural signal
 * that says "evaluate me before the stage body and let me stop it". The engine
 * reads the POLICY, never the key, so this class contributes semantics while
 * the engine stays closed.
 *
 * It lives here, beside the other `core.*` definitions, and NOT in the
 * `pipeline-domain` directive kernel. The S1 fitness rule
 * (`the kernel declares no hardcoded directive names`) forbids namespaced
 * literals inside the kernel precisely because a concrete plugin instance is a
 * composition fact, not part of the seam. The domain owns the KEY TYPE and the
 * pure predicate codec; the core plugin owns its OWN name, published from
 * `...domain.directivekey` so the DSL and the runtime cannot diverge.
 */
/** The `core.when` definition. */
class WhenDirectiveDefinition : DirectiveDefinition<WhenPredicate, GateVerdict> {

    override val key: DirectiveKey get() = WHEN_DIRECTIVE_KEY

    /**
     * A gate is considered BEFORE the stage body, and it is the only policy
     * that can stop the stage — which is precisely what a conditional is.
     */
    override val phase: DirectivePhase get() = DirectivePhase.BEFORE_STAGE

    override val policy: DirectiveExecutionPolicy get() = DirectiveExecutionPolicy.Gate("core.when")

    /**
     * [DirectiveExecutionPolicy.Gate] names the predicate the interpreter
     * resolves, so the engine stays key-agnostic.
     */
    override fun decode(encodedArguments: String): DirectiveDecodeResult<WhenPredicate> =
        WhenPredicateCodec.decode(encodedArguments)
}
