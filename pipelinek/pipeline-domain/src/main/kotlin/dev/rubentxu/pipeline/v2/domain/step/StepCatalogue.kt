package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.BodyInvocationPolicy
import dev.rubentxu.pipeline.v2.domain.ContextKind
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepBody
import dev.rubentxu.pipeline.v2.domain.StepDescriptorRegistry
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveRegistry
import dev.rubentxu.pipeline.v2.domain.directive.JenkinsSurfaceDeclaration
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy

/**
 * One entry of the catalogue of what this product supports.
 *
 * ## The unit of an entry is the RUNTIME KEY
 *
 * An entry is named by the key the runtime resolves (`core.sh`, `core.agent`,
 * `scm-git.checkout`), and not by the function the author writes. The key is
 * the one axis that is COMPLETE: the registry enumerates every step the
 * composition can run, the plugins included, and every directive with it. The
 * DSL function is not enumerable from the registry — it lowers to a key by a
 * rule the compiler applies (`core.${name}`, with three exceptions written by
 * hand), it lowers to MORE THAN ONE key (`pwd` lowers to `core.pwd` and
 * `core.pwd.tmp`), and six keys of the registry have no DSL function at all.
 * An index keyed by the function would lose those six and would have to be
 * written by hand to keep them.
 *
 * ## What is READ and what is DECLARED
 *
 * Every fact of an entry is read from the composition that owns it: the
 * descriptor carries the effects, the policies and the schemas, and the codec
 * carries the schema of the payload. Nothing here is COPIED, so an entry
 * cannot disagree with the step it describes. The facts the code does not
 * carry — the equivalent in Jenkins, a deprecation notice, an example — are
 * absent here rather than empty: an entry that carries no equivalent says so
 * with an empty string, and the field exists so the schema of the catalogue
 * does not have to move when the equivalent is declared.
 */
sealed interface StepCatalogueEntry {

    /** The key the runtime resolves this entry by. */
    val key: String

    /** The name the author writes, when the entry has one. */
    val name: String

    /** The plugin that contributes this entry. */
    val provider: String

    /** The equivalent in Jenkins, empty when no equivalent is declared. */
    val jenkinsSurface: String

    /**
     * The divergence of this entry with the equivalent in Jenkins, with its motive.
     *
     * Empty when there is nothing to declare. It is NOT empty when the entry
     * has no equivalent in Jenkins: in that case it says WHY, so that an empty
     * surface is a statement and not an oversight.
     */
    val jenkinsDivergence: String

    /** The deprecation notice, empty when the entry is not deprecated. */
    val deprecation: String

    /** What kind of thing this entry is. */
    val kind: Kind

    /** The kinds of thing the catalogue holds. */
    enum class Kind {
        /** A step the runtime can run. */
        STEP,

        /** A directive the engine interprets structurally. */
        DIRECTIVE,
    }

    /**
     * A step the runtime can run.
     *
     * @property effects the side effects the step declares. They decide whether a replay may
     *   memoise the step or has to run it again.
     * @property replayPolicy what a replay does with the step.
     * @property recoveryPolicy what the engine does with a step that was RUNNING when the
     *   process died.
     * @property requiredCapabilities the capability keys the step needs to be admitted.
     * @property executionLocation where the step runs.
     * @property body the body the step takes, or that it is terminal.
     * @property inputSchema the JSON Schema fragment the input codec declares.
     * @property outputSchema the JSON Schema fragment the output codec declares.
     * @property jenkinsDivergence the divergence with the equivalent in Jenkins, with its motive.
     */
    data class Step(
        override val key: String,
        override val name: String,
        override val provider: String,
        override val jenkinsSurface: String,
        override val deprecation: String,
        val effects: List<String>,
        val replayPolicy: String,
        val recoveryPolicy: String,
        val requiredCapabilities: List<String>,
        val executionLocation: String,
        val body: StepCatalogueBody,
        val inputSchema: String,
        val outputSchema: String,
        override val jenkinsDivergence: String = "",
    ) : StepCatalogueEntry {
        override val kind: Kind get() = Kind.STEP
    }

    /**
     * A directive the engine interprets structurally.
     *
     * A directive is not a step: it has no effects, no replay and no recovery, because the
     * engine does not run it — it reads it, and the phase and the policy say what it means.
     *
     * @property phase the structural phase the directive is considered at.
     * @property policy what the directive does.
     * @property policyDetail the predicate the policy names, or the phase a resource
     *   directive asks for. Empty when the policy names neither.
     * @property jenkinsDivergence the divergence with the equivalent in Jenkins, with its motive.
     */
    data class Directive(
        override val key: String,
        override val name: String,
        override val provider: String,
        override val jenkinsSurface: String,
        override val deprecation: String,
        val phase: String,
        val policy: String,
        val policyDetail: String,
        override val jenkinsDivergence: String = "",
    ) : StepCatalogueEntry {
        override val kind: Kind get() = Kind.DIRECTIVE
    }
}

