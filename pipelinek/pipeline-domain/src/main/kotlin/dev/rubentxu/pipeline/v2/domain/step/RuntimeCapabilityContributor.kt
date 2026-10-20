package dev.rubentxu.pipeline.v2.domain.step

/**
 * One source of runtime capabilities, asked to contribute the ones it owns.
 *
 * ## Why a contributor, and why it takes no context
 *
 * A finished `Map<StepCapability, Any>` has to travel somewhere, and every place
 * it travels is a decision about ownership. Parking it on `ShOptions` was tried
 * and reverted in WU-093 H2b: `ShOptions` carries FACTS and POLICIES for one
 * execution — workspace root, working directory, timeout, sandbox, network
 * egress — and a map of live ports and clients is a SERVICE REGISTRY. It was
 * parked there to spare the coordinator a parameter, which bought a line count
 * and cost the architecture. `CoordinatorGrowthGuardrailTest` exists to stop
 * that class of trade, not to be satisfied by it.
 *
 * It lives in `pipeline-domain`, beside [StepCapability] and
 * [StepCapabilityAccess], because it is a CONTRACT. A plugin contributes a
 * capability; the runtime decides whether it may be used; neither names the
 * other's types. Putting it in `pipeline-application` would make every plugin
 * depend on the application layer and close a module cycle.
 *
 * ## Why the signature takes no context
 *
 * The two moments that must agree are:
 *
 * ```text
 * PREPARE   RegistryExecutionPreparation.prepare(requiredCapabilities vs available())
 * EXECUTE   handler reads capabilities through StepHandlerContext
 * ```
 *
 * A context-aware contributor would have to be asked TWICE and could answer
 * differently — a clock, a socket probe, an allocation — and a Step would then
 * pass admission and find its capability missing at execution. Taking no
 * argument makes that impossible by construction rather than by discipline: the
 * same value is produced at both moments, always.
 *
 * Anything genuinely per-execution belongs in the runtime's own capability
 * (e.g. `network.egress` reads [dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions]),
 * which is exactly the split that keeps the plugin from needing the context.
 *
 * ## The law on collisions
 *
 * Two contributors providing the same [StepCapability] is a DEFECT, not a
 * precedence question. Fail closed and name both: silently taking the first or
 * the last is how a plugin ends up shadowing a core seam.
 */
fun interface RuntimeCapabilityContributor {
    fun capabilities(): Map<StepCapability, Any>
}

/**
 * Composes contributors into the single set both moments consult.
 *
 * Ordering is explicit and collisions are refused, so the composition is a value
 * a reviewer can read rather than a side effect of registration order.
 */
class CompositeCapabilityContributor(
    private val contributors: List<RuntimeCapabilityContributor>,
) : RuntimeCapabilityContributor {

    override fun capabilities(): Map<StepCapability, Any> {
        val merged = LinkedHashMap<StepCapability, Any>()
        val owner = HashMap<StepCapability, String>()
        for (contributor in contributors) {
            for ((capability, value) in contributor.capabilities()) {
                val previousOwner = owner[capability]
                require(previousOwner == null) {
                    "capability '$capability' is contributed twice: by $previousOwner and by " +
                        describe(contributor) + ". A collision is a defect, not a precedence " +
                        "rule: first-wins and last-wins both let a plugin silently shadow a core " +
                        "seam."
                }
                owner[capability] = describe(contributor)
                merged[capability] = value
            }
        }
        return merged
    }

    private fun describe(contributor: RuntimeCapabilityContributor): String =
        contributor::class.simpleName ?: "anonymous contributor"
}
