package dev.rubentxu.pipeline.v2.domain

/**
 * Pure resolver that turns a [PipelineDefinition]'s steps and edges into
 * the linear execution order the coordinator walks.
 *
 * ## Rules
 *
 * 1. **No edges** → declaration order of [PipelineDefinition.steps]. The
 *    common single-branch pipeline needs no graph reasoning.
 * 2. **With edges** → deterministic topological order (Kahn's algorithm).
 *    Ties are broken by declaration order so the same definition always
 *    resolves to the same sequence (characterisation property; M2-005
 *    "failure mapping estable" depends on this).
 * 3. **Fail-closed violations** — the resolver throws (it validates
 *    definition invariants that the M2 compiler does not yet check):
 *    - an edge referencing a step id that is not in the definition;
 *    - a dependency cycle among the edges.
 *
 * `PARALLEL` and `CONDITIONAL` edges contribute ordering constraints only
 * at the M2 surface: the resolved order is still linear, and the runtime
 * semantics of those kinds are flattened to sequential until LF-0207 /
 * LF-0307 land (see [Edge] KDoc).
 *
 * The resolver is pure: no I/O, no clock, no global state. Same
 * definition in, same order out — always.
 */
object StepOrderResolver {

    /**
     * Returns the steps of [definition] in execution order.
     *
     * @throws IllegalArgumentException if any edge references an unknown
     *         step id, or if the edges contain a cycle.
     */
    fun resolve(definition: PipelineDefinition): List<StepDescriptor> {
        val stepsById = definition.steps.associateBy { it.id }
        definition.edges.forEach { edge ->
            if (edge.from !in stepsById) {
                throw IllegalArgumentException(
                    "Edge references unknown step '${edge.from}'; known ids: ${stepsById.keys.sorted()}"
                )
            }
            if (edge.to !in stepsById) {
                throw IllegalArgumentException(
                    "Edge references unknown step '${edge.to}'; known ids: ${stepsById.keys.sorted()}"
                )
            }
        }
        if (definition.edges.isEmpty()) return definition.steps.toList()

        // Kahn's algorithm; declaration order breaks ties so the resolved
        // sequence is a deterministic function of the definition alone.
        val declaredOrder = definition.steps.withIndex().associate { (index, step) -> step.id to index }
        val frontier = java.util.TreeSet<String>(compareBy { declaredOrder.getValue(it) })
        val successors = mutableMapOf<String, MutableSet<String>>()
        val inDegree = mutableMapOf<String, Int>()
        definition.steps.forEach { step ->
            successors[step.id] = mutableSetOf()
            inDegree[step.id] = 0
        }
        definition.edges.forEach { edge ->
            // Self-edges are already rejected by Edge.init; dedupe repeated
            // edges so in-degree counting stays correct.
            if (successors[edge.from]!!.add(edge.to)) {
                inDegree[edge.to] = inDegree[edge.to]!! + 1
            }
        }
        inDegree.filterValues { it == 0 }.keys.forEach(frontier::add)

        val ordered = mutableListOf<StepDescriptor>()
        while (frontier.isNotEmpty()) {
            val current = frontier.first()
            frontier.remove(current)
            ordered += stepsById.getValue(current)
            successors.getValue(current).forEach { next ->
                val remaining = inDegree[next]!! - 1
                inDegree[next] = remaining
                if (remaining == 0) frontier.add(next)
            }
        }

        if (ordered.size != definition.steps.size) {
            val emittedIds = ordered.map { it.id }.toSet()
            val stuck = definition.steps.map { it.id }.filter { it !in emittedIds }.sorted()
            throw IllegalArgumentException(
                "PipelineDefinition edges contain a cycle; steps unreachable from any root: $stuck"
            )
        }
        return ordered
    }
}