/**
 * The body a step takes, as the catalogue carries it.
 *
 * A terminal step has no body, and that is a FACT and not a missing value: the
 * field exists so a consumer can tell "this step takes no body" from "this
 * catalogue does not say".
 *
 * @property kind `none` for a terminal step, `declared` for a step that takes one.
 * @property invocation how many times the body may run.
 * @property owner the engine that executes the body.
 * @property policy the shape the body executes in.
 * @property introduces the context dimension the body introduces. Empty when it introduces none.
 * @property catchesInterruptions whether the step's semantics contain an interruption of its
 *   body.
 */
data class StepCatalogueBody(
    val kind: String,
    val invocation: String = "",
    val owner: String = "",
    val policy: String = "",
    val introduces: String = "",
    val catchesInterruptions: Boolean = false,
)

/**
 * The catalogue of what a composition supports.
 *
 * ## Why it is BUILT and not written
 *
 * The catalogue is a projection of the registries the composition holds, so it
 * cannot name a step nobody registered and cannot omit one everybody can use.
 * A hand-written list of the steps is a second authority for a fact the
 * registry already owns, and it drifts the first time a step is registered.
 */
object StepCatalogue {

    /**
     * The entries of [steps] and [directives], sorted by key.
     *
     * Sorted because the catalogue is an INDEX: two readers that ask the same
     * composition get the same order, and an order that follows the
     * registration order would change with the order the plugins were
     * discovered in.
     *
     * @param declared the families that are DECLARED without a registered
     *   handler —the core's block steps, the ones the DSL writes and the
     *   canonical body engine executes. They are part of what this product
     *   supports, and a catalogue that left them out would leave out
     *   `retry`, `timeout` and `catchError`: the very scopes a reader coming
     *   from Jenkins writes first. A key the composition ALSO registers is
     *   taken from the composition, because that is the one that runs.
     */
    fun entries(
        steps: StepRegistry,
        directives: DirectiveRegistry,
        declared: StepDescriptorRegistry = StepDescriptorRegistry.standard(),
    ): List<StepCatalogueEntry> {
        val stepEntries = steps.keys().mapNotNull { key -> stepEntry(steps, key) }
        val declaredEntries = declared.keys()
            .filter { key -> steps.definition(key) == null }
            .map { key -> declaredStepEntry(declared, key) }
        val directiveEntries = directives.keys().mapNotNull { key -> directiveEntry(directives, key) }
        return (stepEntries + declaredEntries + directiveEntries).sortedBy { it.key }
    }

    /**
     * The divergences that are the same for every script.
     *
     * They are DECLARED in the code and not written in a document, so the
     * catalogue publishes what the product does and not what a document
     * remembered. The ones that belong to a single entry live in that entry.
     */
    fun divergences(): List<JenkinsDivergence> = JenkinsDivergences.global()

    /**
     * The entry of the step [key], or null when the registry holds no definition for it.
     *
     * A key the registry names but does not define is skipped rather than
     * rendered empty: an entry without a descriptor would publish facts nobody
     * declared.
     */
    private fun stepEntry(registry: StepRegistry, key: PluginStepId): StepCatalogueEntry.Step? {
        val definition = registry.definition(key) ?: return null
        val descriptor = definition.contract.descriptor
        return StepCatalogueEntry.Step(
            key = key.value,
            name = descriptor.name,
            provider = descriptor.pluginId,
            jenkinsSurface = descriptor.jenkinsSurface,
            jenkinsDivergence = descriptor.jenkinsDivergence,
            deprecation = descriptor.deprecation,
            effects = descriptor.effects.map { it.name },
            replayPolicy = descriptor.replayPolicy.name,
            recoveryPolicy = recoveryPolicyName(descriptor.recoveryPolicy),
            requiredCapabilities = definition.contract.requiredCapabilities.map { it.key }.sorted(),
            executionLocation = descriptor.executionLocation.name,
            body = bodyOf(descriptor.body),
            inputSchema = definition.contract.inputCodec.schema(),
            outputSchema = definition.contract.outputCodec.schema(),
        )
    }

    /**
     * The entry of a family that is DECLARED without a registered handler.
     *
     * There is no codec and no contract for these rows: the canonical body
     * engine executes them from the declaration itself, and the payload
     * arrives through the wire codec of the scope that carries it. The entry
     * therefore carries what the declaration says —the body it takes, the
     * dimension it projects and whether it catches interruptions— and no
     * schema, because none is declared.
     */
    private fun declaredStepEntry(
        registry: StepDescriptorRegistry,
        key: PluginStepId,
    ): StepCatalogueEntry.Step {
        val descriptor = registry.get(key) ?: error("the registry named a key it does not hold: $key")
        return StepCatalogueEntry.Step(
            key = key.value,
            name = descriptor.name,
            provider = descriptor.pluginId,
            jenkinsSurface = descriptor.jenkinsSurface,
            jenkinsDivergence = descriptor.jenkinsDivergence,
            deprecation = descriptor.deprecation,
            effects = descriptor.effects.map { it.name },
            replayPolicy = descriptor.replayPolicy.name,
            recoveryPolicy = recoveryPolicyName(descriptor.recoveryPolicy),
            requiredCapabilities = descriptor.requiredCapabilities,
            executionLocation = descriptor.executionLocation.name,
            body = bodyOf(descriptor.body),
            inputSchema = descriptor.inputSchema,
            outputSchema = descriptor.outputSchema,
        )
    }

