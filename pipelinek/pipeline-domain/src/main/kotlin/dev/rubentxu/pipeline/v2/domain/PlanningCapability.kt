package dev.rubentxu.pipeline.v2.domain

/**
 * The capabilities a compiled plan can need of the build that plans it.
 *
 * ## Why this vocabulary exists
 * A stage body shape that the IR can represent and the planner cannot expand
 * is a CAPABILITY the product does not serve, and that is a different fact
 * from a malformed plan: one is answered by a different build, the other by a
 * different plan. A refusal that cannot tell them apart leaves the reader
 * guessing which half of the problem it is.
 *
 * The vocabulary is CLOSED and it is named, because a refusal whose reason is
 * a sentence is a refusal a consumer cannot switch on: the product's rule is
 * that a fail-closed boundary refuses with something the consumer can tell
 * apart from every other refusal.
 *
 * ## Why the matrix is a capability and not a malformed body
 * The matrix body is REPRESENTABLE — it is part of the published IR, and a
 * caller can build one — and this build does not plan it: no planner expands
 * a matrix into the units of the plan. So the plan that carries one is
 * refused by name, at the door where it is accepted, instead of being
 * accepted and failing later with an error that names neither the shape nor
 * the capability.
 */
enum class PlanningCapability {

    /**
     * Expanding a matrix stage into the units of the plan.
     *
     * The matrix body declares axes and the values of each axis, and
     * expanding them is one unit per combination. No planner of this build
     * does that, so a plan that needs it is refused.
     */
    MatrixExpansion,
}

/**
 * The plan needs a capability this build does not serve.
 *
 * ## Why the two facts travel as values
 * The refusal says WHICH capability is missing and WHICH stage needed it, and
 * both are facts a consumer can read instead of parse: [capability] is the
 * closed vocabulary the consumer switches on, and [stage] is the stage of the
 * plan that asked for it.
 *
 * ## Why it is an illegal argument
 * The contract of the structural validation is that an invalid pipeline is
 * refused as an invalid argument, and the durable coordinator honours that
 * contract by turning it into a USER failure of the run. A refusal with
 * another parent would escape that catch and become a crash, and a crash
 * reports a defect of the pipeline as a failure of the infrastructure.
 */
class MissingPlanningCapabilityException(
    val capability: PlanningCapability,
    val stage: String,
) : IllegalArgumentException(
    "stage '$stage' needs the ${capability.name} capability, which this build does not serve",
)
