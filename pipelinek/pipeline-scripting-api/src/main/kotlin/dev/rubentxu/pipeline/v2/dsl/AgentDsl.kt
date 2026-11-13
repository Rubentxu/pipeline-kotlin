package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.StageDirective
import dev.rubentxu.pipeline.v2.domain.directive.AgentLabel
import dev.rubentxu.pipeline.v2.domain.directive.ExecutionTargetRequirement
import dev.rubentxu.pipeline.v2.domain.directive.ExecutionTargetRequirementCodec
import dev.rubentxu.pipeline.v2.domain.directive.RemoteSelector
import dev.rubentxu.pipeline.v2.domain.directivekey.AGENT_DIRECTIVE_KEY
import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * The encoded `core.agent` stage directive for [requirement].
 * A total, pure construction step, for the same reason `appendWhenGate` is: it
 * encodes a DECLARED requirement and nothing else. No I/O, no clock, no
 * manufactured runtime value. The DSL describes; the resolver interprets.
 * The key comes from `...domain.directivekey` rather than being written here, so
 * the script and the runtime cannot invent different names for the same
 * directive — the same reasoning that puts `WHEN_DIRECTIVE_KEY` in the domain
 * rather than in either module.
*/
internal fun appendAgentRequirement(
    requirement: ExecutionTargetRequirement,
): StageDirective = StageDirective(
    key = AGENT_DIRECTIVE_KEY.value,
    encodedArguments = ExecutionTargetRequirementCodec.encode(requirement),
)

/**
 * The requirements the `agent` declarations carry.
 *
 * ## Why ONE authority and not one per layer
 *
 * The stage declares its agent and the pipeline declares the agent that is the
 * DEFAULT of the stages that declare none. Both spellings are the same
 * declaration, so both have to refuse the same contradictions with the same
 * words: a rule copied into the two layers is two rules that can drift, and the
 * drift shows up as a script the stage accepts and the pipeline refuses — or the
 * other way round, which is worse, because the refusal arrives where the author
 * did not write anything.
 *
 * ## Why an OBJECT and not three top-level functions
 *
 * Because the promise of a refusal has to be VISIBLE from the function that
 * makes it. The guard that measures the KDoc promises (`check-kdoc-throws.py`)
 * follows a call it can resolve: a member of the enclosing type, or a top-level
 * function of the same file. A top-level function of ANOTHER file is a call it
 * cannot follow, so a `@throws` that the shared builder keeps would read as a
 * promise the code does not keep — and the guard would be right to say so: the
 * promise is a contract, and a contract nobody can check is a contract nobody
 * keeps. A member of a named object is reachable from both layers and from
 * both files.
 */
internal object AgentRequirements {

    /**
     * The requirement an `agent(...)` declaration carries.
     *
     * @param label the label of a local target, or `null` for a remote selector
     * @param remoteUri the selector of a remote target, or `null` for a local label
     * @throws IllegalArgumentException when neither is supplied, or when both are
     */
    fun of(label: String?, remoteUri: String?): ExecutionTargetRequirement {
        require(label != null || remoteUri != null) {
            "agent requires a label, a remoteUri, or both: declaring no constraint at all is " +
                "what agentAny() is for, so an empty declaration should say so explicitly"
        }
        require(!(label != null && remoteUri != null)) {
            "agent(label = \"$label\", remoteUri = \"$remoteUri\") is contradictory: a remote " +
                "target is selected by selector, not by label. Declare a local label target or a " +
                "remote selector target, not both."
        }
        return if (remoteUri != null) {
            ExecutionTargetRequirement.Remote(RemoteSelector(remoteUri))
        } else {
            // `requireNotNull` and not `label!!`: the `require` above already refuses the
            // empty declaration, and this states the same fact to the compiler in a way that
            // does not pretend the value could be null here.
            ExecutionTargetRequirement.LocalLabels(setOf(AgentLabel(requireNotNull(label))))
        }
    }

    /**
     * The requirement an `agentAny()` declaration carries: a local target with no
     * constraint beyond "somewhere local".
     *
     * It is a requirement of its own and not the absence of one: an observer can
     * tell a deliberate [ExecutionTargetRequirement.LocalAny] from a stage that
     * never mentioned an agent at all.
     */
    fun any(): ExecutionTargetRequirement = ExecutionTargetRequirement.LocalAny

