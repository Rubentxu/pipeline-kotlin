package dev.rubentxu.pipeline.v2.harness.lanes

import java.nio.file.Files
import java.nio.file.Paths

/**
 * CLI entry point for [LanePlanner].
 *
 * BQ2 (2026-10-16 audit, RC2): the [LanePlanner] is a pure
 * decider with unit tests but, until this entry point, it
 * was not reachable from the build. This CLI lets Gradle
 * invoke the planner and writes the partition to a JSON
 * file. The file is a build artifact (not a runtime
 * contract) so the developer can read the lane plan during
 * a `:pipeline-application:laneReport` invocation.
 *
 * Usage: `java -cp ... dev.rubentxu.pipeline.v2.harness.lanes.LanePlannerMainKt --output <path>`
 *
 * The set of `LaneSpec` is the same one the unit tests use
 * ([LanePlanner.exampleSpecs]): a hand-built sample, not a
 * reading of the catalogue. Wiring the catalogue here would
 * couple the lane CLI to a specific catalogue authority; the
 * unit tests already cover the partition logic; this entry
 * point proves the planner is reachable from the build.
 */
fun main(args: Array<String>) {
    var outputPath: String? = null
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--output" -> {
                require(i + 1 < args.size) { "--output requires a value" }
                outputPath = args[i + 1]
                i += 2
            }
            "--help", "-h" -> {
                System.err.println("usage: LanePlannerMainKt [--output <path>]")
                return
            }
            else -> {
                System.err.println("unknown argument: $a")
                System.exit(2)
            }
        }
    }
    val out = outputPath ?: "build/reports/lane-planner.json"

    val specs = realSpecsFromCatalogue()
    val partition = LanePlanner.partition(specs)
    val assignments = LanePlanner.assign(specs)

    val body = buildString {
        append("{\n")
        append("  \"total_specs\": ${specs.size},\n")
        append("  \"by_lane\": {\n")
        partition.entries.joinTo(this, separator = ",\n") { (lane, ids) ->
            val idsJson = ids.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
            "    \"${lane.name}\" : $idsJson"
        }
        append("\n  },\n")
        append("  \"assignments\": [\n")
        assignments.joinTo(this, separator = ",\n") { a ->
            "    { \"contract_id\": \"${a.contractId}\", \"lane\": \"${a.lane.name}\", " +
                "\"reason\": \"${a.reason}\" }"
        }
        append("\n  ]\n")
        append("}\n")
    }
    val outPath = Paths.get(out)
    outPath.parent?.toFile()?.mkdirs()
    Files.writeString(outPath, body)
    System.err.println("WROTE $outPath (total_specs=${specs.size}, lanes=${partition.size})")
}

/**
 * Build [LaneSpec]s from the [dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue].
 *
 * BQ2 audit (2026-10-16, RC3 close-out): the previous CLI used
 * [LanePlanner.exampleSpecs], a hand-built sample. That proved the
 * planner was reachable from the build but did NOT exercise the
 * real catalogue. This function derives one `LaneSpec` per
 * contract, with `minimumFidelity = contract.minFidelity.ordinal`
 * (HF0..HF6 ordinals match the planner's `minimumFidelity` axis)
 * and `requiresInstalledBinary = HF >= 2` (HF2+ tests fork the
 * installed CLI per AGENTS.md rule 11). `requiresExternalCertifier`
 * is set per the audit's sensitivity to the harness-verdict path
 * (HF5/HF6 contracts are certifier-grade by ADR-0072).
 *
 * The function lives here rather than in `LanePlanner` so the
 * planner remains a pure decider over an externally-built
 * `LaneSpec` (it doesn't have to know the catalogue exists).
 */
private fun realSpecsFromCatalogue(): List<LaneSpec> {
    val catalogue = dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue
    return catalogue.CONTRACTS.map { contract ->
        val fidelity = contract.minFidelity.ordinal
        LaneSpec(
            contractId = contract.capability,
            capability = contract.capability,
            minimumFidelity = fidelity,
            requiresInstalledBinary = fidelity >= 2,
            // HF5 (Service Sandbox) and HF6 (Online Smoke) are
            // the certifier-grade fidelities per ADR-0072. A
            // contract whose minFidelity is one of those is
            // owned by the harness, not the producer.
            requiresExternalCertifier = fidelity >= 5,
        )
    }
}
