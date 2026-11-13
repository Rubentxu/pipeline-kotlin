package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDefinition
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveDecodeResult
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey
import dev.rubentxu.pipeline.v2.domain.directive.DirectivePhase
import dev.rubentxu.pipeline.v2.domain.directive.ExecutionTargetRequirement
import dev.rubentxu.pipeline.v2.domain.directive.ExecutionTargetRequirementCodec
import dev.rubentxu.pipeline.v2.domain.directive.JenkinsSurfaceDeclaration
import dev.rubentxu.pipeline.v2.domain.directive.TargetLeaseResult
import dev.rubentxu.pipeline.v2.domain.directivekey.AGENT_DIRECTIVE_KEY

/**
 * The `core.agent` directive definition.
 * The second real [DirectiveDefinition] on the kernel, and the first whose
 * declared [DirectiveExecutionPolicy] is neither a gate nor an observation. It
 * is built exactly like [WhenDirectiveDefinition]: it contributes through the
 * registry, it knows nothing about the engine, and the engine reads its policy
 * rather than its key. If resolving an execution target had required a case in
 * the coordinator, "open by key" would already be a lie.
 * Its output is [TargetLeaseResult] rather than a verdict because the two
 * outcomes are not the same kind of thing: a gate answers "may this stage
 * proceed", a resource answers "what is this stage running on". Collapsing them
 * would give the caller a single boolean and force it to invent the target it
 * was already told about.
*/
class AgentDirectiveDefinition :
    DirectiveDefinition<ExecutionTargetRequirement, TargetLeaseResult>,
    JenkinsSurfaceDeclaration {

    override val key: DirectiveKey get() = AGENT_DIRECTIVE_KEY

    /**
     * The equivalent in Jenkins: the `node` step.
     *
     * It is the step that allocates an executor there, and the name is what a
     * reader coming from Jenkins is going to look for.
     */
    override val jenkinsSurface: String get() = "node"

    /**
     * The divergence with the Jenkins `node`, with its motive.
     *
     * In Jenkins the step ALLOCATES an executor on an agent. Here nothing is
     * allocated: the run executes on the machine where it started. The agent
     * survives only as a DIRECTIVE of the stage — resolved before the body
     * runs, and carried by the plans composed before the language stopped
     * authoring it — and the language does not author it any more.
     */
    override val jenkinsDivergence: String get() =
        "in Jenkins the node step ALLOCATES an executor on an agent; here nothing is allocated: " +
            "the run executes on the machine where it started. The agent survives only as a " +
            "DIRECTIVE of the stage, and the language no longer authors it."

    /**
     * Resolved before the stage body runs.
     * Same phase as a gate, and deliberately: a stage must not start a body
     * that depends on a target nobody has resolved. The policies differ in what
     * they do at that phase, not in when it is.
*/
    override val phase: DirectivePhase get() = DirectivePhase.BEFORE_STAGE

    override val policy: DirectiveExecutionPolicy get() =
        DirectiveExecutionPolicy.Resource(DirectivePhase.BEFORE_STAGE)

    /**
     * Decodes through the requirement's OWN codec.
     * The engine never learns the payload type, which is what lets a vendor
     * resource directive arrive later with a different carrier and no engine
     * change. A malformed payload is a typed value, not an exception crossing
     * the seam.
*/
    override fun decode(encodedArguments: String): DirectiveDecodeResult<ExecutionTargetRequirement> =
        ExecutionTargetRequirementCodec.decode(encodedArguments)
}
