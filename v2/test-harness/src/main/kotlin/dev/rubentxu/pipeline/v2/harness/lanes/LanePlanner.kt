package dev.rubentxu.pipeline.v2.harness.lanes

import dev.rubentxu.pipeline.v2.harness.catalog.CapabilityId
import dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue
import dev.rubentxu.pipeline.v2.harness.catalog.ContractEntry
import dev.rubentxu.pipeline.v2.harness.catalog.ContractId
import dev.rubentxu.pipeline.v2.harness.catalog.ContractMetadata
import dev.rubentxu.pipeline.v2.harness.plan.HarnessFidelity

/**
 * Pure decider: given a [ContractCatalogue], group every contract by its
 * lane. The lane comes from [ContractMetadata] — the catalogue owner
 * declares it; this decider only projects.
 *
 * The default lane is [TestLane.FAST] (HF1). The catalogue owner may
 * promote a contract to a heavier lane by setting the corresponding
 * `requiresInstalledBinary` / `requiresExternalCertifier` flags.
 */
object LanePlanner {
    fun laneFor(metadata: ContractMetadata): TestLane = when {
        metadata.requiresExternalCertifier -> TestLane.ONLINE_SMOKE
        metadata.requiresInstalledBinary -> TestLane.CANDIDATE
        metadata.minimumFidelity >= HarnessFidelity.HF3 -> TestLane.RESTART
        metadata.minimumFidelity == HarnessFidelity.HF2 -> TestLane.INTEGRATION
        else -> TestLane.FAST
    }

    fun partition(catalogue: ContractCatalogue): Map<TestLane, List<ContractId>> {
        val byLane = HashMap<TestLane, MutableList<ContractId>>()
        for (entry in catalogue.all()) {
            val lane = laneFor(entry.metadata)
            byLane.getOrPut(lane) { ArrayList() }.add(entry.id)
        }
        return byLane
    }

    /** Convenience: a hand-built catalogue for tests, isolated from the real one. */
    fun exampleCatalogue(): ContractCatalogue = ContractCatalogue.of(
        listOf(
            ContractEntry(
                id = ContractId("unit.echo"),
                capability = CapabilityId("step.echo"),
                owner = "core",
                metadata = ContractMetadata(
                    minimumFidelity = HarnessFidelity.HF0,
                    requiresInstalledBinary = false,
                    requiresExternalCertifier = false,
                ),
                suiteFqcn = "owner.EchoStepContractTest",
                sourcePatterns = listOf("src/Echo.kt"),
            ),
            ContractEntry(
                id = ContractId("integration.sh"),
                capability = CapabilityId("step.sh"),
                owner = "core",
                metadata = ContractMetadata(
                    minimumFidelity = HarnessFidelity.HF2,
                    requiresInstalledBinary = false,
                    requiresExternalCertifier = false,
                ),
                suiteFqcn = "owner.ShIntegrationTest",
                sourcePatterns = listOf("src/Sh.kt"),
            ),
            ContractEntry(
                id = ContractId("candidate.installed"),
                capability = CapabilityId("cli.installed"),
                owner = "application",
                metadata = ContractMetadata(
                    minimumFidelity = HarnessFidelity.HF2,
                    requiresInstalledBinary = true,
                    requiresExternalCertifier = false,
                ),
                suiteFqcn = "owner.InstalledUat",
                sourcePatterns = listOf("src/Cli.kt"),
            ),
            ContractEntry(
                id = ContractId("restart.resume"),
                capability = CapabilityId("durable.resume"),
                owner = "runtime",
                metadata = ContractMetadata(
                    minimumFidelity = HarnessFidelity.HF3,
                    requiresInstalledBinary = false,
                    requiresExternalCertifier = false,
                ),
                suiteFqcn = "owner.ResumeTest",
                sourcePatterns = listOf("src/Resume.kt"),
            ),
            ContractEntry(
                id = ContractId("online.smoke"),
                capability = CapabilityId("release.smoke"),
                owner = "release",
                metadata = ContractMetadata(
                    minimumFidelity = HarnessFidelity.HF2,
                    requiresInstalledBinary = true,
                    requiresExternalCertifier = true,
                ),
                suiteFqcn = "owner.SmokeTest",
                sourcePatterns = listOf("src/Smoke.kt"),
            ),
        ),
    )
}
