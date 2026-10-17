package dev.rubentxu.pipeline.v2.harness.lanes

import dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue.Fidelity

/**
 * Pure decider: a list of [LaneSpec] becomes a [Map] of [TestLane] →
 * contract ids. The mapping comes from the spec, not from heuristics.
 *
 * The decider is total. New lanes require an ADR.
 */
object LanePlanner {
    fun laneFor(spec: LaneSpec): TestLane = when (spec.access) {
        // Certifier-grade first: an installed + certifier test is
        // the highest-fidelity authority; an unannotated spec
        // would otherwise shadow it on the minimumFidelity branch.
        LaneAccess.InstalledWithCertifier -> TestLane.ONLINE_SMOKE
        LaneAccess.Installed -> TestLane.CANDIDATE
        LaneAccess.InProcess -> when (spec.minimumFidelity) {
            // BQ4 v5 (typed fidelity discriminator, 2026-10-17):
            // the previous shape was `Int` with `>= 3` and
            // `== 2` numeric ranges. The new shape is a
            // closed [Fidelity] enum: the `when` is
            // exhaustive over HF0..HF6 and a future HF7
            // forces a compile-time update here. The
            // mapping is the same as before:
            // HF0/HF1 -> FAST, HF2 -> INTEGRATION,
            // HF3..HF6 -> RESTART.
            Fidelity.HF0, Fidelity.HF1 -> TestLane.FAST
            Fidelity.HF2 -> TestLane.INTEGRATION
            Fidelity.HF3, Fidelity.HF4, Fidelity.HF5, Fidelity.HF6 -> TestLane.RESTART
        }
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
            reason = "fidelity=${spec.minimumFidelity} access=${spec.access::class.simpleName}",
        )
    }

    /** Convenience: a hand-built example for tests, isolated from any real catalogue. */
    fun exampleSpecs(): List<LaneSpec> = listOf(
        LaneSpec("unit.echo", "step.echo", Fidelity.HF0, LaneAccess.InProcess),
        LaneSpec("integration.sh", "step.sh", Fidelity.HF2, LaneAccess.InProcess),
        LaneSpec("candidate.installed", "cli.installed", Fidelity.HF2, LaneAccess.Installed),
        LaneSpec("restart.resume", "durable.resume", Fidelity.HF3, LaneAccess.InProcess),
        LaneSpec("online.smoke", "release.smoke", Fidelity.HF2, LaneAccess.InstalledWithCertifier),
    )
}
