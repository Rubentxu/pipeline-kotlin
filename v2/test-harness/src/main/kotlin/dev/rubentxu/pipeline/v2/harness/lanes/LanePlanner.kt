package dev.rubentxu.pipeline.v2.harness.lanes

/**
 * Pure decider: a list of [LaneSpec] becomes a [Map] of [TestLane] →
 * contract ids. The mapping comes from the spec, not from heuristics.
 *
 * The decider is total. New lanes require an ADR.
 */
object LanePlanner {
    fun laneFor(spec: LaneSpec): TestLane = when {
        spec.requiresExternalCertifier -> TestLane.ONLINE_SMOKE
        spec.requiresInstalledBinary -> TestLane.CANDIDATE
        spec.minimumFidelity >= 3 -> TestLane.RESTART
        spec.minimumFidelity == 2 -> TestLane.INTEGRATION
        else -> TestLane.FAST
    }

    fun partition(specs: List<LaneSpec>): Map<TestLane, List<String>> {
        val byLane = LinkedHashMap<TestLane, MutableList<String>>()
        for (spec in specs) {
            val lane = laneFor(spec)
            byLane.getOrPut(lane) { ArrayList() }.add(spec.contractId)
        }
        return byLane
    }

    fun assign(specs: List<LaneSpec>): List<LaneAssignment> = specs.map { spec ->
        LaneAssignment(
            contractId = spec.contractId,
            lane = laneFor(spec),
            reason = "fidelity=${spec.minimumFidelity} installed=${spec.requiresInstalledBinary} certifier=${spec.requiresExternalCertifier}",
        )
    }

    /** Convenience: a hand-built example for tests, isolated from any real catalogue. */
    fun exampleSpecs(): List<LaneSpec> = listOf(
        LaneSpec("unit.echo", "step.echo", 0, false, false),
        LaneSpec("integration.sh", "step.sh", 2, false, false),
        LaneSpec("candidate.installed", "cli.installed", 2, true, false),
        LaneSpec("restart.resume", "durable.resume", 3, false, false),
        LaneSpec("online.smoke", "release.smoke", 2, true, true),
    )
}
