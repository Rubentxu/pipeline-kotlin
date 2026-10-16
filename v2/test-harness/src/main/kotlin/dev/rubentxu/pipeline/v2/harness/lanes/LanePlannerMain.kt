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

    val specs = LanePlanner.exampleSpecs()
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
