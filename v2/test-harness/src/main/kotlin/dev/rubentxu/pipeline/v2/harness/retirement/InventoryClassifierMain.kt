package dev.rubentxu.pipeline.v2.harness.retirement

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * CLI entry point: read a CSV produced by
 * `tooling/inventory_tests.py`, classify every row with
 * [TestClassifier], assign a [Wave], and emit a per-decision +
 * per-wave summary as JSON.
 *
 * Usage: `java -cp ... InventoryClassifierMainKt <input.csv> <output.json>`
 *
 * The CLI is the BQ5 operator surface. It does NOT delete anything; the
 * deletion guard ([DeletionGuard]) and `tooling/retirement_guard.py` are
 * the only authorities that may retire tests.
 *
 * BQ4 v26 (typed JSON output, 2026-10-17): the previous
 * shape hand-rolled a JSON writer via
 * `buildString { append("\"total\": ...") }` plus a
 * hand-rolled `jsonKey` escape function that only
 * escaped `"`. The hand-rolled writer is the same
 * defect class as the closed `parseFlatObject` (in
 * `1358b771`): a custom string-emitter where a proper
 * library is the right answer. The new shape is a
 * typed [ClassifierOutput] carrier with
 * `@Serializable`; the field order is preserved (the
 * data class declaration order matches the previous
 * `buildString` order), the map iteration order is
 * preserved ([LinkedHashMap] for insertion-order
 * maps, [sortedMapOf] for sorted maps), and the
 * library does proper escaping of all JSON special
 * characters (the hand-rolled `jsonKey` only
 * escaped `"`; a key with `\` or control characters
 * would have produced invalid JSON). The output is
 * structurally equivalent to the previous hand-rolled
 * format; a Python `json.loads` consumer is unaffected
 * by the byte-level differences.
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: InventoryClassifierMain <input.csv> <output.json>" }
    val input = Path.of(args[0])
    val output = Path.of(args[1])
    runClassifier(input, output)
    println("WROTE $output")
}

/**
 * The pure summary function. Reads [input] CSV, classifies every row,
 * and writes the JSON summary to [output]. The function is
 * testable: callers (tests) provide a temp file and inspect the
 * result.
 *
 * The `Json` configuration is local to this file: the test-harness
 * uses the typed JSON for its own internal reports; the consumer
 * (`tooling/retirement_guard.py`) reads the file with a whitespace-
 * and order-tolerant parser. The `prettyPrint = true` + 2-space
 * indent matches the previous hand-rolled format.
 */
fun runClassifier(input: Path, output: Path) {
    val summary = summarize(input)
    Files.writeString(output, SUMMARY_JSON.encodeToString(summary))
}

/**
 * Read [input] CSV and produce the typed [ClassifierOutput] summary.
 * Pure function: no clock, no global state, no I/O beyond reading
 * the input file. The output is a sealed value: the caller can
 * inspect, serialise, or re-render it.
 */
fun summarize(input: Path): ClassifierOutput {
    val byDecision: MutableMap<String, Int> = LinkedHashMap()
    val byWave: MutableMap<String, Int> = sortedMapOf()
    val blockedReasons: MutableMap<String, Int> = LinkedHashMap()
    var total = 0

    input.toFile().bufferedReader().useLines { lines ->
        val iter = lines.iterator()
        val header = iter.next().split(",")
        val ix = header.withIndex().associate { (i, n) -> n to i }

        while (iter.hasNext()) {
            val row = iter.next().split(",")
            if (row.size < header.size) continue
            total++
            val migratedPending = ix["migrated_pending"]?.let { row[it] == "true" } ?: false
            val record = TestRecord(
                path = row[ix.getValue("path")],
                kindHint = parseKind(row[ix.getValue("kind_hint")]),
                runnerHint = parseRunner(row[ix.getValue("runner_hint")]),
                observedProcessApis = ObservedProcessApi.parser(row[ix.getValue("observed_process_api")]),
                testAnnotated = row[ix.getValue("test_annotated")].toInt(),
                disabledAnnotations = row[ix.getValue("disabled_annotations")].toInt(),
                releaseScaleTag = row[ix.getValue("release_scale_tags")].toInt() > 0,
                contentSha256 = row[ix.getValue("content_sha256")],
                migratedPending = migratedPending,
            )
            val d = TestClassifier.classify(record)
            val w = Wave.forRecord(record)
            byDecision[bucketName(d)] = (byDecision[bucketName(d)] ?: 0) + 1
            byWave["wave_${w.name}"] = (byWave["wave_${w.name}"] ?: 0) + 1
            if (d is DeletionDecision.Blocked) {
                val reason = d.reason.take(80)
                blockedReasons[reason] = (blockedReasons[reason] ?: 0) + 1
            }
        }
    }

    return ClassifierOutput(
        total = total,
        byDecision = byDecision,
        byWave = byWave,
        blockedReasons = blockedReasons,
        retirementAuthorisedABC = false,
        retirementAuthorisedD = false,
    )
}

private fun bucketName(d: DeletionDecision): String = when (d) {
    DeletionDecision.KeepCoreContract -> "keep_core_contract"
    DeletionDecision.TemporaryCycle -> "temporary_cycle"
    DeletionDecision.MigrateExternalPending -> "migrate_external_pending"
    is DeletionDecision.RedundantWithProof -> "redundant_with_proof"
    DeletionDecision.TestFixture -> "test_fixture"
    DeletionDecision.LegacyNonV2 -> "legacy_non_v2"
    is DeletionDecision.Blocked -> "blocked"
}

private fun parseKind(s: String): KindHint = when (s) {
    "SUPPORT_OR_ABSTRACT" -> KindHint.SUPPORT_OR_ABSTRACT
    "EXPLICIT_UAT_E2E" -> KindHint.EXPLICIT_UAT_E2E
    "INTEGRATION_CANDIDATE" -> KindHint.INTEGRATION_CANDIDATE
    "PROCESS_USING_CANDIDATE" -> KindHint.PROCESS_USING_CANDIDATE
    else -> KindHint.OTHER_TEST_SOURCE
}

private fun parseRunner(s: String): RunnerHint = when (s) {
    "LEGACY_NOT_DEFAULT_V2" -> RunnerHint.LEGACY_NOT_DEFAULT_V2
    else -> RunnerHint.V2_GRADLE
}

/**
 * The typed output shape. Field declaration order matches the
 * previous hand-rolled `buildString` order so the output JSON
 * has the same field sequence. The `@SerialName` annotations
 * use `snake_case` to match the previous wire format
 * (`by_decision`, `by_wave`, `blocked_reasons`,
 * `retirement_authorised_a_b_c`, `retirement_authorised_d`).
 */
@Serializable
data class ClassifierOutput(
    val total: Int,
    @SerialName("by_decision")
    val byDecision: Map<String, Int>,
    @SerialName("by_wave")
    val byWave: Map<String, Int>,
    @SerialName("blocked_reasons")
    val blockedReasons: Map<String, Int>,
    @SerialName("retirement_authorised_a_b_c")
    val retirementAuthorisedABC: Boolean,
    @SerialName("retirement_authorised_d")
    val retirementAuthorisedD: Boolean,
)

/**
 * The local `Json` configuration. Pretty-printed with 2-space
 * indent to match the previous hand-rolled format; the consumer
 * (`tooling/retirement_guard.py`) is a whitespace-tolerant JSON
 * parser, so the byte-level indentation is presentation only.
 */
private val SUMMARY_JSON: Json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
}
