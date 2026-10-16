package dev.rubentxu.pipeline.v2.harness.retirement

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
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: InventoryClassifierMain <input.csv> <output.json>" }
    val input = java.nio.file.Path.of(args[0])
    val output = java.nio.file.Path.of(args[1])

    val byDecision = HashMap<String, Int>()
    val byWave = HashMap<String, Int>()
    val blockedReasons = HashMap<String, Int>()
    var total = 0

    input.toFile().bufferedReader().useLines { lines ->
        val iter = lines.iterator()
        val header = iter.next().split(",")
        val ix = header.withIndex().associate { (i, n) -> n to i }

        while (iter.hasNext()) {
            val row = iter.next().split(",")
            if (row.size < header.size) continue
            total++
            val record = TestRecord(
                path = row[ix.getValue("path")],
                kindHint = parseKind(row[ix.getValue("kind_hint")]),
                runnerHint = parseRunner(row[ix.getValue("runner_hint")]),
                observedProcessApi = row[ix.getValue("observed_process_api")],
                testAnnotated = row[ix.getValue("test_annotated")].toInt(),
                disabledAnnotations = row[ix.getValue("disabled_annotations")].toInt(),
                releaseScaleTag = row[ix.getValue("release_scale_tags")].toInt() > 0,
                contentSha256 = row[ix.getValue("content_sha256")],
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

    val json = buildString {
        append("{\n")
        append("  \"total\": $total,\n")
        append("  \"by_decision\": {\n")
        byDecision.entries.joinTo(this, separator = ",\n") { (k, v) ->
            "    ${jsonKey(k)}: $v"
        }
        append("\n  },\n")
        append("  \"by_wave\": {\n")
        byWave.entries.sortedBy { it.key }.joinTo(this, separator = ",\n") { (k, v) ->
            "    ${jsonKey(k)}: $v"
        }
        append("\n  },\n")
        append("  \"blocked_reasons\": {\n")
        blockedReasons.entries.joinTo(this, separator = ",\n") { (k, v) ->
            "    ${jsonKey(k)}: $v"
        }
        append("\n  },\n")
        append("  \"retirement_authorised_a_b_c\": false,\n")
        append("  \"retirement_authorised_d\": false\n")
        append("}\n")
    }
    java.nio.file.Files.writeString(output, json)
    println("WROTE $output (total=$total)")
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

private fun jsonKey(s: String): String = "\"" + s.replace("\"", "\\\"") + "\""
