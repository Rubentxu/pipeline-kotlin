package dev.rubentxu.pipeline.v2.domain

/**
 * Domain edge between two pipeline steps.
 * An edge expresses an ordering constraint: `from` must reach a terminal
 * outcome before `to` is allowed to begin. The semantics are intentionally
 * Narrow on the surface
 * - **Sequential** (default): `to` starts only after `from` is terminal.
 * This is the only kind of edge guarantees. Parallelism is a runtime
 * Concern and lives in [RunCoordinator], not here
 * **Parallel**: declared for documentation only at the surface; the
 * Runtime collapses parallel groups to a sequential walk until
 *   `canonical parallel` lands. The `PipelineCompiler` MUST NOT fail on a
 *   `PARALLEL` edge — it is a forward declaration, not an error.
 * **Conditional**: declared for documentation only at the surface; the
 * Runtime treats conditional edges as unconditional until
 *   introduces the predicate system. Again, no compile error.
 * The [from] and [to] reference `StepDescriptor.id` values of the same
 * [PipelineDefinition]. Cross-definition edges are out of scope
*/
data class Edge(
    val from: String,
    val to: String,
    val kind: EdgeKind = EdgeKind.SEQUENTIAL,
) {
    init {
        require(from.isNotBlank()) { "Edge.from must not be blank" }
        require(to.isNotBlank()) { "Edge.to must not be blank" }
        require(from != to) { "Edge must connect two distinct steps; self-edge on '$from' is invalid" }
    }
}

/**
 * Edge kind declared for documentation and runtime routing.
 * Only [SEQUENTIAL] has full runtime semantics. [PARALLEL] and
 * [CONDITIONAL] are forward declarations that the compiler accepts but
 * The runtime flattens to sequential. See [Edge] KDoc for the migration
 * cadence.
*/
enum class EdgeKind { SEQUENTIAL, PARALLEL, CONDITIONAL }
