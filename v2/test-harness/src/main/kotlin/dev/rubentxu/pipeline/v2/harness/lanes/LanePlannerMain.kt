package dev.rubentxu.pipeline.v2.harness.lanes

import dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue.Fidelity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
 * The set of `LaneSpec` is read from
 * [dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue]
 * via [realSpecsFromCatalogue], the same authority the unit
 * tests' H1 TODO 4 governance test pins as the production
 * path. The earlier KDoc said this used a hand-built
 * [LanePlanner.exampleSpecs] sample; that was true at RC2
 * and stale at BQ2 close-out, when the CLI was rewired to
 * the catalogue so a developer reading the lane plan would
 * see what the lane runner would actually do. The
 * governance test [LanePlannerTest]'s `partition over the
 * real catalogue is exhaustive and non-empty` pins this
 * contract: the CLI and the test classpath agree on the
 * catalogue.
 *
 * BQ4 v27 (typed JSON output, 2026-10-17): the previous
 * shape hand-rolled the JSON via
 * `buildString { append("\"total_specs\": ${specs.size},\n") }`
 * with `${a.contractId}` and `${a.reason}` interpolated as
 * raw strings. The hand-rolled writer did NOT escape `"`
 * or `\`; a contractId or reason containing one would have
 * produced invalid JSON. The new shape is a typed
 * [LaneReport] carrier with `@Serializable`; the library
 * does proper escaping of all JSON special characters. The
 * field declaration order of [LaneReport] matches the
 * previous hand-rolled `buildString` order so the output
 * JSON has the same field sequence. The map iteration
 * order is preserved (the [LinkedHashMap] returned by
 * [LanePlanner.partition] is iterated by the library in
 * insertion order, which matches the previous
 * `partition.entries.joinTo(...)` behaviour). The
 * whitespace formatting changes: the typed encoder uses
 * standard JSON (`": "` no space before the colon, `", "`
 * space after the comma in arrays) instead of the
 * previous `" : "` (space before colon) and `","` (no
 * space after comma in arrays). The JSON is
 * semantically equivalent; a `json.loads` consumer
 * accepts both.
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
    val report = buildReport(specs)
    val body = LANE_REPORT_JSON.encodeToString(report)
    val outPath = Paths.get(out)
    outPath.parent?.toFile()?.mkdirs()
    Files.writeString(outPath, body)
    System.err.println("WROTE $outPath (total_specs=${specs.size}, lanes=${report.byLane.size})")
}

/**
 * The pure builder: takes the catalogue-derived specs and
 * produces the typed [LaneReport]. The function is
 * testable: callers (tests) inspect the typed value
 * directly, not the JSON bytes.
 */
internal fun buildReport(specs: List<LaneSpec>): LaneReport {
    val partition = LanePlanner.partition(specs)
    val assignments = LanePlanner.assign(specs)
    return LaneReport(
        totalSpecs = specs.size,
        byLane = partition,
        assignments = assignments.map { a ->
            LaneAssignmentReport(
                contractId = a.contractId,
                lane = a.lane,
                reason = a.reason,
            )
        },
    )
}

/**
 * The typed JSON output shape. Field declaration order matches
 * the previous hand-rolled `buildString` order
 * (`total_specs`, `by_lane`, `assignments`) so the output JSON
 * has the same field sequence. The `@SerialName` annotations
 * use `snake_case` to match the previous wire format
 * (`total_specs`, `by_lane`, `contract_id`).
 */
@Serializable
data class LaneReport(
    @SerialName("total_specs") val totalSpecs: Int,
    @SerialName("by_lane") val byLane: Map<TestLane, List<String>>,
    val assignments: List<LaneAssignmentReport>,
) {
    /**
     * Encode the report with the CLI's [Json] config. Exposed
     * for tests so they can drive the typed carrier end-to-end
     * and assert on the JSON output (field names, escaping)
     * without re-deriving the configuration.
     */
    fun toJsonString(): String = LANE_REPORT_JSON.encodeToString(this)
}

@Serializable
data class LaneAssignmentReport(
    @SerialName("contract_id") val contractId: String,
    val lane: TestLane,
    val reason: String,
) {
    // Carrier self-protect (2026-10-17): the producer
    // [buildReport] is fed by [LaneAssignment] (already
    // self-protected: `require(contractId.isNotBlank())` /
    // `require(reason.isNotBlank())`), so today's
    // construction never produces a blank field. But the
    // carrier is `public` (no visibility modifier) and a
    // future test fixture, a future typed carrier, or a
    // refactor that breaks the upstream protection would
    // silently emit `"contract_id": ""` / `"reason": ""`
    // in the JSON wire format. The typed carrier now
    // refuses blanks at construction with precise
    // messages naming the field, matching the rest of
    // the codebase's self-protect pattern
    // ([TestSuiteId], [HarnessVerdict.Pending],
    // [LaneAssignment], …).
    init {
        require(contractId.isNotBlank()) { "LaneAssignmentReport.contractId must not be blank" }
        require(reason.isNotBlank()) { "LaneAssignmentReport.reason must not be blank" }
    }
}

/**
 * The local `Json` configuration. Pretty-printed with 2-space
 * indent to match the previous hand-rolled format (one level
 * of indent per nesting level); the consumer is a developer
 * reading the file, so the byte-level indentation is
 * presentation only.
 */
private val LANE_REPORT_JSON: Json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
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
 * and `access` derived per ADR-0072: HF2+ tests fork the installed
 * CLI (AGENTS.md rule 11), HF5/HF6 are certifier-grade, and the
 * [LaneAccess] ADT makes the impossible "certifier without
 * install" combination unrepresentable.
 *
 * The function lives here rather than in `LanePlanner` so the
 * planner remains a pure decider over an externally-built
 * `LaneSpec` (it doesn't have to know the catalogue exists).
 */
internal fun realSpecsFromCatalogueForTests(): List<LaneSpec> = realSpecsFromCatalogue()

private fun realSpecsFromCatalogue(): List<LaneSpec> {
    val catalogue = dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue
    return catalogue.CONTRACTS.map { contract ->
        // BQ4 v5 (typed fidelity discriminator, 2026-10-17):
        // the previous shape was `Int = contract.minFidelity.ordinal`
        // and a numeric-range dispatch on the result. The new
        // shape is a closed [Fidelity] enum; the `when` is
        // exhaustive over HF0..HF6 and a future HF7 forces a
        // compile-time update here and in [LanePlanner.laneFor].
        val fidelity = contract.minFidelity
        LaneSpec(
            contractId = contract.capability,
            capability = contract.capability,
            minimumFidelity = fidelity,
            // HF2+ tests fork the installed CLI per AGENTS.md rule
            // 11. HF5 (Service Sandbox) and HF6 (Online Smoke) are
            // the certifier-grade fidelities per ADR-0072; a
            // contract whose minFidelity is one of those is owned by
            // the harness, not the producer. The [LaneAccess] ADT
            // makes the impossible "certifier without install"
            // combination unrepresentable.
            access = when (fidelity) {
                Fidelity.HF0, Fidelity.HF1 -> LaneAccess.InProcess
                Fidelity.HF2, Fidelity.HF3, Fidelity.HF4 -> LaneAccess.Installed
                Fidelity.HF5, Fidelity.HF6 -> LaneAccess.InstalledWithCertifier
            },
        )
    }
}