    /**
     * The entry of the directive [key], or null when the registry holds no definition for it.
     */
    private fun directiveEntry(
        registry: DirectiveRegistry,
        key: DirectiveKey,
    ): StepCatalogueEntry.Directive? {
        val definition = registry.find(key) ?: return null
        val policy = policyOf(definition.policy)
        // The Jenkins facts are the ones the definition DECLARED, and a
        // definition that declares none says nothing: the open SPI asks nothing
        // of the plugins that do not implement it.
        val jenkins = definition as? JenkinsSurfaceDeclaration
        return StepCatalogueEntry.Directive(
            key = key.value,
            name = key.value.substringAfterLast('.'),
            provider = key.value.substringBefore('.'),
            jenkinsSurface = jenkins?.jenkinsSurface ?: "",
            jenkinsDivergence = jenkins?.jenkinsDivergence ?: "",
            deprecation = "",
            phase = definition.phase.name,
            policy = policy.first,
            policyDetail = policy.second,
        )
    }

    /** The name of the recovery policy, read from the closed family it belongs to. */
    private fun recoveryPolicyName(policy: RecoveryPolicy): String = when (policy) {
        RecoveryPolicy.None -> "None"
        RecoveryPolicy.ExternalSubprocess -> "ExternalSubprocess"
    }

    /**
     * The body a step declares, as the catalogue carries it.
     *
     * The policy is carried as the NAME of the shape and not as the whole
     * value: the shape is what a reader of the catalogue asks about ("does
     * this step retry its body?"), and the parameters of the shape arrive with
     * the invocation.
     */
    private fun bodyOf(body: StepBody): StepCatalogueBody {
        val declared = body.declared ?: return StepCatalogueBody(kind = "none")
        return StepCatalogueBody(
            kind = "declared",
            invocation = invocationName(declared.invocation),
            owner = declared.execution.owner.name,
            policy = declared.execution.policy.shapeName(),
            introduces = declared.introduces?.let { contextKindName(it) }.orEmpty(),
            catchesInterruptions = declared.catchesInterruptions,
        )
    }

    /**
     * The name of the cardinality a body may run with.
     *
     * The family is closed, so the name is read from the cases and not from
     * the class: a name that follows the class of the value would change with
     * a refactor, and the catalogue is a published document.
     */
    private fun invocationName(policy: BodyInvocationPolicy): String = when (policy) {
        BodyInvocationPolicy.ONCE -> "ONCE"
        BodyInvocationPolicy.ZERO_OR_MORE -> "ZERO_OR_MORE"
        BodyInvocationPolicy.AT_MOST_ONCE -> "AT_MOST_ONCE"
    }

    /** The name of the context dimension a body introduces. */
    private fun contextKindName(kind: ContextKind): String = when (kind) {
        ContextKind.ENVIRONMENT -> "ENVIRONMENT"
        ContextKind.CWD -> "CWD"
        ContextKind.CREDENTIALS -> "CREDENTIALS"
        ContextKind.OUTPUT_DECORATOR -> "OUTPUT_DECORATOR"
        ContextKind.CANCELLATION -> "CANCELLATION"
    }

    /** The name of the shape a body executes in. */
    private fun BodyExecutionPolicy.shapeName(): String = when (this) {
        is BodyExecutionPolicy.Sequential -> "SEQUENTIAL"
        is BodyExecutionPolicy.Folding -> "FOLDING"
        is BodyExecutionPolicy.Scoped -> "SCOPED"
        is BodyExecutionPolicy.Retrying -> "RETRYING"
        is BodyExecutionPolicy.Parallel -> "PARALLEL"
    }

    /**
     * The policy of a directive, as the code and the detail it carries.
     *
     * The detail is the predicate a gate names, or the phase a resource
     * asks for. Both are empty when the policy names neither.
     */
    private fun policyOf(policy: DirectiveExecutionPolicy): Pair<String, String> = when (policy) {
        is DirectiveExecutionPolicy.Evaluate -> "EVALUATE" to ""
        is DirectiveExecutionPolicy.ProvideContext -> "PROVIDE_CONTEXT" to ""
        is DirectiveExecutionPolicy.Gate -> "GATE" to policy.predicate
        is DirectiveExecutionPolicy.Resource -> "RESOURCE" to policy.phase.name
    }
}