    /**
     * The requirement an `agentWithCapabilities(..)` declaration carries.
     *
     * The capability set is checked against what the run was actually GRANTED,
     * not against what the runtime could provide in principle, so a stage cannot
     * ask for a capability nobody bound and be told yes.
     *
     * @param capabilities the capability keys the target must provide
     * @throws IllegalArgumentException when the set is empty, because an empty
     *   set is indistinguishable from [any]
     */
    fun withCapabilities(capabilities: Array<out String>): ExecutionTargetRequirement {
        require(capabilities.isNotEmpty()) {
            "agentWithCapabilities requires at least one capability key; use agentAny() for an " +
                "unconstrained local target, because an empty set is indistinguishable from it"
        }
        return ExecutionTargetRequirement.CapabilitySet(
            capabilities.map { StepCapability(it) }.toSet(),
        )
    }
}

/**
 * The scope an `agent { }` block opens.
 *
 * ## What the block holds
 *
 * The OPTIONS of the agent, and nothing else. The block of Jenkins holds the
 * label, the container, the pod and their options, and admits no step at all;
 * the restriction here is the same one, and it is carried by the TYPE of the
 * block: the scope carries the marker of the step layer AND the marker of the
 * pipeline layer, so the scope that opened the block is out of reach from
 * inside it and the compiler refuses a step instead of declaring it.
 *
 * ## Why the vocabulary is the label and nothing else
 *
 * Because that is the whole of what a local target can be required to have: a
 * NAME the target has to advertise. The remote selector of this product has
 * its own spelling —`agent(remoteUri = ...)`— and it is NOT here: the block is
 * the block of Jenkins, and a spelling of Jenkins that this product does not
 * have would be a second way of saying the same thing.
 *
 * ## Why the block declares ONE label
 *
 * Because the block of Jenkins has ONE `label` option, and because the block
 * and the call have to declare the same agent: the requirement of a call
 * carries one label, and a block that carried two would be a declaration the
 * call cannot make.
 *
 * ## What this scope does NOT do
 *
 * It does not resolve anything: the requirement it builds is a CONSTRAINT, and
 * the runtime decides whether the machine the run is on satisfies it.
 *
 * ## Why the scope carries TWO markers
 *
 * Because the block opens at TWO levels —inside a stage and inside a
 * pipeline— and it has to hide the scope that opened it at either of them. A
 * DSL marker hides the outer receivers that carry the SAME marker (measured
 * with the compiler of this product), so the scope carries the marker of the
 * step layer AND the marker of the pipeline layer: inside a stage the step
 * marker hides the stage, and inside a pipeline the pipeline marker hides the
 * pipeline. A scope with one of the two would let the other level's calls
 * through.
 */
@StepDslMarker
@PipelineDslMarker
class AgentScope {

    /**
     * The label the target has to advertise, or `null` when the block
     * declared none.
     */
    private var declaredLabel: String? = null

    /**
     * The label the target has to advertise.
     *
     * The name is the one of Jenkins: the option of the block is `label`, and
     * the block of a Jenkinsfile writes `agent { label 'linux' }`.
     *
     * @param label the label of a local target
     * @throws IllegalArgumentException when the block already declared a
     *   label, because the block declares one and the call declares one
     */
    fun label(label: String) {
        require(declaredLabel == null) {
            "the block declares ONE label: the block of Jenkins has one `label` option, and a " +
                "target is required to carry the labels of ONE declaration. Declare one agent."
        }
        declaredLabel = label
    }

    /**
     * The requirement the block declares.
     *
     * It is built by the SAME authority the call uses, so a block and a call
     * that declare the same agent declare the same agent, and the directive
     * that carries it is the same one.
     *
     * @throws IllegalArgumentException when the block declared no label
     */
    internal fun requirement(): ExecutionTargetRequirement {
        val label = requireNotNull(declaredLabel) {
            "the block declares a label: `agent { label(\"linux\") }`; an agent with no " +
                "constraint at all is `agentAny()`"
        }
        return AgentRequirements.of(label, null)
    }
}
