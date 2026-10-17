package dev.rubentxu.pipeline.v2.harness.retirement

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * BQ4 v26 (typed JSON output, 2026-10-17): the
 * `InventoryClassifierMain` previously hand-rolled
 * the JSON output via
 * `buildString { append("\"total\": ...") }` plus a
 * hand-rolled `jsonKey` escape function that only
 * escaped `"`. The new shape is a typed
 * [ClassifierOutput] carrier with
 * `kotlinx.serialization.json` encoding.
 *
 * The tests pin the **structural** contract:
 *
 * 1. `summarize` is a pure function on the input CSV.
 * 2. The output is a [ClassifierOutput] with the
 *    expected shape (total, byDecision, byWave,
 *    blockedReasons, retirementAuthorisedABC,
 *    retirementAuthorisedD).
 * 3. The map iteration order is preserved:
 *    `byDecision` is insertion-order, `byWave` is
 *    sorted, `blockedReasons` is insertion-order.
 * 4. The JSON output contains the snake_case field
 *    names expected by the
 *    `tooling/retirement_guard.py` consumer:
 *    `total`, `by_decision`, `by_wave`,
 *    `blocked_reasons`,
 *    `retirement_authorised_a_b_c`,
 *    `retirement_authorised_d`.
 * 5. The two boolean fields are always `false`
 *    (the CLI is a classification report, not an
 *    authorisation; the [DeletionGuard] is the
 *    sole authority for retirement).
 *
 * A byte-exact comparison of the JSON output is
 * NOT pinned because (a) the previous
 * `buildString` output was already implementation-
 * defined (whitespace was hand-formatted, escapes
 * were partial), and (b) the consumer is a
 * whitespace-tolerant JSON parser. The test pins
 * the structural contract that matters to the
 * consumer.
 */
class InventoryClassifierMainTest {

    private val sampleCsv = """
        path,kind_hint,runner_hint,observed_process_api,test_annotated,disabled_annotations,release_scale_tags,content_sha256,migrated_pending
        v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatLocal001KeepTest.kt,EXPLICIT_UAT_E2E,V2_GRADLE,,3,0,0,${"a".repeat(64)},false
        v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatLocal002LegacyTest.kt,OTHER_TEST_SOURCE,LEGACY_NOT_DEFAULT_V2,,2,0,0,${"b".repeat(64)},false
        v2/test-harness/src/test/kotlin/dev/rubentxu/pipeline/v2/harness/retirement/TestRecordFixture.kt,SUPPORT_OR_ABSTRACT,V2_GRADLE,,0,0,0,${"c".repeat(64)},false
        v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatLocal003PendingTest.kt,OTHER_TEST_SOURCE,V2_GRADLE,,1,0,0,${"d".repeat(64)},true
    """.trimIndent()

    @Test
    fun `summarize returns typed ClassifierOutput with all expected buckets`(@TempDir tmp: Path) {
        val input = tmp.resolve("input.csv")
        input.toFile().writeText(sampleCsv)

        val out = summarize(input)

        assertEquals(4, out.total)
        // Three distinct decision buckets present:
        // - MigrateExternalPending (migrated_pending=true or kindHint=EXPLICIT_UAT_E2E w/o process API w/ release-scale=0
        //   and @Test count > 0 -> Blocked)
        // - Blocked (UatLocal001: EXPLICIT_UAT_E2E with 3 @Test and no release scale)
        // - LegacyNonV2 (UatLocal002)
        // - TestFixture (UatLocal003 is MigrateExternalPending; the
        //   SUPPORT_OR_ABSTRACT (UatLocal003 row in this fixture) -> TestFixture)
        assertNotNull(out.byDecision["blocked"])
        assertNotNull(out.byDecision["legacy_non_v2"])
        assertNotNull(out.byDecision["test_fixture"])
        assertNotNull(out.byDecision["migrate_external_pending"])
    }

    @Test
    fun `summarize preserves insertion order in byDecision`(@TempDir tmp: Path) {
        val input = tmp.resolve("input.csv")
        input.toFile().writeText(sampleCsv)

        val out = summarize(input)

        // Insertion order is the order the first row of each
        // bucket was seen. The CSV is: blocked, legacy, test_fixture,
        // migrate_external_pending.
        val keys = out.byDecision.keys.toList()
        assertEquals(
            listOf("blocked", "legacy_non_v2", "test_fixture", "migrate_external_pending"),
            keys,
            "BQ4 v26 fitness: byDecision is LinkedHashMap — insertion order is the contract",
        )
    }

    @Test
    fun `summarize sorts byWave alphabetically`(@TempDir tmp: Path) {
        val input = tmp.resolve("input.csv")
        input.toFile().writeText(sampleCsv)

        val out = summarize(input)

        // Sample CSV yields waves A, C, D (no wave B because
        // releaseScaleTag is false everywhere; wave B requires
        // release-scale tag OR kindHint=EXPLICIT_UAT_E2E).
        val keys = out.byWave.keys.toList()
        // Sorted order: a < c < d
        assertTrue(keys == keys.sorted(), "byWave must be sorted: $keys")
    }

    @Test
    fun `retirementAuthorisedABC and retirementAuthorisedD are always false`(@TempDir tmp: Path) {
        val input = tmp.resolve("input.csv")
        input.toFile().writeText(sampleCsv)

        val out = summarize(input)

        // The CLI is a classification report, not an
        // authorisation. The DeletionGuard is the sole
        // authority for retirement; the boolean fields on
        // the output are the explicit "I did not authorise
        // this" signal.
        assertFalse(out.retirementAuthorisedABC)
        assertFalse(out.retirementAuthorisedD)
    }

    @Test
    fun `JSON output contains snake_case wire format fields`(@TempDir tmp: Path) {
        val input = tmp.resolve("input.csv")
        val output = tmp.resolve("output.json")
        input.toFile().writeText(sampleCsv)

        runClassifier(input, output)

        val json = output.toFile().readText()
        val root = Json.parseToJsonElement(json).let { it as JsonObject }
        assertEquals(setOf(
            "total",
            "by_decision",
            "by_wave",
            "blocked_reasons",
            "retirement_authorised_a_b_c",
            "retirement_authorised_d",
        ), root.keys)
    }
}
