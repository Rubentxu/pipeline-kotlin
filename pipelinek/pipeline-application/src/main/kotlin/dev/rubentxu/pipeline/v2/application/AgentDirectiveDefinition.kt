package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDefinition
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDecodeResult
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey
import dev.rubentxu.pipeline.v2.domain.directive.DirectivePhase
import dev.rubentxu.pipeline.v2.domain.directive.ExecutionTargetRequirement
import dev.rubentxu.pipeline.v2.domain.directive.ExecutionTargetRequirementCodec
import dev.rubentxu.pipeline.v2.domain.directive.TargetLeaseResult
import dev.rubentxu.pipeline.v2.domain.directivekey.AGENT_DIRECTIVE_KEY

/**
 * S3.1 — the `core.agent` directive definition.
 *
 * The second real [DirectiveDefinition] on the S1 kernel, and the first whose
 * declared [DirectiveExecutionPolicy] is neither a gate nor an observation. It
 * is built exactly like [WhenDirectiveDefinition]: it contributes through the
 * registry, it knows nothing about the engine, and the engine reads its policy
 * rather than its key. If resolving an execution target had required a case in
 * the coordinator, "open by key" would already be a lie.
 *
 * Its output is [TargetLeaseResult] rather than a verdict because the two
 * outcomes are not the same kind of thing: a gate answers "may this stage
 * proceed", a resource answers "what is this stage running on". Collapsing them
 * would give the caller a single boolean and force it to invent the target it
 * was already told about.
 */
class AgentDirectiveDefinition : DirectiveDefinition<ExecutionTargetRequirement, TargetLeaseResult> {

    override val key: DirectiveKey get() = AGENT_DIRECTIVE_KEY

    /**
     * Resolved before the stage body runs.
     *
     * Same phase as a gate, and deliberately: a stage must not start a body
     * that depends on a target nobody has resolved. The policies differ in what
     * they do at that phase, not in when it is.
     */
    override val phase: DirectivePhase get() = DirectivePhase.BEFORE_STAGE

    override val policy: DirectiveExecutionPolicy get() =
        DirectiveExecutionPolicy.Resource(DirectivePhase.BEFORE_STAGE)

    /**
     * Decodes through the requirement's OWN codec.
     *
     * The engine never learns the payload type, which is what lets a vendor
     * resource directive arrive later with a different carrier and no engine
     * change. A malformed payload is a typed value, not an exception crossing
     * the seam.
     */
    override fun decode(encodedArguments: String): DirectiveDecodeResult<ExecutionTargetRequirement> =
        ExecutionTargetRequirementCodec.decode(encodedArguments)
}
