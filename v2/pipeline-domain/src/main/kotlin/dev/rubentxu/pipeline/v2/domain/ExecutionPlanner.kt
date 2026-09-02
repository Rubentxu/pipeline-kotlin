package dev.rubentxu.pipeline.v2.domain

/**
 * Pure planner that resolves a [PipelineDefinition] into an
 * [ExecutionPlan] (LF-0207 canonical parallel).
 *
 * ## Concurrency is opt-in
 *
 * Only steps explicitly joined by a `PARALLEL` edge are co-scheduled into
 * an [ExecutionUnit.Concurrent] wave. Everything else — including steps
 * that merely *could* run concurrently because no constraint relates
 * them — stays a single step in legacy declaration order. This keeps the
 * M2 exit criterion honest ("orden semántico equivalente"): a definition
 * without `PARALLEL` edges plans exactly the linear order the runtime has
 * always executed, and parallelism can never appear implicitly.
 *
 * ## Edge semantics
 *
 * | Edge kind       | Meaning for the plan |
 * |-----------------|----------------------|
 * | `SEQUENTIAL`    | ordering constraint: `from` before `to` |
 * | `CONDITIONAL`   | ordering constraint (predicate runtime is LF-0307) |
 * | `PARALLEL`      | **no** ordering constraint; asserts both endpoints are co-scheduled in the same wave |
 *
 * ## Algorithm
 *
 * 1. Fail-closed validation: every edge must reference known step ids.
 * 2. Longest-path leveling (Kahn) over the ordering edges assigns each
 *    step a wave level; ties are broken by declaration order, so the same
 *    definition always plans identically (M2-005 determinism property).
 * 3. A cycle among ordering edges fails closed and names the stuck steps.
 * 4. Every `PARALLEL` edge is validated: both endpoints must sit at the
 *    same level — a PARALLEL edge across levels is contradictory (other
 *    constraints already order them) and fails closed.
 * 5. Steps joined by `PARALLEL` edges are merged (union-find) into wave
 *    groups; a group of one stays [ExecutionUnit.Single], a group of two
 *    or more becomes [ExecutionUnit.Concurrent]. Groups emit in level
 *    order, then by their smallest declaration index; steps inside a
 *    group in declaration order.
 *
 * The planner is pure: no I/O, no clock, no global state. It subsumes the
 * former linear-only `StepOrderResolver`: a definition without `PARALLEL`
 * edges plans to single-step units in the very same deterministic linear
 * order that resolver produced.
 */
object ExecutionPlanner {

    fun plan(definition: PipelineDefinition): ExecutionPlan {
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

        val declaredOrder = definition.steps.withIndex().associate { (index, step) -> step.id to index }
        val ordering = definition.edges.filter { it.kind != EdgeKind.PARALLEL }
        val parallelAsserts = definition.edges.filter { it.kind == EdgeKind.PARALLEL }

        // Longest-path leveling over ordering constraints.
        val level = mutableMapOf<String, Int>()
        definition.steps.forEach { level[it.id] = 0 }

        if (ordering.isNotEmpty()) {
            val successors = mutableMapOf<String, MutableSet<String>>()
            val inDegree = mutableMapOf<String, Int>()
            definition.steps.forEach { step ->
                successors[step.id] = mutableSetOf()
                inDegree[step.id] = 0
            }
            ordering.forEach { edge ->
                if (successors[edge.from]!!.add(edge.to)) {
                    inDegree[edge.to] = inDegree[edge.to]!! + 1
                }
            }

            val frontier = java.util.TreeSet<String>(compareBy { declaredOrder.getValue(it) })
            inDegree.filterValues { it == 0 }.keys.forEach(frontier::add)
            var processed = 0
            while (frontier.isNotEmpty()) {
                val current = frontier.first()
                frontier.remove(current)
                processed++
                successors.getValue(current).forEach { next ->
                    level[next] = maxOf(level.getValue(next), level.getValue(current) + 1)
                    val remaining = inDegree[next]!! - 1
                    inDegree[next] = remaining
                    if (remaining == 0) frontier.add(next)
                }
            }
            if (processed != definition.steps.size) {
                val stuck = definition.steps.map { it.id }.sorted()
                    .filter { id -> inDegree.getValue(id) > 0 }
                throw IllegalArgumentException(
                    "PipelineDefinition ordering edges contain a cycle; steps unreachable from any root: $stuck"
                )
            }
        }

        // PARALLEL assertions: same level required, then union into groups.
        val parent = definition.steps.associate { it.id to it.id }.toMutableMap()
        fun find(id: String): String {
            var current = id
            while (parent.getValue(current) != current) current = parent.getValue(current)
            return current
        }
        parallelAsserts.forEach { edge ->
            val fromWave = level.getValue(edge.from)
            val toWave = level.getValue(edge.to)
            if (fromWave != toWave) {
                throw IllegalArgumentException(
                    "PARALLEL edge between '${edge.from}' (wave $fromWave) and '${edge.to}' (wave $toWave) " +
                        "is contradictory: ordering constraints already separate them"
                )
            }
            val fromRoot = find(edge.from)
            val toRoot = find(edge.to)
            if (fromRoot != toRoot) parent[toRoot] = fromRoot
        }

        // Wave groups: (level, component root) → steps, emitted level-first,
        // then by the group's smallest declaration index; steps inside a
        // group in declaration order.
        data class Group(val level: Int, val minOrder: Int, val steps: MutableList<StepDescriptor>)

        val groups = LinkedHashMap<String, Group>()
        definition.steps.forEach { step ->
            val key = "${level.getValue(step.id)}:${find(step.id)}"
            groups.getOrPut(key) { Group(level.getValue(step.id), declaredOrder.getValue(step.id), mutableListOf()) }
                .steps += step
        }
        val units = groups.values
            .sortedWith(compareBy({ it.level }, { it.minOrder }))
            .map { group ->
                val ordered = group.steps.sortedBy { declaredOrder.getValue(it.id) }
                if (ordered.size == 1) ExecutionUnit.Single(ordered.single())
                else ExecutionUnit.Concurrent(ordered)
            }
        return ExecutionPlan(units)
    }
}
